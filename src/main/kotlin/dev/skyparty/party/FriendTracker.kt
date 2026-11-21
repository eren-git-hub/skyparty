package dev.skyparty.party

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import dev.skyparty.Skyparty
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.module.impl.party.FriendAlerts
import dev.skyparty.util.Chat
import dev.skyparty.util.Location
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Knows your Hypixel friends and tells you when one is in your lobby.
 *
 * Friends are learned from chat only: the /f list pages (typed by you, or read quietly once a day by
 * [sync], output hidden), "Friend > Name joined/left." and "You are now friends with Name". Hypixel's API
 * has no friends endpoint any more, so chat is the only source. Saved in config/skyparty/friends.json.
 *
 * "In your lobby" = in the player list of the server you are on (lobby, SkyBlock island or hub instance).
 */
object FriendTracker {
	private const val NAME = "(?:\\[[^\\]]{1,20}] )?([A-Za-z0-9_]{1,16})"

	/** UNVERIFIED: "<< Friends (Page 1 of 3) >>" (arrows only where there is another page). */
	val LIST_HEADER = Regex("^[<\\s]*Friends \\(Page (\\d+) of (\\d+)\\)[>\\s]*$")
	/** UNVERIFIED: "[MVP+] Name is in SkyBlock" / "Name is currently offline" / "Name is in an unknown realm". */
	val LIST_ENTRY = Regex("^$NAME is (?:currently offline|in .+)$")
	/** UNVERIFIED: reply to /f list without friends. */
	val NO_FRIENDS = Regex("^You don't have any friends.*$")
	/** VERIFIED: "Friend > Name joined." / "Friend > Name left." */
	val ONLINE = Regex("^Friend > $NAME (?:joined|left)\\.$")
	/** VERIFIED: "You are now friends with [MVP+] Name" */
	val ADDED = Regex("^You are now friends with $NAME!?$")
	/** UNVERIFIED: "You removed [VIP] Name from your friends list!" / "[VIP] Name removed you from their friends list!" */
	val REMOVED = Regex("^You removed $NAME from your friends list!?$")
	val REMOVED_BY = Regex("^$NAME removed you from their friends list!?$")

	private val gson = GsonBuilder().setPrettyPrinting().create()
	private val file = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("friends.json")

	/** Lowercase name → name as Hypixel spells it. */
	private val friends = LinkedHashMap<String, String>()
	private var syncedAt = 0L

	val names: Collection<String> get() = friends.values
	fun isFriend(name: String) = name.lowercase() in friends

	// --- /f list parsing --------------------------------------------------------------------------
	private var inBlock = false
	private var blockPage = 0
	private var blockPages = 0
	private var blockAt = 0L
	private val blockNames = ArrayList<String>()

	// --- quiet sync -------------------------------------------------------------------------------
	private var syncing = false
	private var syncLoud = false
	private var syncPage = 0
	private var nextPageAt = 0L
	private var syncTimeout = 0L
	private var hideUntil = 0L
	private val syncNames = ArrayList<String>()
	private var joinedAt = 0L

	// --- lobby ------------------------------------------------------------------------------------
	private var lastLevel: Any? = null
	private var settleAt = 0L
	private val inLobby = HashSet<String>()
	private var ticks = 0

	/** Tests only: names that count as being in the lobby too (singleplayer has no other players). */
	val testPlayers = mutableSetOf<String>()
	/** Tests only: the last lobby message shown. */
	var lastAlert: String? = null
		private set

