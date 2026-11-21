package dev.skyparty.minigames

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.skyparty.net.Net
import dev.skyparty.util.Chat
import java.util.UUID

/**
 * Party tournament: the host of a game room picks "Tournament" and the room plays [ROUNDS] random games in a
 * row. Each game reports one score per player at its end ([matchScore]); the host ranks them and hands out
 * points (best of n players gets n, then n-1, …; no result = 0). Most points after the last game wins.
 *
 * Protocol (lobby room, echoed to everyone):
 *  - "tour.new"   {sid, games:[ids]}               host started a tournament in room sid
 *  - "tour.go"    {sid, round, players:[ids]}      round starts (host then sends lobby.game + lobby.start)
 *  - "tour.score" {sid, round, v, lower}           sender's result of this round
 *  - "tour.round" {sid, round, pts:{uuid: n}}      host: points of the round (also when it was ended early)
 *  - "tour.stop"  {sid}                            host cancelled it
 */
object Tournament {
	const val ROUNDS = 3
	/** Games that report a score for everyone at the end of a match. */
	val POOL = listOf("parkour", "reaction", "bluff", "quiz", "typing", "trader", "fishing")

	class Tour(val sid: String, val games: List<String>) {
		var round = 0
		var open = false
		/** The round's game was seen running (before that, nobody is "playing" yet). */
		var started = false
		var closing = false
		val players = linkedSetOf<UUID>()
		val scores = HashMap<UUID, Double>()
		var lower = false
		val points = HashMap<UUID, Int>()
		val names = HashMap<UUID, String>()
		/** Points per round, for the summary. */
		val history = ArrayList<Map<UUID, Int>>()
		val finished get() = round >= games.size
		val gameId get() = games.getOrNull(round)

		fun standings(): List<Pair<UUID, Int>> = (names.keys + points.keys).distinct()
			.map { it to (points[it] ?: 0) }.sortedByDescending { it.second }
	}

	private val tours = HashMap<String, Tour>()

	fun of(lobby: MinigameHub.Lobby, session: MinigameHub.Session?): Tour? {
		session ?: return null
		val t = tours[session.id] ?: return null
		if (lobby.sessions[session.id] == null) { tours.remove(session.id); return null }
		return t
	}

	/** Host: start a tournament with random games that fit the room. */
	fun create(lobby: MinigameHub.Lobby, s: MinigameHub.Session) {
		if (!s.isHost || s.inGame) return
		val fits = POOL.mapNotNull { MinigameRegistry.byId(it) }.filter { s.joined.size in it.minPlayers..it.maxPlayers }
		if (fits.size < ROUNDS) return
		val games = fits.shuffled().take(ROUNDS).map { it.id }
		lobby.room.send("tour.new", JsonObject().apply {
			addProperty("sid", s.id)
			add("games", JsonArray().apply { games.forEach { add(it) } })
		})
	}

	/** Host: play the next game with everyone in the room. */
	fun startRound(lobby: MinigameHub.Lobby, s: MinigameHub.Session) {
		val t = of(lobby, s) ?: return
		if (!s.isHost || s.inGame || t.open || t.finished) return
		val game = MinigameRegistry.byId(t.gameId ?: return) ?: return
		val players = s.joined.entries.take(game.maxPlayers).associate { it.key to it.value }
		lobby.room.send("tour.go", JsonObject().apply {
			addProperty("sid", s.id); addProperty("round", t.round)
			add("players", JsonArray().apply { players.keys.forEach { add(it.toString()) } })
		})
		if (s.gameId != game.id) lobby.room.send("lobby.game", JsonObject().apply { addProperty("sid", s.id); addProperty("game", game.id) })
		MinigameHub.startWith(lobby, s, players)
	}

	/** Host: end the round now (someone is still running / never finishes). */
	fun endRound(lobby: MinigameHub.Lobby, s: MinigameHub.Session) {
		val t = of(lobby, s) ?: return
		if (s.isHost && t.open) finishRound(lobby, t)
	}

	fun stop(lobby: MinigameHub.Lobby, s: MinigameHub.Session) {
		if (s.isHost) lobby.room.send("tour.stop", JsonObject().apply { addProperty("sid", s.id) })
	}

	/** From the games: your score of the match that just ended. */
	fun matchScore(gameId: String, value: Double, lowerIsBetter: Boolean) {
		val lobby = MinigameHub.lobby ?: return
		val active = lobby.active ?: return
		val t = tours[active.session] ?: return
		if (!t.open || t.gameId != gameId) return
		lobby.room.send("tour.score", JsonObject().apply {
			addProperty("sid", t.sid); addProperty("round", t.round)
			addProperty("v", value); addProperty("lower", lowerIsBetter)
		})
	}

