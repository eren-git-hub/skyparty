package dev.skyparty.module

import dev.skyparty.setting.BooleanSetting
import dev.skyparty.setting.ColorSetting
import dev.skyparty.setting.NumberSetting
import dev.skyparty.setting.SelectorSetting
import dev.skyparty.setting.Setting
import dev.skyparty.setting.StringSetting

enum class Category(val displayName: String, val icon: String, val color: Int) {
	PETS("Pets", "❀", 0xFFFF7EB6.toInt()),
	PARTY("Party", "★", 0xFFFFC94D.toInt()),
	STATS("Wrapped", "✦", 0xFF7CE38B.toInt()),
	CLIENT("Settings", "⚙", 0xFF8EC5FF.toInt()),
}

/**
 * A feature with settings shown in the Skyparty menus. Features are always on; only the ones marked
 * [switchable] (the pets) get an on/off switch in the menu.
 *
 * @param id stable key used in the config file; never change it once released.
 */
abstract class Module(
	val id: String,
	val name: String,
	val description: String,
	val category: Category,
	defaultEnabled: Boolean = false,
	/** False for modules that are only a settings holder (e.g. the Click-GUI itself). */
	val toggleable: Boolean = true,
	/** Shows an on/off switch in the menu (and remembers the state). */
	val switchable: Boolean = false,
) {
	val settings = mutableListOf<Setting<*>>()
	var enabled: Boolean = toggleable
		private set

	fun setEnabled(state: Boolean, @Suppress("UNUSED_PARAMETER") notify: Boolean = false) {
		if (!toggleable || state == enabled) return
		enabled = state
		if (state) onEnable() else onDisable()
	}

	open fun onEnable() {}
	open fun onDisable() {}

	/** Called every client tick while enabled. */
	open fun onTick() {}

	protected fun <S : Setting<*>> register(setting: S): S {
		settings += setting
		return setting
	}

	protected fun boolean(name: String, default: Boolean, description: String = "") =
		register(BooleanSetting(name, default, description))

	protected fun number(
		name: String,
		default: Double,
		min: Double,
		max: Double,
		step: Double = 1.0,
		unit: String = "",
		description: String = "",
	) = register(NumberSetting(name, default, min, max, step, unit, description))

	protected fun selector(name: String, default: String, options: List<String>, description: String = "") =
		register(SelectorSetting(name, default, options, description))

	protected fun color(name: String, default: Int, allowAlpha: Boolean = true, description: String = "") =
		register(ColorSetting(name, default, allowAlpha, description))

	protected fun text(name: String, default: String, maxLength: Int = 32, description: String = "") =
		register(StringSetting(name, default, maxLength, description))
}
