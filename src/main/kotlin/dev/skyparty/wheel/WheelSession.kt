package dev.skyparty.wheel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.skyparty.module.impl.party.DecisionWheel
import dev.skyparty.net.LoopbackTransport
import dev.skyparty.net.Net
import dev.skyparty.party.PartyRoom
import dev.skyparty.party.PartyTracker
import dev.skyparty.util.Chat
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import org.slf4j.LoggerFactory
import kotlin.math.floor
import kotlin.math.pow

/**
 * Wheel state shared by everyone in a room (party room, a "code:XXXXXX" room or local only).
 *
 * Protocol (all JSON, see [Net]):
 *  - "wheel.state"    {title, entries:[{n,w}], host, hostOnlyEdit, hostOnlySpin} – full entry list.
 *  - "wheel.hello"    {} – sent on join; the host answers with "wheel.state".
 *  - "wheel.spin.rng" {title, entries:[{n,w}]} – the server adds "roll" (0..1). Every client computes the
 *    same weighted result and the same final angle from that roll, so all wheels stop on the same slice.
 */
object WheelSession {
	class Entry(var name: String, var weight: Int)

	class Spin(
		val title: String,
		val names: List<String>,
		val weights: List<Int>,
		val result: Int,
		val spinner: String,
		val startMs: Long,
		val startRot: Double,
		val endRot: Double,
	) {
		val starts: DoubleArray = startsOf(weights)
		var done = false
		val resultName get() = names[result]
	}

	class Result(val title: String, val name: String, val spinner: String, val at: Long)

	enum class Mode(val label: String) { LOCAL("Local"), PARTY("Party"), CODE("Room code") }

	const val SPIN_MS = 6000L
	const val MAX_ENTRIES = 24
	const val MAX_NAME = 24
	const val MAX_WEIGHT = 20
	private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	private const val HOST_WAIT_MS = 2500L

	private val log = LoggerFactory.getLogger("Skyparty/Wheel")

	val entries = mutableListOf<Entry>()
	var title = "Decision Wheel"
		private set

	var spin: Spin? = null
		private set

	/** Rotation (turns) while idle; the wheel keeps the angle it stopped at. */
	private var restRotation = 0.0
	val history = ArrayDeque<Result>()
	/** Incremented on every finished spin (the screen starts confetti when it changes). */
	var resultSerial = 0
		private set
	val lastResult get() = history.firstOrNull()

	// Room
	private var codeRoom: Net.Room? = null
	private var localRoom: Net.Room? = null
	/** Host announced in "wheel.state" (code rooms) or the party member that answered as host. */
	private var announcedHost: String? = null
	private var helloSentAt = 0L
	private var hostOnlyEditRemote: Boolean? = null
	private var hostOnlySpinRemote: Boolean? = null
	private var pendingOpen = false
	private var initialized = false

	val roomCode: String? get() = codeRoom?.id?.removePrefix("code:")

	val mode: Mode
		get() = when {
			codeRoom != null -> Mode.CODE
			DecisionWheel.enabled && DecisionWheel.autoParty.value && PartyTracker.inParty -> Mode.PARTY
			else -> Mode.LOCAL
		}

	private val self get() = Net.selfName

	/** Who controls the wheel: code room creator, party leader (once heard from) or yourself. */
	val host: String?
		get() = when (mode) {
			Mode.LOCAL -> self
			Mode.CODE -> announcedHost
			Mode.PARTY -> announcedHost
		}

	val isHost get() = host?.equals(self, ignoreCase = true) ?: false

	val hostOnlyEdit get() = if (isHost) DecisionWheel.onlyHostEdits.value else hostOnlyEditRemote ?: false
	val hostOnlySpin get() = if (isHost) DecisionWheel.onlyHostSpins.value else hostOnlySpinRemote ?: false

	val canEdit get() = isHost || host == null || !hostOnlyEdit
	val canSpin get() = (isHost || host == null || !hostOnlySpin) && spin?.done != false && validEntries().size >= 2

	val spinning get() = spin?.done == false

	fun init() {
		if (initialized) return
		initialized = true
		if (entries.isEmpty()) applyPreset(Presets.all.first())
		PartyRoom.demand { DecisionWheel.enabled && DecisionWheel.autoParty.value }
		PartyRoom.on("wheel.") { if (codeRoom == null && DecisionWheel.autoParty.value) handle(it) }
		PartyRoom.roomChanged += { onRoomJoined() }
		PartyTracker.listeners += { if (codeRoom == null) announcedHost = null }
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(::tick)
		WheelCommands.init()
	}

	fun tick(mc: Minecraft) {
		if (pendingOpen && mc.player != null) {
			pendingOpen = false
			mc.gui.setScreen(WheelScreen())
		}
		val s = spin
		if (s != null && !s.done && System.currentTimeMillis() - s.startMs >= SPIN_MS) finish(s)
		// Nobody answered our hello → this room has no host yet, take it.
		if (helloSentAt != 0L && announcedHost == null && System.currentTimeMillis() - helloSentAt > HOST_WAIT_MS) {
			helloSentAt = 0L
			if (mode == Mode.CODE) {
				announcedHost = self
				broadcastState()
			}
		}
	}

