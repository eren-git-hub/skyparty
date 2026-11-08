package dev.skyparty.minigames.ui

import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
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
private const val CHAT_ROWS = 4

abstract class GameScreen(title: String, /** The running game (null for menus); pausing it freezes its clock. */ val gameRoom: LobbyRoom? = null) : Screen(Component.literal(title)) {
	init {
		gameRoom?.screen = this
		gameRoom?.inProgress = { inProgress() }
	}

	/** Whether a match is running (the host leaving it then pauses it for everyone instead of quitting). */
	protected open fun inProgress(): Boolean = false

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
		gameRoom?.let { if (it.paused) { frameHits.clear(); tooltip = null; drawPaused(g, it, mouseX, mouseY) } }
		drawPartyChat(g, mouseX, mouseY)
		hits = frameHits.toList()
		tooltip?.let { g.setTooltipForNextFrame(font, Component.literal(it), mouseX, mouseY) }
	}

	abstract fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float)

	/** Called for left clicks that hit no button. */
	open fun onClickAnywhere(mx: Double, my: Double): Boolean = false

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		// The click that refocuses the window (after Alt+Tab) must not press a button.
		if (dev.skyparty.util.FocusGuard.ignoreClicks) return true
		// A click outside the party chat box gives the keyboard back to the screen.
		if (chatFocused && !inChat(event.x(), event.y())) chatFocused = false
		if (event.button() == 0) {
			hits.lastOrNull { it.contains(event.x(), event.y()) }?.let {
				it.action()
				return true
			}
			if (onClickAnywhere(event.x(), event.y())) return true
		}
		return super.mouseClicked(event, doubleClick)
	}

	final override fun keyPressed(event: KeyEvent): Boolean {
		if (chatFocused) return chatKey(event)
		// Paused: only Esc (back to the hub, the game stays paused).
		if (gameRoom?.paused == true) return if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) super.keyPressed(event) else true
		return onKey(event)
	}

	/** Key presses (not called while the game is paused). */
	open fun onKey(event: KeyEvent): Boolean = super.keyPressed(event)

	final override fun charTyped(event: CharacterEvent): Boolean {
		if (chatFocused) {
			if (event.isAllowedChatCharacter() && chatInput.length < dev.skyparty.party.PartyChat.MAX_INPUT) chatInput += event.codepointAsString()
			return true
		}
		return if (gameRoom?.paused == true) true else onChar(event)
	}

	/** Typed characters (not called while the game is paused). */
	open fun onChar(event: CharacterEvent): Boolean = super.charTyped(event)

	final override fun tick() {
		if (gameRoom?.paused != true) gameTick()
	}

	/** Screen tick (not called while the game is paused). */
	open fun gameTick() {}

	private val onceKeys = HashSet<String>()

	/**
	 * Runs [action] only the first time for [key]. For host messages a game loop would otherwise send every
	 * frame until its own echo comes back (which floods the room and can restart rounds).
	 */
	protected fun once(key: String, action: () -> Unit) {
		if (onceKeys.add(key)) action()
	}

	// ------------------------------------------------------------------ party chat box

	private var chatFocused = false
	private var chatInput = ""
	/** Where the box was drawn this frame (x0, y0, x1, y1); empty when hidden. */
	private var chatBox = intArrayOf(0, 0, 0, 0)

	/** Bottom-right party chat: the last few party messages and a line to write into (Enter sends /pc). */
	private fun drawPartyChat(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		if (!dev.skyparty.party.PartyChat.visible) {
			chatBox = intArrayOf(0, 0, 0, 0)
			chatFocused = false
			return
		}
		val pc = dev.skyparty.party.PartyChat
		if (!pc.windowOpen) {
			// Folded: a tiny button in the corner, with a red dot when something new came in.
			chatFocused = false
			val bw = 14
			val bh = 12
			val bx = width - bw - 2
			val by = height - bh - 2
			chatBox = intArrayOf(bx, by, bx + bw, by + bh)
			val hover = hovered(bx, by, bw, bh, mx, my)
			g.fill(bx, by, bx + bw, by + bh, if (hover) 0xFF302038.toInt() else 0xFF101016.toInt())
			g.outline(bx, by, bw, bh, ColorUtil.withAlpha(0xFFFF7EB6.toInt(), if (hover) 0xE0 else 0x80))
			g.text(font, "§d✉", bx + 3, by + 2, Theme.TEXT, false)
			if (pc.unread > 0) {
				g.fill(bx + bw - 4, by - 1, bx + bw + 1, by + 4, 0xFF1A0A0A.toInt())
				g.fill(bx + bw - 3, by, bx + bw, by + 3, 0xFFFF3B3B.toInt())
			}
			if (hover) tooltip(if (pc.unread > 0) "Party chat (${pc.unread} new)" else "Party chat")
			clickArea(bx, by, bw, bh) { pc.windowOpen = true; chatFocused = true }
			return
		}
		val w = (width * 0.3f).toInt().coerceIn(140, 200)
		val rowsMax = CHAT_ROWS
		val h = 12 + rowsMax * 9 + 16
		val x = width - w - 2
		val y = height - h - 2
		chatBox = intArrayOf(x, y, x + w, y + h)
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, Theme.SHADOW)
		g.fill(x, y, x + w, y + h, 0xFF101016.toInt())
		g.outline(x, y, w, h, ColorUtil.withAlpha(0xFFFF7EB6.toInt(), if (chatFocused) 0xE0 else 0x70))
		g.text(font, "§d✉ Party chat", x + 4, y + 3, Theme.TEXT, false)
		pc.markRead()
		// Newest messages at the bottom, wrapped to the box.
		val rows = dev.skyparty.party.PartyChat.lines.toList().takeLast(rowsMax)
			.flatMap { font.split(Component.literal(dev.skyparty.party.PartyChat.format(it)), w - 8) }.takeLast(rowsMax)
		if (rows.isEmpty()) g.text(font, "§8No party messages yet", x + 4, y + 13, Theme.TEXT, false)
		for ((i, row) in rows.withIndex()) g.text(font, row, x + 4, y + 13 + (rowsMax - rows.size + i) * 9, Theme.TEXT, false)
		// Input line
		val iy = y + h - 14
		g.fill(x + 3, iy, x + w - 3, iy + 11, 0xFF0A0A0F.toInt())
		g.outline(x + 3, iy, w - 6, 11, if (chatFocused) ColorUtil.withAlpha(0xFFFF7EB6.toInt(), 0xC0) else GameColors.LINE)
		val shown = if (chatInput.isEmpty() && !chatFocused) "§8Write to your party…" else chatInput
		val visibleText = font.plainSubstrByWidth(shown.reversed(), w - 14).reversed()
		g.text(font, visibleText, x + 6, iy + 2, Theme.TEXT, false)
		if (chatFocused && (System.currentTimeMillis() / 500) % 2 == 0L) {
			val cx = x + 6 + font.width(visibleText)
			g.fill(cx, iy + 1, cx + 1, iy + 10, Theme.TEXT)
		}
		// Clicks on the box never reach what is underneath; a click focuses the input. "▾" folds it again.
		clickArea(x, y, w, h) { chatFocused = true }
		val fx = x + w - 13
		val fHover = hovered(fx, y + 1, 12, 10, mx, my)
		if (fHover) g.fill(fx, y + 1, fx + 12, y + 11, 0x40FFFFFF)
		g.text(font, "▾", fx + 3, y + 2, if (fHover) Theme.TEXT else Theme.TEXT_DIM, false)
		if (fHover) tooltip("Fold the party chat")
		clickArea(fx, y + 1, 12, 10) { pc.windowOpen = false; chatFocused = false }
	}

	private fun inChat(mx: Double, my: Double) = chatBox[2] > 0 && mx >= chatBox[0] && mx < chatBox[2] && my >= chatBox[1] && my < chatBox[3]

	/** Keys while the chat input is focused. */
	private fun chatKey(event: KeyEvent): Boolean {
		when (event.key()) {
			org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> chatFocused = false
			org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> chatInput = chatInput.dropLast(1)
			org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
				dev.skyparty.party.PartyChat.send(chatInput)
				chatInput = ""
			}
			org.lwjgl.glfw.GLFW.GLFW_KEY_V -> if (event.hasControlDownWithQuirk()) {
				chatInput = (chatInput + minecraft.keyboardHandler.clipboard.replace("\n", " ")).take(dev.skyparty.party.PartyChat.MAX_INPUT)
			}
		}
		return true
	}

	// ------------------------------------------------------------------ pause

	private fun drawPaused(g: GuiGraphicsExtractor, room: LobbyRoom, mx: Int, my: Int) {
		g.fill(0, 22, width, height, 0xB4000000.toInt())
		val w = minOf(width - 20, 270)
		val h = 96
		val x = (width - w) / 2
		val y = (height - h) / 2
		panel(g, x, y, w, h)
		g.outline(x, y, w, h, Theme.rainbow(0f, 0.5f))
		val cx = x + w / 2
		if (room.resumeAt != 0L) {
			val left = ((room.resumeAt - System.currentTimeMillis()) / 1000 + 1).coerceAtLeast(1)
			bigText(g, "Resuming in $left…", cx, y + 14, 1.5f, GameColors.GREEN)
			centered(g, "§7Get ready!", cx, y + 36, Theme.TEXT)
		} else {
			bigText(g, "❚❚ Paused", cx, y + 10, 1.5f, GameColors.GOLD)
			centered(g, "§7by §f${room.pausedBy ?: "someone"}", cx, y + 28, Theme.TEXT)
			centered(g, "§8Close it and play SkyBlock – the game waits.", cx, y + 42, Theme.TEXT)
			centered(g, "§8Type §7/sp §8to come back.", cx, y + 52, Theme.TEXT)
			val bw = (w - 24) / 3
			button(g, x + 6, y + h - 22, bw, 16, "▶ Resume", mx, my, color = GameColors.GREEN, hint = "Continue for everyone (3 s countdown)") { room.requestPause(false) }
			button(g, x + 12 + bw, y + h - 22, bw, 16, "Close", mx, my, hint = "Back to the game, the game stays paused") { minecraft.gui.setScreen(null) }
			button(g, x + 18 + bw * 2, y + h - 22, bw, 16, "Quit game", mx, my, color = GameColors.RED, hint = "Leave this game for good") {
				room.quit()
				minecraft.gui.setScreen(HubScreen())
			}
		}
	}

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
		gameRoom?.let { room ->
			if (!room.paused) {
				val chipW = font.width("${MinigameStore.balance()} Tokens") + 16
				button(g, width - 8 - chipW - 6 - 52, 4, 52, 14, "❚❚ Pause", lastMx, lastMy, color = GameColors.GOLD,
					hint = "Pause for everyone – go do a dungeon and continue later") { room.requestPause(true) }
			}
		}
		g.text(font, bold("★ $title"), tx, 7, Theme.rainbow(0f, 0.55f), true)
		if (subtitle != null) g.text(font, subtitle, tx + 7 + boldWidth("★ $title"), 7, Theme.TEXT_DIM, false)
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
		// Narrow windows: less padding, then drop the icons, so the tabs never run off the screen.
		var pad = 14
		var labels = HubTabs.names
		fun total() = labels.sumOf { font.width(it) + pad + 3 }
		if (total() > width - 16) pad = 8
		if (total() > width - 16) labels = labels.map { it.substringAfter(' ') }
		if (total() > width - 16) pad = 4
		var x = 8
		val ty = h + 3
		for ((i, tab) in labels.withIndex()) {
			val w = font.width(tab) + pad
			val on = i == active
			val hover = hovered(x, ty, w, 14, lastMx, lastMy)
			g.fill(x, ty, x + w, ty + 14, if (on) ColorUtil.withAlpha(Theme.accent, 0xD0) else if (hover) GameColors.CARD_HOVER else GameColors.CARD)
			if (on) g.fill(x, ty + 13, x + w, ty + 14, Theme.rainbow(i * 0.2f))
			g.text(font, tab, x + pad / 2, ty + 3, if (on) Theme.TEXT else Theme.TEXT_DIM, on)
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

	/** [text] in bold as a component: resource packs draw bold wider than "§l" strings measure, so measure this. */
	protected fun bold(text: String): Component = Component.literal(text).withStyle(net.minecraft.ChatFormatting.BOLD)

	protected fun boldWidth(text: String) = font.width(bold(text))

	/** [text] cut so that it fits [w] pixels in bold, ending in "…". */
	protected fun fitBold(text: String, w: Int): String {
		if (boldWidth(text) <= w) return text
		var t = text
		while (t.isNotEmpty() && boldWidth(t + "…") > w) t = t.dropLast(1)
		return t.trimEnd() + "…"
	}

	/** [text] cut to [w] pixels, ending in "…" when it was too long. */
	protected fun fit(text: String, w: Int): String =
		if (font.width(text) <= w) text else font.plainSubstrByWidth(text, (w - font.width("…")).coerceAtLeast(0)).trimEnd() + "…"

	protected fun centered(g: GuiGraphicsExtractor, text: String, cx: Int, y: Int, color: Int, shadow: Boolean = true) {
		g.text(font, text, cx - font.width(text) / 2, y, color, shadow)
	}
}
