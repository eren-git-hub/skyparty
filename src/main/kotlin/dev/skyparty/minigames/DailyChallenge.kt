package dev.skyparty.minigames

import dev.skyparty.minigames.parkour.ParkourScreen
import dev.skyparty.net.Net

/**
 * Daily Parkour: the same course for everyone, a new one every day at 00:00 UTC. The seed comes from the
 * date, so no server is needed to agree on it; only the finish time goes to the leaderboard.
 */
object DailyChallenge : Minigame {
	const val ID = "daily"
	override val id = ID
	override val name = "Daily Parkour"
	override val description = "One course for everyone, new every day. Beat the others' times in the Ranks tab."
	override val minPlayers = 1
	override val maxPlayers = 1
	override val realtime = true
	override val length = GameLength.SHORT

	private const val DAY_MS = 86_400_000L

	fun today(): Long = System.currentTimeMillis() / DAY_MS

	/** Parkour seed of the day (1..1,000,000, like random courses). */
	fun seed(day: Long = today()): Long {
		var x = day * 0x9E3779B97F4A7C15uL.toLong() + 0x2545F491
		x = x xor (x ushr 29)
		x *= 0xBF58476D1CE4E5B9uL.toLong()
		x = x xor (x ushr 32)
		return Math.floorMod(x, 1_000_000L) + 1
	}

	fun msUntilNext(): Long = (today() + 1) * DAY_MS - System.currentTimeMillis()

	override fun open(room: Net.Room?) = MinigameHub.openScreen(ParkourScreen(LobbyRoom.solo("parkour"), seed(), daily = true))

	fun init() = MinigameRegistry.register(this)
}
