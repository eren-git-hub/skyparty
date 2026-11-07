package dev.skyparty.pet.scoreboard

/**
 * Union of the sidebar background rectangles drawn this frame (filled by HudSidebarMixin).
 * [consume] hands the box to the pet once per frame and resets, so a hidden sidebar yields null.
 */
object SidebarBounds {
	private var left = Int.MAX_VALUE
	private var top = Int.MAX_VALUE
	private var right = Int.MIN_VALUE
	private var bottom = Int.MIN_VALUE
	private var seen = false

	data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		val width get() = right - left
	}

	@JvmStatic
	fun record(x1: Int, y1: Int, x2: Int, y2: Int) {
		left = minOf(left, x1, x2)
		top = minOf(top, y1, y2)
		right = maxOf(right, x1, x2)
		bottom = maxOf(bottom, y1, y2)
		seen = true
	}

	fun consume(): Box? {
		val box = if (seen) Box(left, top, right, bottom) else null
		left = Int.MAX_VALUE
		top = Int.MAX_VALUE
		right = Int.MIN_VALUE
		bottom = Int.MIN_VALUE
		seen = false
		return box
	}
}
