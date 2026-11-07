package dev.skyparty.reminders

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import dev.skyparty.Skyparty
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.minigames.price.Bazaar
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.nio.file.Files
import java.util.Locale

/**
 * Bazaar price alerts: "tell me when Enchanted Diamond drops below 1.5k". Uses the public Bazaar endpoint
 * (insta-buy price), checked about once a minute while alerts exist. An alert fires once and is then removed.
 *
 * Command: /sp alert <item> below|above <price>   (price like 1500, 1.5k, 2m)
 */
object PriceAlerts {
	class Alert(val id: Long, val itemId: String, val below: Boolean, val price: Double)

	private const val CHECK_MS = 60_000L
	private val file = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("price_alerts.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()
	val list = mutableListOf<Alert>()
	private var ticks = 0
	private var checkedAt = 0L

	fun init() {
		load()
		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			if (list.isEmpty() || mc.player == null || ++ticks % 20 != 0) return@register
			Bazaar.ensureLoaded(maxAgeMs = CHECK_MS - 5_000)
			if (Bazaar.fetchedAt > checkedAt) {
				checkedAt = Bazaar.fetchedAt
				check()
			}
		}
		SkypartyCommands.sub {
			ClientCommands.literal("alert")
				.then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes { ctx ->
					addFromCommand(StringArgumentType.getString(ctx, "args")); 1
				})
				.executes { Chat.info("§7Usage: §f/sp alert <item> below|above <price> §8· e.g. §f/sp alert enchanted diamond below 1.5k"); 1 }
		}
	}

	private val COMMAND = Regex("^(.+?)\\s+(below|above|under|over|<|>)\\s+(\\S+)$", RegexOption.IGNORE_CASE)

	private fun addFromCommand(args: String) {
		val m = COMMAND.find(args.trim())
		if (m == null) { Chat.info("§cTry: §f/sp alert enchanted diamond below 1.5k"); return }
		val price = parsePrice(m.groupValues[3])
		if (price == null) { Chat.info("§cCouldn't read the price \"${m.groupValues[3]}\". Try 1500, 1.5k or 2m."); return }
		if (Bazaar.allPrices.isEmpty()) {
			Bazaar.ensureLoaded()
			Chat.info("§7Loading Bazaar prices… try again in a few seconds.")
			return
		}
		val id = findItem(m.groupValues[1])
		if (id == null) { Chat.info("§cNo Bazaar item matches \"${m.groupValues[1]}\"."); return }
		val below = m.groupValues[2].lowercase() in setOf("below", "under", "<")
		add(id, below, price)
		Chat.info("§a🔔 Alert set: §f${Bazaar.prettyName(id)} §7${if (below) "below" else "above"} §6${formatCoins(price)}" +
			(Bazaar.allPrices[id]?.let { " §8(now ${formatCoins(it)})" } ?: ""))
	}

	fun add(itemId: String, below: Boolean, price: Double) {
		list += Alert(System.nanoTime(), itemId, below, price)
		checkedAt = 0 // check against the cached prices right away
		save()
	}

	fun remove(a: Alert) { list.remove(a); save() }

	private fun check() {
		val prices = Bazaar.allPrices
		val hit = list.filter { a -> prices[a.itemId]?.let { if (a.below) it <= a.price else it >= a.price } == true }
		if (hit.isEmpty()) return
		val mc = Minecraft.getInstance()
		for (a in hit) {
			Chat.info("§e§l🔔 Bazaar: §f${Bazaar.prettyName(a.itemId)} §7is ${if (a.below) "down to" else "up to"} §6${formatCoins(prices.getValue(a.itemId))} " +
				"§8(alert: ${if (a.below) "below" else "above"} ${formatCoins(a.price)})")
			list.remove(a)
		}
		mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0f))
		mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f))
		save()
	}

	/**
	 * Best Bazaar product for what the player typed: exact id ("ENCHANTED_DIAMOND"), exact name ("enchanted diamond"),
	 * then the shortest name that starts with / contains the text. Null if nothing matches.
	 */
	fun findItem(query: String): String? {
		val prices = Bazaar.allPrices
		if (query == lastQuery && prices === lastPrices) return lastMatch
		lastMatch = match(query, prices.keys)
		lastQuery = query
		lastPrices = prices
		return lastMatch
	}

	// The screen asks every frame; only search again when the text or the price data changed.
	private var lastQuery: String? = null
	private var lastPrices: Map<String, Double>? = null
	private var lastMatch: String? = null

	private fun match(query: String, ids: Set<String>): String? {
		val q = query.trim().lowercase(Locale.ROOT)
		if (q.isEmpty()) return null
		val asId = q.uppercase(Locale.ROOT).replace(' ', '_')
		if (asId in ids) return asId
		val named = ids.map { it to Bazaar.prettyName(it).lowercase(Locale.ROOT) }
		named.firstOrNull { it.second == q }?.let { return it.first }
		named.filter { it.second.startsWith(q) }.minByOrNull { it.second.length }?.let { return it.first }
		return named.filter { it.second.contains(q) }.minByOrNull { it.second.length }?.first
	}

	/** "1500", "1,500", "1.5k", "2m", "1b" → coins. */
	fun parsePrice(s: String): Double? {
		val t = s.trim().lowercase(Locale.ROOT).replace(",", "")
		val m = Regex("^(\\d+(?:\\.\\d+)?)([kmb]?)$").find(t) ?: return null
		val n = m.groupValues[1].toDouble()
		val v = n * when (m.groupValues[2]) { "k" -> 1e3; "m" -> 1e6; "b" -> 1e9; else -> 1.0 }
		return v.takeIf { it > 0 }
	}

	fun formatCoins(v: Double): String = when {
		v >= 1e9 -> "%.2fB".format(Locale.ROOT, v / 1e9)
		v >= 1e6 -> "%.2fM".format(Locale.ROOT, v / 1e6)
		v >= 1e4 -> "%.1fk".format(Locale.ROOT, v / 1e3)
		v >= 100 -> "%,.0f".format(Locale.ROOT, v)
		else -> "%.1f".format(Locale.ROOT, v)
	}

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			JsonParser.parseString(Files.readString(file)).asJsonArray.forEach {
				val o = it.asJsonObject
				list += Alert(o["id"].asLong, o["item"].asString, o["below"].asBoolean, o["price"].asDouble)
			}
		}.onFailure { Skyparty.LOGGER.warn("Could not read price_alerts.json", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			val arr = JsonArray()
			for (a in list) arr.add(JsonObject().apply {
				addProperty("id", a.id); addProperty("item", a.itemId); addProperty("below", a.below); addProperty("price", a.price)
			})
			Files.writeString(file, gson.toJson(arr))
		}.onFailure { Skyparty.LOGGER.warn("Could not save price_alerts.json", it) }
	}
}