	fun init() {
		load()
		ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
			if (overlay || System.currentTimeMillis() > hideUntil) return@register true
			val text = Chat.strip(message.string)
			if (!text.lines().all { isListLine(it.trim()) }) return@register true
			onChat(text)
			false
		}
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onChat(Chat.strip(message.string)) }
		ClientPlayConnectionEvents.JOIN.register { _, _, _ -> joinedAt = System.currentTimeMillis() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> stopSync(); lastLevel = null; inLobby.clear() }
		ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
		SkypartyCommands.sub {
			ClientCommands.literal("friends")
				.executes { describe(); 1 }
				.then(ClientCommands.literal("sync").executes { sync(loud = true); 1 })
				.then(ClientCommands.literal("add").then(ClientCommands.argument("name", StringArgumentType.word()).executes { c ->
					val n = StringArgumentType.getString(c, "name")
					if (add(n)) { save(); Chat.info("§aAdded §f$n §ato your Skyparty friends.") } else Chat.info("§7$n is already on the list.")
					1
				}))
				.then(ClientCommands.literal("remove").then(ClientCommands.argument("name", StringArgumentType.word()).executes { c ->
					val n = StringArgumentType.getString(c, "name")
					if (friends.remove(n.lowercase()) != null) { save(); Chat.info("§7Removed §f$n§7.") } else Chat.info("§7$n is not on the list.")
					1
				}))
		}
	}

	/** Feeds one stripped chat message (may hold several lines). Public for gametests. */
	fun onChat(raw: String) {
		for (line in raw.split('\n')) handleLine(line.trim())
	}

	private fun isListLine(l: String) = l.isEmpty() || PartyPatterns.SEPARATOR.matches(l) || LIST_HEADER.matches(l) ||
		LIST_ENTRY.matches(l) || NO_FRIENDS.matches(l)

	private fun handleLine(line: String) {
		if (line.isEmpty()) return
		if (inBlock) {
			LIST_ENTRY.matchEntire(line)?.let { blockNames += it.groupValues[1]; return }
			endBlock()
			if (PartyPatterns.SEPARATOR.matches(line)) return
		}
		LIST_HEADER.matchEntire(line)?.let {
			inBlock = true
			blockPage = it.groupValues[1].toInt()
			blockPages = it.groupValues[2].toInt()
			blockNames.clear()
			blockAt = System.currentTimeMillis()
			return
		}
		if (NO_FRIENDS.matches(line)) {
			if (syncing) { syncNames.clear(); finishSync() }
			return
		}
		ONLINE.matchEntire(line)?.let { if (add(it.groupValues[1])) save(); return }
		ADDED.matchEntire(line)?.let { if (add(it.groupValues[1])) save(); return }
		(REMOVED.matchEntire(line) ?: REMOVED_BY.matchEntire(line))?.let { if (friends.remove(it.groupValues[1].lowercase()) != null) save() }
	}

	private fun endBlock() {
		inBlock = false
		var changed = false
		for (n in blockNames) changed = add(n) || changed
		if (syncing && blockPage == syncPage) {
			syncNames += blockNames
			if (blockPage >= blockPages || blockNames.isEmpty()) finishSync()
			else { syncPage++; nextPageAt = System.currentTimeMillis() + 1_500 }
		}
		if (changed) save()
	}

	private fun add(name: String): Boolean {
		if (name.equals(Minecraft.getInstance().user.name, ignoreCase = true)) return false
		val old = friends.put(name.lowercase(), name)
		return old != name
	}

	/** Reads every /f list page, 1.5 s apart, hiding the output. Afterwards the list is exactly your friends. */
	fun sync(loud: Boolean) {
		if (Minecraft.getInstance().player == null) return
		if (!Location.onHypixel && !Location.isDev) { if (loud) Chat.info("§7Friend sync only works on Hypixel."); return }
		if (syncing) return
		syncing = true
		syncLoud = loud
		syncPage = 1
		syncNames.clear()
		nextPageAt = System.currentTimeMillis()
		if (loud) Chat.info("§7Reading your friend list…")
	}

	private fun stopSync() { syncing = false; nextPageAt = 0L }

	private fun finishSync() {
		stopSync()
		friends.clear()
		syncNames.forEach { add(it) }
		syncedAt = System.currentTimeMillis()
		save()
		if (syncLoud) Chat.info("§aFriend list synced: §f${friends.size} §afriends.")
		ticks = 0
	}

	private fun tick(mc: Minecraft) {
		val now = System.currentTimeMillis()
		// A list without a closing line still ends.
		if (inBlock && now - blockAt > 1_000) endBlock()
		if (syncing) {
			if (nextPageAt != 0L && now >= nextPageAt) {
				nextPageAt = 0L
				syncTimeout = now + 5_000
				hideUntil = now + 4_000
				Chat.command("f list $syncPage")
			} else if (nextPageAt == 0L && now > syncTimeout && !inBlock) {
				stopSync()
				if (syncLoud) Chat.info("§cCouldn't read your friend list (no answer from Hypixel).")
			}
		}
		if (mc.player == null) return
		// Once a day, a few seconds after joining Hypixel.
		if (joinedAt != 0L && now - joinedAt > 8_000) {
			joinedAt = 0L
			if (FriendAlerts.autoSync.value && Location.onHypixel && now - syncedAt > 24 * 3_600_000L) sync(loud = false)
		}
		if (++ticks % 20 != 0) return
		checkLobby(mc, now)
	}

	private fun checkLobby(mc: Minecraft, now: Long) {
		val level = mc.level
		if (level !== lastLevel) {
			// New server or world: wait a moment for the player list, then report everyone at once.
			lastLevel = level
			inLobby.clear()
			settleAt = now + 3_000
			return
		}
		if (now < settleAt) return
		val present = lobbyNames(mc).filter { isFriend(it) }.associateBy { it.lowercase() }
		val fresh = present.filterKeys { it !in inLobby }.values.sortedBy { it.lowercase() }
		val first = settleAt != 0L
		settleAt = 0L
		inLobby.retainAll(present.keys)
		inLobby += present.keys
		if (fresh.isEmpty() || !FriendAlerts.alerts.value) return
		val list = fresh.joinToString("§7, ") { "§f$it" }
		val msg = when {
			first && fresh.size == 1 -> "§aYour friend $list §ais in your lobby!"
			first -> "§aFriends in your lobby: $list"
			fresh.size == 1 -> "§aYour friend $list §ajoined your lobby!"
			else -> "§aFriends joined your lobby: $list"
		}
		lastAlert = msg
		Chat.info(msg)
		if (FriendAlerts.sound.value) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f))
	}

	/** Every player name on this server (the full player list, not just players in render distance). */
	private fun lobbyNames(mc: Minecraft): Set<String> {
		val self = mc.user.name
		val out = HashSet<String>()
		mc.connection?.onlinePlayers?.forEach { out += it.profile.name }
		mc.level?.players()?.forEach { out += it.gameProfile.name }
		out += testPlayers
		out.removeIf { it.equals(self, ignoreCase = true) }
		return out
	}

	private fun describe() {
		if (friends.isEmpty()) {
			Chat.info("§7No friends known yet. Run §f/sp friends sync §7(or open §f/f list§7).")
			return
		}
		val here = lobbyNames(Minecraft.getInstance()).filter { isFriend(it) }
		Chat.info("§f${friends.size} §7friends known" + if (here.isEmpty()) "§7, none in this lobby." else "§7. In this lobby: §a" + here.joinToString("§7, §a"))
		Chat.raw("§8 /sp friends sync · add <name> · remove <name>")
	}

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			val o = JsonParser.parseString(Files.readString(file)).asJsonObject
			syncedAt = o["syncedAt"]?.asLong ?: 0L
			o.getAsJsonArray("names")?.forEach { val n = it.asString; friends[n.lowercase()] = n }
		}.onFailure { Skyparty.LOGGER.warn("Bad friends file: {}", it.message) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			val o = JsonObject().apply {
				addProperty("syncedAt", syncedAt)
				add("names", JsonArray().apply { friends.values.forEach { add(it) } })
			}
			val tmp = file.resolveSibling("friends.json.tmp")
			Files.writeString(tmp, gson.toJson(o))
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		}.onFailure { Skyparty.LOGGER.warn("Could not save friends: {}", it.message) }
	}

	/** Tests only: forget everything (file included). */
	fun resetForTest() {
		friends.clear(); syncedAt = 0L; testPlayers.clear(); inLobby.clear(); lastAlert = null; lastLevel = null
		stopSync(); save()
	}
}
