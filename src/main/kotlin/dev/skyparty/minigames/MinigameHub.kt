package dev.skyparty.minigames

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.StringArgumentType
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.core.Compat
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
 * Minigames framework: lobby rooms (party room or a shareable code room), game rooms inside them, invites,
 * the hub screen and the "/sp games" command.
 *
 * A lobby (party/code room) can hold several game rooms at once ("sessions"), e.g. two players in Bazaar
 * Bluff and two in Parkour. Each player is in at most one. Everyone starts NOT ready; the host starts with
 * the players that are ready. Game messages carry the session id ("_s"), so games never see each other.
 *
 * Lobby protocol (all messages echo to the sender, every client applies them in the same order):
 *  - "lobby.open"  {sid, game}            sender opened a game room (and is in it, not ready)
 *  - "lobby.game"  {sid, game}            host switched the room to another game (everyone stays, not ready)
 *  - "lobby.pause" {sid, on}              a player paused / resumed the running game
 *  - "lobby.join"  {sid}                  sender joined that room (leaving any other; ignored when full)
 *  - "lobby.ready" {sid, on}              sender is (not) ready
 *  - "lobby.leave" {sid}                  sender left the room; the host leaving closes it
 *  - "lobby.start" {sid, players:[…]}     host started; listed players open the game
 *  - "lobby.quit"  {sid}                  sender closed the running game
 *  - "lobby.hello" {pv, v}                someone (re)joined the lobby; every host answers with "lobby.state",
 *                                         everyone answers with "lobby.ver"
 *  - "lobby.ver"   {pv, v}                sender's room protocol and mod version (see [dev.skyparty.core.Compat])
 *  - "lobby.state" {sid, game, joined:[…], ready:[ids], playing:[ids]}  full state of one room (from its host)
 */
object MinigameHub {
	private val mc get() = Minecraft.getInstance()

	/** One game room inside a lobby. */
	class Session(val id: String, var gameId: String, val host: UUID, val hostName: String) {
		/** The running game is paused (someone went to do a dungeon etc.). */
		var paused = false
		var pausedBy: String? = null
		/** Everyone in this room (join order, host first). */
		val joined = linkedMapOf<UUID, String>()
		val ready = linkedSetOf<UUID>()
		/** Players of the running game (empty = waiting). */
		val playing = linkedSetOf<UUID>()

		val game get() = MinigameRegistry.byId(gameId)
		val isHost get() = host == Net.selfId
		val inGame get() = playing.isNotEmpty()
		val selfIn get() = Net.selfId in joined
		val selfReady get() = Net.selfId in ready
		fun nameOf(id: UUID) = joined[id] ?: "Player"
	}

	/** Lobby state for one joined party/code room. */
	class Lobby(val room: Net.Room, val isCode: Boolean) {
		val transport: Net.Transport = Net.transport
		val sessions = linkedMapOf<String, Session>()
		/** Room protocol and mod version per player (players that never said are 0.2.6 = protocol 1). */
		val protocols = HashMap<UUID, Int>()
		val versions = HashMap<UUID, String>()

		fun protocolOf(id: UUID) = if (id == Net.selfId) Compat.PROTOCOL else protocols[id] ?: 1
		/** Can play with you (same room protocol). */
		fun compatible(id: UUID) = protocolOf(id) == Compat.PROTOCOL
		fun versionOf(id: UUID) = if (id == Net.selfId) Compat.VERSION else versions[id] ?: "0.2.6"
		var active: LobbyRoom? = null

		val code: String? get() = if (isCode) room.id.removePrefix("code:") else null
		val label get() = if (isCode) "Code room ${code}" else "Party room"
		/** The game room you are in, if any. */
		val mine: Session? get() = sessions.values.firstOrNull { it.selfIn }
	}

	class Invite(val lobby: Lobby, val sessionId: String, val gameId: String, val hostName: String, val at: Long)

	private var partyLobby: Lobby? = null
	private var codeLobby: Lobby? = null

	/** Lobby shown in the hub: the code room if one is joined, otherwise the party room. */
	val lobby: Lobby? get() = codeLobby ?: partyLobby

