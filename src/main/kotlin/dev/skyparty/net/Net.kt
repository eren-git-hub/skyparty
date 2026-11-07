package dev.skyparty.net

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Networking API used by every online feature (wheel, pings, chat replies, minigames, pet sync).
 *
 * CONTRACT (other features code against this; the transport behind it can change):
 *  - Rooms are identified by a string id, e.g. "party:<hash>" or "code:ABC123".
 *  - Every message has a short [type] ("wheel.spin", "ping", "chess.move", ...) and a JSON payload.
 *  - Messages are delivered to all members of the room INCLUDING the sender (echo), so features can
 *    treat the server as the single source of truth (e.g. the server picks the wheel result).
 *  - The server may add fields: "from" (uuid), "fromName", "serverTime" and for "*.rng" requests a "roll".
 *
 * The default transport is [LoopbackTransport] (dev/offline: only you are in each room).
 * The real backend transport (WebSocket to the Skyparty Cloudflare Worker) replaces it via [transport].
 */
object Net {
	data class Message(val type: String, val payload: JsonObject, val from: UUID, val fromName: String)

	interface Room {
		val id: String
		val members: Set<UUID>
		fun send(type: String, payload: JsonObject = JsonObject())
		fun leave()
		/** Called on the client thread for every message (including your own echo). */
		var onMessage: (Message) -> Unit
		var onMembersChanged: (Set<UUID>) -> Unit
	}

	interface Transport {
		val connected: Boolean
		fun join(roomId: String): Room
	}

	/**
	 * Set to [BackendTransport] by the Network module once logged in to the backend, and back to
	 * [LoopbackTransport] when online features are off. Rooms keep the transport they were joined with,
	 * so features that want to move rooms over can listen to [transportListeners].
	 */
	@Volatile
	var transport: Transport = LoopbackTransport
		set(value) {
			val old = field
			field = value
			if (old !== value) Minecraft.getInstance().execute { transportListeners.toList().forEach { it(value) } }
		}

	/** Called on the client thread after [transport] changed (e.g. the backend came online). */
	val transportListeners = mutableListOf<(Transport) -> Unit>()

	val selfId: UUID get() = Minecraft.getInstance().user.profileId
	val selfName: String get() = Minecraft.getInstance().user.name

	fun join(roomId: String): Room = transport.join(roomId)
}

/**
 * Offline transport: every room only contains you, messages are echoed back on the next client tick.
 * "*.rng" requests get a server-like "roll" (0..1) added so features can be tested without a backend.
 */
object LoopbackTransport : Net.Transport {
	override val connected = false

	override fun join(roomId: String): Net.Room = object : Net.Room {
		override val id = roomId
		override val members: Set<UUID> get() = setOf(Net.selfId)
		override var onMessage: (Net.Message) -> Unit = {}
		override var onMembersChanged: (Set<UUID>) -> Unit = {}

		override fun send(type: String, payload: JsonObject) {
			val copy = payload.deepCopy()
			if (type.endsWith(".rng")) copy.addProperty("roll", Math.random())
			copy.addProperty("serverTime", System.currentTimeMillis())
			val msg = Net.Message(type, copy, Net.selfId, Net.selfName)
			Minecraft.getInstance().execute { onMessage(msg) }
		}

		override fun leave() {}
	}
}
