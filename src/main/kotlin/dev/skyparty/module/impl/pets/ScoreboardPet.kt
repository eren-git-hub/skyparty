package dev.skyparty.module.impl.pets

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object ScoreboardPet : Module(
	id = "scoreboard_pet",
	name = "Scoreboard Pet",
	description = "2D version of your pet on the scoreboard. Feed it with the Feed Pet key. With chat open: drag it, right-click to pet it, click to throw a ball.",
	category = Category.PETS,
) {
	val size = number("Size", 1.0, 0.5, 2.0, 0.25, "x")
	val speed = number("Speed", 1.0, 0.25, 2.0, 0.25, "x")
	val interactions = boolean("Interactions", true, "Drag, throw a ball, pet it")
	val reactions = boolean("Reactions", true, "Happy on PB/drop, scared when you die")
}
