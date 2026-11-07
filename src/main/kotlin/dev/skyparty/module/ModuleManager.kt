package dev.skyparty.module

import com.mojang.blaze3d.platform.InputConstants
import dev.skyparty.module.impl.client.ClickGuiModule
import dev.skyparty.module.impl.party.DecisionWheel
import dev.skyparty.module.impl.party.Minigames
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.module.impl.pets.ScoreboardPet
import dev.skyparty.module.impl.stats.Wrapped
import dev.skyparty.setting.KeybindSetting
import dev.skyparty.util.Chat
import net.minecraft.client.Minecraft

object ModuleManager {
	val modules: List<Module> = listOf(
		// Pets
		CustomPets,
		ScoreboardPet,
		// Party
		Minigames,
		DecisionWheel,
		// Stats
		Wrapped,
		// Client
		ClickGuiModule,
		dev.skyparty.module.impl.client.OnlineModule,
	)

	private val keyWasDown = HashMap<Module, Boolean>()

	init {
		val ids = modules.map { it.id }
		require(ids.size == ids.toSet().size) { "Duplicate module ids: $ids" }
	}

	fun byCategory(category: Category) = modules.filter { it.category == category }

	fun byId(id: String) = modules.firstOrNull { it.id == id }

	fun tick(mc: Minecraft) {
		if (mc.gui.screen() == null && mc.player != null) handleKeybinds(mc)
		for (module in modules) if (module.enabled) module.onTick()
	}

	/** Edge-detects module keybinds so holding a key toggles only once. */
	private fun handleKeybinds(mc: Minecraft) {
		val window = mc.window
		for (module in modules) {
			val key = module.keybind.value
			if (key == KeybindSetting.NONE) continue
			val down = InputConstants.isKeyDown(window, key)
			if (down && keyWasDown[module] != true) module.toggle()
			keyWasDown[module] = down
		}
	}

	fun onToggled(module: Module) {
		if (!ClickGuiModule.toggleMessages.value) return
		val state = if (module.enabled) "§aon §f✔" else "§coff"
		Chat.info("§f${module.name} $state")
	}
}
