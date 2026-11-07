package dev.skyparty.setting

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.skyparty.util.ColorUtil

/**
 * Base class for every module setting. Each setting knows how to (de)serialize itself,
 * so the config system never needs to know the concrete types.
 */
sealed class Setting<T>(val name: String, val description: String, val default: T) {
	open var value: T = default

	/** Hide the setting in the GUI unless this returns true (e.g. depends on another setting). */
	var visibleIf: () -> Boolean = { true }

	fun visible(): Boolean = visibleIf()

	fun reset() {
		value = default
	}

	abstract fun toJson(): JsonElement
	abstract fun fromJson(json: JsonElement)
}

class BooleanSetting(name: String, default: Boolean, description: String = "") :
	Setting<Boolean>(name, description, default) {
	fun toggle() {
		value = !value
	}

	override fun toJson(): JsonElement = JsonPrimitive(value)
	override fun fromJson(json: JsonElement) {
		value = json.asBoolean
	}
}

class NumberSetting(
	name: String,
	default: Double,
	val min: Double,
	val max: Double,
	val step: Double = 1.0,
	val unit: String = "",
	description: String = "",
) : Setting<Double>(name, description, default) {
	override var value: Double = default
		set(v) {
			val stepped = Math.round(v / step) * step
			field = stepped.coerceIn(min, max)
		}

	val intValue get() = value.toInt()
	val floatValue get() = value.toFloat()

	fun display(): String {
		val text = if (step >= 1.0) value.toInt().toString() else "%.${decimals()}f".format(value)
		return text + unit
	}

	private fun decimals(): Int {
		var s = step
		var d = 0
		while (s < 1.0 && d < 4) {
			s *= 10
			d++
		}
		return d
	}

	override fun toJson(): JsonElement = JsonPrimitive(value)
	override fun fromJson(json: JsonElement) {
		value = json.asDouble
	}
}

class SelectorSetting(name: String, default: String, val options: List<String>, description: String = "") :
	Setting<String>(name, description, default) {
	init {
		require(default in options) { "Default '$default' is not an option of '$name'" }
	}

	val index get() = options.indexOf(value).coerceAtLeast(0)

	fun cycle(forward: Boolean) {
		val next = if (forward) index + 1 else index - 1 + options.size
		value = options[next % options.size]
	}

	override fun toJson(): JsonElement = JsonPrimitive(value)
	override fun fromJson(json: JsonElement) {
		val v = json.asString
		if (v in options) value = v
	}
}

/**
 * ARGB color stored as hue/saturation/brightness/alpha so the hue survives when saturation hits zero.
 */
class ColorSetting(name: String, default: Int, val allowAlpha: Boolean = true, description: String = "") :
	Setting<Int>(name, description, default) {
	var hue = 0f
	var saturation = 0f
	var brightness = 0f
	var alpha = 1f

	init {
		setArgb(default)
	}

	override var value: Int
		get() = ColorUtil.hsbToArgb(hue, saturation, brightness, if (allowAlpha) alpha else 1f)
		set(v) = setArgb(v)

	private fun setArgb(argb: Int) {
		val hsb = ColorUtil.argbToHsb(argb)
		hue = hsb[0]
		saturation = hsb[1]
		brightness = hsb[2]
		alpha = ((argb ushr 24) and 0xFF) / 255f
	}

	override fun toJson(): JsonElement = JsonObject().apply {
		addProperty("h", hue)
		addProperty("s", saturation)
		addProperty("b", brightness)
		addProperty("a", alpha)
	}

	override fun fromJson(json: JsonElement) {
		if (json.isJsonObject) {
			val o = json.asJsonObject
			hue = o["h"].asFloat
			saturation = o["s"].asFloat
			brightness = o["b"].asFloat
			alpha = o["a"].asFloat
		} else {
			value = json.asInt
		}
	}
}

/** GLFW key code, or [NONE] when unbound. */
class KeybindSetting(name: String, default: Int = NONE, description: String = "") :
	Setting<Int>(name, description, default) {
	companion object {
		const val NONE = -1
	}

	val bound get() = value != NONE

	override fun toJson(): JsonElement = JsonPrimitive(value)
	override fun fromJson(json: JsonElement) {
		value = json.asInt
	}
}

class StringSetting(name: String, default: String, val maxLength: Int = 32, description: String = "") :
	Setting<String>(name, description, default) {
	override var value: String = default
		set(v) {
			field = v.take(maxLength)
		}

	override fun toJson(): JsonElement = JsonPrimitive(value)
	override fun fromJson(json: JsonElement) {
		value = json.asString
	}
}