	/** In a party but sitting alone in a code room (the hub then offers to go back to the party room). */
	val aloneInCodeWithParty: Boolean
		get() = partyLobby != null && codeLobby?.let { it.room.members.size <= 1 && it.active == null } == true

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
		MinigameRegistry.register(dev.skyparty.minigames.quiz.SkyblockQuiz)
		MinigameRegistry.register(dev.skyparty.minigames.typing.TypingRace)
		MinigameRegistry.register(dev.skyparty.minigames.trader.BazaarTrader)
		MinigameRegistry.register(dev.skyparty.minigames.fishing.FishingDerby)
		MinigameRegistry.register(dev.skyparty.minigames.dungeon.DungeonRun)
		for ((cmd, game) in listOf<Pair<String, Minigame>>("connect4" to dev.skyparty.minigames.board.ConnectFour, "tictactoe" to dev.skyparty.minigames.board.TicTacToe,
			"bomb" to dev.skyparty.minigames.bomb.BombPass, "imposter" to dev.skyparty.minigames.imposter.Imposter,
			"bluff" to dev.skyparty.minigames.bluff.BazaarBluff, "sketch" to dev.skyparty.minigames.sketch.Pictionary,
			"quiz" to dev.skyparty.minigames.quiz.SkyblockQuiz, "typing" to dev.skyparty.minigames.typing.TypingRace,
			"trader" to dev.skyparty.minigames.trader.BazaarTrader, "fishing" to dev.skyparty.minigames.fishing.FishingDerby, "dungeon" to dev.skyparty.minigames.dungeon.DungeonRun)) {
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
			codeLobby?.let { old -> old.code?.let { code -> leaveLobby(old); codeLobby = joinLobby("code:$code", isCode = true) } }
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
		invite?.let { if (System.currentTimeMillis() - it.at > INVITE_TIMEOUT_MS || it.lobby.sessions[it.sessionId] == null) invite = null }
	}

	// ------------------------------------------------------------------ rooms

	private fun syncPartyRoom() {
		val wanted = if (Minigames.enabled) PartyTracker.roomId() else null
		val current = partyLobby
		if (current?.room?.id == wanted && (current == null || current.transport === Net.transport)) return
		current?.let { leaveLobby(it) }
		partyLobby = wanted?.let { joinLobby(it, isCode = false) }
		// Joined a party while sitting alone in a code room: drop the code room so the party room takes over.
		if (current == null && aloneInCodeWithParty) {
			val code = codeLobby?.code
			leaveCode()
			Chat.info("§7Left your empty room $code – games now go to your party.")
		}
	}

	private fun joinLobby(roomId: String, isCode: Boolean): Lobby {
		val room = Net.join(roomId)
		val lobby = Lobby(room, isCode)
		room.onMessage = { handle(lobby, it) }
		room.onMembersChanged = { members ->
			val present = members + Net.selfId
			for (s in lobby.sessions.values.toList()) {
				if (s.host !in present) { lobby.sessions.remove(s.id); continue }
				s.joined.keys.retainAll(present); s.ready.retainAll(present); s.playing.retainAll(present)
			}
			lobby.active?.let { a -> a.members.filter { it !in present }.forEach { a.removePlayer(it) } }
		}
		// Ask the hosts of rooms that already exist to describe them.
		room.send("lobby.hello", versionJson())
		return lobby
	}

