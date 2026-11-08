package dev.skyparty.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.module.ModuleManager
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists module state to config/skyparty/config.json.
 * Modules are keyed by their stable id, settings by name; unknown entries are ignored.
 */
object ConfigManager {
	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val dir = FabricLoader.getInstance().configDir.resolve("skyparty")
	private val file = dir.resolve("config.json")
	private const val VERSION = 2

	fun load() {
		if (!Files.exists(file)) return
		try {
			val root = JsonParser.parseString(Files.readString(file)).asJsonObject
			val version = root["version"]?.asInt ?: 1
			root.getAsJsonObject("modules")?.let { modules ->
				for ((id, element) in modules.entrySet()) {
					val module = ModuleManager.byId(id) ?: continue
					val obj = element.asJsonObject
					// Configs before version 2 saved the scoreboard pet as off by default (friends never saw it): ignore that.
					val staleOff = version < 2 && module === dev.skyparty.module.impl.pets.ScoreboardPet
					if (module.switchable && !staleOff) obj["enabled"]?.let { module.setEnabled(it.asBoolean) }
					obj.getAsJsonObject("settings")?.let { settings ->
						for (setting in module.settings) {
							settings[setting.name]?.let { value ->
								runCatching { setting.fromJson(value) }
									.onFailure { Skyparty.LOGGER.warn("Bad value for ${module.id}.${setting.name}", it) }
							}
						}
					}
				}
			}
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not load config, keeping defaults", e)
		}
	}

	fun save() {
		try {
			val modules = JsonObject()
			for (module in ModuleManager.modules) {
				modules.add(module.id, JsonObject().apply {
					if (module.switchable) addProperty("enabled", module.enabled)
					add("settings", JsonObject().apply {
						for (setting in module.settings) add(setting.name, setting.toJson())
					})
				})
			}
			val root = JsonObject().apply {
				addProperty("version", VERSION)
				add("modules", modules)
			}
			Files.createDirectories(dir)
			// Write to a temp file first so a crash mid-write never corrupts the config.
			val tmp = dir.resolve("config.json.tmp")
			Files.writeString(tmp, gson.toJson(root))
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not save config", e)
		}
	}
}
