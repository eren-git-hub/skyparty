package dev.skyparty.minigames

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Local, purely cosmetic minigame progress: the fake "Tokens" currency and one highscore per game.
 * Stored in config/skyparty/minigames.json. Nothing here is ever sent anywhere.
 */
object MinigameStore {
	const val START_TOKENS = 100

	class Highscore(val value: Double, val display: String)

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val dir = FabricLoader.getInstance().configDir.resolve("skyparty")
	private val file = dir.resolve("minigames.json")

	var tokens: Int = START_TOKENS
		private set
	private val highscores = linkedMapOf<String, Highscore>()
	private var loaded = false

	fun load() {
		loaded = true
		if (!Files.exists(file)) return
		try {
			val root = JsonParser.parseString(Files.readString(file)).asJsonObject
			tokens = root["tokens"]?.asInt ?: START_TOKENS
			root.getAsJsonObject("highscores")?.entrySet()?.forEach { (id, el) ->
				val o = el.asJsonObject
				highscores[id] = Highscore(o["value"].asDouble, o["display"]?.asString ?: o["value"].asString)
			}
		} catch (e: Exception) {
			Skyparty.LOGGER.warn("Could not read minigames.json, starting fresh", e)
		}
	}

	fun save() {
		try {
			val root = JsonObject().apply {
				addProperty("tokens", tokens)
				add("highscores", JsonObject().apply {
					for ((id, h) in highscores) add(id, JsonObject().apply {
						addProperty("value", h.value)
						addProperty("display", h.display)
					})
				})
			}
			Files.createDirectories(dir)
			val tmp = dir.resolve("minigames.json.tmp")
			Files.writeString(tmp, gson.toJson(root))
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not save minigames.json", e)
		}
	}

	private fun ensureLoaded() {
		if (!loaded) load()
	}

	fun balance(): Int {
		ensureLoaded()
		return tokens
	}

	fun addTokens(amount: Int) {
		ensureLoaded()
		tokens = (tokens + amount).coerceAtLeast(0)
		save()
	}

	/** Deducts [amount] if affordable. */
	fun trySpend(amount: Int): Boolean {
		ensureLoaded()
		if (amount > tokens) return false
		tokens -= amount
		save()
		return true
	}

	fun best(gameId: String): Highscore? {
		ensureLoaded()
		return highscores[gameId]
	}

	/** Records [value] if it beats the stored best. Returns true for a new personal best. */
	fun submit(gameId: String, value: Double, lowerIsBetter: Boolean, display: String): Boolean {
		ensureLoaded()
		val old = highscores[gameId]
		val better = old == null || (if (lowerIsBetter) value < old.value else value > old.value)
		if (better) {
			highscores[gameId] = Highscore(value, display)
			save()
		}
		return better
	}
}