	fun openScreenNextTick() {
		pendingOpen = true
	}

	// --- Rooms ----------------------------------------------------------------------------------

	private fun room(): Net.Room? {
		codeRoom?.let { return it }
		if (DecisionWheel.autoParty.value && DecisionWheel.enabled) {
			PartyRoom.sync()
			PartyRoom.room?.let { return it }
		}
		return localRoom ?: LoopbackTransport.join("wheel-local").also {
			it.onMessage = ::handle
			localRoom = it
		}
	}

	fun createRoom(): String {
		val code = (1..6).map { CODE_CHARS.random() }.joinToString("")
		joinCode(code, asHost = true)
		return code
	}

	fun joinCode(rawCode: String, asHost: Boolean = false) {
		val code = rawCode.removePrefix("code:").uppercase().filter { it.isLetterOrDigit() }.take(6)
		leaveCode()
		val r = Net.join("code:$code")
		r.onMessage = ::handle
		codeRoom = r
		announcedHost = if (asHost) self else null
		hostOnlyEditRemote = null
		hostOnlySpinRemote = null
		if (asHost) broadcastState() else sayHello(r)
		log.info("Joined wheel room code:{} (host={})", code, asHost)
	}

	fun leaveCode() {
		codeRoom?.leave()
		codeRoom = null
		announcedHost = null
		helloSentAt = 0L
	}

	private fun onRoomJoined() {
		if (codeRoom != null) return
		announcedHost = null
		val r = PartyRoom.room ?: return
		if (PartyRoom.isSolo) return
		// The party leader announces itself as host; everyone else asks for the current wheel.
		if (PartyTracker.leader.equals(self, ignoreCase = true)) {
			announcedHost = self
			broadcastState()
		} else {
			sayHello(r)
		}
	}

	private fun sayHello(r: Net.Room) {
		helloSentAt = System.currentTimeMillis()
		r.send("wheel.hello", JsonObject())
	}

	// --- Editing --------------------------------------------------------------------------------

	fun validEntries() = entries.filter { it.name.isNotBlank() && it.weight > 0 }

	fun add(name: String): Boolean {
		val clean = name.trim().replace("§", "").take(MAX_NAME)
		if (clean.isEmpty() || entries.size >= MAX_ENTRIES || !canEdit) return false
		entries += Entry(clean, 1)
		changed()
		return true
	}

	fun remove(index: Int) {
		if (!canEdit || index !in entries.indices) return
		entries.removeAt(index)
		changed()
	}

	fun setWeight(index: Int, weight: Int) {
		if (!canEdit || index !in entries.indices) return
		entries[index].weight = weight.coerceIn(1, MAX_WEIGHT)
		changed()
	}

	fun applyPreset(preset: Presets.Preset) {
		if (initialized && !canEdit) return
		title = preset.title
		entries.clear()
		preset.entries.forEach { entries += Entry(it, 1) }
		if (initialized) changed()
	}

	fun clear() {
		if (!canEdit) return
		entries.clear()
		title = "Decision Wheel"
		changed()
	}

	private fun changed() {
		if (mode != Mode.LOCAL) broadcastState()
	}

	private fun entriesJson(list: List<Entry>) = JsonArray().apply {
		list.forEach { e -> add(JsonObject().apply { addProperty("n", e.name); addProperty("w", e.weight) }) }
	}

	private fun broadcastState() {
		val r = room() ?: return
		r.send("wheel.state", JsonObject().apply {
			addProperty("title", title)
			add("entries", entriesJson(entries))
			host?.let { addProperty("host", it) }
			addProperty("hostOnlyEdit", hostOnlyEdit)
			addProperty("hostOnlySpin", hostOnlySpin)
		})
	}

	// --- Spinning -------------------------------------------------------------------------------

	/** Asks the room to spin. Returns an error text when not allowed. */
	fun requestSpin(): String? {
		if (spinning) return "The wheel is already spinning."
		val valid = validEntries()
		if (valid.size < 2) return "Add at least two entries."
		if (!canSpin) return "Only the host (${host}) can spin."
		val r = room() ?: return "No room."
		r.send("wheel.spin.rng", JsonObject().apply {
			addProperty("title", title)
			add("entries", entriesJson(valid))
		})
		return null
	}

	/** Current wheel rotation in turns (animated while spinning). */
	fun rotation(now: Long = System.currentTimeMillis()): Double {
		val s = spin ?: return restRotation
		if (s.done) return s.endRot
		val t = ((now - s.startMs).toDouble() / SPIN_MS).coerceIn(0.0, 1.0)
		return s.startRot + (s.endRot - s.startRot) * ease(t)
	}

	/** Quartic ease-out: a hard flick that slowly settles, like a real prize wheel. */
	private fun ease(t: Double) = 1 - (1 - t).pow(4)

