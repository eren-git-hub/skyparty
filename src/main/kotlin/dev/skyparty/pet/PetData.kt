package dev.skyparty.pet

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.util.Location
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Tracks active SkyBlock playtime (AFK time excluded) and persists it to config/skyparty/pet.json.
 * This local value is what the pet grows from; the backend sync will later take over as the source of truth.
 */
object PetData {
	/** No movement or camera rotation for this long counts as AFK. */
	private const val AFK_AFTER_MS = 120_000L
	private const val SAVE_EVERY_MS = 60_000L

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("pet.json")

	var playtimeMs = 0L
		private set

	val stage get() = PetStage.forPlaytime(playtimeMs)

	var isAfk = false
		private set

	private var lastTickAt = 0L
	private var lastActiveAt = System.currentTimeMillis()
	private var lastSaveAt = System.currentTimeMillis()
	private var lastPos = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
	private var dirty = false

	fun tick(mc: Minecraft) {
		val now = System.currentTimeMillis()
		val elapsed = if (lastTickAt == 0L) 0L else (now - lastTickAt).coerceIn(0L, 1_000L)
		lastTickAt = now

		val player = mc.player ?: return
		val current = doubleArrayOf(player.x, player.y, player.z, player.yRot.toDouble(), player.xRot.toDouble())
		if (!current.contentEquals(lastPos)) {
			lastActiveAt = now
			lastPos = current
		}
		isAfk = now - lastActiveAt > AFK_AFTER_MS

		if (Location.inSkyBlockOrDev && !isAfk && elapsed > 0) addPlaytime(elapsed)

		if (dirty && now - lastSaveAt > SAVE_EVERY_MS) save()
	}

	private fun addPlaytime(ms: Long) {
		val before = stage
		playtimeMs += ms
		dirty = true
		if (stage != before) onStageReached(stage)
	}

	/** Dev/testing only: jump to a given playtime. */
	fun setPlaytime(ms: Long) {
		val before = stage
		playtimeMs = ms.coerceAtLeast(0)
		dirty = true
		if (stage != before) onStageReached(stage)
		save()
	}

	private fun onStageReached(stage: PetStage) {
		val name = CustomPets.petName.value.ifBlank { "Your pet" }
		val text = when (stage) {
			PetStage.EGG -> return
			PetStage.HATCHLING -> "§e✦ $name has hatched!"
			PetStage.JUVENILE -> "§a✦ $name is now a juvenile!"
			PetStage.ADULT -> "§6✦ $name is fully grown!"
		}
		dev.skyparty.util.Chat.info(text)
		dev.skyparty.pet.scoreboard.ScoreboardPetHud.react(dev.skyparty.pet.scoreboard.ScoreboardPetHud.Reaction.HAPPY)
	}

	fun load() {
		if (!Files.exists(file)) return
		try {
			val root = JsonParser.parseString(Files.readString(file)).asJsonObject
			playtimeMs = root["playtimeMs"]?.asLong ?: 0L
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not load pet data", e)
		}
	}

	fun save() {
		try {
			Files.createDirectories(file.parent)
			val tmp = file.resolveSibling("pet.json.tmp")
			Files.writeString(tmp, gson.toJson(JsonObject().apply {
				addProperty("version", 1)
				addProperty("playtimeMs", playtimeMs)
			}))
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			dirty = false
			lastSaveAt = System.currentTimeMillis()
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not save pet data", e)
		}
	}

	fun formatDuration(ms: Long): String {
		val totalMinutes = ms / 60_000
		val h = totalMinutes / 60
		val m = totalMinutes % 60
		return if (h > 0) "${h}h ${m}min" else "${m}min"
	}
}
