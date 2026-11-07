package dev.skyparty.minigames

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.StringArgumentType
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.minigames.petrace.PetRace
import dev.skyparty.minigames.price.PriceGuess
import dev.skyparty.minigames.reaction.ReactionDuel
import dev.skyparty.minigames.ui.HubScreen
import dev.skyparty.minigames.ui.InviteToast
import dev.skyparty.module.impl.party.Minigames
import dev.skyparty.net.Net
import dev.skyparty.party.PartyTracker
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.Identifier
import java.util.UUID
import kotlin.random.Random

/**
 * Minigames framework: lobby rooms (party room or a shareable code room), invites, the hub screen
 * and the "/sp games" command.
 *
 * Lobby protocol (inside a party/code room, all messages echo to the sender):
 *  - "lobby.invite"  {game, inviteId, ready:[{id,name}]}  host picked a game; receivers get an invite toast
 *  - "lobby.hello"   {}                                    someone joined a code room; host re-sends the invite
 *  - "lobby.ready"   {inviteId}                           sender accepted
 *  - "lobby.unready" {}                                   sender declined / left the lobby
 *  - "lobby.start"   {game, inviteId, players:[{id,name}]} host started; listed players call game.open(room)
 *  - "lobby.quit"    {}                                    sender closed the running game
 * Any other message type is forwarded to the running game's [LobbyRoom].
 */
object MinigameHub {
	private val mc get() = Minecraft.getInstance()

	/** Lobby state for one joined room. */
	class Lobby(val room: Net.Room, val isCode: Boolean) {
		val transport: Net.Transport = Net.transport
		var gameId: String? = null
		var inviteId: String? = null
		var host: UUID? = null
		var hostName: String = ""
		val ready = linkedMapOf<UUID, String>()
		var active: LobbyRoom? = null

		val code: String? get() = if (isCode) room.id.removePrefix("code:") else null
		val isHost get() = host == Net.selfId
		val game get() = gameId?.let { MinigameRegistry.byId(it) }
		val selfReady get() = Net.selfId in ready
		val label get() = if (isCode) "Code room ${code}" else "Party room"
	}

	class Invite(val lobby: Lobby, val gameId: String, val inviteId: String, val hostName: String, val at: Long)

	private var partyLobby: Lobby? = null
	private var codeLobby: Lobby? = null

	/** Lobby shown in the hub: the code room if one is joined, otherwise the party room. */
	val lobby: Lobby? get() = codeLobby ?: partyLobby

	var invite: Invite? = null
		private set

	private val pending = mutableListOf<() -> Unit>()
	private var tickCount = 0

