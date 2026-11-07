package dev.skyparty.minigames.price

import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.util.Location
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Real Bazaar prices from the public Hypixel endpoint (no API key needed):
 * GET https://api.hypixel.net/v2/skyblock/bazaar →
 * {"success":true,"lastUpdated":…,"products":{"ID":{"product_id":"ID","quick_status":{"buyPrice":…,"sellPrice":…,
 *  "buyMovingWeek":…,"sellMovingWeek":…,…},"sell_summary":[…],"buy_summary":[…]}}}
 * Fetched asynchronously, cached for [CACHE_MS]. In the dev environment a built-in sample is used when offline.
 */
object Bazaar {
	data class Product(val id: String, val name: String, val price: Double)

	private const val URL = "https://api.hypixel.net/v2/skyblock/bazaar"
	private const val CACHE_MS = 10 * 60_000L
	private const val MIN_WEEKLY_VOLUME = 20_000L

	private val http: HttpClient by lazy {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
	}

	@Volatile var products: List<Product>? = null
		private set
	@Volatile var loading = false
		private set
	@Volatile var error: String? = null
		private set
	@Volatile var usingSample = false
		private set
	private var fetchedAt = 0L

	/** Starts a fetch unless fresh data is cached or a fetch is running. */
	fun ensureLoaded(force: Boolean = false) {
		if (loading) return
		if (!force && products != null && !usingSample && System.currentTimeMillis() - fetchedAt < CACHE_MS) return
		loading = true
		error = null
		val request = HttpRequest.newBuilder(URI.create(URL))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", "Skyparty-Mod")
			.GET().build()
		http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply { response ->
				if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
				parse(response.body())
			}
			.whenComplete { list, throwable ->
				Minecraft.getInstance().execute {
					loading = false
					if (throwable != null || list.isNullOrEmpty()) {
						Skyparty.LOGGER.warn("Bazaar fetch failed: {}", throwable?.message ?: "no products")
						error = "Couldn't reach the Hypixel Bazaar API. Are you offline?"
						if (Location.isDev && products == null) useSample()
					} else {
						products = list
						usingSample = false
						fetchedAt = System.currentTimeMillis()
					}
				}
			}
	}

	fun useSample() {
		products = SAMPLE.map { (id, price) -> Product(id, prettyName(id), price) }
		usingSample = true
	}

	private fun parse(body: String): List<Product> {
		val root = JsonParser.parseString(body).asJsonObject
		if (root["success"]?.asBoolean != true) error("API returned success=false")
		val out = mutableListOf<Product>()
		for ((id, el) in root.getAsJsonObject("products").entrySet()) {
			if (!usable(id)) continue
			val q = el.asJsonObject.getAsJsonObject("quick_status") ?: continue
			val buy = q["buyPrice"]?.asDouble ?: 0.0
			val sell = q["sellPrice"]?.asDouble ?: 0.0
			val volume = (q["buyMovingWeek"]?.asLong ?: 0L) + (q["sellMovingWeek"]?.asLong ?: 0L)
			if (buy <= 0.5 || sell <= 0.0 || volume < MIN_WEEKLY_VOLUME) continue
			// Skip items with a huge spread (manipulated / dead markets).
			if (buy > sell * 4) continue
			out += Product(id, prettyName(id), buy)
		}
		return out.sortedBy { it.id }
	}

	private fun usable(id: String) = ':' !in id && !id.startsWith("ENCHANTMENT_") && !id.startsWith("GENERATOR_UPGRADE") &&
		!id.startsWith("FACTION_") && id.length > 2

	// ------------------------------------------------------------------ names

	private val NAME_OVERRIDES = mapOf(
		"CARROT_ITEM" to "Carrot", "POTATO_ITEM" to "Potato", "INK_SACK" to "Ink Sac", "SULPHUR" to "Gunpowder",
		"NETHER_STALK" to "Nether Wart", "ENCHANTED_NETHER_STALK" to "Enchanted Nether Wart", "PORK" to "Raw Porkchop",
		"RAW_FISH" to "Raw Cod", "ENDER_STONE" to "End Stone", "ENCHANTED_ENDSTONE" to "Enchanted End Stone",
		"SEEDS" to "Wheat Seeds", "WATER_LILY" to "Lily Pad", "ENCHANTED_WATER_LILY" to "Enchanted Lily Pad",
		"HUGE_MUSHROOM_1" to "Brown Mushroom Block", "HUGE_MUSHROOM_2" to "Red Mushroom Block",
		"BAZAAR_COOKIE" to "Booster Cookie", "SNOW_BALL" to "Snowball", "LOG" to "Oak Log",
	)

	fun prettyName(id: String): String {
		NAME_OVERRIDES[id]?.let { return it }
		var base = id
		var suffix = ""
		when {
			base.startsWith("SHARD_") -> { base = base.removePrefix("SHARD_"); suffix = " Shard" }
			base.startsWith("ESSENCE_") -> { base = base.removePrefix("ESSENCE_"); suffix = " Essence" }
			base.endsWith("_GEM") -> { base = base.removeSuffix("_GEM"); suffix = " Gemstone" }
		}
		return base.split('_').filter { it.isNotEmpty() }.joinToString(" ") { word ->
			word.lowercase().replaceFirstChar { it.uppercase() }
		} + suffix
	}

	// ------------------------------------------------------------------ icons

