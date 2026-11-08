package dev.skyparty.minigames

import com.google.gson.JsonObject
import dev.skyparty.net.LoopbackTransport
import dev.skyparty.net.Net
import net.minecraft.client.gui.screens.Screen
import java.util.UUID

/**
 * The [Net.Room] handed to [Minigame.open]. It wraps the lobby's real room so the hub keeps receiving
 * lobby messages while a game runs: every non-"lobby.*" message is forwarded to [onMessage].
 *
 * [members] are only the players who joined this game (not everyone in the party), [host] is the
 * player who started it. Games may cast `room as? LobbyRoom` to read [host] and [names].
 * [leave] only ends your participation in the game; the underlying party/code room stays joined.
 *
 * Games read time through [now] / [nanos]: that clock stops while the game is paused, so every timer
 * (rounds, fuses, bots, physics) simply freezes and continues where it was.
 */
class LobbyRoom internal constructor(
	private val inner: Net.Room,
	val host: UUID,
	players: Map<UUID, String>,
	/** Game room inside the lobby; added to every message ("_s") so parallel games never mix. */
	val session: String?,
	private val onLeave: (LobbyRoom) -> Unit,
) : Net.Room {
	private val players = LinkedHashMap(players)

	override val id: String get() = inner.id
	override val members: Set<UUID> get() = players.keys
	override var onMessage: (Net.Message) -> Unit = {}
	override var onMembersChanged: (Set<UUID>) -> Unit = {}

	/** Player names by id, in join order (host first). */
	val names: Map<UUID, String> get() = players

	val isHost: Boolean get() = host == Net.selfId
	val solo: Boolean get() = players.size <= 1

	fun nameOf(id: UUID): String = players[id] ?: "Player"

	override fun send(type: String, payload: JsonObject) {
		if (session != null) payload.addProperty("_s", session)
		inner.send(type, payload)
	}

	// ------------------------------------------------------------------ pause

	/** Set by the game screen: a match is running. */
	var inProgress: () -> Boolean = { false }

	/** The game screen, kept while the game is paused so it can be reopened later. */
	var screen: Screen? = null

	/** Sends the pause request to everyone (set by the hub); null = local only (solo). */
	internal var pauseSender: ((Boolean) -> Unit)? = null

	private var pausedFlag = false
	private var pauseStartMs = 0L
	private var pauseStartNs = 0L
	private var pausedMs = 0L
	private var pausedNs = 0L
	/** Wall time when a resume countdown ends (0 = none). */
	var resumeAt = 0L
		private set
	var pausedBy: String? = null
		private set
	private var quitting = false

	/** Paused, or in the short countdown after someone pressed resume. */
	val paused: Boolean get() { settle(); return pausedFlag }

	private fun settle() {
		if (pausedFlag && resumeAt != 0L && System.currentTimeMillis() >= resumeAt) {
			pausedMs += System.currentTimeMillis() - pauseStartMs
			pausedNs += System.nanoTime() - pauseStartNs
			pausedFlag = false
			resumeAt = 0L
			pausedBy = null
		}
	}

	/** Game clock in ms: like [System.currentTimeMillis], but standing still while paused. */
	fun now(): Long { settle(); return (if (pausedFlag) pauseStartMs else System.currentTimeMillis()) - pausedMs }

	/** Game clock in ns: like [System.nanoTime], but standing still while paused. */
	fun nanos(): Long { settle(); return (if (pausedFlag) pauseStartNs else System.nanoTime()) - pausedNs }

	/** Asks everyone in the game to pause / resume. */
	fun requestPause(on: Boolean) {
		val sender = pauseSender
		if (sender != null) sender(on) else applyPause(on, Net.selfName)
	}

	/** Applies a pause / resume (resume runs a 3 s countdown first). */
	internal fun applyPause(on: Boolean, by: String) {
		settle()
		if (on) {
			if (pausedFlag && resumeAt == 0L) return
			if (!pausedFlag) {
				pauseStartMs = System.currentTimeMillis()
				pauseStartNs = System.nanoTime()
			}
			pausedFlag = true
			resumeAt = 0L
			pausedBy = by
		} else if (pausedFlag && resumeAt == 0L) {
			resumeAt = System.currentTimeMillis() + RESUME_COUNTDOWN_MS
		}
	}

	/**
	 * Called when the game screen closes. A running match is never left that way: Esc, Back, a lobby or server
	 * switch (Minecraft replaces the screen) all pause it for everyone and keep it, so "/sp" brings you back.
	 * Only a finished game is left here; mid-game you leave with "Quit game" ([quit]).
	 */
	override fun leave() {
		if (left || (paused && !quitting)) return
		if (!quitting && inProgress()) {
			applyPause(true, Net.selfName)
			requestPause(true)
			return
		}
		left = true
		onLeave(this)
	}

	/** The game was left or quit (its screen is gone for good). */
	var left = false
		private set

	/** Really leaves the game, paused or not. */
	fun quit() {
		if (left) return
		quitting = true
		left = true
		onLeave(this)
	}

	internal fun removePlayer(id: UUID) {
		if (players.remove(id) != null) onMembersChanged(members)
	}

	companion object {
		const val RESUME_COUNTDOWN_MS = 3_000L

		/**
		 * The room a game should use: the hub's [LobbyRoom] as-is, a solo room for null, or a view around a
		 * plain room from elsewhere (host = smallest member id, names unknown).
		 */
		fun of(room: Net.Room?, gameId: String): LobbyRoom = when (room) {
			null -> solo(gameId)
			is LobbyRoom -> room
			else -> {
				val members = room.members.ifEmpty { setOf(Net.selfId) }
				val names = members.associateWith { if (it == Net.selfId) Net.selfName else "Player" }
				val wrapped = LobbyRoom(room, members.minOrNull() ?: Net.selfId, names, null) { room.leave() }
				room.onMessage = { wrapped.onMessage(it) }
				wrapped
			}
		}

		/** Offline practice room: only you, loopback echo, ".rng" rolls generated locally. */
		fun solo(gameId: String): LobbyRoom {
			val inner = LoopbackTransport.join("solo:$gameId")
			val room = LobbyRoom(inner, Net.selfId, mapOf(Net.selfId to Net.selfName), null) { inner.leave() }
			inner.onMessage = { room.onMessage(it) }
			MinigameHub.soloStarted(room)
			return room
		}
	}
}

/** Roughly how long one match takes (shown on the game cards). */
enum class GameLength(val label: String, val hint: String, val color: Int) {
	SHORT("Short", "about 1–3 minutes", 0xFF6FD68A.toInt()),
	MEDIUM("Medium", "about 3–8 minutes", 0xFFFFD45E.toInt()),
	LONG("Long", "10+ minutes", 0xFFFF8A6A.toInt()),
}
