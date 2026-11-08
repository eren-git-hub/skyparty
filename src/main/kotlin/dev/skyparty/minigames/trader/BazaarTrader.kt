package dev.skyparty.minigames.trader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.skyparty.minigames.GameLength
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.common.RoundGameScreen
import dev.skyparty.minigames.price.Bazaar
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.net.Net
import dev.skyparty.reminders.PriceAlerts
import dev.skyparty.ui.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.sounds.SoundEvents
import java.util.UUID
import kotlin.math.exp
import kotlin.math.floor
import kotlin.random.Random

/**
 * Bazaar Trader: everyone starts with the same coins and trades five real Bazaar items over seven days.
 * The prices move the same way for everyone (one shared seed), rumors hint at tomorrow's moves (usually right).
 * Highest net worth after the last day wins. Trades are private; only each day's net worth is shared.
 *
 * Protocol (echoed to the sender):
 *  - "trade.start" {seed, items:[{id,name,price}]}  (host) a new match with these items and start prices
 *  - "trade.day"   {d}                             (host) day d starts (d == DAYS: market closed, final prices)
 *  - "trade.ready" {d}                             a player is done trading for day d (all ready = next day early)
 *  - "trade.worth" {d, w}                          a player's net worth at the end of day d
 */
object BazaarTrader : Minigame {
	override val id = "trader"
	override val name = "Bazaar Trader"
	override val description = "Trade real Bazaar items over 7 days. Prices move for everyone the same. Richest wins."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = false
	override val length = GameLength.MEDIUM

	const val DAYS = 7
	const val DAY_MS = 25_000L
	const val ITEMS = 5
	const val START_COINS = 1_000_000L

	override fun open(room: Net.Room?) = MinigameHub.openScreen(TraderScreen(LobbyRoom.of(room, id)))

	class Item(val id: String, val name: String, val start: Double)

	/** A rumor shown during day d about the change from d to d+1. */
	class News(val item: Int, val up: Boolean, val text: String)

	/** Prices for days 0..DAYS and the rumors, all from [seed] so every client gets the same market. */
	fun simulate(seed: Long, items: List<Item>): Pair<Array<DoubleArray>, Array<News?>> {
		val rng = Random(seed)
		val prices = Array(items.size) { DoubleArray(DAYS + 1) }
		val news = arrayOfNulls<News>(DAYS)
		for (i in items.indices) prices[i][0] = items[i].start
		for (d in 0 until DAYS) {
			val rumor = if (rng.nextInt(100) < 70) {
				val i = rng.nextInt(items.size)
				val up = rng.nextBoolean()
				News(i, up, rumorText(rng, items[i].name, up))
			} else null
			news[d] = rumor
			for (i in items.indices) {
				// A normal day: up or down by up to ~25 %.
				var drift = (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * 0.3
				if (rumor != null && rumor.item == i) {
					// Rumors are right 3 out of 4 times, and then the move is big.
					val right = rng.nextInt(100) < 75
					val dir = if (rumor.up == right) 1 else -1
					drift = dir * (0.18 + rng.nextDouble() * 0.3)
				}
				prices[i][d + 1] = (prices[i][d] * exp(drift)).coerceAtLeast(0.1)
			}
		}
		return prices to news
	}

	private fun rumorText(rng: Random, item: String, up: Boolean): String {
		val ups = listOf(
			"Rumor: a new mayor perk will need lots of %s.", "Big flippers are buying up %s.",
			"A patch might make %s useful for a new craft.", "Everyone wants %s for the next event.",
		)
		val downs = listOf(
			"Rumor: a dupe of %s is going around.", "Someone is about to dump a stack of %s.",
			"The next patch may nerf the recipe using %s.", "Minions now make %s twice as fast?",
		)
		return (if (up) ups else downs).random(rng).format(item)
	}
}

class TraderScreen(room: LobbyRoom) : RoundGameScreen("Bazaar Trader", room, listOf("Flipper Phil", "Stonks Steve", "Panic Seller")) {
	enum class Phase { WAITING, TRADING, CLOSED }

	override fun inProgress() = phase != Phase.CLOSED

