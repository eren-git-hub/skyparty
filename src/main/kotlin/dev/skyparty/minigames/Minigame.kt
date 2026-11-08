package dev.skyparty.minigames

import dev.skyparty.net.Net

/**
 * CONTRACT for lobby minigames. Each game registers itself in its own init via [MinigameRegistry.register].
 * The lobby hub (minigames framework work package) lists registered games, handles invites/room codes
 * and calls [open] with the room everyone joined (null = solo / practice).
 *
 * Message types inside a room should be prefixed with the game id, e.g. "chess.move", "parkour.state".
 */
interface Minigame {
	/** Short stable id, also used as message prefix. */
	val id: String
	val name: String
	val description: String
	val minPlayers: Int
	val maxPlayers: Int
	/** Real-time games need lower latency (P2P opt-in may be used later); turn-based games don't. */
	val realtime: Boolean

	/** Roughly how long one match takes; shown as info on the game card. */
	val length: GameLength get() = GameLength.MEDIUM

	/** Opens the game screen. [room] is shared by all players; null for solo play. */
	fun open(room: Net.Room?)
}

object MinigameRegistry {
	private val games = linkedMapOf<String, Minigame>()

	fun register(game: Minigame) {
		require(game.id !in games) { "Duplicate minigame id ${game.id}" }
		games[game.id] = game
	}

	val all: Collection<Minigame> get() = games.values

	fun byId(id: String) = games[id]
}
