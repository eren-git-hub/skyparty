package dev.skyparty.pet.scoreboard

import dev.skyparty.util.Location
import net.minecraft.client.Minecraft

/**
 * Where the sidebar scoreboard is, so the scoreboard pet can stand on it.
 *
 * Normally HudSidebarMixin records the background rectangles vanilla draws. Other mods with a custom
 * scoreboard (they hide vanilla's) never trigger that, so then the box is worked out the way vanilla lays
 * the sidebar out (right edge, vertically centered) from the scoreboard's lines. With no scoreboard at
 * all the box is null and the pet hides.
 */
object SidebarBounds {
	private var left = Int.MAX_VALUE
	private var top = Int.MAX_VALUE
	private var right = Int.MIN_VALUE
	private var bottom = Int.MIN_VALUE
	private var seen = false

	/** Tests only: scale the vanilla sidebar (like a scoreboard-scale mod). */
	var testScale = 1f

	/** Tests only: pretend vanilla drew nothing (as with a custom scoreboard mod). */
	var ignoreVanillaForTest = false

	data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		val width get() = right - left
	}

	@JvmStatic
	fun record(x1: Int, y1: Int, x2: Int, y2: Int) {
		if (ignoreVanillaForTest) return
		left = minOf(left, x1, x2)
		top = minOf(top, y1, y2)
		right = maxOf(right, x1, x2)
		bottom = maxOf(bottom, y1, y2)
		seen = true
	}

	/** The box drawn this frame (or the computed one) and resets for the next frame. */
	fun consume(): Box? {
		val box = if (seen) Box(left, top, right, bottom) else computed()
		left = Int.MAX_VALUE
		top = Int.MAX_VALUE
		right = Int.MIN_VALUE
		bottom = Int.MIN_VALUE
		seen = false
		return box
	}

	/** Vanilla's sidebar layout from the scoreboard's title and lines. */
	private fun computed(): Box? {
		val title = Location.sidebarTitle
		val lines = Location.sidebarLines.take(15)
		if (title.isEmpty() && lines.isEmpty()) return null
		val mc = Minecraft.getInstance()
		val font = mc.font
		val gw = mc.window.guiScaledWidth
		val gh = mc.window.guiScaledHeight
		val maxW = maxOf(font.width(title), lines.maxOfOrNull { font.width(it) } ?: 0)
		val linesH = lines.size * 9
		val bottom = gh / 2 + linesH / 3
		val left = gw - maxW - 3 - 2
		val right = gw - 3 + 2
		val top = bottom - linesH - 9 - 1
		return Box(left, top, right, bottom)
	}
}
