package dev.skyparty.module.impl.pets

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object EmotesModule : Module(
	id = "emotes",
	name = "Emotes",
	description = "Visual emotes: hold B for the emote wheel (or /sp emote). Others with Skyparty see them.",
	category = Category.PETS,
	defaultEnabled = true,
) {
	val showOthers = boolean("Show Others' Emotes", true)
	val autoThirdPerson = boolean("Auto Third Person", true, "Switch to third person during an emote and back afterwards")
	val chairColor = color("Chair Color", 0xFF2F5FB3.toInt(), allowAlpha = false)
}
