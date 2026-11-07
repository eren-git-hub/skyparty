package dev.skyparty.ui

import dev.skyparty.module.impl.client.ClickGuiModule
import dev.skyparty.util.ColorUtil

/**
 * Party palette: deep purple panels, candy-colored categories, rainbow accents.
 * [active] is set by a panel while it renders, so its rows and settings use the category color.
 */
object Theme {
	/** Category color of the panel currently rendering, or null outside panels. */
	var active: Int? = null

	val accent: Int get() = active ?: (ClickGuiModule.accent.value or 0xFF000000.toInt())
	fun accent(alpha: Int) = ColorUtil.withAlpha(accent, alpha)

	val HEADER = 0xF2241338.toInt()
	val MODULE = 0xE81C1030.toInt()
	val MODULE_HOVER = 0xE82A1846.toInt()
	val SETTING = 0xE8150B24.toInt()
	val SETTING_HOVER = 0xE81F1234.toInt()
	val TRACK = 0xFF3A2752.toInt()
	val TEXT = 0xFFFFF6FF.toInt()
	val TEXT_DIM = 0xFFB9A6CF.toInt()
	val SHADOW = 0x70000000

	const val HEADER_HEIGHT = 20
	const val MODULE_HEIGHT = 15
	const val SETTING_HEIGHT = 13

	private val start = System.currentTimeMillis()

	/** Seconds since the client started, for animations. */
	val time: Float get() = (System.currentTimeMillis() - start) / 1000f

	/** Animated rainbow color; [offset] shifts the hue (0..1) so neighbours differ. */
	fun rainbow(offset: Float = 0f, saturation: Float = 0.65f, brightness: Float = 1f, alpha: Int = 0xFF): Int =
		ColorUtil.withAlpha(ColorUtil.hsbToArgb((time * 0.25f + offset) % 1f, saturation, brightness), alpha)
}
