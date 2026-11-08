package dev.skyparty.core

import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Keeps different Skyparty versions from silently breaking each other.
 *
 * RULES FOR FUTURE UPDATES (read before changing any room message):
 *  - Only ADD message types and fields; never rename or remove one that older versions send or read.
 *    Unknown types and fields are ignored by every version, so additions are always safe.
 *  - If a change can't be made that way (a message must mean something different), bump [PROTOCOL].
 *    Players on another protocol are then shown as "needs update" and can't be put into a game together,
 *    instead of joining and getting stuck.
 *  - Settings are stored by name: renaming a setting resets it, so keep names.
 *
 * Also checks GitHub for a newer release once per start and offers the download link in chat.
 */
object Compat {
	/** Room protocol. 1 = the format since 0.2.6 (clients that send no number are 0.2.6, i.e. 1). */
	const val PROTOCOL = 1

	val VERSION: String = FabricLoader.getInstance().getModContainer(Skyparty.MOD_ID)
		.map { it.metadata.version.friendlyString }.orElse("0.0.0")

	private const val RELEASES_API = "https://api.github.com/repos/eren-git-hub/skyparty/releases/latest"
	const val RELEASES_PAGE = "https://github.com/eren-git-hub/skyparty/releases/latest"

	/** Newest released version if it is newer than ours (null = up to date or unknown). */
	@Volatile var newerVersion: String? = null
		private set
	private var announced = false
	private var announceAt = 0L

	/** True if [a] is a higher version than [b] ("0.2.10" > "0.2.9"). */
	fun isNewer(a: String, b: String): Boolean {
		val pa = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
		val pb = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
		for (i in 0 until maxOf(pa.size, pb.size)) {
			val x = pa.getOrElse(i) { 0 }
			val y = pb.getOrElse(i) { 0 }
			if (x != y) return x > y
		}
		return false
	}

	fun init() {
		checkForUpdate()
		// Tell the player a few seconds after joining a server (chat is ready then), once per start.
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> announceAt = System.currentTimeMillis() + 6_000 }
		ClientTickEvents.END_CLIENT_TICK.register {
			if (!announced && announceAt != 0L && System.currentTimeMillis() >= announceAt) {
				announceAt = 0L
				newerVersion?.let { announced = true; announce(it) }
			}
		}
	}

	private fun checkForUpdate() {
		val req = HttpRequest.newBuilder(URI.create(RELEASES_API)).timeout(Duration.ofSeconds(15))
			.header("User-Agent", "Skyparty-Mod").header("Accept", "application/vnd.github+json").GET().build()
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
			.sendAsync(req, HttpResponse.BodyHandlers.ofString())
			.thenAccept { res ->
				if (res.statusCode() != 200) return@thenAccept
				val tag = JsonParser.parseString(res.body()).asJsonObject["tag_name"]?.asString ?: return@thenAccept
				if (isNewer(tag, VERSION)) newerVersion = tag.removePrefix("v")
			}
			.exceptionally { Skyparty.LOGGER.info("Update check failed: {}", it.message); null }
	}

	private fun announce(version: String) {
		val link = Component.literal("§a§l[Download]").withStyle { st ->
			st.withClickEvent(ClickEvent.OpenUrl(URI.create(RELEASES_PAGE)))
				.withHoverEvent(HoverEvent.ShowText(Component.literal(RELEASES_PAGE)))
		}
		Minecraft.getInstance().player?.sendSystemMessage(
			Component.literal("${Chat.PREFIX}§fSkyparty §e$version §fis out §7(you have $VERSION). ").append(link)
		)
	}
}
