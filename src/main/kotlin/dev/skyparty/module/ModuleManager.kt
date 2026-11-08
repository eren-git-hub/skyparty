package dev.skyparty.module

import dev.skyparty.module.impl.client.ClickGuiModule
import dev.skyparty.module.impl.party.DecisionWheel
import dev.skyparty.module.impl.party.Minigames
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.module.impl.pets.ScoreboardPet
import dev.skyparty.module.impl.stats.Wrapped
import net.minecraft.client.Minecraft

object ModuleManager {
	val modules: List<Module> = listOf(
		// Pets
		CustomPets,
		ScoreboardPet,
		// Party
		Minigames,
		DecisionWheel,
		dev.skyparty.module.impl.party.PartyChatModule,
		// Stats
		Wrapped,
		// Client
		ClickGuiModule,
		dev.skyparty.module.impl.client.OnlineModule,
	)

	init {
		val ids = modules.map { it.id }
		require(ids.size == ids.toSet().size) { "Duplicate module ids: $ids" }
	}

	fun byCategory(category: Category) = modules.filter { it.category == category }

	fun byId(id: String) = modules.firstOrNull { it.id == id }

	fun tick(mc: Minecraft) {
		for (module in modules) if (module.enabled) module.onTick()
	}

}
