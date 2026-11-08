package dev.skyparty.cosmetics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/**
 * Who has which cosmetic, from `cosmetics.json` in the GitHub repo, so cosmetics can be given or taken
 * away by editing that file — no new jar needed. Format:
 *
 *     { "cat_ears": ["name", …], "dragon_wings": […], "horns": […], "bunny_ears": […],
 *       "ranks": { "LARP": { "tag": "&b[LARP]", "players": ["exlusiv"] }, … } }
 *
 * "ranks" defines custom ranks once and lists who has them: that player's Hypixel rank ([VIP] … [MVP++]) is
 * replaced with the tag in chat, the tab list and name tags — only on screens of players with the mod.
 * Colors with & (or §) codes. The older form "rank_tags": { "name": "tag" } still works too.
 *
 * Fetched at start and every 10 minutes; the last good list is cached on disk for offline starts.
 * Until a list was ever loaded, the built-in names below are used.
 */
object CosmeticOwners {
	const val URL = "https://raw.githubusercontent.com/eren-git-hub/skyparty/main/cosmetics.json"
	const val CAT_EARS = "cat_ears"
	const val DRAGON_WINGS = "dragon_wings"
	const val HORNS = "horns"
	const val BUNNY_EARS = "bunny_ears"
	const val RANK_TAGS = "rank_tags"
	const val RANKS = "ranks"
	private const val REFRESH_TICKS = 20 * 60 * 10

	private val BUILT_IN = mapOf(
		CAT_EARS to setOf("evocative", "mutsiri", "jijli", "mongers", "limnophobia"),
		DRAGON_WINGS to setOf("exlusiv"),
	)
	private val BUILT_IN_TAGS = mapOf("exlusiv" to "§b[LARP]")

	private val cacheFile = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("cosmetics_cache.json")
	private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build() }

	@Volatile private var owners: Map<String, Set<String>> = BUILT_IN
	@Volatile private var tags: Map<String, String> = BUILT_IN_TAGS

	/** Lowercase name -> rank text that replaces their Hypixel rank. */
	val rankTags: Map<String, String> get() = tags
	@Volatile private var loading = false
	private var ticks = 0

	/** Lowercase names per cosmetic (for tests and the command). */
	val current: Map<String, Set<String>> get() = owners

	fun has(cosmetic: String, name: String) = name.lowercase() in owners[cosmetic].orEmpty()

	fun init() {
		runCatching { if (Files.exists(cacheFile)) Files.readString(cacheFile).let { owners = parse(it); tags = parseTags(it) } }
			.onFailure { Skyparty.LOGGER.warn("Bad cosmetics cache: {}", it.message) }
		refresh()
		ClientTickEvents.END_CLIENT_TICK.register { if (++ticks % REFRESH_TICKS == 0) refresh() }
		SkypartyCommands.sub {
			ClientCommands.literal("cosmetics").then(ClientCommands.literal("reload").executes {
				refresh { ok -> Chat.info(if (ok) "§aCosmetics reloaded: §f${summary()}" else "§cCouldn't load the cosmetics list (offline?).") }
				1
			})
		}
	}

	private fun summary() = owners.entries.joinToString(" §7· §f") { "${it.key} ${it.value.size}" }

	/** Loads the list from GitHub; [done] runs on the client thread with whether it worked. */
	fun refresh(done: ((Boolean) -> Unit)? = null) {
		if (loading) return
		loading = true
		// A per-minute query string keeps GitHub's CDN from serving a long-stale copy.
		val req = HttpRequest.newBuilder(URI.create("$URL?t=${System.currentTimeMillis() / 60_000}"))
			.timeout(Duration.ofSeconds(15)).header("User-Agent", "Skyparty-Mod").GET().build()
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
			.thenApply { if (it.statusCode() != 200) error("HTTP ${it.statusCode()}"); Triple(it.body(), parse(it.body()), parseTags(it.body())) }
			.whenComplete { result, t ->
				Minecraft.getInstance().execute {
					loading = false
					if (t != null || result == null) {
						Skyparty.LOGGER.warn("Cosmetics list fetch failed: {}", t?.message)
						done?.invoke(false)
					} else {
						owners = result.second
						tags = result.third
						runCatching {
							Files.createDirectories(cacheFile.parent)
							Files.writeString(cacheFile, result.first)
						}
						done?.invoke(true)
					}
				}
			}
	}

	/**
	 * Lowercase player name -> rank tag, from "ranks" ({ rank: { tag, players } }) and the older "rank_tags"
	 * ({ name: tag }). & codes become § codes; tags are cut at 32 chars. Without either key: the built-in tags.
	 */
	fun parseTags(body: String): Map<String, String> {
		val root = JsonParser.parseString(body).asJsonObject
		val ranks = root.getAsJsonObject(RANKS)
		val single = root.getAsJsonObject(RANK_TAGS)
		if (ranks == null && single == null) return BUILT_IN_TAGS
		val out = linkedMapOf<String, String>()
		single?.entrySet()?.forEach { (k, v) -> runCatching { out[k.trim().lowercase()] = color(v.asString) } }
		ranks?.entrySet()?.forEach { (rankName, def) ->
			runCatching {
				val o = def.asJsonObject
				val tag = color(o["tag"]?.asString ?: "[$rankName]")
				o.getAsJsonArray("players")?.forEach { p -> p.asString.trim().lowercase().takeIf(String::isNotEmpty)?.let { out[it] = tag } }
			}
		}
		return out
	}

	/** "&b[LARP]" -> "§b[LARP]" (only real color / style codes), max 32 chars. */
	private fun color(tag: String) = tag.replace(Regex("&([0-9a-fk-orA-FK-OR])"), "§$1").take(32)

	/** Parses the JSON; names are lowercased, unknown keys are kept (for future cosmetics). */
	fun parse(body: String): Map<String, Set<String>> {
		val root: JsonObject = JsonParser.parseString(body).asJsonObject
		return root.entrySet().filter { it.value.isJsonArray }.associate { (key, arr) ->
			key to arr.asJsonArray.mapNotNull { runCatching { it.asString.trim().lowercase() }.getOrNull()?.takeIf(String::isNotEmpty) }.toSet()
		}
	}
}