	private var phase = Phase.WAITING
	private var phaseStart = 0L
	private var day = -1
	private var items = listOf<BazaarTrader.Item>()
	private var prices = arrayOf<DoubleArray>()
	private var news = arrayOf<BazaarTrader.News?>()
	private var coins = BazaarTrader.START_COINS
	private var owned = LongArray(BazaarTrader.ITEMS)
	private val ready = mutableSetOf<UUID>()
	/** Solo bots trade with their own coins and stacks. */
	private val botCoins = HashMap<UUID, Long>()
	private val botOwned = HashMap<UUID, LongArray>()
	private var sentWorth = -1

	init {
		Bazaar.ensureLoaded()
		room.onMessage = ::onMessage
	}

	private fun price(i: Int) = prices[i][day.coerceIn(0, BazaarTrader.DAYS)]
	private fun worthOf(c: Long, own: LongArray) = c + own.indices.sumOf { (own[it] * price(it)).toLong() }
	private val myWorth get() = worthOf(coins, owned)

	// ------------------------------------------------------------------ protocol

	private fun startMatch() {
		val list = Bazaar.products ?: return
		// Items between 50 coins and 300k, so you can buy a sensible amount of each.
		val pool = list.filter { it.price in 50.0..300_000.0 && !Bazaar.icon(it.id).isEmpty }.shuffled()
		if (pool.size < BazaarTrader.ITEMS) return
		val picks = pool.take(BazaarTrader.ITEMS)
		val seed = Random.nextLong()
		if (room.solo) begin(seed, picks.map { BazaarTrader.Item(it.id, it.name, it.price) })
		else room.send("trade.start", JsonObject().apply {
			addProperty("seed", seed)
			add("items", JsonArray().apply { picks.forEach { p -> add(JsonObject().apply { addProperty("id", p.id); addProperty("name", p.name); addProperty("price", p.price) }) } })
		})
	}

	private fun onMessage(msg: Net.Message) {
		val p = msg.payload
		when (msg.type) {
			"trade.start" -> if (msg.from == room.host) {
				val list = p["items"]?.asJsonArray?.map { el -> el.asJsonObject.let { BazaarTrader.Item(it["id"].asString, it["name"].asString, it["price"].asDouble) } } ?: return
				if (list.size != BazaarTrader.ITEMS) return
				begin(p["seed"]?.asLong ?: return, list)
			}
			"trade.day" -> if (msg.from == room.host) startDay(p["d"]?.asInt ?: return)
			"trade.ready" -> if (p["d"]?.asInt == day && msg.from in names) { ready += msg.from; checkAllReady() }
			"trade.worth" -> if (msg.from in names) p["w"]?.asLong?.let { scores[msg.from] = it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
		}
	}

	private fun begin(seed: Long, list: List<BazaarTrader.Item>) {
		resetMatch()
		items = list
		val (p, n) = BazaarTrader.simulate(seed, list)
		prices = p
		news = n
		coins = BazaarTrader.START_COINS
		owned = LongArray(BazaarTrader.ITEMS)
		botCoins.clear(); botOwned.clear()
		for (b in bots) { botCoins[b] = BazaarTrader.START_COINS; botOwned[b] = LongArray(BazaarTrader.ITEMS) }
		sentWorth = -1
		names.keys.forEach { scores[it] = (BazaarTrader.START_COINS).toInt() }
		// A new match (also after "Play again" on a client that still shows the closed market).
		day = -1
		phase = Phase.WAITING
		startDay(0)
	}

	private fun startDay(d: Int) {
		if (d < 0 || d > BazaarTrader.DAYS || d <= day && phase != Phase.WAITING) return
		day = d
		ready.clear()
		if (d == BazaarTrader.DAYS) {
			close()
			return
		}
		phase = Phase.TRADING
		phaseStart = now()
		botsTrade()
		play(SoundEvents.NOTE_BLOCK_CHIME.value())
	}

	/** Bots: sell what they have, then put most of their coins into one item (following the rumor 60 % of the time). */
	private fun botsTrade() {
		val rumor = news.getOrNull(day)
		for (b in bots) {
			val own = botOwned.getValue(b)
			var c = botCoins.getValue(b)
			for (i in own.indices) { c += (own[i] * price(i)).toLong(); own[i] = 0 }
			val pick = if (rumor != null && rumor.up && Random.nextInt(100) < 60) rumor.item else Random.nextInt(items.size)
			val qty = floor(c * 0.8 / price(pick)).toLong()
			own[pick] = qty
			c -= (qty * price(pick)).toLong()
			botCoins[b] = c
		}
	}

	private fun close() {
		phase = Phase.CLOSED
		phaseStart = now()
		// Final worth at the closing prices, for everyone (bots locally, players from their own message).
		for (b in bots) scores[b] = worthOf(botCoins.getValue(b), botOwned.getValue(b)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		shareWorth()
		val won = rewardMatch(BazaarTrader.id) { PriceAlerts.formatCoins(it.toDouble()) + " coins" }
		play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.VILLAGER_NO)
	}

	/** Tell everyone your net worth at today's prices (once per day). */
	private fun shareWorth() {
		if (sentWorth == day) return
		sentWorth = day
		val w = myWorth
		if (room.solo) scores[me] = w.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		else room.send("trade.worth", JsonObject().apply { addProperty("d", day); addProperty("w", w) })
	}

	private fun markReady() {
		if (phase != Phase.TRADING || me in ready) return
		if (room.solo) { ready += me; checkAllReady() }
		else room.send("trade.ready", JsonObject().apply { addProperty("d", day) })
	}

	private fun checkAllReady() {
		if (hostLike && phase == Phase.TRADING && names.keys.filter { it !in bots }.all { it in ready }) nextDay()
	}

	private fun nextDay() {
		val d = day + 1
		if (room.solo) startDay(d) else once("m$match d$d") { room.send("trade.day", JsonObject().apply { addProperty("d", d) }) }
	}

	// ------------------------------------------------------------------ trading (local)

	private fun buy(i: Int, budget: Long) {
		if (phase != Phase.TRADING) return
		val qty = floor(minOf(budget, coins) / price(i)).toLong()
		if (qty <= 0) return
		owned[i] += qty
		coins -= (qty * price(i)).toLong()
		play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6f)
	}

