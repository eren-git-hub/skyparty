package dev.skyparty.module.impl.client

import dev.skyparty.module.Category
import dev.skyparty.module.Module

/** Look and behaviour of the Skyparty menus. */
object ClickGuiModule : Module(
	id = "click_gui",
	name = "Interface",
	description = "Look and behaviour of the Skyparty menus.",
	category = Category.CLIENT,
	toggleable = false,
) {
	val accent = color("Accent Color", 0xFF4FC3F7.toInt(), allowAlpha = false, description = "Main color of the interface")
	val toggleMessages = boolean("Chat Messages", true, "Chat message when a feature is toggled")
}
