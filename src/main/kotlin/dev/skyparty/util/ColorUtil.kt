package dev.skyparty.util

import kotlin.math.floor
import kotlin.math.roundToInt

object ColorUtil {
	fun argb(a: Int, r: Int, g: Int, b: Int): Int =
		((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

	fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

	/** Multiplies RGB by [factor] (>1 brightens, <1 darkens), keeps alpha. */
	fun shade(color: Int, factor: Float): Int {
		val a = (color ushr 24) and 0xFF
		val r = (((color shr 16) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
		val g = (((color shr 8) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
		val b = ((color and 0xFF) * factor).roundToInt().coerceIn(0, 255)
		return argb(a, r, g, b)
	}

	fun hsbToArgb(hue: Float, saturation: Float, brightness: Float, alpha: Float = 1f): Int {
		val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
		if (saturation <= 0f) {
			val v = (brightness.coerceIn(0f, 1f) * 255).roundToInt()
			return argb(a, v, v, v)
		}
		val h = (hue - floor(hue)) * 6f
		val f = h - floor(h)
		val p = brightness * (1f - saturation)
		val q = brightness * (1f - saturation * f)
		val t = brightness * (1f - saturation * (1f - f))
		val (r, g, b) = when (h.toInt()) {
			0 -> Triple(brightness, t, p)
			1 -> Triple(q, brightness, p)
			2 -> Triple(p, brightness, t)
			3 -> Triple(p, q, brightness)
			4 -> Triple(t, p, brightness)
			else -> Triple(brightness, p, q)
		}
		return argb(a, (r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
	}

	fun argbToHsb(argb: Int): FloatArray {
		val r = ((argb shr 16) and 0xFF) / 255f
		val g = ((argb shr 8) and 0xFF) / 255f
		val b = (argb and 0xFF) / 255f
		val max = maxOf(r, g, b)
		val min = minOf(r, g, b)
		val delta = max - min
		val saturation = if (max == 0f) 0f else delta / max
		var hue = when {
			delta == 0f -> 0f
			max == r -> ((g - b) / delta) % 6f
			max == g -> (b - r) / delta + 2f
			else -> (r - g) / delta + 4f
		} / 6f
		if (hue < 0f) hue += 1f
		return floatArrayOf(hue, saturation, max)
	}
}
