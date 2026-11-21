package dev.skyparty.emote

import com.mojang.blaze3d.platform.InputConstants
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Emote wheel: hold the key (B by default), point at an emote, let go. Clicking works too; the middle stops
 * your emote. [holdKey] is the key that opened it (null = opened by command, then only clicks count).
 */
class EmoteWheelScreen(private val holdKey: Int?) : Screen(Component.literal("Emotes")) {
	private class Slot(val label: String, val icon: String, val on: () -> Boolean, val action: () -> Unit)

	private val slots = Emotes.Emote.entries.map { e -> Slot(e.label, e.icon, { Emotes.active == e }) { Emotes.toggle(e) } } +
		Slot("Campfire", "♨", { Campfire.own != null }) { Campfire.toggle() }

	private var hovered = -1
	private var centerHovered = false
	private var openedAt = System.currentTimeMillis()

	override fun isPauseScreen() = false

	override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		g.fillGradient(0, 0, width, height, 0x60000000, 0x80000000.toInt())
	}

	override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.extractRenderState(g, mouseX, mouseY, partialTick)
		val cx = width / 2
		val cy = height / 2
		val radius = (minOf(width, height) * 0.32f).coerceIn(60f, 105f)
		val dx = mouseX - cx.toDouble()
		val dy = mouseY - cy.toDouble()
		val dist = hypot(dx, dy)
		centerHovered = dist < 20
		hovered = if (dist < 26) -1 else {
			// Slot 0 at the top, clockwise.
			val a = (Math.toDegrees(atan2(dx, -dy)) + 360) % 360
			((a + 180.0 / slots.size) / (360.0 / slots.size)).toInt() % slots.size
		}
		for ((i, s) in slots.withIndex()) {
			val ang = Math.toRadians(i * 360.0 / slots.size)
			val sx = cx + (sin(ang) * radius).toInt()
			val sy = cy - (cos(ang) * radius).toInt()
			val w = 58
			val h = 34
			val hot = i == hovered
			val bg = if (hot) ColorUtil.withAlpha(Theme.accent, 0xE0) else 0xD0201830.toInt()
			g.fill(sx - w / 2, sy - h / 2, sx + w / 2, sy + h / 2, bg)
			g.outline(sx - w / 2, sy - h / 2, w, h, if (s.on()) 0xFF7CE38B.toInt() else if (hot) 0xFFFFFFFF.toInt() else 0x60FFFFFF)
			val pose = g.pose()
			pose.pushMatrix()
			pose.translate(sx.toFloat(), sy - 13f)
			pose.scale(2f, 2f)
			g.centeredText(font, s.icon, 0, 0, Theme.TEXT)
			pose.popMatrix()
			g.centeredText(font, s.label, sx, sy + 6, if (hot) Theme.TEXT else Theme.TEXT_DIM)
		}
		// Middle: stop / what you point at.
		g.fill(cx - 20, cy - 10, cx + 20, cy + 10, if (centerHovered) 0xE0802030.toInt() else 0xC0201830.toInt())
		g.centeredText(font, if (centerHovered) "Stop" else "✦", cx, cy - 4, Theme.TEXT)
		val hint = if (holdKey != null) "Point and let go" else "Click an emote"
		g.centeredText(font, "§7$hint §8· moving ends emotes", cx, (cy - radius - 32).toInt().coerceAtLeast(4), Theme.TEXT)
		// Let go of the key: pick what is under the mouse.
		if (holdKey != null && System.currentTimeMillis() - openedAt > 120 && !InputConstants.isKeyDown(minecraft.window, holdKey)) choose()
	}

	private fun choose() {
		val i = hovered
		onClose()
		when {
			i >= 0 -> slots[i].action()
			centerHovered -> { Emotes.stop() }
		}
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (event.button() == 0) { choose(); return true }
		return super.mouseClicked(event, doubleClick)
	}

	override fun keyPressed(event: KeyEvent): Boolean = super.keyPressed(event)
}