	private fun leaveLobby(lobby: Lobby) {
		lobby.active?.quit()
		lobby.mine?.let { lobby.room.send("lobby.leave", sid(it.id)) }
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
		codeLobby = joinLobby("code:$code", isCode = true)
		Chat.info("§aJoined room §f$code§a. Open a game or join one in the room list.")
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

	private fun sid(id: String) = JsonObject().apply { addProperty("sid", id) }

	/** Opens a new game room for [game] in the current lobby (you join it, not ready). */
	fun hostGame(game: Minigame) {
		val lobby = lobby ?: run {
			Chat.info("§cJoin a party or create a room code first.")
			return
		}
		val mine = lobby.mine
		if (mine != null) {
			// You stay in your room until you press Leave: the host just switches its game.
			if (mine.isHost) changeGame(lobby, mine, game)
			else Chat.info("§7${mine.hostName} picks the game in your room. Press §fLeave §7first to open your own.")
			return
		}
		lobby.room.send("lobby.open", sid(UUID.randomUUID().toString().take(8)).apply { addProperty("game", game.id) })
	}

	/** Host: switch your room to [game]; everyone stays in the room (not ready). */
	fun changeGame(lobby: Lobby, session: Session, game: Minigame) {
		if (!session.isHost || session.inGame || session.gameId == game.id) return
		if (session.joined.size > game.maxPlayers) {
			Chat.info("§c${game.name} is for at most ${game.maxPlayers} players – your room has ${session.joined.size}.")
			return
		}
		lobby.room.send("lobby.game", sid(session.id).apply { addProperty("game", game.id) })
	}

	fun join(lobby: Lobby, session: Session) {
		if (session.selfIn) return
		val game = session.game ?: return
		if (!lobby.compatible(session.host)) {
			Chat.info(versionHint(lobby, session.host, session.hostName))
			return
		}
		if (session.joined.size >= game.maxPlayers) {
			Chat.info("§c${session.hostName}'s ${game.name} room is full (${game.maxPlayers} players).")
			return
		}
		lobby.mine?.let { leave(lobby, it) }
		lobby.room.send("lobby.join", sid(session.id))
		if (invite?.sessionId == session.id) invite = null
	}

	fun leave(lobby: Lobby, session: Session) {
		if (lobby.active?.session == session.id) lobby.active?.quit()
		lobby.room.send("lobby.leave", sid(session.id))
	}

	fun setReady(lobby: Lobby, session: Session, ready: Boolean) =
		lobby.room.send("lobby.ready", sid(session.id).apply { addProperty("on", ready) })

	/** Your running game in the shown lobby (also while it is paused and its screen is closed). */
	val activeGame: LobbyRoom? get() = lobby?.active ?: soloActive?.takeIf { !it.left }
	private var soloActive: LobbyRoom? = null

	/** Every solo room registers here, so a paused solo game can be reopened with "/sp". */
	internal fun soloStarted(room: LobbyRoom) {
		soloActive = room
	}

	/** What "/sp" opens: your running (paused) game if there is one, otherwise the games menu. */
	fun mainScreen(): Screen = activeGame?.screen ?: HubScreen()

	/** Reopens the screen of your running (paused) game. */
	fun backToGame(): Boolean {
		val screen = activeGame?.screen ?: return false
		mc.gui.setScreen(screen)
		return true
	}

	fun accept() {
		val inv = invite ?: run {
			Chat.info("§7No pending minigame invite.")
			return
		}
		invite = null
		val session = inv.lobby.sessions[inv.sessionId] ?: run {
			Chat.info("§7That game room is gone.")
			return
		}
		// Accepting a party invite while alone in a code room: leave it, or the hub would keep showing the empty room.
		if (inv.lobby === partyLobby && aloneInCodeWithParty) leaveCode()
		join(inv.lobby, session)
		openHub()
	}

	fun deny() {
		if (invite == null) Chat.info("§7No pending minigame invite.") else Chat.info("§7Invite declined.")
		invite = null
	}

	fun start(lobby: Lobby, session: Session) {
		val game = session.game ?: return
		if (!session.isHost || session.inGame) return
		incompatibleReady(lobby, session).takeIf { it.isNotEmpty() }?.let { names ->
			Chat.info("§c${names.joinToString()} ${if (names.size == 1) "has" else "have"} a different Skyparty version – everyone needs the same one to play together.")
			return
		}
		if (!session.selfReady) {
			Chat.info("§7Ready up first – the host plays too.")
			return
		}
		val ready = session.joined.filterKeys { it in session.ready }
		if (ready.size < game.minPlayers) {
			Chat.info("§c${game.name} needs at least ${game.minPlayers} ready players.")
			return
		}
		lobby.room.send("lobby.start", sid(session.id).apply {
			add("players", playersJson(ready.entries.take(game.maxPlayers).associate { it.key to it.value }))
		})
	}

	// ------------------------------------------------------------------ messages

	private fun handle(lobby: Lobby, msg: Net.Message) {
		val p = msg.payload
		val from = msg.from
		val s = p["sid"]?.asString?.let { lobby.sessions[it] }
		when (msg.type) {
			"lobby.open" -> {
				val id = p["sid"]?.asString ?: return
				val game = MinigameRegistry.byId(p["game"]?.asString ?: return) ?: return
				leaveAll(lobby, from)
				val session = Session(id, game.id, from, msg.fromName)
				session.joined[from] = msg.fromName
				lobby.sessions[id] = session
				if (from != Net.selfId && lobby.mine == null) {
					if (lobby.compatible(from)) offerInvite(lobby, session, game)
					else if (Minigames.invites.value) Chat.info("§f${msg.fromName} §7opened §b${game.name}§7, but " + versionHint(lobby, from, msg.fromName).removePrefix("§c"))
				}
			}
			"lobby.game" -> if (s != null && from == s.host && !s.inGame) {
				val game = MinigameRegistry.byId(p["game"]?.asString ?: return) ?: return
				s.gameId = game.id
				s.ready.clear()
				if (from != Net.selfId && s.selfIn) Chat.info("§f${s.hostName} §7switched your room to §b${game.name}§7.")
			}
			"lobby.pause" -> if (s != null && from in s.playing) {
				val on = p["on"]?.asBoolean == true
				s.paused = on
				s.pausedBy = if (on) msg.fromName else null
				val active = lobby.active
				if (active != null && active.session == s.id) {
					active.applyPause(on, msg.fromName)
					if (!on && mc.gui.screen() !== active.screen) offerResume(active, msg.fromName)
				}
			}
			"lobby.join" -> if (s != null && (from in s.joined || s.joined.size < (s.game?.maxPlayers ?: 8))) {
				leaveAll(lobby, from, except = s.id)
				s.joined[from] = msg.fromName
			}
			"lobby.ready" -> if (s != null && from in s.joined) {
				if (p["on"]?.asBoolean == true) s.ready += from else s.ready -= from
			}
			"lobby.leave" -> if (s != null) removeFrom(lobby, s, from)
			"lobby.start" -> if (s != null && from == s.host) {
				val game = s.game ?: return
				val players = readPlayers(p["players"]?.asJsonArray)
				s.playing.clear(); s.playing += players.keys
				s.ready.clear()
				s.paused = false; s.pausedBy = null
				if (Net.selfId in players) startGame(lobby, s, game, players)
			}
			"lobby.quit" -> if (s != null) {
				s.playing -= from
				if (s.playing.isEmpty()) { s.paused = false; s.pausedBy = null }
				if (lobby.active?.session == s.id) lobby.active?.removePlayer(from)
			}
			"lobby.hello" -> if (from != Net.selfId) {
				readVersion(lobby, from, p)
				lobby.room.send("lobby.ver", versionJson())
				for (session in lobby.sessions.values) if (session.isHost) lobby.room.send("lobby.state", stateJson(session))
			}
			"lobby.ver" -> if (from != Net.selfId) readVersion(lobby, from, p)
			"lobby.state" -> {
				val id = p["sid"]?.asString ?: return
				if (id in lobby.sessions) return
				val game = MinigameRegistry.byId(p["game"]?.asString ?: return) ?: return
				val session = Session(id, game.id, from, msg.fromName)
				session.joined.putAll(readPlayers(p["joined"]?.asJsonArray))
				session.joined.putIfAbsent(from, msg.fromName)
				p["ready"]?.asJsonArray?.forEach { runCatching { session.ready += UUID.fromString(it.asString) } }
				p["playing"]?.asJsonArray?.forEach { runCatching { session.playing += UUID.fromString(it.asString) } }
				session.paused = p["paused"]?.asBoolean == true
				lobby.sessions[id] = session
			}
			else -> lobby.active?.let { active ->
				// Game traffic: only for the running game of the same room.
				if (p["_s"]?.asString == active.session && from in active.members) active.onMessage(msg)
			}
		}
	}

	/** [player] leaves every room of [lobby] (one room per player), except [except]. */
	private fun leaveAll(lobby: Lobby, player: UUID, except: String? = null) {
		for (s in lobby.sessions.values.toList()) if (s.id != except && player in s.joined) removeFrom(lobby, s, player)
	}

	private fun removeFrom(lobby: Lobby, s: Session, player: UUID) {
		s.joined.remove(player); s.ready.remove(player); s.playing.remove(player)
		if (player == s.host || s.joined.isEmpty()) {
			lobby.sessions.remove(s.id)
			if (invite?.sessionId == s.id) invite = null
		}
	}

	private fun stateJson(s: Session) = sid(s.id).apply {
		addProperty("game", s.gameId)
		add("joined", playersJson(s.joined))
		add("ready", JsonArray().apply { s.ready.forEach { add(it.toString()) } })
		add("playing", JsonArray().apply { s.playing.forEach { add(it.toString()) } })
		addProperty("paused", s.paused)
	}

	private fun startGame(lobby: Lobby, session: Session, game: Minigame, players: Map<UUID, String>) {
		if (CombatGuard.blocked) {
			Chat.info("§c${game.name} started, but you're in combat – skipped (Pause in Combat).")
			lobby.room.send("lobby.quit", sid(session.id))
			return
		}
		lobby.active?.quit()
		val room = LobbyRoom(lobby.room, session.host, players, session.id) { r ->
			if (lobby.active === r) {
				lobby.active = null
				lobby.room.send("lobby.quit", sid(session.id))
			}
		}
		room.pauseSender = { on -> lobby.room.send("lobby.pause", sid(session.id).apply { addProperty("on", on) }) }
		lobby.active = room
		mc.gui.setScreen(null)
		game.open(room)
	}

	/** Someone resumed your paused game: bring its screen back (or post a link if you are busy). */
	private fun offerResume(room: LobbyRoom, by: String) {
		val current = mc.gui.screen()
		if ((current == null || current is HubScreen) && !CombatGuard.blocked && room.screen != null) {
			mc.gui.setScreen(room.screen)
			return
		}
		val link = Component.literal("§a§l[Back to game]").withStyle { st ->
			st.withClickEvent(ClickEvent.RunCommand("/sp games resume"))
				.withHoverEvent(HoverEvent.ShowText(Component.literal("Reopen your game")))
		}
		mc.player?.sendSystemMessage(Component.literal("${Chat.PREFIX}§f$by §7resumed your game. ").append(link))
	}

	private fun versionJson() = JsonObject().apply {
		addProperty("pv", Compat.PROTOCOL)
		addProperty("v", Compat.VERSION)
	}

	private fun readVersion(lobby: Lobby, from: UUID, p: JsonObject) {
		p["pv"]?.asInt?.let { lobby.protocols[from] = it }
		p["v"]?.asString?.take(24)?.let { lobby.versions[from] = it }
	}

	/** Ready players of [session] who can't play with you (different room protocol). */
	fun incompatibleReady(lobby: Lobby, session: Session): List<String> =
		session.joined.filter { (id, _) -> id in session.ready && !lobby.compatible(id) }.values.toList()

	/** "You need to update" or "they need to update", whichever side is older. */
	fun versionHint(lobby: Lobby, id: UUID, name: String): String {
		val theirs = lobby.versionOf(id)
		return if (Compat.isNewer(theirs, Compat.VERSION)) "§cyou need to update Skyparty ($name has $theirs, you ${Compat.VERSION})."
		else "§c$name needs to update Skyparty (they have $theirs, you ${Compat.VERSION})."
	}

	private fun offerInvite(lobby: Lobby, session: Session, game: Minigame) {
		if (!Minigames.enabled || !Minigames.invites.value || CombatGuard.blocked) return
		invite = Invite(lobby, session.id, game.id, session.hostName, System.currentTimeMillis())
		val accept = Component.literal("§a§l[Join]").withStyle { st ->
			st.withClickEvent(ClickEvent.RunCommand("/sp games accept"))
				.withHoverEvent(HoverEvent.ShowText(Component.literal("Join ${game.name}")))
		}
		val text = Component.literal("${Chat.PREFIX}§f${session.hostName} §7opened §b${game.name}§7. ")
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
			root.then(ClientCommands.literal("resume").executes {
				later { if (!backToGame()) Chat.info("§7No paused game.") }
				1
			})
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

	/** Tests only: feed a lobby message as if [name] had sent it. */
	fun debugMessage(type: String, payload: JsonObject, from: UUID, name: String) {
		lobby?.let { handle(it, Net.Message(type, payload, from, name)) }
	}

	const val INVITE_TIMEOUT_MS = 60_000L
}
