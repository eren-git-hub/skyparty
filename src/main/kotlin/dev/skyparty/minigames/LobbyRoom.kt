package dev.skyparty.minigames

import com.google.gson.JsonObject
import dev.skyparty.net.LoopbackTransport
import dev.skyparty.net.Net
import java.util.UUID

/**
 * The [Net.Room] handed to [Minigame.open]. It wraps the lobby's real room so the hub keeps receiving
 * lobby messages while a game runs: every non-"lobby.*" message is forwarded to [onMessage].
 *
 * [members] are only the players who joined this game (not everyone in the party), [host] is the
 * player who started it. Games may cast `room as? LobbyRoom` to read [host] and [names].
 * [leave] only ends your participation in the game; the underlying party/code room stays joined.
 */
class LobbyRoom internal constructor(
	private val inner: Net.Room,
	val host: UUID,
	players: Map<UUID, String>,
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

	override fun send(type: String, payload: JsonObject) = inner.send(type, payload)

	override fun leave() = onLeave(this)

	internal fun removePlayer(id: UUID) {
		if (players.remove(id) != null) onMembersChanged(members)
	}

	companion object {
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
				val wrapped = LobbyRoom(room, members.minOrNull() ?: Net.selfId, names) { room.leave() }
				room.onMessage = { wrapped.onMessage(it) }
				wrapped
			}
		}

		/** Offline practice room: only you, loopback echo, ".rng" rolls generated locally. */
		fun solo(gameId: String): LobbyRoom {
			val inner = LoopbackTransport.join("solo:$gameId")
			val room = LobbyRoom(inner, Net.selfId, mapOf(Net.selfId to Net.selfName)) { inner.leave() }
			inner.onMessage = { room.onMessage(it) }
			return room
		}
	}
}
