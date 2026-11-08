package dev.skyparty.minigames.quiz

import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Names, rarities and kinds of all SkyBlock items from the public Hypixel API (no key). Loaded once per
 * start in the background; the quiz uses it for rarity and item-kind questions (and the typing race for names).
 */
object SkyblockItems {
	data class Item(val id: String, val name: String, val tier: String, val category: String)

	private const val URL = "https://api.hypixel.net/v2/resources/skyblock/items"
	val TIERS = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

	/** Kinds players recognise; the others (reforge stones, cosmetics, …) make poor questions. */
	private val KINDS = setOf(
		"SWORD", "BOW", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "ACCESSORY", "PET_ITEM", "DRILL", "PICKAXE",
		"AXE", "WAND", "FISHING_ROD", "NECKLACE", "CLOAK", "BELT", "GLOVES", "BRACELET", "LONGSWORD", "DEPLOYABLE",
	)

	@Volatile var items: List<Item>? = null
		private set
	@Volatile private var loading = false

	fun ensureLoaded() {
		if (items != null || loading) return
		loading = true
		val req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30)).header("User-Agent", "Skyparty-Mod").GET().build()
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
			.sendAsync(req, HttpResponse.BodyHandlers.ofString())
			.thenApply { if (it.statusCode() != 200) error("HTTP ${it.statusCode()}"); parse(it.body()) }
			.whenComplete { list, t ->
				Minecraft.getInstance().execute {
					loading = false
					if (list != null) items = list else Skyparty.LOGGER.warn("SkyBlock items fetch failed: {}", t?.message)
				}
			}
	}

	private fun parse(body: String): List<Item> {
		val arr = JsonParser.parseString(body).asJsonObject.getAsJsonArray("items") ?: return emptyList()
		return arr.mapNotNull { el ->
			val o = el.asJsonObject
			val name = o["name"]?.asString?.replace(Regex("§."), "")?.trim() ?: return@mapNotNull null
			val category = o["category"]?.asString ?: return@mapNotNull null
			// No tier in the API means COMMON.
			val tier = o["tier"]?.asString ?: "COMMON"
			if (category !in KINDS || tier !in TIERS || name.length > 28) return@mapNotNull null
			Item(o["id"]?.asString ?: name, name, tier, category)
		}
	}

	fun prettyTier(t: String) = t.lowercase().replaceFirstChar { it.uppercase() }

	/** Rarity color as a § code, like in game. */
	fun tierColor(t: String) = when (t) {
		"COMMON" -> "§f"; "UNCOMMON" -> "§a"; "RARE" -> "§9"; "EPIC" -> "§5"; "LEGENDARY" -> "§6"; "MYTHIC" -> "§d"; else -> "§7"
	}

	/** Words in an item name that reveal its kind (word endings, so "Shortbow" and "Ironclad Helm" count too). */
	private val HINTS = mapOf(
		"SWORD" to listOf("sword", "blade", "katana", "saber", "sabre", "cleaver", "dagger", "scythe", "rapier", "cutlass"),
		"LONGSWORD" to listOf("sword", "blade", "longsword", "claymore"),
		"BOW" to listOf("bow"),
		"HELMET" to listOf("helmet", "helm", "hat", "cap", "mask", "crown", "hood", "head", "goggles"),
		"CHESTPLATE" to listOf("chestplate", "chest", "tunic", "plate", "shirt", "robe"),
		"LEGGINGS" to listOf("leggings", "pants", "trousers", "legs"),
		"BOOTS" to listOf("boots", "shoes", "slippers", "sandals", "feet"),
		"ACCESSORY" to listOf("talisman", "ring", "artifact", "artefact", "relic", "charm"),
		"DRILL" to listOf("drill"),
		"PICKAXE" to listOf("pickaxe", "pick", "gauntlet"),
		"AXE" to listOf("axe", "hatchet", "treecapitator"),
		"WAND" to listOf("wand", "staff", "sceptre", "scepter"),
		"FISHING_ROD" to listOf("rod"),
		"NECKLACE" to listOf("necklace", "amulet", "pendant", "choker"),
		"CLOAK" to listOf("cloak", "cape"),
		"BELT" to listOf("belt"),
		"GLOVES" to listOf("gloves", "gauntlet", "gauntlets", "glove"),
		"BRACELET" to listOf("bracelet", "bangle"),
		"DEPLOYABLE" to listOf("flux", "orb"),
		"PET_ITEM" to listOf("pet"),
	)

	fun nameGivesKind(item: Item): Boolean {
		val hints = HINTS[item.category] ?: return false
		val words = item.name.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
		return words.any { w -> hints.any { h -> w == h || w.endsWith(h) && h.length >= 3 && w.length > h.length + 2 } }
	}

	fun prettyKind(c: String) = when (c) {
		"PET_ITEM" -> "Pet item"
		"FISHING_ROD" -> "Fishing rod"
		else -> c.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
	}
}