	private fun sell(i: Int) {
		if (phase != Phase.TRADING || owned[i] <= 0) return
		coins += (owned[i] * price(i)).toLong()
		owned[i] = 0
		play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.2f)
	}

	private fun update() {
		val t = now() - phaseStart
		when (phase) {
			Phase.WAITING -> if (hostLike && day < 0 && Bazaar.products != null) once("m$match start") { startMatch() }
			Phase.TRADING -> {
				// Worth is shared a moment before the day ends so the scoreboard is up to date.
				if (t >= BazaarTrader.DAY_MS - 1_500) shareWorth()
				if (hostLike && t >= BazaarTrader.DAY_MS) nextDay()
			}
			Phase.CLOSED -> {}
		}
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val top = header(g, "Bazaar Trader", if (room.solo) "vs 3 bots" else "${names.size} players")
		val cx = width / 2
		if (phase == Phase.WAITING) {
			centered(g, if (Bazaar.products == null) "§7Loading Bazaar prices…" else "§7Waiting for the host…", cx, height / 2, Theme.TEXT)
			return
		}
		val w = minOf(360, width - 130)
		val x0 = cx - w / 2
		drawScores(g, x0 + w) { PriceAlerts.formatCoins(it.toDouble()) }
		var y = top + 6
		val dayLabel = if (phase == Phase.CLOSED) "§6§lMarket closed" else "§fDay §l${day + 1}§r§7/${BazaarTrader.DAYS}"
		g.text(font, dayLabel, x0, y, Theme.TEXT, true)
		val worth = "§7Net worth §6§l${PriceAlerts.formatCoins(myWorth.toDouble())}"
		g.text(font, worth, x0 + w - font.width(worth), y, Theme.TEXT, true)
		y += 12
		if (phase == Phase.TRADING) {
			timer(g, x0 + w / 2, y, 1f - (now() - phaseStart).toFloat() / BazaarTrader.DAY_MS, w)
			y += 7
			val rumor = news.getOrNull(day)
			g.fill(x0, y, x0 + w, y + 14, 0xC0201830.toInt())
			g.text(font, fit("§e✉ §f" + (rumor?.text ?: "§7No rumors today."), w - 8), x0 + 4, y + 3, Theme.TEXT, false)
			y += 18
		}

		// One row per item: icon, name, price + change, a tiny chart, what you own, buy / sell.
		// Rows shrink on small screens so the bottom line (coins, next day, scores) still fits.
		val reserve = if (phase == Phase.CLOSED) 72 else 48 // the game-over block needs more room
		val rowH = ((height - y - reserve) / items.size - 3).coerceIn(18, 26)
		val bx = x0 + w - 116
		val chartW = 44
		val chartX = bx - chartW - 6
		for ((i, item) in items.withIndex()) {
			val ry = y + i * (rowH + 3)
			g.fill(x0, ry, x0 + w, ry + rowH, GameColors.CARD)
			val icon = Bazaar.icon(item.id)
			if (!icon.isEmpty) g.item(icon, x0 + 4, ry + (rowH - 16) / 2)
			val textW = chartX - x0 - 28
			val l1 = ry + rowH / 2 - 9
			val l2 = ry + rowH / 2 + 1
			g.text(font, fit("§f" + item.name, textW), x0 + 24, l1, Theme.TEXT, false)
			val p = price(i)
			val prev = if (day > 0) prices[i][day - 1] else p
			val pct = (p / prev - 1) * 100
			val change = if (day == 0) "§8–" else (if (pct >= 0) "§a▲" else "§c▼") + "%.0f%%".format(kotlin.math.abs(pct))
			val own = if (owned[i] > 0) "  §bx${owned[i]}" else ""
			g.text(font, fit("§6${PriceAlerts.formatCoins(p)} $change$own", textW), x0 + 24, l2, Theme.TEXT, false)
			drawChart(g, i, chartX, ry + 3, chartW, rowH - 6)
			if (phase == Phase.TRADING) {
				val by = ry + (rowH - 16) / 2
				button(g, bx, by, 36, 16, "+10%", mouseX, mouseY, enabled = coins >= p, color = GameColors.GREEN,
					hint = "Buy for 10 % of your start coins") { buy(i, BazaarTrader.START_COINS / 10) }
				button(g, bx + 38, by, 36, 16, "Max", mouseX, mouseY, enabled = coins >= p, color = GameColors.GREEN,
					hint = "Buy as many as you can") { buy(i, coins) }
				button(g, bx + 76, by, 38, 16, "Sell", mouseX, mouseY, enabled = owned[i] > 0, color = GameColors.RED,
					hint = "Sell all") { sell(i) }
			}
		}
		y += items.size * (rowH + 3) + 4
		g.text(font, "§7Coins: §6${PriceAlerts.formatCoins(coins.toDouble())}", x0, y + 4, Theme.TEXT, false)
		when (phase) {
			Phase.TRADING -> {
				val waiting = names.filterKeys { it !in bots && it !in ready }.size
				val label = if (me in ready) "Waiting ($waiting)…" else "Next day ▶"
				button(g, x0 + w - 90, y, 90, 16, label, mouseX, mouseY, enabled = me !in ready, hint = "Skip ahead when everyone is done") { markReady() }
			}
			Phase.CLOSED -> drawOver(g, cx, y + 22, mouseX, mouseY, { PriceAlerts.formatCoins(it.toDouble()) + " coins" }) { day = -1; phase = Phase.WAITING }
			Phase.WAITING -> {}
		}
	}

