package dev.skyparty.minigames.price

import com.google.gson.JsonObject
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * Price Guess: Higher or Lower with real Bazaar prices. Everyone in a room gets the same item sequence
 * (seed from the host's "price.start.rng" roll) and plays until their first wrong guess; scores are shared.
 */
object PriceGuess : Minigame {
	override val id = "price"
	override val name = "Price Guess"
	override val description = "Higher or lower? Guess Bazaar prices and build the longest streak."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = false
	override val length = dev.skyparty.minigames.GameLength.SHORT

	override fun open(room: Net.Room?) = MinigameHub.openScreen(PriceScreen(LobbyRoom.of(room, id)))
}

class PriceScreen(val room: LobbyRoom) : GameScreen("Price Guess", room) {
	enum class Phase { WAITING, GUESS, REVEAL, OVER }

	override fun inProgress() = phase != Phase.OVER

	private var phase = Phase.WAITING
	private var seed = 0L
	private var rng = Random(0)
	private var left: Bazaar.Product? = null
	private var right: Bazaar.Product? = null
	private var streak = 0
	private var lastCorrect = false
	private var revealAt = 0L
	private var newBest = false
	private var reward = 0
	private val scores = linkedMapOf<UUID, Int>()

	private val revealMs = 900L

	init {
		Bazaar.ensureLoaded()
		room.onMessage = ::onMessage
		if (room.isHost) requestStart()
	}

	// ------------------------------------------------------------------ protocol

	private fun requestStart() = room.send("price.start.rng", JsonObject())

	private fun onMessage(msg: Net.Message) {
		when (msg.type) {
			"price.start.rng" -> {
				if (msg.from != room.host) return
				seed = ((msg.payload["roll"]?.asDouble ?: Math.random()) * Long.MAX_VALUE).toLong()
				scores.clear()
				startRun()
			}
			"price.score" -> {
				scores[msg.from] = msg.payload["streak"]?.asInt ?: 0
			}
		}
	}

	private fun startRun() {
		rng = Random(seed)
		streak = 0
		newBest = false
		reward = 0
		left = null
		right = null
		phase = Phase.WAITING
		tryDeal()
	}

	/** Deals the first pair as soon as Bazaar data is available. */
	private fun tryDeal() {
		val products = sortedProducts() ?: return
		if (left == null) left = products[rng.nextInt(products.size)]
		right = nextDifferent(products, left!!)
		phase = Phase.GUESS
	}

	private fun sortedProducts(): List<Bazaar.Product>? =
		Bazaar.products?.filter { it.price > 0 }?.sortedBy { it.id }?.takeIf { it.size >= 4 }

	/** A product whose price differs from [from] by at least 10% so there are no coin-flip ties. */
	private fun nextDifferent(products: List<Bazaar.Product>, from: Bazaar.Product): Bazaar.Product {
		repeat(60) {
			val p = products[rng.nextInt(products.size)]
			if (p.id != from.id && abs(p.price - from.price) / maxOf(p.price, from.price) >= 0.10) return p
		}
		return products.first { it.id != from.id }
	}

	private fun guess(higher: Boolean) {
		val l = left ?: return
		val r = right ?: return
		if (phase != Phase.GUESS) return
		lastCorrect = (r.price > l.price) == higher
		revealAt = room.now()
		phase = Phase.REVEAL
		val sound = if (lastCorrect) SoundEvents.EXPERIENCE_ORB_PICKUP else SoundEvents.VILLAGER_NO
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, if (lastCorrect) 1.4f else 1f))
	}

	private fun afterReveal() {
		if (lastCorrect) {
			streak++
			left = right
			right = nextDifferent(sortedProducts() ?: return, left!!)
			phase = Phase.GUESS
		} else {
			phase = Phase.OVER
			reward = streak * 2
			if (reward > 0) MinigameStore.addTokens(reward)
			newBest = streak > 0 && MinigameStore.submit(PriceGuess.id, streak.toDouble(), false, "$streak in a row")
			room.send("price.score", JsonObject().apply { addProperty("streak", streak) })
		}
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = header(g, "Price Guess", if (room.solo) "solo" else "${room.members.size} players")
		val cx = width / 2

		if (phase == Phase.WAITING) tryDeal()
		if (phase == Phase.REVEAL && room.now() - revealAt > revealMs + 500) afterReveal()

		val products = sortedProducts()
		if (products == null) {
			drawLoading(g, cx, top, mouseX, mouseY)
			return
		}

		centered(g, "§fIs the right item §a§lHIGHER §for §c§lLOWER§f?", cx, top + 10, Theme.TEXT)
		val streakColor = Theme.rainbow(0f, 0.55f)
		bigText(g, "Streak $streak", cx, top + 24, 1.5f, streakColor)

		val cardW = 130
		val cardH = 110
		val y = top + 46
		val l = left
		val r = right
		if (l != null) drawCard(g, l, cx - cardW - 12, y, cardW, cardH, showPrice = true, highlight = 0)
		if (r != null) {
			val revealing = phase == Phase.REVEAL || phase == Phase.OVER
			val hl = if (!revealing) 0 else if (lastCorrect) 1 else -1
			drawCard(g, r, cx + 12, y, cardW, cardH, showPrice = revealing, highlight = hl)
		}
		bigText(g, "VS", cx, y + cardH / 2 - 6, 1.5f, GameColors.GOLD)

		val by = y + cardH + 12
		when (phase) {
			Phase.GUESS -> {
				button(g, cx - 104, by, 96, 20, "▲ HIGHER", mouseX, mouseY, color = GameColors.GREEN) { guess(true) }
				button(g, cx + 8, by, 96, 20, "▼ LOWER", mouseX, mouseY, color = GameColors.RED) { guess(false) }
			}
			Phase.REVEAL -> centered(g, if (lastCorrect) "§a§lCorrect! ✦" else "§c§lNope!", cx, by + 6, Theme.TEXT)
			Phase.OVER -> drawGameOver(g, cx, by, mouseX, mouseY)
			Phase.WAITING -> {}
		}

		if (!room.solo) drawScores(g, mouseX, mouseY)
		if (Bazaar.usingSample) g.text(font, "§8sample prices (offline dev mode)", 6, height - 12, Theme.TEXT_DIM, false)
	}

	private fun drawLoading(g: GuiGraphicsExtractor, cx: Int, top: Int, mx: Int, my: Int) {
		val err = Bazaar.error
		if (err == null) {
			val dots = ".".repeat(((room.now() / 300) % 4).toInt())
			centered(g, "Fetching Bazaar prices$dots", cx, top + 60, Theme.TEXT)
		} else {
			centered(g, "§c$err", cx, top + 50, Theme.TEXT)
			button(g, cx - 40, top + 70, 80, 18, "Retry", mx, my) { Bazaar.ensureLoaded(force = true) }
		}
	}

	private fun drawCard(g: GuiGraphicsExtractor, p: Bazaar.Product, x: Int, y: Int, w: Int, h: Int, showPrice: Boolean, highlight: Int) {
		panel(g, x, y, w, h)
		val border = when (highlight) {
			1 -> GameColors.GREEN
			-1 -> GameColors.RED
			else -> Theme.rainbow(x / 300f, 0.5f)
		}
		g.outline(x, y, w, h, border)

		// Item icon (3x) or a colored placeholder
		val icon = Bazaar.icon(p.id)
		val iconX = x + w / 2 - 24
		val iconY = y + 10
		if (!icon.isEmpty) {
			val pose = g.pose()
			pose.pushMatrix()
			pose.translate(iconX.toFloat(), iconY.toFloat())
			pose.scale(3f, 3f)
			g.item(icon, 0, 0)
			pose.popMatrix()
		} else {
			val hue = (p.id.hashCode() and 0xFFFF) / 65535f
			g.fill(iconX + 4, iconY + 4, iconX + 44, iconY + 44, dev.skyparty.util.ColorUtil.hsbToArgb(hue, 0.55f, 0.85f))
			centered(g, p.name.take(1), iconX + 24, iconY + 20, Theme.TEXT)
		}

		val name = fit(p.name, w - 8)
		centered(g, name, x + w / 2, y + 66, Theme.TEXT)
		val priceText = if (showPrice) {
			val shown = if (highlight != 0) {
				val t = ((room.now() - revealAt).toDouble() / revealMs).coerceIn(0.0, 1.0)
				p.price * (1 - (1 - t) * (1 - t))
			} else p.price
			"§6${formatCoins(shown)} coins"
		} else "§7??? coins"
		centered(g, priceText, x + w / 2, y + 84, Theme.TEXT)
	}

	private fun drawGameOver(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		val best = MinigameStore.best(PriceGuess.id)
		if (!room.solo) {
			// Everyone plays until their first miss; the longest streak wins once all are out.
			val waiting = room.members.count { it !in scores }
			val top = scores.values.maxOrNull() ?: 0
			val leaders = scores.filterValues { it == top }.keys.map { if (it == Net.selfId) "You" else room.nameOf(it) }
			val line = if (waiting > 0) "§7Waiting for $waiting player${if (waiting == 1) "" else "s"}… §8(leader: ${leaders.joinToString()} · $top)"
			else "§6§l♛ ${leaders.joinToString(" & ")} ${if (leaders.size == 1 && leaders[0] != "You") "wins" else "win"} with $top!"
			centered(g, line, cx, y - 12, Theme.TEXT)
		}
		centered(g, if (newBest) "§e§l✦ New personal best! ✦" else "§7Best: §f${best?.display ?: "-"}", cx, y, Theme.TEXT)
		if (reward > 0) centered(g, "§6+$reward Tokens", cx, y + 11, Theme.TEXT)
		if (room.solo || room.isHost) {
			button(g, cx - 104, y + 24, 96, 18, "Play again", mx, my) { if (room.solo) { seed = Random.nextLong(); startRun() } else requestStart() }
		} else {
			centered(g, "§7Waiting for the host to start again…", cx, y + 30, Theme.TEXT)
		}
		button(g, cx + 8, y + 24, 96, 18, "Leave", mx, my, color = GameColors.RED) { room.quit(); onClose() }
	}

	private fun drawScores(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val x = width - 110
		if (x < width / 2 + 12 + 130 + 6) {
			// No room beside the cards: one line along the bottom instead.
			val line = room.names.entries.sortedByDescending { scores[it.key] ?: -1 }
				.joinToString("  ") { (id, name) -> "§f$name §6${scores[id]?.toString() ?: "§8…"}" }
			centered(g, fit("§7Scores: $line", width - 16), width / 2, height - 12, Theme.TEXT)
			return
		}
		val y = 30
		panel(g, x, y, 102, 16 + room.members.size * 11, "Scores")
		room.names.entries.sortedByDescending { scores[it.key] ?: -1 }.forEachIndexed { i, (id, name) ->
			val s = scores[id]?.toString() ?: "§8playing"
			g.text(font, font.plainSubstrByWidth(name, 60), x + 6, y + 19 + i * 11, Theme.TEXT, false)
			g.text(font, s, x + 96 - font.width(s), y + 19 + i * 11, GameColors.GOLD, false)
		}
	}

	override fun removed() {
		room.leave()
		super.removed()
	}

	companion object {
		fun formatCoins(v: Double): String = when {
			v >= 1e9 -> "%.2fB".format(v / 1e9)
			v >= 1e6 -> "%.2fM".format(v / 1e6)
			v >= 1e3 -> "%.1fk".format(v / 1e3)
			else -> "%.1f".format(v)
		}.replace(',', '.')
	}
}
