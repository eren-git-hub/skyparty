package dev.skyparty.minigames.ui

import dev.skyparty.minigames.CombatGuard
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameRegistry
import dev.skyparty.ui.Theme
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Small popup in the top-right corner while a minigame invite is pending. */
object InviteToast {
	fun render(g: GuiGraphicsExtractor, delta: DeltaTracker) {
		val invite = MinigameHub.invite ?: return
		val mc = Minecraft.getInstance()
		if (mc.gui.screen() != null || CombatGuard.blocked) return
		val game = MinigameRegistry.byId(invite.gameId) ?: return
		val font = mc.font
		val line1 = "§b${game.name} §7invite"
		val line2 = "§ffrom §e${invite.hostName}"
		val line3 = "§7/sp games accept"
		val w = maxOf(font.width(line1), font.width(line2), font.width(line3)) + 16
		val h = 40
		val x = g.guiWidth() - w - 6
		val y = 6
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, Theme.SHADOW)
		g.fill(x, y, x + w, y + h, GameColors.PANEL)
		g.fill(x, y, x + 2, y + h, Theme.accent(0xFF))
		g.text(font, line1, x + 8, y + 4, Theme.TEXT, true)
		g.text(font, line2, x + 8, y + 15, Theme.TEXT, true)
		g.text(font, line3, x + 8, y + 26, Theme.TEXT, true)
		val left = 1f - (System.currentTimeMillis() - invite.at).toFloat() / MinigameHub.INVITE_TIMEOUT_MS
		g.fill(x + 2, y + h - 1, x + 2 + ((w - 2) * left.coerceIn(0f, 1f)).toInt(), y + h, Theme.accent(0xC0))
	}
}
