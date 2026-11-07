package dev.skyparty

import com.mojang.blaze3d.platform.InputConstants
import dev.skyparty.config.ConfigManager
import dev.skyparty.core.Features
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.module.ModuleManager
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.pet.PetController
import dev.skyparty.pet.PetData
import dev.skyparty.pet.PetRenderer
import dev.skyparty.pet.scoreboard.ScoreboardPetHud
import dev.skyparty.minigames.ui.HubScreen
import dev.skyparty.util.Chat
import dev.skyparty.util.Location
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object Skyparty : ClientModInitializer {
	const val MOD_ID = "skyparty"
	val LOGGER = LoggerFactory.getLogger("Skyparty")

	private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
	private lateinit var openGuiKey: KeyMapping
	lateinit var feedPetKey: KeyMapping
		private set

	/** Screen to open on the next tick (commands run while the chat screen is still closing). */
	private var pendingScreen: Screen? = null

	fun openNextTick(screen: Screen) {
		pendingScreen = screen
	}

	override fun onInitializeClient() {
		openGuiKey = KeyMappingHelper.registerKeyMapping(
			KeyMapping("key.skyparty.clickgui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category)
		)

		feedPetKey = KeyMappingHelper.registerKeyMapping(
			KeyMapping("key.skyparty.feed_pet", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category)
		)

		ConfigManager.load()

		ClientTickEvents.END_CLIENT_TICK.register(::onTick)
		ClientLifecycleEvents.CLIENT_STOPPING.register {
			ConfigManager.save()
		}

		// Pets: 3D pet in the world + 2D pet on the scoreboard
		LevelRenderEvents.COLLECT_SUBMITS.register(PetRenderer::render)
		LevelRenderEvents.COLLECT_SUBMITS.register(dev.skyparty.cosmetics.DragonWings::render)
		LevelRenderEvents.COLLECT_SUBMITS.register(dev.skyparty.cosmetics.CatEars::render)
		HudElementRegistry.attachElementAfter(
			VanillaHudElements.SCOREBOARD,
			Identifier.fromNamespaceAndPath(MOD_ID, "scoreboard_pet"),
			ScoreboardPetHud::render,
		)
		ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
			if (screen is ChatScreen) {
				ScreenMouseEvents.allowMouseClick(screen).register { _, e ->
					!ScoreboardPetHud.onMouseClick(e.x(), e.y(), e.button())
				}
				ScreenMouseEvents.allowMouseRelease(screen).register { _, e ->
					!ScoreboardPetHud.onMouseRelease(e.x(), e.y(), e.button())
				}
			}
		}

		Features.inits.forEach { it() }
		registerCommands()

		LOGGER.info("Skyparty loaded with {} modules", ModuleManager.modules.size)
	}

	private fun registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			for (name in listOf("skyparty", "sp")) {
				val pet = ClientCommands.literal("pet").executes {
					sendPetInfo()
					1
				}
				val root = ClientCommands.literal(name)
					.executes {
						pendingScreen = HubScreen()
						1
					}
					.then(pet)
				SkypartyCommands.attachAll(root)
				dispatcher.register(root)
			}
		}
	}

	private fun sendPetInfo() {
		val name = CustomPets.petName.value.ifBlank { "Your pet" }
		Chat.info("§f$name §7follows you around. Others with Skyparty see it too.")
		Chat.raw("§7Change its name and colors in §f/sp §7→ §f❀ Pets§7.")
		Chat.raw("§7Press §f" + feedPetKey.translatedKeyMessage.string + " §7to feed your scoreboard pet.")
	}

	private fun onTick(mc: Minecraft) {
		pendingScreen?.let {
			pendingScreen = null
			mc.gui.setScreen(it)
		}
		while (feedPetKey.consumeClick()) {
			val why = when {
				!dev.skyparty.module.impl.pets.ScoreboardPet.enabled -> "turn on Scoreboard Pet in the settings first"
				!ScoreboardPetHud.visible -> "no scoreboard on screen"
				else -> null
			}
			if (why == null) ScoreboardPetHud.feed()
			else if (mc.gui.screen() == null) Chat.info("§7Can't feed your pet: $why")
		}
		while (openGuiKey.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(HubScreen())
		}
		ModuleManager.tick(mc)
		PetData.tick(mc)
		PetController.tick(mc)
		dev.skyparty.pet.RemotePets.tick(mc)
		ScoreboardPetHud.tick()
	}
}
