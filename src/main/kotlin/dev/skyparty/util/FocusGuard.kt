package dev.skyparty.util

import net.minecraft.client.Minecraft

/**
 * Swallows the click that brings the game window back into focus (after Alt+Tab). Windows passes that
 * click on to the game, so it used to press whatever button was under the cursor (e.g. "New room").
 */
object FocusGuard {
	private const val GRACE_MS = 400L
	private var wasActive = true
	private var focusedAt = 0L

	fun tick(mc: Minecraft) {
		val active = mc.isWindowActive
		if (active && !wasActive) focusedAt = System.currentTimeMillis()
		wasActive = active
	}

	/** True right after the window regained focus, or while it is still unfocused. */
	val ignoreClicks: Boolean
		get() {
			if (!Minecraft.getInstance().isWindowActive) return true
			return System.currentTimeMillis() - focusedAt < GRACE_MS
		}
}
