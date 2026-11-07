package dev.skyparty.minigames.ui

import dev.skyparty.minigames.MinigameStore
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** Shared colors for the minigame screens (on top of the Click-GUI [Theme]). */
object GameColors {
	val BACKDROP = 0xD80A0A0F.toInt()
	val PANEL = 0xF0121218.toInt()
	val CARD = 0xF01A1A22.toInt()
	val CARD_HOVER = 0xF0242430.toInt()
	val LINE = 0xFF2A2A36.toInt()
	val GREEN = 0xFF3FBF6A.toInt()
	val RED = 0xFFD9484A.toInt()
	val GOLD = 0xFFFFC94A.toInt()
	val SILVER = 0xFFC9D1DB.toInt()
	val BRONZE = 0xFFD08A4E.toInt()
	val TOKEN = 0xFFFFD45E.toInt()
}

/**
 * Base for all minigame screens: immediate-mode buttons (declared while drawing, clicked on the next
 * mouse press), a header bar with the token balance and a dark backdrop. Never pauses the game.
 */
abstract class GameScreen(title: String) : Screen(Component.literal(title)) {
	private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit) {
		fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h
	}

	private val frameHits = mutableListOf<Hit>()
	private var hits = listOf<Hit>()
	private var tooltip: String? = null

	override fun isPauseScreen() = false

	/** Whether closing (Esc, Back, Close) returns to the games hub instead of the world. */
	protected open val backToHub = true
	private var lastMx = 0
	private var lastMy = 0

	override fun onClose() {
		if (backToHub) minecraft.gui.setScreen(HubScreen()) else super.onClose()
	}

	override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		g.fillGradient(0, 0, width, height, 0xE0301050.toInt(), 0xE00C1030.toInt())
	}

	final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.extractRenderState(g, mouseX, mouseY, partialTick)
		frameHits.clear()
		tooltip = null
		lastMx = mouseX
		lastMy = mouseY
		draw(g, mouseX, mouseY, partialTick)
		hits = frameHits.toList()
		tooltip?.let { g.setTooltipForNextFrame(font, Component.literal(it), mouseX, mouseY) }
	}

	abstract fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float)

	/** Called for left clicks that hit no button. */
	open fun onClickAnywhere(mx: Double, my: Double): Boolean = false

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (event.button() == 0) {
			hits.lastOrNull { it.contains(event.x(), event.y()) }?.let {
				it.action()
				return true
			}
			if (onClickAnywhere(event.x(), event.y())) return true
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun keyPressed(event: KeyEvent): Boolean = super.keyPressed(event)

	// ------------------------------------------------------------------ widgets

	protected fun hovered(x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) = mx >= x && mx < x + w && my >= y && my < y + h

	/** Invisible click target. */
	protected fun clickArea(x: Int, y: Int, w: Int, h: Int, action: () -> Unit) {
		frameHits += Hit(x, y, w, h, action)
	}

	protected fun tooltip(text: String) {
		tooltip = text
	}

	protected fun button(
		g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, label: String, mx: Int, my: Int,
		enabled: Boolean = true, color: Int = Theme.accent, hint: String? = null, action: () -> Unit,
	) {
		val hover = enabled && hovered(x, y, w, h, mx, my)
		val base = if (enabled) color else 0xFF3A3A46.toInt()
		g.fill(x, y, x + w, y + h, ColorUtil.withAlpha(ColorUtil.shade(base, if (hover) 0.75f else 0.5f), 0xF0))
		g.fill(x, y + h - 1, x + w, y + h, ColorUtil.shade(base, if (hover) 1.15f else 0.9f))
		if (hover) g.outline(x, y, w, h, ColorUtil.withAlpha(ColorUtil.shade(base, 1.3f), 0xC0))
		val text = font.plainSubstrByWidth(label, w - 4)
		val textColor = if (enabled) Theme.TEXT else Theme.TEXT_DIM
		g.text(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, textColor, true)
		if (hint != null && hovered(x, y, w, h, mx, my)) tooltip(hint)
		if (enabled) clickArea(x, y, w, h, action)
	}

	protected fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, title: String? = null) {
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, Theme.SHADOW)
		g.fill(x, y, x + w, y + h, GameColors.PANEL)
		g.outline(x, y, w, h, GameColors.LINE)
		if (title != null) {
			g.text(font, title, x + 6, y + 5, Theme.accent, true)
			g.fill(x + 6, y + 15, x + w - 6, y + 16, GameColors.LINE)
		}
	}

	/** Top bar: game title, subtitle and the token balance. Returns its height. */
	protected fun header(g: GuiGraphicsExtractor, title: String, subtitle: String? = null): Int {
		val h = 22
		g.fill(0, 0, width, h, Theme.HEADER)
		for (i in 0 until width step 2) g.fill(i, h - 2, i + 2, h, Theme.rainbow(i / width.toFloat()))
		var tx = 8
		if (backToHub) {
			button(g, 4, 4, 42, 14, "◀ Back", lastMx, lastMy, hint = "Back to the games overview (Esc)") { onClose() }
			tx = 52
		}
		g.text(font, "§l★ $title", tx, 7, Theme.rainbow(0f, 0.55f), true)
		if (subtitle != null) g.text(font, subtitle, tx + 6 + font.width("§l★ $title"), 7, Theme.TEXT_DIM, false)
		tokenChip(g, width - 8, 5)
		return h
	}

	/**
	 * Top of the main Skyparty menu: title, Pets + Settings buttons and the token chip, then the tab bar
	 * (Games, Skyblockdle, News, Reminders). Returns the y where content starts.
	 */
	protected fun hubChrome(g: GuiGraphicsExtractor, active: Int): Int {
		val h = 22
		g.fill(0, 0, width, h, Theme.HEADER)
		for (i in 0 until width step 2) g.fill(i, h - 2, i + 2, h, Theme.rainbow(i / width.toFloat()))
		val title = Component.literal("★ Skyparty").withStyle(net.minecraft.ChatFormatting.BOLD)
		g.text(font, title, 8, 7, Theme.rainbow(0f, 0.55f), true)
		// Measure the styled component (bold widths differ per font / resource pack) and leave a clear gap.
		g.text(font, "- by Exlusiv", 8 + font.width(title) + 8, 7, Theme.TEXT_DIM, false)
		val chipW = font.width("${MinigameStore.balance()} Tokens") + 16
		tokenChip(g, width - 8, 5)
		val sx = width - 8 - chipW - 6 - 62
		button(g, sx, 4, 62, 14, "⚙ Settings", lastMx, lastMy) { minecraft.gui.setScreen(dev.skyparty.ui.screens.SettingsScreen()) }
		button(g, sx - 52, 4, 48, 14, "❀ Pets", lastMx, lastMy, color = 0xFFFF7EB6.toInt()) { minecraft.gui.setScreen(dev.skyparty.ui.screens.PetsScreen()) }
		// Tab bar
		var x = 8
		val ty = h + 3
		for ((i, tab) in HubTabs.names.withIndex()) {
			val w = font.width(tab) + 14
			val on = i == active
			val hover = hovered(x, ty, w, 14, lastMx, lastMy)
			g.fill(x, ty, x + w, ty + 14, if (on) ColorUtil.withAlpha(Theme.accent, 0xD0) else if (hover) GameColors.CARD_HOVER else GameColors.CARD)
			if (on) g.fill(x, ty + 13, x + w, ty + 14, Theme.rainbow(i * 0.2f))
			g.text(font, tab, x + 7, ty + 3, if (on) Theme.TEXT else Theme.TEXT_DIM, on)
			if (!on) clickArea(x, ty, w, 14) { minecraft.gui.setScreen(HubTabs.open(i)) }
			x += w + 3
		}
		return ty + 14
	}

	/** Token balance chip, right-aligned at [right]. */
	protected fun tokenChip(g: GuiGraphicsExtractor, right: Int, y: Int) {
		val text = "${MinigameStore.balance()} Tokens"
		val w = font.width(text) + 16
		val x = right - w
		g.fill(x, y, right, y + 12, 0xFF1E1A10.toInt())
		g.outline(x, y, w, 12, ColorUtil.withAlpha(GameColors.TOKEN, 0x90))
		drawCoin(g, x + 3, y + 2)
		g.text(font, text, x + 13, y + 2, GameColors.TOKEN, false)
	}

	protected fun drawCoin(g: GuiGraphicsExtractor, x: Int, y: Int) {
		g.fill(x + 1, y, x + 7, y + 8, GameColors.TOKEN)
		g.fill(x, y + 1, x + 8, y + 7, GameColors.TOKEN)
		g.fill(x + 2, y + 2, x + 6, y + 6, 0xFFE0A82E.toInt())
		g.fill(x + 3, y + 3, x + 5, y + 5, GameColors.TOKEN)
	}

	/** Draws [text] scaled by [scale], centered on [cx]. */
	protected fun bigText(g: GuiGraphicsExtractor, text: String, cx: Int, y: Int, scale: Float, color: Int) {
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(cx.toFloat(), y.toFloat())
		pose.scale(scale, scale)
		g.text(font, text, -font.width(text) / 2, 0, color, true)
		pose.popMatrix()
	}

	protected fun centered(g: GuiGraphicsExtractor, text: String, cx: Int, y: Int, color: Int, shadow: Boolean = true) {
		g.text(font, text, cx - font.width(text) / 2, y, color, shadow)
	}
}
