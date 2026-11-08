package dev.skyparty.minigames.common

import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import java.util.UUID
import kotlin.random.Random

/**
 * Shared parts of the round-based party games (quiz, typing, trader, fishing): players plus solo bots,
 * scores, a timer bar, the score panel and the game-over block with "Play again" / "Leave".
 *
 * Conventions like Bazaar Bluff: the host decides when rounds start and end and sends each such message only
 * once ([once] with the [match] counter); every client applies the same messages in the same order.
 */
abstract class RoundGameScreen(title: String, val room: LobbyRoom, botNames: List<String>) : GameScreen(title, room) {
	/** Everyone playing (players and, solo, the bots), in join order. */
	val names = linkedMapOf<UUID, String>()
	val bots = mutableSetOf<UUID>()
	val scores = linkedMapOf<UUID, Int>()
	/** Bumped by "Play again" so the host announces the rounds of the new match once each. */
	var match = 0
	private var rewarded = false

	init {
		names.putAll(room.names)
		if (room.solo) for (n in botNames) {
			val id = UUID(Random.nextLong(), Random.nextLong())
			names[id] = n
			bots += id
		}
	}

	val me: UUID get() = Net.selfId
	/** The host (or solo) runs the rounds. */
	val hostLike get() = room.solo || room.isHost
	fun now() = room.now()

	fun play(sound: SoundEvent, pitch: Float = 1f) = Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch))

	fun addScore(id: UUID, pts: Int) {
		if (pts != 0) scores[id] = (scores[id] ?: 0) + pts
	}

	/** Called by the game when a new match starts on every client. */
	protected fun resetMatch() {
		scores.clear()
		rewarded = false
		names.keys.forEach { scores[it] = 0 }
	}

	/** Tokens and highscore once per match; returns whether you won. */
	protected fun rewardMatch(gameId: String, display: (Int) -> String = { "$it points" }): Boolean {
		val best = scores.values.maxOrNull() ?: 0
		val mine = scores[me] ?: 0
		val won = mine == best && best > 0
		if (!rewarded) {
			rewarded = true
			MinigameStore.addTokens(if (won) 8 else 2)
			MinigameStore.submit(gameId, mine.toDouble(), false, display(mine))
		}
		return won
	}

	// ------------------------------------------------------------------ drawing helpers

	/** Score panel in the top-right corner (only when there is room beside [contentRight]). */
	protected fun drawScores(g: GuiGraphicsExtractor, contentRight: Int, label: (Int) -> String = { it.toString() }) {
		val x = width - 110
		if (x < contentRight + 6) {
			// Not enough room: one line along the bottom.
			val line = names.entries.sortedByDescending { scores[it.key] ?: 0 }
				.joinToString("  ") { (id, n) -> (if (id == me) "§e" else "§f") + n + " §6" + label(scores[id] ?: 0) }
			centered(g, fit("§7Scores: $line", width - 16), width / 2, height - 12, Theme.TEXT)
			return
		}
		val y = 30
		panel(g, x, y, 102, 16 + names.size * 11, "Scores")
		names.entries.sortedByDescending { scores[it.key] ?: 0 }.forEachIndexed { i, (id, name) ->
			val s = label(scores[id] ?: 0)
			g.text(font, (if (id == me) "§e" else "§f") + font.plainSubstrByWidth(name, 96 - font.width(s) - 10), x + 6, y + 19 + i * 11, Theme.TEXT, false)
			g.text(font, s, x + 96 - font.width(s), y + 19 + i * 11, GameColors.GOLD, false)
		}
	}

	protected fun timer(g: GuiGraphicsExtractor, cx: Int, y: Int, frac: Float, w: Int = 180) {
		val f = frac.coerceIn(0f, 1f)
		g.fill(cx - w / 2, y, cx + w / 2, y + 3, 0xFF2A1A44.toInt())
		g.fill(cx - w / 2, y, cx - w / 2 + (w * f).toInt(), y + 3, if (f > 0.3f) GameColors.GREEN else GameColors.RED)
	}

	/** Winner line, then "Play again" for the host (or solo) and "Leave". */
	protected fun drawOver(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int, label: (Int) -> String = { "$it points" }, again: () -> Unit) {
		val best = scores.values.maxOrNull() ?: 0
		val winners = scores.filterValues { it == best }.keys.map { if (it == me) "You" else names[it] ?: "?" }
		val verb = if (winners.size == 1 && winners[0] != "You") "wins" else "win"
		centered(g, "§6§l♛ ${winners.joinToString(" & ")} $verb with ${label(best)}!", cx, y, Theme.TEXT)
		if (hostLike) button(g, cx - 100, y + 14, 92, 16, "Play again", mx, my, color = GameColors.GREEN) { match++; again() }
		else centered(g, "§7Waiting for the host…", cx - 54, y + 18, Theme.TEXT)
		button(g, cx + 8, y + 14, 92, 16, "Leave", mx, my, color = GameColors.RED) { room.quit(); onClose() }
	}

	override fun removed() {
		room.leave()
		super.removed()
	}
}