	/** Slices shown right now: the spin's snapshot while it is running or showing its result. */
	fun displayNames(): List<String> = spin?.takeIf { !it.done || entriesMatch(it) }?.names ?: validEntries().map { it.name }
	fun displayStarts(): DoubleArray = spin?.takeIf { !it.done || entriesMatch(it) }?.starts ?: startsOf(validEntries().map { it.weight })

	private fun entriesMatch(s: Spin): Boolean {
		val v = validEntries()
		return v.size == s.names.size && v.indices.all { v[it].name == s.names[it] && v[it].weight == s.weights[it] }
	}

	// --- Incoming -------------------------------------------------------------------------------

	private fun handle(msg: Net.Message) {
		val from = msg.fromName
		val fromHost = host?.equals(from, ignoreCase = true) ?: false
		when (msg.type) {
			"wheel.hello" -> if (!from.equals(self, ignoreCase = true) && (isHost || (host == null && entries.isNotEmpty()))) broadcastState()
			"wheel.state" -> onState(msg, from)
			"wheel.spin.rng" -> onSpin(msg, from, fromHost)
		}
	}

	private fun onState(msg: Net.Message, from: String) {
		val p = msg.payload
		val claimedHost = p.get("host")?.asString
		if (host == null && claimedHost != null && claimedHost.equals(from, ignoreCase = true)) {
			// First state from the room's host (code room creator / party leader).
			announcedHost = claimedHost
			helloSentAt = 0L
		}
		val fromHost = host?.equals(from, ignoreCase = true) ?: false
		if (host != null && !fromHost && hostOnlyEdit) return
		if (fromHost) {
			hostOnlyEditRemote = p.get("hostOnlyEdit")?.asBoolean
			hostOnlySpinRemote = p.get("hostOnlySpin")?.asBoolean
		}
		if (from.equals(self, ignoreCase = true)) return // our own echo; already applied locally
		title = p.get("title")?.asString?.take(40) ?: title
		entries.clear()
		p.getAsJsonArray("entries")?.forEach { el ->
			val o = el.asJsonObject
			val name = o.get("n")?.asString?.replace("§", "")?.take(MAX_NAME) ?: return@forEach
			entries += Entry(name, (o.get("w")?.asInt ?: 1).coerceIn(1, MAX_WEIGHT))
		}
		while (entries.size > MAX_ENTRIES) entries.removeLast()
	}

	private fun onSpin(msg: Net.Message, from: String, fromHost: Boolean) {
		if (host != null && hostOnlySpin && !fromHost) {
			log.info("Ignored spin from {} (host only)", from)
			return
		}
		val p = msg.payload
		val roll = p.get("roll")?.asDouble ?: return
		val names = mutableListOf<String>()
		val weights = mutableListOf<Int>()
		p.getAsJsonArray("entries")?.forEach { el ->
			val o = el.asJsonObject
			names += (o.get("n")?.asString ?: "?").replace("§", "").take(MAX_NAME)
			weights += (o.get("w")?.asInt ?: 1).coerceIn(1, MAX_WEIGHT)
		}
		if (names.size < 2) return
		val starts = startsOf(weights)
		val r = roll.coerceIn(0.0, 0.999999)
		val result = Shapes.sliceAt(starts, r)
		// Land inside the slice (never on a border): same roll → same angle on every client.
		val w = starts[result + 1] - starts[result]
		val within = (r - starts[result]) / w
		val target = starts[result] + w * (0.12 + 0.76 * within)
		val turns = 5 + (floor(roll * 1000).toInt() % 3)
		val startRot = rotation()
		val endBase = -target
		val delta = ((endBase - startRot) % 1.0 + 1.0) % 1.0
		val endRot = startRot + turns + delta
		spin = Spin(p.get("title")?.asString?.take(40) ?: title, names, weights, result, from, System.currentTimeMillis(), startRot, endRot)
		log.info("Spin by {} roll={} -> {}", from, roll, names[result])
	}

	private fun finish(s: Spin) {
		s.done = true
		restRotation = s.endRot % 1.0
		history.addFirst(Result(s.title, s.resultName, s.spinner, System.currentTimeMillis()))
		while (history.size > 12) history.removeLast()
		resultSerial++
		if (DecisionWheel.sound.value) {
			val sm = Minecraft.getInstance().soundManager
			sm.play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4f, 0.6f))
			sm.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2f, 0.8f))
		}
		if (Minecraft.getInstance().gui.screen() !is WheelScreen) {
			Chat.info("§d${s.title}§7: §f§l${s.resultName} §8(spun by ${s.spinner})")
		}
	}

	/** Party chat line for the "Send to party" button (only sent when the player clicks it). */
	fun sendResultToParty() {
		val r = lastResult ?: return
		Chat.command("pc [Wheel] ${r.title}: ${r.name}")
	}

	fun startsOf(weights: List<Int>): DoubleArray {
		val total = weights.sum().toDouble().coerceAtLeast(1.0)
		val out = DoubleArray(weights.size + 1)
		for (i in weights.indices) out[i + 1] = out[i] + weights[i] / total
		out[weights.size] = 1.0
		return out
	}
}