	private val ICON_OVERRIDES = mapOf(
		"CARROT_ITEM" to "carrot", "POTATO_ITEM" to "potato", "INK_SACK" to "ink_sac", "SULPHUR" to "gunpowder",
		"NETHER_STALK" to "nether_wart", "PORK" to "porkchop", "GRILLED_PORK" to "cooked_porkchop", "RAW_FISH" to "cod",
		"COOKED_FISH" to "cooked_cod", "ENDER_STONE" to "end_stone", "ENDSTONE" to "end_stone", "SEEDS" to "wheat_seeds",
		"WATER_LILY" to "lily_pad", "MELON" to "melon_slice", "RAW_CHICKEN" to "chicken", "RAW_BEEF" to "beef",
		"HUGE_MUSHROOM_1" to "brown_mushroom_block", "HUGE_MUSHROOM_2" to "red_mushroom_block", "LOG" to "oak_log",
		"BAZAAR_COOKIE" to "cookie", "SNOW_BALL" to "snowball", "RABBIT_HIDE" to "rabbit_hide", "CLAY_BALL" to "clay_ball",
		"ENCHANTED_CARROT_ON_A_STICK" to "carrot_on_a_stick", "HAY_BLOCK" to "hay_block", "HAY_BALE" to "hay_block",
		"GOLD_BLOCK" to "gold_block", "IRON_BLOCK" to "iron_block", "DIAMOND_BLOCK" to "diamond_block",
		"ACACIA_LOG" to "acacia_log", "DARK_OAK_LOG" to "dark_oak_log", "BIRCH_LOG" to "birch_log",
		"SPRUCE_LOG" to "spruce_log", "JUNGLE_LOG" to "jungle_log", "BONE_BLOCK" to "bone_block",
		"QUARTZ" to "quartz", "QUARTZ_BLOCK" to "quartz_block", "SUGAR" to "sugar", "SUGAR_CANE" to "sugar_cane",
	)

	private val iconCache = HashMap<String, ItemStack>()

	/** A vanilla item that matches the product, with glint for "Enchanted …"; [ItemStack.EMPTY] if none. */
	fun icon(id: String): ItemStack = iconCache.getOrPut(id) {
		val enchanted = id.startsWith("ENCHANTED_")
		val base = id.removePrefix("ENCHANTED_")
		val candidates = listOfNotNull(
			ICON_OVERRIDES[id], ICON_OVERRIDES[base], id.lowercase(), base.lowercase(),
			base.lowercase().removeSuffix("_block"), base.lowercase().removeSuffix("_item"),
		)
		val item = candidates.firstNotNullOfOrNull { name ->
			val key = Identifier.tryParse("minecraft:$name") ?: return@firstNotNullOfOrNull null
			BuiltInRegistries.ITEM.getOptional(key).orElse(null)?.takeIf { it != Items.AIR }
		}
		if (item == null) ItemStack.EMPTY
		else ItemStack(item).also { if (enchanted) it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
	}

	// ------------------------------------------------------------------ sample (dev / offline)

	/** Rough prices (coins, insta-buy) used only in the dev environment when the API can't be reached. */
	private val SAMPLE = listOf(
		"WHEAT" to 6.2, "ENCHANTED_BREAD" to 62.0, "CARROT_ITEM" to 3.1, "ENCHANTED_CARROT" to 520.0,
		"POTATO_ITEM" to 2.9, "ENCHANTED_POTATO" to 480.0, "PUMPKIN" to 9.5, "ENCHANTED_PUMPKIN" to 1_550.0,
		"MELON" to 2.4, "ENCHANTED_MELON" to 390.0, "SUGAR_CANE" to 4.2, "ENCHANTED_SUGAR" to 680.0,
		"COBBLESTONE" to 3.0, "ENCHANTED_COBBLESTONE" to 480.0, "COAL" to 4.6, "ENCHANTED_COAL" to 760.0,
		"IRON_INGOT" to 5.8, "ENCHANTED_IRON" to 920.0, "GOLD_INGOT" to 6.0, "ENCHANTED_GOLD" to 1_010.0,
		"DIAMOND" to 9.5, "ENCHANTED_DIAMOND" to 1_580.0, "ENCHANTED_DIAMOND_BLOCK" to 254_000.0,
		"OBSIDIAN" to 12.0, "ENCHANTED_OBSIDIAN" to 1_950.0, "ENDER_PEARL" to 9.0, "ENCHANTED_ENDER_PEARL" to 145.0,
		"BLAZE_ROD" to 11.5, "ENCHANTED_BLAZE_ROD" to 312_000.0, "SLIME_BALL" to 7.0, "ENCHANTED_SLIME_BALL" to 1_160.0,
		"STRING" to 6.0, "ENCHANTED_STRING" to 960.0, "BONE" to 4.5, "ENCHANTED_BONE" to 720.0,
		"ROTTEN_FLESH" to 3.4, "ENCHANTED_ROTTEN_FLESH" to 560.0, "GLOWSTONE_DUST" to 6.8, "SNOW_BALL" to 1.6,
		"BAZAAR_COOKIE" to 3_250_000.0, "RECOMBOBULATOR_3000" to 7_900_000.0, "HOT_POTATO_BOOK" to 91_000.0,
		"FUMING_POTATO_BOOK" to 1_350_000.0, "SUMMONING_EYE" to 1_050_000.0, "JUDGEMENT_CORE" to 98_000_000.0,
		"FINE_TOPAZ_GEM" to 116_000.0, "FLAWLESS_RUBY_GEM" to 2_400_000.0, "ESSENCE_WITHER" to 3_100.0,
		"ESSENCE_DRAGON" to 1_500.0, "SHARD_SALMON" to 4_200.0, "MAGMA_CREAM" to 9.2, "ENCHANTED_MAGMA_CREAM" to 1_580.0,
	)
}