	fun handle(lobby: MinigameHub.Lobby, s: MinigameHub.Session?, msg: Net.Message) {
		val p = msg.payload
		val sid = p["sid"]?.asString ?: return
		if (s == null || msg.from != s.host && msg.type != "tour.score") return
		when (msg.type) {
			"tour.new" -> {
				val games = p["games"]?.asJsonArray?.mapNotNull { it.asString.takeIf { id -> MinigameRegistry.byId(id) != null } } ?: return
				tours[sid] = Tour(sid, games).also { t -> s.joined.forEach { (id, n) -> t.names[id] = n } }
				if (s.selfIn) Chat.info("§6♛ Tournament! §7${games.size} games: §f" + games.joinToString("§7, §f") { MinigameRegistry.byId(it)!!.name })
			}
			"tour.go" -> {
				val t = tours[sid] ?: return
				if (p["round"]?.asInt != t.round) return
				t.open = true
				t.started = false
				t.closing = false
				t.scores.clear()
				t.players.clear()
				p["players"]?.asJsonArray?.forEach { runCatching { t.players += UUID.fromString(it.asString) } }
				s.joined.forEach { (id, n) -> t.names[id] = n }
			}
			"tour.score" -> {
				val t = tours[sid] ?: return
				if (!t.open || p["round"]?.asInt != t.round || msg.from !in t.players) return
				t.scores[msg.from] = p["v"]?.asDouble ?: return
				t.lower = p["lower"]?.asBoolean == true
				if (s.isHost && t.scores.keys.containsAll(t.players.filter { it in s.playing || it in t.scores })) finishRound(lobby, t)
			}
			"tour.round" -> {
				val t = tours[sid] ?: return
				if (p["round"]?.asInt != t.round) return
				val pts = HashMap<UUID, Int>()
				p["pts"]?.asJsonObject?.entrySet()?.forEach { (k, v) -> runCatching { pts[UUID.fromString(k)] = v.asInt } }
				pts.forEach { (id, n) -> t.points[id] = (t.points[id] ?: 0) + n }
				t.history += pts
				val name = MinigameRegistry.byId(t.gameId ?: "")?.name ?: "Round"
				t.open = false
				t.round++
				if (s.selfIn) announce(t, name, pts)
			}
			"tour.stop" -> if (tours.remove(sid) != null && s.selfIn) Chat.info("§7Tournament cancelled.")
		}
	}

	/** Every tick, for rooms you host: the round ends when everyone has a result or has left the game. */
	fun tick(lobby: MinigameHub.Lobby) {
		for (t in tours.values) {
			val s = lobby.sessions[t.sid] ?: continue
			if (!s.isHost || !t.open) continue
			if (s.inGame) t.started = true
			if (t.started && t.players.all { it in t.scores || it !in s.playing }) finishRound(lobby, t)
		}
	}

	/** Host: everyone with a result is ranked; ties share the place. */
	private fun finishRound(lobby: MinigameHub.Lobby, t: Tour) {
		if (t.closing) return
		t.closing = true
		val n = t.players.size
		val ranked = t.scores.entries.sortedWith(compareBy { if (t.lower) it.value else -it.value })
		val pts = JsonObject()
		var place = 0
		for ((i, e) in ranked.withIndex()) {
			if (i > 0 && e.value != ranked[i - 1].value) place = i
			pts.addProperty(e.key.toString(), n - place)
		}
		for (id in t.players) if (!pts.has(id.toString())) pts.addProperty(id.toString(), 0)
		lobby.room.send("tour.round", JsonObject().apply {
			addProperty("sid", t.sid); addProperty("round", t.round); add("pts", pts)
		})
	}

	private fun announce(t: Tour, game: String, pts: Map<UUID, Int>) {
		val line = pts.entries.sortedByDescending { it.value }.joinToString("§7, ") { "§f${t.names[it.key] ?: "?"} §a+${it.value}" }
		Chat.info("§6♛ $game §8(${t.round}/${t.games.size})§7: $line")
		if (!t.finished) return
		val st = t.standings()
		val top = st.firstOrNull()?.second ?: 0
		val winners = st.filter { it.second == top }.map { t.names[it.first] ?: "?" }
		Chat.info("§6§l♛ ${winners.joinToString(" & ")} ${if (winners.size == 1) "wins" else "win"} the tournament! §7(" +
			st.joinToString("§7, ") { "§f${t.names[it.first] ?: "?"} §e${it.second}" } + "§7)")
		if (st.any { it.first == Net.selfId && it.second == top }) MinigameStore.addTokens(15)
	}

	/** Tests only. */
	fun debugTour(sid: String) = tours[sid]
}
