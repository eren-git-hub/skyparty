package dev.skyparty

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.DoubleArgumentType
import dev.skyparty.config.ConfigManager
import dev.skyparty.core.Features
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.module.ModuleManager
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.pet.PetController
import dev.skyparty.pet.PetData
import dev.skyparty.pet.PetRenderer
import dev.skyparty.pet.PetStage
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

	/** Screen to open on the next tick (commands run while the chat screen is still closing). */
	private var pendingScreen: Screen? = null

	fun openNextTick(screen: Screen) {
		pendingScreen = screen
	}

	override fun onInitializeClient() {
		openGuiKey = KeyMappingHelper.registerKeyMapping(
			KeyMapping("key.skyparty.clickgui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category)
		)

		ConfigManager.load()
		PetData.load()

		ClientTickEvents.END_CLIENT_TICK.register(::onTick)
		ClientLifecycleEvents.CLIENT_STOPPING.register {
			ConfigManager.save()
			PetData.save()
		}

		// Pets: 3D pet in the world + 2D pet on the scoreboard
		LevelRenderEvents.COLLECT_SUBMITS.register(PetRenderer::render)
		LevelRenderEvents.COLLECT_SUBMITS.register(dev.skyparty.cosmetics.DragonWings::render)
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
				if (Location.isDev) {
					// Testing helper: /sp pet hours <h> jumps to a playtime to preview stages.
					pet.then(ClientCommands.literal("hours").then(
						ClientCommands.argument("h", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
							PetData.setPlaytime((DoubleArgumentType.getDouble(ctx, "h") * 3_600_000).toLong())
							sendPetInfo()
							1
						}
					))
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
		val stage = PetData.stage
		val name = CustomPets.petName.value.ifBlank { "Your pet" }
		Chat.info("§f$name §7– §e${stage.displayName}")
		Chat.raw("§7Active playtime: §f${PetData.formatDuration(PetData.playtimeMs)}")
		stage.next?.let { next ->
			val pct = (PetStage.progress(PetData.playtimeMs) * 100).toInt()
			val left = next.millis - PetData.playtimeMs
			Chat.raw("§7Next stage: §f${next.displayName} §7in §f${PetData.formatDuration(left)} §8($pct%)")
		} ?: Chat.raw("§6Final stage reached!")
		val counting = when {
			PetData.isAfk -> "§cpaused (AFK)"
			Location.inSkyBlockOrDev -> "§arunning"
			else -> "§cpaused (not in SkyBlock)"
		}
		Chat.raw("§7Counter: $counting")
	}

	private fun onTick(mc: Minecraft) {
		pendingScreen?.let {
			pendingScreen = null
			mc.gui.setScreen(it)
		}
		while (openGuiKey.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(HubScreen())
		}
		ModuleManager.tick(mc)
		PetData.tick(mc)
		PetController.tick(mc)
		ScoreboardPetHud.tick()
	}
}
