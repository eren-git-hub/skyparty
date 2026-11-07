package dev.skyparty.ui

import dev.skyparty.ui.elements.SettingElement

/** The setting element that currently receives keyboard input (text field, keybind listener). */
object UiFocus {
	var focused: SettingElement? = null
}
