package dev.skyparty.module.impl.pets

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object CustomPets : Module(
	id = "custom_pets",
	name = "Custom Pets",
	description = "Your 3D pet that follows you around. Others with Skyparty see it too.",
	category = Category.PETS,
	defaultEnabled = true,
	switchable = true,
) {
	val petName = text("Pet Name", "Lusi", 16)
	val species = selector("Species", "Dragon", dev.skyparty.pet.PetSpecies.labels)
	val bodyColor = color("Body Color", 0xFF7FD3FF.toInt(), allowAlpha = false)
	val accentColor = color("Accent Color", 0xFFFFB347.toInt(), allowAlpha = false)
	val visibility = selector("Visibility", "Everyone", listOf("Everyone", "Only Me"))
	val showOthers = boolean("Show Others' Pets", true)
	val renderDistance = number("Render Distance", 32.0, 8.0, 64.0, 4.0, " blocks")
	val opacity = number("Opacity", 100.0, 20.0, 100.0, 5.0, "%")
}
