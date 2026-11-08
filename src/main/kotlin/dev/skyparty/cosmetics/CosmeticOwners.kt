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
 *     { "cat_ears": ["name", …], "dragon_wings": ["name", …] }
 *
 * Fetched at start and every 10 minutes; the last good list is cached on disk for offline starts.
 * Until a list was ever loaded, the built-in names below are used.
 */
object CosmeticOwners {
	const val URL = "https://raw.githubusercontent.com/eren-git-hub/skyparty/main/cosmetics.json"
	const val CAT_EARS = "cat_ears"
	const val DRAGON_WINGS = "dragon_wings"
	private const val REFRESH_TICKS = 20 * 60 * 10

	private val BUILT_IN = mapOf(
		CAT_EARS to setOf("evocative", "mutsiri", "jijli", "mongers", "limnophobia"),
		DRAGON_WINGS to setOf("exlusiv"),
	)

	private val cacheFile = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("cosmetics_cache.json")
	private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build() }

	@Volatile private var owners: Map<String, Set<String>> = BUILT_IN
	@Volatile private var loading = false
	private var ticks = 0

	/** Lowercase names per cosmetic (for tests and the command). */
	val current: Map<String, Set<String>> get() = owners

	fun has(cosmetic: String, name: String) = name.lowercase() in owners[cosmetic].orEmpty()

	fun init() {
		runCatching { if (Files.exists(cacheFile)) owners = parse(Files.readString(cacheFile)) }
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
			.thenApply { if (it.statusCode() != 200) error("HTTP ${it.statusCode()}"); it.body() to parse(it.body()) }
			.whenComplete { result, t ->
				Minecraft.getInstance().execute {
					loading = false
					if (t != null || result == null) {
						Skyparty.LOGGER.warn("Cosmetics list fetch failed: {}", t?.message)
						done?.invoke(false)
					} else {
						owners = result.second
						runCatching {
							Files.createDirectories(cacheFile.parent)
							Files.writeString(cacheFile, result.first)
						}
						done?.invoke(true)
					}
				}
			}
	}

	/** Parses the JSON; names are lowercased, unknown keys are kept (for future cosmetics). */
	fun parse(body: String): Map<String, Set<String>> {
		val root: JsonObject = JsonParser.parseString(body).asJsonObject
		return root.entrySet().filter { it.value.isJsonArray }.associate { (key, arr) ->
			key to arr.asJsonArray.mapNotNull { runCatching { it.asString.trim().lowercase() }.getOrNull()?.takeIf(String::isNotEmpty) }.toSet()
		}
	}
}
