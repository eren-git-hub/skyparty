package dev.skyparty.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.util.Location
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Real transport: talks to the Skyparty Cloudflare Worker (see backend/README.md).
 *
 * Login never sends the Minecraft access token to the backend. Like joining a vanilla server, the
 * client asks Mojang's session server to "join" a random serverId handed out by the backend
 * (authlib's joinServer, i.e. POST sessionserver.mojang.com/session/minecraft/join); the backend then
 * asks Mojang "hasJoined?" and returns a signed 24h session token.
 *
 * One WebSocket per room id (shared by every [Net.join] of the same id), reconnecting with backoff.
 * All callbacks into features run on the client thread via mc.execute.
 */
object BackendTransport : Net.Transport {
	enum class State(val label: String) {
		OFF("Off"),
		NOT_CONFIGURED("Backend URL not configured"),
		CONNECTING("Connecting"),
		ONLINE("Online"),
		ERROR("Error"),
	}

	data class Session(val token: String, val uuid: UUID, val name: String, val expiresAt: Long, val dev: Boolean)

	private const val PING_SECONDS = 30L
	private const val MAX_QUEUED = 32
	private val log get() = Skyparty.LOGGER

	private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Skyparty-Net").apply { isDaemon = true } }
	private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

	@Volatile
	var state = State.OFF
		private set
	@Volatile
	var lastError: String? = null
		private set
	@Volatile
	var session: Session? = null
		private set
	@Volatile
	var baseUrl: String = ""
		private set

	/** Bumped on every (re)start so stale async results are ignored. */
	@Volatile private var generation = 0
	private var authAttempts = 0
	private var authTask: ScheduledFuture<*>? = null
	private var pingTask: ScheduledFuture<*>? = null

	private val rooms = ConcurrentHashMap<String, RoomConnection>()

	/** Listeners (client thread) told when a new session starts, e.g. to re-upload the pet. */
	val sessionListeners = CopyOnWriteArrayList<(Session) -> Unit>()

	override val connected: Boolean get() = state == State.ONLINE && session != null

	val online get() = connected

	// ---- lifecycle ------------------------------------------------------------------------------

	/** Called every tick by the Network module with the desired configuration. Cheap when nothing changed. */
	fun update(enabled: Boolean, url: String) {
		val normalized = url.trim().trimEnd('/')
		val wanted = when {
			!enabled -> State.OFF
			!isUsableUrl(normalized) -> State.NOT_CONFIGURED
			else -> null
		}
		if (wanted != null) {
			if (state != wanted || baseUrl.isNotEmpty()) stop(wanted)
			return
		}
		if (normalized != baseUrl || state == State.OFF || state == State.NOT_CONFIGURED) start(normalized)
		else session?.let { if (it.expiresAt - System.currentTimeMillis() < 3_600_000 && authTask == null) scheduleAuth(0) }
	}

	/** https anywhere; plain http only for a local `wrangler dev`. Placeholder URLs count as not configured. */
	fun isUsableUrl(url: String): Boolean {
		if (url.contains("YOUR-ACCOUNT", ignoreCase = true)) return false
		val uri = runCatching { URI(url) }.getOrNull() ?: return false
		val host = uri.host ?: return false
		return when (uri.scheme) {
			"https" -> true
			"http" -> host == "localhost" || host == "127.0.0.1" || host == "[::1]"
			else -> false
		}
	}

	@Synchronized
	private fun start(url: String) {
		stopConnections()
		generation++
		baseUrl = url
		session = null
		authAttempts = 0
		state = State.CONNECTING
		lastError = null
		scheduleAuth(0)
	}

	@Synchronized
	fun stop(newState: State = State.OFF) {
		generation++
		stopConnections()
		session = null
		baseUrl = ""
		state = newState
		authTask?.cancel(false)
		authTask = null
		if (Net.transport === this) Net.transport = LoopbackTransport
	}

	/** Forces a fresh login and reconnects all rooms (/sl net reconnect). */
	fun reconnect() {
		val url = baseUrl
		if (url.isNotEmpty()) start(url)
	}

	private fun stopConnections() {
		pingTask?.cancel(false)
		pingTask = null
		rooms.values.forEach { it.disconnect() }
	}

	// ---- auth -----------------------------------------------------------------------------------

	@Synchronized
	private fun scheduleAuth(delayMs: Long) {
		authTask?.cancel(false)
		val gen = generation
		authTask = io.schedule({ authenticate(gen) }, delayMs, TimeUnit.MILLISECONDS)
	}

	private fun authenticate(gen: Int) {
		synchronized(this) { authTask = null }
		if (gen != generation) return
		if (session == null) state = State.CONNECTING
		try {
			val info = getJson("/", null)
			val user = Minecraft.getInstance().user
			val result = if (Location.isDev && info.get("dev")?.asBoolean == true && user.name.matches(NAME_RE)) {
				// Local `wrangler dev` with DEV_MODE: offline dev accounts cannot talk to Mojang.
				postJson("/auth/verify", JsonObject().apply {
					addProperty("username", "dev:" + user.name)
					addProperty("uuid", user.profileId.toString())
				})
			} else {
				val challenge = getJson("/auth/challenge", null)
				// The access token goes to Mojang only (same call vanilla makes when joining a server).
				Minecraft.getInstance().services().sessionService()
					.joinServer(user.profileId, user.accessToken, challenge.get("serverId").asString)
				postJson("/auth/verify", JsonObject().apply {
					addProperty("username", user.name)
					addProperty("challenge", challenge.get("challenge").asString)
				})
			}
			if (gen != generation) return
			val s = Session(
				token = result.get("token").asString,
				uuid = UUID.fromString(result.get("uuid").asString),
				name = result.get("name").asString,
				expiresAt = System.currentTimeMillis() + result.get("expiresIn").asLong * 1000,
				dev = result.get("dev")?.asBoolean == true,
			)
			onLoggedIn(gen, s)
		} catch (e: Exception) {
			if (gen != generation) return
			val msg = describe(e)
			log.warn("Skyparty backend login failed: {}", msg)
			lastError = msg
			if (session == null) state = State.ERROR
			authAttempts++
			// 15s, 30s, 1min ... capped at 10 min.
			scheduleAuth(min(600_000L, 15_000L shl min(authAttempts - 1, 6)))
		}
	}

	private fun onLoggedIn(gen: Int, s: Session) {
		val first = session == null
		synchronized(this) {
			if (gen != generation) return
			session = s
			authAttempts = 0
			state = State.ONLINE
			lastError = null
			if (pingTask == null) {
				pingTask = io.scheduleAtFixedRate({ rooms.values.forEach { it.ping() } }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS)
			}
		}
		log.info("Skyparty backend: logged in as {}{}", s.name, if (s.dev) " (dev)" else "")
		rooms.values.forEach { if (first || !it.isOpen) it.connectNow() }
		Minecraft.getInstance().execute {
			Net.transport = this
			sessionListeners.forEach { it(s) }
		}
	}

	private val NAME_RE = Regex("[A-Za-z0-9_]{1,16}")

	// ---- HTTP helpers (blocking, call from background threads) ------------------------------------

	class HttpError(val status: Int, val code: String) : Exception("HTTP $status $code")

	private fun request(path: String, token: String?): HttpRequest.Builder {
		val b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
			.header("User-Agent", "Skyparty")
		if (token != null) b.header("Authorization", "Bearer $token")
		return b
	}

	private fun send(req: HttpRequest): JsonObject {
		val res = http.send(req, HttpResponse.BodyHandlers.ofString())
		val body = runCatching { JsonParser.parseString(res.body()).asJsonObject }.getOrNull()
		if (res.statusCode() !in 200..299) throw HttpError(res.statusCode(), body?.get("error")?.asString ?: "error")
		return body ?: JsonObject()
	}

	private fun getJson(path: String, token: String?) = send(request(path, token).GET().build())

	private fun postJson(path: String, body: JsonObject, token: String? = null, method: String = "POST") = send(
		request(path, token).header("Content-Type", "application/json")
			.method(method, HttpRequest.BodyPublishers.ofString(body.toString())).build()
	)

	private fun describe(e: Throwable): String = when (e) {
		is HttpError -> when (e.code) {
			"not_joined" -> "Mojang did not confirm the login (restart the game if your session expired)"
			"rate_limited" -> "Rate limited, retrying later"
			"not_configured" -> "Backend has no SESSION_SECRET set"
			else -> "Backend error ${e.status} (${e.code})"
		}
		is com.mojang.authlib.exceptions.AuthenticationException -> "Mojang login failed: ${e.message ?: e.javaClass.simpleName}"
		is java.net.ConnectException, is java.net.http.HttpConnectTimeoutException -> "Backend unreachable"
		is java.util.concurrent.CompletionException -> e.cause?.let { describe(it) } ?: e.toString()
		else -> e.message ?: e.javaClass.simpleName
	}

	/** Runs an authenticated API call off-thread. [onResult] runs on the client thread (null = failed). */
	fun <T> api(call: (token: String) -> T, onResult: (T?) -> Unit) {
		val s = session
		if (s == null || !connected) {
			onResult(null)
			return
		}
		io.execute {
			val result = try {
				call(s.token)
			} catch (e: Exception) {
				if (e is HttpError && e.status == 401) scheduleAuth(0)
				log.debug("Skyparty API call failed: {}", describe(e))
				null
			}
			Minecraft.getInstance().execute { onResult(result) }
		}
	}

	/** PUT /pets/me. Blocking; use through [api]. */
	fun putPet(token: String, pet: JsonObject): JsonObject = postJson("/pets/me", pet, token, "PUT")

	/** GET /pets?uuids=… (max 50). Blocking; use through [api]. */
	fun getPets(token: String, uuids: Collection<UUID>): JsonObject {
		val q = URLEncoder.encode(uuids.joinToString(",") { it.toString() }, StandardCharsets.UTF_8)
		return getJson("/pets?uuids=$q", token).getAsJsonObject("pets") ?: JsonObject()
	}

	// ---- rooms ----------------------------------------------------------------------------------

	val roomSummaries: List<Pair<String, Int>>
		get() = rooms.values.map { it.id to if (it.isOpen) it.members.size else 0 }

	override fun join(roomId: String): Net.Room {
		val conn = rooms.computeIfAbsent(roomId) { RoomConnection(it) }
		val handle = conn.Handle()
		conn.handles += handle
		if (connected && !conn.isOpen) conn.connectNow()
		return handle
	}

	private class RoomConnection(val id: String) {
		val handles = CopyOnWriteArrayList<Handle>()
		@Volatile var members: Set<UUID> = emptySet()
		@Volatile private var ws: WebSocket? = null
		@Volatile private var connecting = false
		private val queue = ConcurrentLinkedQueue<String>()
		private var sendChain: CompletableFuture<*> = CompletableFuture.completedFuture(null)
		private var attempts = 0
		private var reconnectTask: ScheduledFuture<*>? = null
		/** Incremented on each connect so callbacks from replaced sockets are ignored. */
		@Volatile private var connId = 0

		val isOpen get() = ws != null

		inner class Handle : Net.Room {
			override val id get() = this@RoomConnection.id
			override val members: Set<UUID>
				get() = this@RoomConnection.members.ifEmpty { setOf(Net.selfId) }
			override var onMessage: (Net.Message) -> Unit = {}
			override var onMembersChanged: (Set<UUID>) -> Unit = {}

			override fun send(type: String, payload: JsonObject) {
				if (BackendTransport.state == State.OFF || BackendTransport.state == State.NOT_CONFIGURED) {
					// Online features were switched off after this room was joined: behave like loopback.
					val copy = payload.deepCopy()
					if (type.endsWith(".rng")) copy.addProperty("roll", Math.random())
					copy.addProperty("serverTime", System.currentTimeMillis())
					val msg = Net.Message(type, copy, Net.selfId, Net.selfName)
					Minecraft.getInstance().execute { onMessage(msg) }
					return
				}
				this@RoomConnection.send(JsonObject().apply {
					addProperty("type", type)
					add("payload", payload)
				}.toString())
			}

			override fun leave() {
				handles.remove(this)
				if (handles.isEmpty()) {
					rooms.remove(id, this@RoomConnection)
					disconnect()
				}
			}
		}

		@Synchronized
		fun connectNow() {
			reconnectTask?.cancel(false)
			reconnectTask = null
			if (ws != null || connecting || handles.isEmpty()) return
			val s = session ?: return
			connecting = true
			val myId = ++connId
			val wsUrl = baseUrl.replaceFirst("http", "ws") + "/rooms/" + URLEncoder.encode(id, StandardCharsets.UTF_8)
			http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer ${s.token}")
				.buildAsync(URI.create(wsUrl), Listener(myId))
				.whenComplete { socket, err ->
					synchronized(this) {
						connecting = false
						if (myId != connId) {
							socket?.sendClose(WebSocket.NORMAL_CLOSURE, "stale")
							return@whenComplete
						}
						if (err != null) {
							val cause = (err as? java.util.concurrent.CompletionException)?.cause ?: err
							val status = (cause as? WebSocketHandshakeException)?.response?.statusCode()
							log.debug("Skyparty room {} connect failed: {}", id, status ?: cause.toString())
							if (status == 401) scheduleAuth(0)
							scheduleReconnect(if (status == 429) 30_000 else null)
						} else {
							ws = socket
							attempts = 0
							flush()
						}
					}
				}
		}

		@Synchronized
		fun disconnect() {
			connId++
			reconnectTask?.cancel(false)
			reconnectTask = null
			ws?.let { runCatching { it.sendClose(WebSocket.NORMAL_CLOSURE, "leave") } }
			ws = null
			applyMembers(emptySet())
		}

		fun ping() {
			ws?.let { send("ping") }
		}

		fun send(text: String) {
			synchronized(this) {
				if (ws == null) {
					if (text != "ping") {
						queue.add(text)
						while (queue.size > MAX_QUEUED) queue.poll()
					}
					return
				}
				enqueue(text)
			}
		}

		/** java.net.http.WebSocket allows only one outstanding send, so sends are chained. */
		private fun enqueue(text: String) {
			val socket = ws ?: return
			sendChain = sendChain.handle { _, _ -> null }.thenCompose { socket.sendText(text, true) }
		}

		private fun flush() {
			while (true) enqueue(queue.poll() ?: break)
		}

		@Synchronized
		private fun scheduleReconnect(fixedDelay: Long? = null) {
			if (handles.isEmpty() || state == State.OFF || state == State.NOT_CONFIGURED) return
			attempts++
			val base = fixedDelay ?: min(60_000L, 1_000L shl min(attempts - 1, 6))
			val delay = base + (Math.random() * base * 0.3).toLong()
			reconnectTask?.cancel(false)
			reconnectTask = io.schedule({ connectNow() }, delay, TimeUnit.MILLISECONDS)
		}

		private fun onClosed(myId: Int, code: Int, reason: String) {
			synchronized(this) {
				if (myId != connId) return
				ws = null
				applyMembers(emptySet())
				log.debug("Skyparty room {} closed: {} {}", id, code, reason)
				when (code) {
					4000 -> return // replaced by a newer connection of ours: do not fight over the slot
					4003, 4008, 4009 -> scheduleReconnect(60_000) // banned / kicked / full
					else -> scheduleReconnect()
				}
			}
		}

		private fun applyMembers(set: Set<UUID>) {
			if (set == members) return
			members = set
			val visible = set.ifEmpty { setOf(Net.selfId) }
			Minecraft.getInstance().execute { handles.forEach { it.onMembersChanged(visible) } }
		}

		private fun onFrame(text: String) {
			if (text == "pong") return
			val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
			val type = obj.get("type")?.asString ?: return
			val payload = obj.getAsJsonObject("payload") ?: JsonObject()
			when (type) {
				"room.members" -> applyMembers(
					payload.getAsJsonArray("members")
						?.mapNotNull { runCatching { UUID.fromString(it.asJsonObject.get("uuid").asString) }.getOrNull() }
						?.toSet() ?: emptySet()
				)
				"room.error" -> log.debug("Skyparty room {} error: {}", id, payload.get("code")?.asString)
				else -> {
					val from = runCatching { UUID.fromString(obj.get("from").asString) }.getOrNull() ?: return
					val msg = Net.Message(type, payload, from, obj.get("fromName")?.asString ?: "")
					Minecraft.getInstance().execute { handles.forEach { it.onMessage(msg) } }
				}
			}
		}

		private inner class Listener(val myId: Int) : WebSocket.Listener {
			private val buffer = StringBuilder()

			override fun onOpen(webSocket: WebSocket) {
				webSocket.request(1)
			}

			override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
				buffer.append(data)
				if (last) {
					val text = buffer.toString()
					buffer.setLength(0)
					if (myId == connId) onFrame(text)
				}
				webSocket.request(1)
				return null
			}

			override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
				onClosed(myId, statusCode, reason)
				return null
			}

			override fun onError(webSocket: WebSocket, error: Throwable) {
				onClosed(myId, 1006, error.toString())
			}
		}
	}
}