	fun init() {
		MinigameRegistry.register(ReactionDuel)
		MinigameRegistry.register(PetRace)
		MinigameRegistry.register(PriceGuess)
		MinigameRegistry.register(dev.skyparty.minigames.board.ConnectFour)
		MinigameRegistry.register(dev.skyparty.minigames.board.TicTacToe)
		MinigameRegistry.register(dev.skyparty.minigames.bomb.BombPass)
		MinigameRegistry.register(dev.skyparty.minigames.imposter.Imposter)
		MinigameRegistry.register(dev.skyparty.minigames.bluff.BazaarBluff)
		MinigameRegistry.register(dev.skyparty.minigames.sketch.Pictionary)
		for ((cmd, game) in listOf<Pair<String, Minigame>>("connect4" to dev.skyparty.minigames.board.ConnectFour, "tictactoe" to dev.skyparty.minigames.board.TicTacToe,
			"bomb" to dev.skyparty.minigames.bomb.BombPass, "imposter" to dev.skyparty.minigames.imposter.Imposter,
			"bluff" to dev.skyparty.minigames.bluff.BazaarBluff, "sketch" to dev.skyparty.minigames.sketch.Pictionary)) {
			SkypartyCommands.sub { ClientCommands.literal(cmd).executes { game.open(null); 1 } }
		}
		MinigameStore.load()

		ClientTickEvents.END_CLIENT_TICK.register { onTick(it) }
		ClientLifecycleEvents.CLIENT_STOPPING.register { MinigameStore.save() }
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) CombatGuard.onChat(message.string) }
		PartyTracker.listeners += { syncPartyRoom() }
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("skyparty", "minigame_invite"), InviteToast::render)
		registerCommand()
		// Server came online / went offline: move the code room over to the new connection.
		Net.transportListeners += {
			codeLobby?.let { old -> old.code?.let { code -> leaveLobby(old); codeLobby = joinLobby("code:$code", isCode = true).also { l -> l.room.send("lobby.hello") } } }
			syncPartyRoom()
		}
	}

	// ------------------------------------------------------------------ screens

	/** Opens [screen] after the chat screen (which ran the command) has closed. */
	fun openScreen(screen: Screen?) = later { mc.gui.setScreen(screen) }

	/** Runs [action] on the next client tick (after a command's chat screen has closed). */
	fun later(action: () -> Unit) {
		pending += action
	}

	fun openHub() = openScreen(HubScreen())

	private fun onTick(mc: Minecraft) {
		tickCount++
		CombatGuard.tick(mc)
		if (pending.isNotEmpty()) {
			val actions = pending.toList()
			pending.clear()
			actions.forEach { it() }
		}
		if (tickCount % 40 == 0) syncPartyRoom()
		invite?.let { if (System.currentTimeMillis() - it.at > INVITE_TIMEOUT_MS) invite = null }
	}

	// ------------------------------------------------------------------ rooms

	private fun syncPartyRoom() {
		val wanted = if (Minigames.enabled) PartyTracker.roomId() else null
		val current = partyLobby
		if (current?.room?.id == wanted && (current == null || current.transport === Net.transport)) return
		current?.let { leaveLobby(it) }
		partyLobby = wanted?.let { joinLobby(it, isCode = false) }
	}

	private fun joinLobby(roomId: String, isCode: Boolean): Lobby {
		val room = Net.join(roomId)
		val lobby = Lobby(room, isCode)
		room.onMessage = { handle(lobby, it) }
		room.onMembersChanged = { members ->
			lobby.ready.keys.retainAll(members + Net.selfId)
			lobby.active?.let { a -> a.members.filter { it !in members }.forEach { a.removePlayer(it) } }
		}
		return lobby
	}

	private fun leaveLobby(lobby: Lobby) {
		if (lobby.selfReady) lobby.room.send("lobby.unready")
		lobby.room.leave()
		if (invite?.lobby === lobby) invite = null
	}

	fun createCode(): String {
		codeLobby?.let { leaveLobby(it) }
		val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
		val code = (1..6).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
		codeLobby = joinLobby("code:$code", isCode = true)
		copyCode()
		return code
	}

	fun joinCode(raw: String) {
		val code = raw.trim().uppercase().removePrefix("CODE:")
		if (!Regex("[A-Z0-9]{6}").matches(code)) {
			Chat.info("§cRoom codes have 6 letters/digits, e.g. §fK7QX2M§c.")
			return
		}
		codeLobby?.let { leaveLobby(it) }
		val lobby = joinLobby("code:$code", isCode = true)
		codeLobby = lobby
		lobby.room.send("lobby.hello")
		Chat.info("§aJoined room §f$code§a. Waiting for the host to pick a game…")
		if (!Net.transport.connected) Chat.info("§7(Offline mode: online rooms need the Skyparty backend.)")
	}

	fun leaveCode() {
		codeLobby?.let { leaveLobby(it) }
		codeLobby = null
	}

	fun copyCode() {
		val code = codeLobby?.code ?: return
		mc.keyboardHandler.clipboard = code
		Chat.info("§aRoom code §f$code §acopied. Friends join with §f/sp games join $code")
	}

	// ------------------------------------------------------------------ actions

	fun playSolo(game: Minigame) {
		if (CombatGuard.blocked) {
			Chat.info("§cMinigames are paused while you're in combat.")
			return
		}
		mc.gui.setScreen(null)
		game.open(LobbyRoom.solo(game.id))
	}

	/** Host: pick [game] in the current lobby and invite everyone in it. */
	fun hostGame(game: Minigame) {
		val lobby = lobby ?: run {
			Chat.info("§cJoin a party or create a room code first.")
			return
		}
		lobby.room.send("lobby.invite", JsonObject().apply {
			addProperty("game", game.id)
			addProperty("inviteId", UUID.randomUUID().toString().take(8))
			add("ready", playersJson(mapOf(Net.selfId to Net.selfName)))
		})
	}

	fun accept() {
		val inv = invite ?: run {
			Chat.info("§7No pending minigame invite.")
			return
		}
		invite = null
		inv.lobby.room.send("lobby.ready", JsonObject().apply { addProperty("inviteId", inv.inviteId) })
		openHub()
	}

	fun deny() {
		if (invite == null) Chat.info("§7No pending minigame invite.") else Chat.info("§7Invite declined.")
		invite = null
	}

	fun setReady(lobby: Lobby, ready: Boolean) {
		if (ready) lobby.room.send("lobby.ready", JsonObject().apply { addProperty("inviteId", lobby.inviteId) })
		else lobby.room.send("lobby.unready")
	}

	fun start(lobby: Lobby) {
		val game = lobby.game ?: return
		if (!lobby.isHost) return
		if (lobby.ready.size < game.minPlayers) {
			Chat.info("§c${game.name} needs at least ${game.minPlayers} players.")
			return
		}
		val players = lobby.ready.entries.take(game.maxPlayers).associate { it.key to it.value }
		lobby.room.send("lobby.start", JsonObject().apply {
			addProperty("game", game.id)
			addProperty("inviteId", lobby.inviteId)
			add("players", playersJson(players))
		})
	}

	// ------------------------------------------------------------------ messages

	private fun handle(lobby: Lobby, msg: Net.Message) {
		val p = msg.payload
		when (msg.type) {
			"lobby.invite" -> {
				val gameId = p["game"]?.asString ?: return
				val game = MinigameRegistry.byId(gameId) ?: return
				lobby.gameId = gameId
				lobby.inviteId = p["inviteId"]?.asString
				lobby.host = msg.from
				lobby.hostName = msg.fromName
				lobby.ready.clear()
				lobby.ready.putAll(readPlayers(p["ready"]?.asJsonArray))
				lobby.ready[msg.from] = msg.fromName
				if (msg.from != Net.selfId && !lobby.selfReady) offerInvite(lobby, game)
			}
			"lobby.hello" -> if (lobby.isHost && lobby.gameId != null && msg.from != Net.selfId) {
				lobby.room.send("lobby.invite", JsonObject().apply {
					addProperty("game", lobby.gameId)
					addProperty("inviteId", lobby.inviteId)
					add("ready", playersJson(lobby.ready))
				})
			}
			"lobby.ready" -> if (lobby.gameId != null) {
				lobby.ready[msg.from] = msg.fromName
				if (msg.from == Net.selfId && invite?.lobby === lobby) invite = null
			}
			"lobby.unready" -> {
				lobby.ready.remove(msg.from)
				if (msg.from == lobby.host) {
					lobby.gameId = null
					lobby.host = null
					if (invite?.lobby === lobby) invite = null
				}
			}
			"lobby.start" -> {
				val game = MinigameRegistry.byId(p["game"]?.asString ?: return) ?: return
				val players = readPlayers(p["players"]?.asJsonArray)
				if (msg.from == Net.selfId || Net.selfId in players) startGame(lobby, game, msg.from, players)
				if (invite?.lobby === lobby) invite = null
			}
			"lobby.quit" -> lobby.active?.removePlayer(msg.from)
			else -> lobby.active?.let { active -> if (msg.from in active.members) active.onMessage(msg) }
		}
	}

	private fun startGame(lobby: Lobby, game: Minigame, host: UUID, players: Map<UUID, String>) {
		if (CombatGuard.blocked) {
			Chat.info("§c${game.name} started, but you're in combat – skipped (Pause in Combat).")
			lobby.room.send("lobby.quit")
			return
		}
		lobby.active?.leave()
		val room = LobbyRoom(lobby.room, host, players) { r ->
			if (lobby.active === r) {
				lobby.active = null
				lobby.room.send("lobby.quit")
			}
		}
		lobby.active = room
		mc.gui.setScreen(null)
		game.open(room)
	}

	private fun offerInvite(lobby: Lobby, game: Minigame) {
		if (!Minigames.enabled || !Minigames.invites.value || CombatGuard.blocked) return
		val inviteId = lobby.inviteId ?: return
		if (invite?.inviteId == inviteId) return
		invite = Invite(lobby, game.id, inviteId, lobby.hostName, System.currentTimeMillis())
		val accept = Component.literal("§a§l[Accept]").withStyle { s ->
			s.withClickEvent(ClickEvent.RunCommand("/sp games accept"))
				.withHoverEvent(HoverEvent.ShowText(Component.literal("Join ${game.name}")))
		}
		val text = Component.literal("${Chat.PREFIX}§f${lobby.hostName} §7invited you to §b${game.name}§7. ")
			.append(accept)
			.append(Component.literal(" §8(/sp games accept)"))
		mc.player?.sendSystemMessage(text)
	}

	private fun playersJson(players: Map<UUID, String>) = JsonArray().apply {
		for ((id, name) in players) add(JsonObject().apply {
			addProperty("id", id.toString())
			addProperty("name", name)
		})
	}

	private fun readPlayers(arr: JsonArray?): LinkedHashMap<UUID, String> {
		val out = linkedMapOf<UUID, String>()
		arr?.forEach { el ->
			val o = el.asJsonObject
			runCatching { out[UUID.fromString(o["id"].asString)] = o["name"].asString }
		}
		return out
	}

	// ------------------------------------------------------------------ command

	private fun registerCommand() {
		SkypartyCommands.sub {
			val root = ClientCommands.literal("games").executes { openHub(); 1 }
			root.then(ClientCommands.literal("join").then(
				ClientCommands.argument("code", StringArgumentType.word()).executes { ctx ->
					joinCode(StringArgumentType.getString(ctx, "code"))
					openHub()
					1
				}
			))
			root.then(ClientCommands.literal("accept").executes { accept(); 1 })
			root.then(ClientCommands.literal("deny").executes { deny(); 1 })
			root.then(ClientCommands.literal("code").executes { createCode(); openHub(); 1 })
			root.then(ClientCommands.literal("leave").executes {
				leaveCode()
				Chat.info("§7Left the code room.")
				1
			})
			root.then(ClientCommands.literal("play").then(
				ClientCommands.argument("game", StringArgumentType.word()).executes { ctx ->
					val id = StringArgumentType.getString(ctx, "game")
					val game = MinigameRegistry.byId(id)
					if (game == null) Chat.info("§cUnknown game §f$id§c. Games: ${MinigameRegistry.all.joinToString { it.id }}")
					else if (CombatGuard.blocked) Chat.info("§cMinigames are paused while you're in combat.")
					else later { game.open(LobbyRoom.solo(game.id)) }
					1
				}
			))
			root
		}
	}

	const val INVITE_TIMEOUT_MS = 60_000L
}
