package dev.skyparty.pet

import dev.skyparty.module.impl.pets.CustomPets

/** The kinds of pet you can pick in the Pets menu (3D model + scoreboard sprite each). */
enum class PetSpecies(val label: String, val floats: Boolean, val scale: Float) {
	DRAGON("Dragon", false, 0.85f),
	RYU("Ryu", true, 0.8f),
	AXOLOTL("Axolotl", false, 0.9f),
	GHOST("Ghost", true, 0.85f),
	PENGUIN("Penguin", false, 0.9f);

	companion object {
		val labels = entries.map { it.label }

		fun of(label: String?): PetSpecies = entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: DRAGON

		/** Your own pet's species (from the Custom Pets settings). */
		val own: PetSpecies get() = of(CustomPets.species.value)
	}
}
