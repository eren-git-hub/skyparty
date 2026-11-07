package dev.skyparty.ui.elements

import com.mojang.blaze3d.platform.InputConstants
import dev.skyparty.setting.BooleanSetting
import dev.skyparty.setting.ColorSetting
import dev.skyparty.setting.KeybindSetting
import dev.skyparty.setting.NumberSetting
import dev.skyparty.setting.SelectorSetting
import dev.skyparty.setting.Setting
import dev.skyparty.setting.StringSetting
import dev.skyparty.ui.UiFocus
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW

/**
 * One row (or block) in a module's settings list. Bounds are assigned during the
 * render pass and reused for hit-testing on the next input event.
 */
abstract class SettingElement(open val setting: Setting<*>) {
	var x = 0
	var y = 0
	var width = 0

	abstract val height: Int

	protected val font get() = Minecraft.getInstance().font

	fun isHovered(mx: Int, my: Int) = mx >= x && mx < x + width && my >= y && my < y + height

	protected fun background(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		g.fill(x, y, x + width, y + height, if (isHovered(mx, my)) Theme.SETTING_HOVER else Theme.SETTING)
	}

	/** Draws the setting name, shortened with "…" so it never runs into [reserve] px on the right. */
	protected fun label(g: GuiGraphicsExtractor, reserve: Int = 0, text: String = setting.name) {
		val maxWidth = width - 12 - reserve
		val shown = if (font.width(text) <= maxWidth) text else font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…"
		g.text(font, shown, x + 6, y + (Theme.SETTING_HEIGHT - 8) / 2 + 1, Theme.TEXT_DIM, false)
	}

	abstract fun render(g: GuiGraphicsExtractor, mx: Int, my: Int)

	/** @return true when the click was consumed. */
	open fun mouseClicked(mx: Int, my: Int, button: Int): Boolean = false
	open fun mouseReleased(mx: Int, my: Int, button: Int) {}

	/** Only called while this element holds keyboard focus. */
	open fun keyPressed(key: Int): Boolean = false
	open fun charTyped(text: String): Boolean = false

	companion object {
		fun of(setting: Setting<*>): SettingElement = when (setting) {
			is BooleanSetting -> BooleanElement(setting)
			is NumberSetting -> NumberElement(setting)
			is SelectorSetting -> SelectorElement(setting)
			is ColorSetting -> ColorElement(setting)
			is KeybindSetting -> KeybindElement(setting)
			is StringSetting -> StringElement(setting)
		}
	}
}

class BooleanElement(override val setting: BooleanSetting) : SettingElement(setting) {
	override val height = Theme.SETTING_HEIGHT

	override fun render(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		background(g, mx, my)
		label(g, reserve = 22)
		// Pill switch on the right
		val sw = 16
		val sh = 7
		val sx = x + width - sw - 5
		val sy = y + (height - sh) / 2
		g.fill(sx, sy, sx + sw, sy + sh, if (setting.value) Theme.accent(0xFF) else Theme.TRACK)
		val knobX = if (setting.value) sx + sw - sh else sx
		g.fill(knobX, sy, knobX + sh, sy + sh, Theme.TEXT)
	}

	override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
		if (!isHovered(mx, my) || button != 0) return false
		setting.toggle()
		return true
	}
}

class NumberElement(override val setting: NumberSetting) : SettingElement(setting) {
	override val height = 21
	private var dragging = false

	override fun render(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val tx = x + 6
		val tw = width - 12
		if (dragging) {
			val pct = ((mx - tx).toDouble() / tw).coerceIn(0.0, 1.0)
			setting.value = setting.min + pct * (setting.max - setting.min)
		}
		background(g, mx, my)
		val valueText = setting.display()
		label(g, reserve = font.width(valueText) + 4)
		g.text(font, valueText, x + width - 6 - font.width(valueText), y + 3, Theme.TEXT, false)

		val ty = y + 14
		g.fill(tx, ty, tx + tw, ty + 3, Theme.TRACK)
		val pct = (setting.value - setting.min) / (setting.max - setting.min)
		val filled = (tw * pct).toInt()
		g.fill(tx, ty, tx + filled, ty + 3, Theme.accent(0xFF))
		g.fill(tx + filled - 1, ty - 1, tx + filled + 2, ty + 4, Theme.TEXT)
	}

	override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
		if (!isHovered(mx, my) || button != 0) return false
		dragging = true
		return true
	}

	override fun mouseReleased(mx: Int, my: Int, button: Int) {
		dragging = false
	}
}

class SelectorElement(override val setting: SelectorSetting) : SettingElement(setting) {
	override val height = Theme.SETTING_HEIGHT

	override fun render(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		background(g, mx, my)
		val v = "‹ ${setting.value} ›"
		label(g, reserve = font.width(v) + 4)
		g.text(font, v, x + width - 6 - font.width(v), y + 3, Theme.accent(0xFF), false)
	}

	override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
		if (!isHovered(mx, my)) return false
		when (button) {
			0 -> setting.cycle(true)
			1 -> setting.cycle(false)
			else -> return false
		}
		return true
	}
}