	/** Price line for item [i] up to today. */
	private fun drawChart(g: GuiGraphicsExtractor, i: Int, x: Int, y: Int, w: Int, h: Int) {
		val upto = day.coerceIn(0, BazaarTrader.DAYS)
		val series = prices[i].copyOfRange(0, upto + 1)
		val lo = series.min()
		val hi = series.max()
		g.fill(x, y, x + w, y + h, 0x40000000)
		if (series.size < 2 || hi <= lo) return
		val step = w / BazaarTrader.DAYS.toFloat()
		for (k in 1 until series.size) {
			val y0 = y + h - 1 - ((series[k - 1] - lo) / (hi - lo) * (h - 2)).toInt()
			val y1 = y + h - 1 - ((series[k] - lo) / (hi - lo) * (h - 2)).toInt()
			val x1 = x + (k * step).toInt()
			val x0 = x + ((k - 1) * step).toInt()
			val col = if (series[k] >= series[k - 1]) GameColors.GREEN else GameColors.RED
			g.fill(x0, minOf(y0, y1), x0 + 1, maxOf(y0, y1) + 1, col)
			g.fill(x0, y1, x1 + 1, y1 + 1, col)
		}
	}

	/** Test hooks. */
	val debugDay get() = day
	val debugPhase get() = phase
	fun debugBuyMax(i: Int) = buy(i, coins)
	fun debugNextDay() = markReady()
}
