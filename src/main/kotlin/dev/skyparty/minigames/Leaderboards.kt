package dev.skyparty.minigames

import com.google.gson.JsonObject
import dev.skyparty.net.BackendTransport
import org.slf4j.LoggerFactory

/**
 * Daily and weekly leaderboards on the Skyparty server. Every finished game that [MinigameStore.submit]s a
 * result reports it here once; the server keeps each player's best of the day and of the week (one tiny
 * request per game, nothing during play). Boards reset at 00:00 UTC (day) and Monday 00:00 UTC (week).
 */
object Leaderboards {
	private val log = LoggerFactory.getLogger("Skyparty/Leaderboards")

	/** Board id → shown name, in the order of the Ranks tab. The server has the same list. */
	val BOARDS = linkedMapOf(
		DailyChallenge.ID to "Daily Parkour",
		"dungeon" to "Dungeon Run",
		"reaction" to "Reaction Duel",
		"price" to "Price Guess",
		"quiz" to "SkyBlock Quiz",
		"typing" to "Item Typing",
		"trader" to "Bazaar Trader",
		"fishing" to "Fishing Derby",
		"petrace" to "Pet Race",
		"bluff" to "Bazaar Bluff",
	)

	class Entry(val name: String, val display: String, val me: Boolean)
	class Board(val top: List<Entry>, val myRank: Int?, val myDisplay: String?, val total: Int, val resetsAt: Long, val fetchedAt: Long)

	private val cache = HashMap<String, Board>()
	private val loading = HashSet<String>()
	private val failed = HashMap<String, Long>()

	/** Tests only: called instead of the server for every report. */
	var testSink: ((String, Double, String) -> Unit)? = null

	/** From [MinigameStore.submit]: maps the local game id to a board and sends the result. */
	fun report(gameId: String, value: Double, display: String) {
		val board = when {
			gameId == "parkour:${DailyChallenge.seed()}" -> DailyChallenge.ID
			gameId in BOARDS -> gameId
			else -> return
		}
		testSink?.let { it(board, value, display); return }
		val body = JsonObject().apply {
			addProperty("game", board)
			addProperty("value", value)
			addProperty("display", display)
		}
		BackendTransport.api({ BackendTransport.postScore(it, body) }) { res ->
			if (res == null) log.debug("Score for {} not sent (offline?)", board)
			// What we have shown is out of date now.
			cache.keys.removeIf { it.startsWith("$board|") }
		}
	}

	/** Cached board (refreshed after a minute); null while it loads or offline. Starts loading when needed. */
	fun get(game: String, daily: Boolean): Board? {
		val key = "$game|${if (daily) "day" else "week"}"
		val now = System.currentTimeMillis()
		val b = cache[key]
		if ((b == null || now - b.fetchedAt > 60_000 || now > b.resetsAt) && key !in loading && now - (failed[key] ?: 0L) > 15_000) load(key, game, daily)
		return b
	}

	fun isLoading(game: String, daily: Boolean) = "$game|${if (daily) "day" else "week"}" in loading

	private fun load(key: String, game: String, daily: Boolean) {
		loading += key
		BackendTransport.api({ BackendTransport.getScores(it, game, if (daily) "day" else "week") }) { res ->
			loading -= key
			if (res == null) { failed[key] = System.currentTimeMillis(); return@api }
			runCatching {
				val top = res.getAsJsonArray("top").map { val o = it.asJsonObject; Entry(o["name"].asString, o["display"].asString, o["me"]?.asBoolean == true) }
				val me = res["me"]?.takeIf { it.isJsonObject }?.asJsonObject
				val now = System.currentTimeMillis()
				cache[key] = Board(top, me?.get("rank")?.asInt, me?.get("display")?.asString, res["total"]?.asInt ?: top.size, now + (res["resetsIn"]?.asLong ?: 60_000L), now)
			}.onFailure { failed[key] = System.currentTimeMillis(); log.debug("Bad scores answer: {}", it.message) }
		}
	}

	/** Tests only: show this board instead of asking the server. */
	fun injectForTest(game: String, daily: Boolean, board: Board) { cache["$game|${if (daily) "day" else "week"}"] = board }
}
