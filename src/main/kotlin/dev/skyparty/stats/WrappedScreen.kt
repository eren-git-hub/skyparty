package dev.skyparty.stats

import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.HubTabs
import dev.skyparty.module.impl.stats.Wrapped
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor

/** SkyBlock Wrapped tab: a grid of recap cards for the last week / month / year. */
class WrappedScreen(
	private var period: WrappedSummary.Period =
		WrappedSummary.Period.parse(Wrapped.defaultPeriod.value) ?: WrappedSummary.Period.WEEK,
) : GameScreen("SkyBlock Wrapped") {
	override val backToHub = false

	private class Card(val title: String, val value: String, val sub: String, val color: Int, val hours: LongArray? = null)

	private lateinit var summary: WrappedSummary
	private var cards = emptyList<Card>()
	private var openedAt = 0L
	private var copiedAt = 0L

	init { switchTo(period) }

	private fun switchTo(p: WrappedSummary.Period) {
		period = p
		StatsStore.save()
		summary = WrappedSummary(p)
		cards = buildCards(summary)
		openedAt = System.currentTimeMillis()
	}

	private fun buildCards(s: WrappedSummary): List<Card> {
		val gold = 0xFFFFC857.toInt()
		val green = 0xFF6BE38E.toInt()
		val red = 0xFFFF6B6B.toInt()
		val purple = 0xFFC48BFF.toInt()
		val blue = 0xFF7FB8FF.toInt()
		val drop = s.biggestDrop
		return listOf(
			Card("PLAYTIME", WrappedSummary.duration(s.playtimeMs), "${s.daysPlayed} of ${s.period.days} days played", Theme.TEXT),
			Card("TOP AREA", s.topArea?.key ?: "-", s.topArea?.let { WrappedSummary.duration(it.value) } ?: "No area seen yet", blue),
			Card("COINS GAINED", WrappedSummary.compact(s.coinsGained.toDouble()),
				"${WrappedSummary.compact(s.coinsLost.toDouble())} spent or lost", gold),
			Card("SKILL XP", WrappedSummary.compact(s.skillTotal),
				s.topSkill?.let { "Top: ${it.key} ${WrappedSummary.compact(it.value)}" } ?: "No XP gained", green),
			Card("BIGGEST DROP", drop?.item ?: "-",
				drop?.let { it.rarity.removeSuffix("!").lowercase().replaceFirstChar(Char::uppercase) +
					(if (it.magicFind > 0) " · +${it.magicFind}% MF" else "") } ?: "No rare drops yet",
				drop?.let { rarityColor(it.rarity) } ?: Theme.TEXT),
			Card("RARE DROPS", s.drops.size.toString(),
				s.drops.maxOfOrNull { it.magicFind }?.takeIf { it > 0 }?.let { "Best Magic Find +$it%" } ?: "RARE DROP! and better", purple),
			Card("DEATHS", s.deaths.toString(), s.topDeathCause?.let { "Mostly: ${it.key}" } ?: "Not a single one", red),
			Card("DUNGEON RUNS", s.runTotal.toString(), s.topFloor?.let { "Top floor: ${it.key} ×${it.value}" } ?: "No runs yet", blue),
			Card("SLAYER BOSSES", s.slayerTotal.toString(), s.topSlayer?.let { "${it.key} ×${it.value}" } ?: "No quests yet", red),
			Card("MOST ACTIVE HOUR", s.activeHour?.let { "%02d:00".format(it) } ?: "-",
				s.activeHour?.let(WrappedSummary::hourRange) ?: "", gold, s.hours),
			Card("BEST DAY", s.bestDay?.let { WrappedSummary.duration(it.playtimeMs) } ?: "-", s.bestDayLabel ?: "Play a bit first", green),
			Card("AVERAGE DAY", WrappedSummary.duration(s.averagePerDayMs), "On days you played", purple),
		)
	}

	private fun rarityColor(rarity: String): Int = when (StatsPatterns.RARITY_COLOR[rarity]) {
		"§9" -> 0xFF5B8CFF.toInt()
		"§a" -> 0xFF6BE38E.toInt()
		"§5" -> 0xFFB45BFF.toInt()
		"§d" -> 0xFFFF6BEA.toInt()
		"§c" -> 0xFFFF5555.toInt()
		"§6" -> 0xFFFFAA00.toInt()
		else -> Theme.TEXT
	}

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = hubChrome(g, HubTabs.WRAPPED)
		val margin = 8
		val barY = top + 5

		// Period chips on the left, date range, copy button on the right.
		var x = margin
		for (p in WrappedSummary.Period.entries) {
			val w = font.width(p.label) + 14
			button(g, x, barY, w, 13, p.label, mouseX, mouseY, color = if (period == p) Theme.accent else 0xFF3A3050.toInt()) { switchTo(p) }
			x += w + 3
		}
		g.text(font, "§7${period.title} · ${summary.dateRange}", x + 6, barY + 3, Theme.TEXT, false)
		val copied = System.currentTimeMillis() - copiedAt < 2000
		button(g, width - margin - 92, barY, 92, 13, if (copied) "Copied!" else "Copy summary", mouseX, mouseY,
			hint = "Copies a short text recap to your clipboard") {
			minecraft.keyboardHandler.clipboard = summary.text()
			copiedAt = System.currentTimeMillis()
		}

		val gridTop = barY + 19
		if (!Wrapped.enabled) {
			centered(g, "§7SkyBlock Wrapped is turned off in the settings, nothing is being recorded.", width / 2, gridTop + 6, Theme.TEXT)
		}
		val cols = when {
			width < 300 -> 2
			width < 420 -> 3
			else -> 4
		}
		val rows = (cards.size + cols - 1) / cols
		val gap = 5
		val cardW = ((width - 2 * margin - (cols - 1) * gap) / cols).coerceAtMost(160)
		val cardH = ((height - gridTop - 8 - (rows - 1) * gap) / rows).coerceIn(30, 62)
		val gridW = cols * cardW + (cols - 1) * gap
		val left = (width - gridW) / 2
		val now = System.currentTimeMillis()
		cards.forEachIndexed { i, card ->
			val t = ((now - openedAt - i * 35) / 220f).coerceIn(0f, 1f)
			val ease = 1f - (1f - t) * (1f - t)
			val cx = left + (i % cols) * (cardW + gap)
			val cy = gridTop + (i / cols) * (cardH + gap) + ((1f - ease) * 10).toInt()
			renderCard(g, card, cx, cy, cardW, cardH, ease)
		}
	}

	private fun renderCard(g: GuiGraphicsExtractor, c: Card, x: Int, y: Int, w: Int, h: Int, alpha: Float) {
		val a = (alpha * 255).toInt().coerceIn(5, 255)
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, ColorUtil.withAlpha(0, a / 3))
		g.fill(x, y, x + w, y + h, ColorUtil.withAlpha(GameColors.CARD, a))
		g.fill(x, y, x + w, y + 2, ColorUtil.withAlpha(c.color, a))
		g.text(font, c.title, x + 6, y + 6, ColorUtil.withAlpha(Theme.TEXT_DIM, a), false)

		val maxW = w - 12
		val big = font.width(c.value) * 1.5f <= maxW && h >= 44
		val valueY = y + 17
		if (big) {
			val pose = g.pose()
			pose.pushMatrix()
			pose.translate((x + 6).toFloat(), valueY.toFloat())
			pose.scale(1.5f, 1.5f)
			g.text(font, c.value, 0, 0, ColorUtil.withAlpha(c.color, a), true)
			pose.popMatrix()
		} else {
			g.text(font, ellipsize(c.value, maxW), x + 6, valueY, ColorUtil.withAlpha(c.color, a), true)
		}

		val hours = c.hours
		val subY = y + h - 11
		if (hours != null && h >= 50) {
			// Tiny 24-hour activity histogram.
			val maxV = hours.maxOrNull()?.takeIf { it > 0 } ?: 1L
			val barW = ((w - 12) / 24).coerceAtLeast(1)
			val chartLeft = x + w - 6 - barW * 24
			val base = subY + 7
			for (i in 0 until 24) {
				val bh = (hours[i] * 12 / maxV).toInt()
				val bx = chartLeft + i * barW
				val bw = barW - (if (barW > 2) 1 else 0)
				g.fill(bx, base - 12, bx + bw, base, ColorUtil.withAlpha(GameColors.LINE, a))
				if (bh > 0) g.fill(bx, base - bh, bx + bw, base, ColorUtil.withAlpha(c.color, a))
			}
		} else if (c.sub.isNotEmpty()) {
			g.text(font, ellipsize(c.sub, maxW), x + 6, subY, ColorUtil.withAlpha(Theme.TEXT_DIM, a), false)
		}
	}

	private fun ellipsize(text: String, maxW: Int): String {
		if (font.width(text) <= maxW) return text
		return font.plainSubstrByWidth(text, maxW - font.width("…")) + "…"
	}
}