class ColorElement(override val setting: ColorSetting) : SettingElement(setting) {
	private var expanded = false
	private var draggingBar = -1
	private val bars get() = if (setting.allowAlpha) 4 else 3
	private val barSpacing = 9

	override val height get() = Theme.SETTING_HEIGHT + if (expanded) bars * barSpacing + 3 else 0

	override fun render(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val bx = x + 6
		val bw = width - 12
		if (draggingBar >= 0) {
			val pct = ((mx - bx).toFloat() / bw).coerceIn(0f, 1f)
			when (draggingBar) {
				0 -> setting.hue = pct.coerceAtMost(0.999f)
				1 -> setting.saturation = pct
				2 -> setting.brightness = pct
				3 -> setting.alpha = pct
			}
		}

		background(g, mx, my)
		label(g, reserve = 18)
		// Swatch
		val sx = x + width - 18
		g.fill(sx - 1, y + 2, sx + 13, y + 11, Theme.TRACK)
		g.fill(sx, y + 3, sx + 12, y + 10, setting.value)
		if (!expanded) return

		for (bar in 0 until bars) {
			val by = y + Theme.SETTING_HEIGHT + 1 + bar * barSpacing
			for (i in 0 until bw) {
				val p = i.toFloat() / bw
				val c = when (bar) {
					0 -> ColorUtil.hsbToArgb(p, 1f, 1f)
					1 -> ColorUtil.hsbToArgb(setting.hue, p, setting.brightness)
					2 -> ColorUtil.hsbToArgb(setting.hue, setting.saturation, p)
					else -> ColorUtil.withAlpha(
						ColorUtil.hsbToArgb(setting.hue, setting.saturation, setting.brightness), (p * 255).toInt(),
					)
				}
				g.fill(bx + i, by, bx + i + 1, by + 5, c)
			}
			val pct = when (bar) {
				0 -> setting.hue
				1 -> setting.saturation
				2 -> setting.brightness
				else -> setting.alpha
			}
			val kx = bx + (bw * pct).toInt()
			g.fill(kx - 1, by - 1, kx + 2, by + 6, Theme.TEXT)
		}
	}

	override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
		if (!isHovered(mx, my)) return false
		if (my < y + Theme.SETTING_HEIGHT) {
			expanded = !expanded
			return true
		}
		if (button != 0) return true
		val bar = (my - (y + Theme.SETTING_HEIGHT + 1)) / barSpacing
		if (bar in 0 until bars) draggingBar = bar
		return true
	}

	override fun mouseReleased(mx: Int, my: Int, button: Int) {
		draggingBar = -1
	}
}

class KeybindElement(override val setting: KeybindSetting) : SettingElement(setting) {
	override val height = Theme.SETTING_HEIGHT
	private val listening get() = UiFocus.focused === this

	override fun render(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		background(g, mx, my)
		val text = when {
			listening -> "..."
			!setting.bound -> "None"
			else -> InputConstants.Type.KEYSYM.getOrCreate(setting.value).displayName.string
		}
		val v = "[$text]"
		label(g, reserve = font.width(v) + 4)
		g.text(font, v, x + width - 6 - font.width(v), y + 3, if (listening) Theme.accent(0xFF) else Theme.TEXT, false)
	}

	override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
		if (!isHovered(mx, my)) return false
		when (button) {
			0 -> UiFocus.focused = if (listening) null else this
			1 -> {
				setting.value = KeybindSetting.NONE
				if (listening) UiFocus.focused = null
			}
		}
		return true
	}

	override fun keyPressed(key: Int): Boolean {
		when (key) {
			GLFW.GLFW_KEY_ESCAPE -> {}
			GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> setting.value = KeybindSetting.NONE
			else -> setting.value = key
		}
		UiFocus.focused = null
		return true
	}
}

class StringElement(override val setting: StringSetting) : SettingElement(setting) {
	override val height = 25
	private val focused get() = UiFocus.focused === this

	override fun render(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		background(g, mx, my)
		label(g)
		val fx = x + 6
		val fy = y + 12
		val fw = width - 12
		g.fill(fx, fy, fx + fw, fy + 11, Theme.TRACK)
		if (focused) g.outline(fx, fy, fw, 11, Theme.accent(0xFF))
		val blink = focused && (System.currentTimeMillis() / 500) % 2 == 0L
		val shown = font.plainSubstrByWidth(setting.value, fw - 6, true) + if (blink) "_" else ""
		g.text(font, shown, fx + 3, fy + 2, Theme.TEXT, false)
	}

	override fun mouseClicked(mx: Int, my: Int, button: Int): Boolean {
		if (!isHovered(mx, my)) return false
		if (button == 1) setting.value = ""
		UiFocus.focused = this
		return true
	}

	override fun keyPressed(key: Int): Boolean {
		when (key) {
			GLFW.GLFW_KEY_BACKSPACE -> setting.value = setting.value.dropLast(1)
			GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> UiFocus.focused = null
			else -> return false
		}
		return true
	}

	override fun charTyped(text: String): Boolean {
		setting.value += text
		return true
	}
}
