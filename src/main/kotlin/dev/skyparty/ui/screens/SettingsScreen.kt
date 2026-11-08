package dev.skyparty.ui.screens

import dev.skyparty.config.ConfigManager
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.module.Category
import dev.skyparty.module.Module
import dev.skyparty.module.ModuleManager
import dev.skyparty.ui.Theme
import dev.skyparty.ui.UiFocus
import dev.skyparty.ui.elements.SettingElement
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * Base for screens that show module cards with their settings (Settings, Pets). A module's settings are
 * drawn with the shared [SettingElement]s; clicks, drags and typing are forwarded to them.
 */
abstract class ModuleSettingsScreen(title: String) : GameScreen(title) {
	private val elements = HashMap<Module, List<SettingElement>>()
	private val visible = mutableListOf<SettingElement>()
	private var scroll = 0
	private var maxScroll = 0
	private var area = intArrayOf(0, 0, 0, 0)

	private fun elementsOf(m: Module) = elements.getOrPut(m) { m.settings.map { SettingElement.of(it) } }

	/** Draws [modules] as cards in a scrollable column at (x, y, w, h). */
	protected fun drawModules(
		g: GuiGraphicsExtractor, modules: List<Module>, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int,
		/** Extra card drawn above the modules at (x, y, w); returns its height. */
		header: ((Int, Int, Int) -> Int)? = null,
	) {
		area = intArrayOf(x, y, w, h)
		visible.clear()
		g.enableScissor(x, y, x + w, y + h)
		var cy = y - scroll
		if (header != null) cy += header(x, cy, w) + 6
		for (m in modules) {
			// Only settings that are shown; a feature with none (e.g. the connection) gets no card.
			val els = elementsOf(m).filter { it.setting.visible() }
			if (els.isEmpty()) continue
			val cardH = 30 + els.sumOf { it.height + 1 } + 4
			g.fill(x, cy, x + w, cy + cardH, GameColors.CARD)
			g.fill(x, cy, x + 2, cy + cardH, m.category.color)
			g.text(font, "§l" + m.name, x + 8, cy + 5, Theme.TEXT, true)
			g.text(font, fit(m.description, w - if (m.switchable) 46 else 16), x + 8, cy + 17, Theme.TEXT_DIM, false)
			if (m.switchable) {
				val tx = x + w - 34
				val ty = cy + 6
				val on = m.enabled
				g.fill(tx, ty, tx + 26, ty + 12, if (on) GameColors.GREEN else 0xFF3A3A46.toInt())
				val knob = if (on) tx + 15 else tx + 1
				g.fill(knob, ty + 1, knob + 10, ty + 11, Theme.TEXT)
				if (my in y until y + h) clickArea(tx, ty, 26, 12) { m.setEnabled(!m.enabled); ConfigManager.save() }
			}
			var ey = cy + 30
			for (e in els) {
				e.x = x + 6
				e.y = ey
				e.width = w - 12
				if (ey + e.height > y && ey < y + h) {
					e.render(g, mx, my)
					visible += e
				}
				ey += e.height + 1
			}
			cy += cardH + 6
		}
		g.disableScissor()
		val total = cy + scroll - y
		maxScroll = (total - h).coerceAtLeast(0)
		scroll = scroll.coerceIn(0, maxScroll)
		if (maxScroll > 0) {
			val barH = (h * h / (h + maxScroll)).coerceAtLeast(12)
			val barY = y + (h - barH) * scroll / maxScroll
			g.fill(x + w + 2, barY, x + w + 4, barY + barH, ColorUtil.withAlpha(Theme.accent, 0xC0))
		}
	}

	override fun onClickAnywhere(mx: Double, my: Double): Boolean {
		val (x, y, w, h) = area
		if (mx < x || mx >= x + w || my < y || my >= y + h) { UiFocus.focused = null; return false }
		for (e in visible) if (e.mouseClicked(mx.toInt(), my.toInt(), 0)) return true
		UiFocus.focused = null
		return false
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (event.button() != 0) for (e in visible) if (e.mouseClicked(event.x().toInt(), event.y().toInt(), event.button())) return true
		return super.mouseClicked(event, doubleClick)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		for (e in visible) e.mouseReleased(event.x().toInt(), event.y().toInt(), event.button())
		ConfigManager.save()
		return super.mouseReleased(event)
	}

	override fun onScroll(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		scroll = (scroll - (scrollY * 20).toInt()).coerceIn(0, maxScroll)
		return true
	}

	override fun onKey(event: KeyEvent): Boolean {
		UiFocus.focused?.let { if (it.keyPressed(event.key())) return true }
		return super.onKey(event)
	}

	override fun onChar(event: CharacterEvent): Boolean {
		if (!event.isAllowedChatCharacter()) return false
		UiFocus.focused?.let { return it.charTyped(event.codepointAsString()) }
		return false
	}

	override fun removed() {
		UiFocus.focused = null
		ConfigManager.save()
		super.removed()
	}
}

/** ⚙ Settings: every feature except the pets (those live on the Pets screen). */
class SettingsScreen : ModuleSettingsScreen("Settings") {
	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = header(g, "Settings", "features and interface")
		// Interface (colors) first, then the features.
		val modules = ModuleManager.modules.filter { it.category != Category.PETS }.sortedBy { it.category != Category.CLIENT }
		val w = minOf(320, width - 40)
		drawModules(g, modules, width / 2 - w / 2, top + 8, w, height - top - 16, mouseX, mouseY) { x, y, cw -> drawActiveUsers(g, x, y, cw) }
	}

	/** Not a setting, but the Settings tab is where it fits: everyone online with Skyparty right now. */
	private fun drawActiveUsers(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int): Int {
		val names = dev.skyparty.net.ActiveUsers.names
		val online = dev.skyparty.net.ActiveUsers.online
		// Names in columns, as many as fit.
		val colW = (names.maxOfOrNull { font.width(it) } ?: 40) + 12
		val cols = ((w - 16) / colW).coerceAtLeast(1)
		val rows = (names.size + cols - 1) / cols
		val h = if (names.isEmpty()) 30 else 30 + rows * 10 + 6
		g.fill(x, y, x + w, y + h, GameColors.CARD)
		g.fill(x, y, x + 2, y + h, GameColors.GREEN)
		g.text(font, bold("Active users"), x + 8, y + 5, Theme.TEXT, true)
		val sub = if (online) "§7${names.size} online with Skyparty right now" else "§8Offline – connect to the Skyparty server to see who's on"
		g.text(font, fit(sub, w - 16), x + 8, y + 17, Theme.TEXT, false)
		for ((i, n) in names.withIndex()) {
			val me = n.equals(dev.skyparty.net.Net.selfName, ignoreCase = true)
			g.text(font, (if (me) "§a● §f" else "§a● §7") + n, x + 8 + (i % cols) * colW, y + 30 + (i / cols) * 10, Theme.TEXT, false)
		}
		return h
	}
}
