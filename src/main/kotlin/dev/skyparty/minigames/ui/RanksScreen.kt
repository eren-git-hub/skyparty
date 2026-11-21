package dev.skyparty.minigames.ui

import dev.skyparty.minigames.DailyChallenge
import dev.skyparty.minigames.Leaderboards
import dev.skyparty.minigames.MinigameRegistry
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor

/** "♛ Ranks" tab: today's and this week's top 10 per game from the Skyparty server. */
class RanksScreen(private var game: String = DailyChallenge.ID) : GameScreen("Ranks") {
	override val backToHub = false
	/** Daily Parkour is only ranked per day; the other games default to the week. */
	private var daily = game == DailyChallenge.ID

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = hubChrome(g, HubTabs.RANKS)
		val m = 8
		val y = top + m
		val h = height - y - m
		val sideW = (width * 0.3f).toInt().coerceIn(110, 150)

		// Board list
		panel(g, m, y, sideW, h, "Leaderboards")
		var cy = y + 20
		for ((id, name) in Leaderboards.BOARDS) {
			if (cy + 15 > y + h - 4) break
			val on = id == game
			val hover = hovered(m + 4, cy, sideW - 8, 14, mouseX, mouseY)
			g.fill(m + 4, cy, m + sideW - 4, cy + 14, if (on) Theme.accent(0xA0) else if (hover) GameColors.CARD_HOVER else GameColors.CARD)
			g.text(font, fit((if (id == DailyChallenge.ID) "§e☀ " else "") + name, sideW - 16), m + 8, cy + 3, if (on) Theme.TEXT else Theme.TEXT_DIM, on)
			if (!on) clickArea(m + 4, cy, sideW - 8, 14) { game = id; daily = id == DailyChallenge.ID }
			cy += 16
		}

		// The board
		val x = m * 2 + sideW
		val w = width - x - m
		val title = Leaderboards.BOARDS[game] ?: game
		panel(g, x, y, w, h, title)
		if (game != DailyChallenge.ID) {
			val bw = 52
			button(g, x + w - bw * 2 - 8, y + 2, bw, 12, "Today", mouseX, mouseY, color = if (daily) GameColors.GREEN else 0xFF3A3050.toInt()) { daily = true }
			button(g, x + w - bw - 6, y + 2, bw, 12, "This week", mouseX, mouseY, color = if (!daily) GameColors.GREEN else 0xFF3A3050.toInt()) { daily = false }
		}
		val ix = x + 8
		val iw = w - 16
		var ly = y + 22
		if (game == DailyChallenge.ID) {
			button(g, ix, ly, iw, 16, "▶ Play today's course", mouseX, mouseY, color = GameColors.GREEN,
				hint = "Same course for everyone. Your best time today counts.") { DailyChallenge.open(null) }
			ly += 22
		}
		val board = Leaderboards.get(game, daily)
		if (board == null) {
			val msg = when {
				!Net.transport.connected -> "§7Connect to the Skyparty server to see the ranks (Settings → Online)."
				Leaderboards.isLoading(game, daily) -> "§7Loading…"
				else -> "§cCouldn't load the ranks. Trying again soon."
			}
			centered(g, fit(msg, iw), x + w / 2, y + h / 2, Theme.TEXT)
			return
		}
		val resetMs = board.resetsAt - System.currentTimeMillis()
		val reset = "§8${if (daily) "New day" else "New week"} in ${duration(resetMs)} · ${board.total} player${if (board.total == 1) "" else "s"}"
		g.text(font, fit(reset, iw), ix, ly, Theme.TEXT, false)
		ly += 13
		if (board.top.isEmpty()) {
			centered(g, "§7Nobody yet – be the first!", x + w / 2, ly + 20, Theme.TEXT)
		}
		for ((i, e) in board.top.withIndex()) {
			if (ly + 13 > y + h - 22) break
			val color = when (i) { 0 -> GameColors.GOLD; 1 -> GameColors.SILVER; 2 -> GameColors.BRONZE; else -> Theme.TEXT }
			if (e.me) g.fill(ix - 2, ly - 2, ix + iw + 2, ly + 10, Theme.accent(0x50))
			g.text(font, "${i + 1}.", ix, ly, color, false)
			g.text(font, fit(e.name, iw - 90), ix + 20, ly, if (e.me) Theme.TEXT else color, e.me)
			g.text(font, e.display, ix + iw - font.width(e.display), ly, color, false)
			ly += 13
		}
		val mine = board.myRank
		val line = when {
			mine == null -> "§7You have no result here yet."
			mine <= board.top.size -> "§aYou are #$mine §7(${board.myDisplay})"
			else -> "§eYou are #$mine §7(${board.myDisplay})"
		}
		g.fill(ix, y + h - 20, ix + iw, y + h - 19, GameColors.LINE)
		g.text(font, fit(line, iw), ix, y + h - 14, Theme.TEXT, false)
		val game = MinigameRegistry.byId(this.game)
		if (game != null && this.game != DailyChallenge.ID) {
			button(g, ix + iw - 60, y + h - 17, 60, 13, "Play", mouseX, mouseY, color = GameColors.GREEN, hint = "Open ${game.name}") {
				minecraft.gui.setScreen(HubScreen())
			}
		}
	}

	private fun duration(ms: Long): String {
		val m = (ms / 60_000).coerceAtLeast(0)
		return when {
			m >= 1440 -> "${m / 1440}d ${m % 1440 / 60}h"
			m >= 60 -> "${m / 60}h ${m % 60}m"
			else -> "${m}m"
		}
	}
}
