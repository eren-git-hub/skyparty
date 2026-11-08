package dev.skyparty.minigames.bluff

import com.google.gson.JsonObject
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.price.Bazaar
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.net.Net
import dev.skyparty.reminders.PriceAlerts
import dev.skyparty.ui.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Bazaar Bluff: everyone secretly guesses the Bazaar price of the same item; the closest guesses score.
 * Five rounds. Solo: you and three bots.
 *
 * Protocol (echoed to the sender):
 *  - "bluff.round" {r, id, price}  (host) round r starts with this item; the host's price is the truth for everyone
 *  - "bluff.guess" {r, v}          a player's secret guess (hidden in the UI until the reveal)
 *  - "bluff.reveal" {r}            (host) time is up
 */
object BazaarBluff : Minigame {
	override val id = "bluff"
	override val name = "Bazaar Bluff"
	override val description = "Everyone secretly guesses a Bazaar price. Closest guess scores the most."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = false
	override val length = dev.skyparty.minigames.GameLength.SHORT

	const val ROUNDS = 5
	const val GUESS_MS = 25_000L
	const val REVEAL_MS = 6_500L

	/** Points by rank (closest first); within 5% of the price gives +1 on top. */
	val POINTS = listOf(3, 2, 1)

	override fun open(room: Net.Room?) = MinigameHub.openScreen(BluffScreen(LobbyRoom.of(room, id)))
}

class BluffScreen(val room: LobbyRoom) : GameScreen("Bazaar Bluff", room) {
	enum class Phase { WAITING, GUESS, REVEAL, OVER }

	override fun inProgress() = phase != Phase.OVER

	private var phase = Phase.WAITING
	private var phaseStart = 0L
	private var round = -1
	private var item: Bazaar.Product? = null
	private val names = linkedMapOf<UUID, String>()
	private val bots = mutableSetOf<UUID>()
	private val guesses = linkedMapOf<UUID, Double>()
	private val gained = linkedMapOf<UUID, Int>()
	private val scores = linkedMapOf<UUID, Int>()
	private val botGuessAt = HashMap<UUID, Long>()
	private val used = mutableSetOf<String>()
	private var input = ""
	private var error: String? = null
	private var rewarded = false
	private val rounds = BazaarBluff.ROUNDS
	/** Bumped by "Play again" so the host announces each round of a new match once. */
	private var match = 0

	init {
		Bazaar.ensureLoaded()
		room.onMessage = ::onMessage
		names.putAll(room.names)
		if (room.solo) for (n in listOf("Bazaar Flipper", "Coin Goblin", "Lucky Larry")) {
			val id = UUID(Random.nextLong(), Random.nextLong()); names[id] = n; bots += id
		}
	}

	private fun now() = room.now()
	private val me get() = Net.selfId
	private val hostLike get() = room.solo || room.isHost

	// ------------------------------------------------------------------ protocol

	/** Host: pick an item nobody had yet and announce the round. */
	private fun startRound(r: Int) {
		val list = Bazaar.products ?: return
		val pool = list.filter { it.id !in used && !Bazaar.icon(it.id).isEmpty }.ifEmpty { list }
		val p = pool.random()
		val payload = JsonObject().apply { addProperty("r", r); addProperty("id", p.id); addProperty("price", p.price) }
		if (room.solo) beginRound(r, p) else room.send("bluff.round", payload)
	}

	private fun onMessage(msg: Net.Message) {
		val r = msg.payload["r"]?.asInt ?: return
		when (msg.type) {
			"bluff.round" -> if (msg.from == room.host) {
				val id = msg.payload["id"]?.asString ?: return
				val price = msg.payload["price"]?.asDouble ?: return
				beginRound(r, Bazaar.Product(id, Bazaar.prettyName(id), price))
			}
			"bluff.guess" -> if (phase == Phase.GUESS && r == round) msg.payload["v"]?.asDouble?.let { addGuess(msg.from, it) }
			"bluff.reveal" -> if (msg.from == room.host && r == round) reveal()
		}
	}

	private fun beginRound(r: Int, p: Bazaar.Product) {
		if (r == 0) { scores.clear(); rewarded = false; used.clear() }
		round = r
		item = p
		used += p.id
		guesses.clear(); gained.clear(); botGuessAt.clear()
		input = ""; error = null
		for (b in bots) botGuessAt[b] = now() + Random.nextLong(3_000, 14_000)
		enter(Phase.GUESS)
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1f)
	}

	private fun enter(p: Phase) { phase = p; phaseStart = now() }

	private fun addGuess(from: UUID, v: Double) {
		if (from in guesses || from !in names || v <= 0) return
		guesses[from] = v
		if (from != me) play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.4f)
		if (guesses.size >= names.size && hostLike) {
			if (room.solo) reveal() else once("m$match v$round") { room.send("bluff.reveal", JsonObject().apply { addProperty("r", round) }) }
		}
	}

	private fun submit() {
		if (phase != Phase.GUESS || me in guesses) return
		val v = PriceAlerts.parsePrice(input)
		if (v == null) { error = "Like 1500, 1.5k or 2m"; return }
		input = ""
		if (room.solo) addGuess(me, v) else room.send("bluff.guess", JsonObject().apply { addProperty("r", round); addProperty("v", v) })
	}

	/** Closest by ratio (guessing 2x and 0.5x are equally far off). */
	private fun miss(guess: Double, price: Double) = abs(ln(guess / price))

	private fun reveal() {
		if (phase != Phase.GUESS) return
		val price = item?.price ?: return
		val ranked = guesses.entries.sortedBy { miss(it.value, price) }
		var rank = 0
		var prev = -1.0
		ranked.forEachIndexed { i, (id, v) ->
			val m = miss(v, price)
			if (m != prev) rank = i // equal guesses share a rank
			prev = m
			val pts = (BazaarBluff.POINTS.getOrNull(rank) ?: 0) + if (abs(v / price - 1) <= 0.05) 1 else 0
			gained[id] = pts
			scores[id] = (scores[id] ?: 0) + pts
		}
		enter(Phase.REVEAL)
		play(if ((gained[me] ?: 0) > 0) SoundEvents.EXPERIENCE_ORB_PICKUP else SoundEvents.NOTE_BLOCK_BASS.value(), 1f)
	}

	private fun finish() {
		enter(Phase.OVER)
		val best = scores.values.maxOrNull() ?: 0
		val won = (scores[me] ?: 0) == best && best > 0
		if (!rewarded) {
			rewarded = true
			MinigameStore.addTokens(if (won) 8 else 2)
			MinigameStore.submit(BazaarBluff.id, (scores[me] ?: 0).toDouble(), false, "${scores[me] ?: 0} points")
		}
		play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.VILLAGER_NO, 1f)
	}

	private fun play(sound: SoundEvent, pitch: Float) = Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch))

	// ------------------------------------------------------------------ loop

	private fun update() {
		val t = now() - phaseStart
		when (phase) {
			Phase.WAITING -> if (hostLike && round < 0 && Bazaar.products != null) once("m$match r0") { startRound(0) }
			Phase.GUESS -> {
				val price = item?.price ?: return
				for ((b, at) in botGuessAt.toMap()) if (now() >= at) {
					botGuessAt.remove(b)
					// Bots are off by a random factor of roughly 0.4x .. 2.5x, sometimes spot on.
					val factor = exp(Random.nextDouble(-0.9, 0.9) * if (Random.nextInt(5) == 0) 0.1 else 1.0)
					addGuess(b, price * factor)
				}
				if (phase == Phase.GUESS && hostLike && t >= BazaarBluff.GUESS_MS) {
					if (room.solo) reveal() else once("m$match v$round") { room.send("bluff.reveal", JsonObject().apply { addProperty("r", round) }) }
				}
			}
			Phase.REVEAL -> if (t >= BazaarBluff.REVEAL_MS) {
				if (round + 1 >= rounds) finish()
				else if (hostLike) once("m$match r${round + 1}") { startRound(round + 1) }
			}
			else -> {}
		}
	}

	// ------------------------------------------------------------------ input

	override fun onKey(event: KeyEvent): Boolean {
		if (phase == Phase.GUESS) when (event.key()) {
			GLFW.GLFW_KEY_BACKSPACE -> { input = input.dropLast(1); return true }
			GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { submit(); return true }
		}
		return super.onKey(event)
	}

	override fun onChar(event: CharacterEvent): Boolean {
		if (phase != Phase.GUESS || me in guesses) return false
		val s = event.codepointAsString()
		if (s.length == 1 && (s[0].isDigit() || s[0] in ".,kKmMbB") && input.length < 12) input += s.lowercase()
		error = null
		return true
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val top = header(g, "Bazaar Bluff", if (room.solo) "vs 3 bots" else "${names.size} players")
		val cx = width / 2
		if (phase == Phase.WAITING && round < 0) {
			centered(g, if (Bazaar.products == null) (Bazaar.error?.let { "§c$it" } ?: "§7Loading Bazaar prices…") else "§7Waiting for the host…", cx, height / 2, Theme.TEXT)
			return
		}
		drawScores(g)
		val p = item ?: return
		val t = now() - phaseStart

		// Item card
		val cw = 180
		val cy = top + 8
		panel(g, cx - cw / 2, cy, cw, 74)
		centered(g, "§7Round ${round + 1}/${rounds}", cx, cy + 5, Theme.TEXT)
		val icon = Bazaar.icon(p.id)
		if (!icon.isEmpty) {
			val pose = g.pose()
			pose.pushMatrix()
			pose.translate((cx - 16).toFloat(), (cy + 16).toFloat())
			pose.scale(2f, 2f)
			g.item(icon, 0, 0)
			pose.popMatrix()
		}
		centered(g, "§f§l" + font.plainSubstrByWidth(p.name, cw - 10), cx, cy + 52, Theme.TEXT)
		val priceLine = if (phase == Phase.GUESS) "§7Insta-buy price: §8???" else "§7Insta-buy price: §6§l${PriceAlerts.formatCoins(p.price)}"
		centered(g, priceLine, cx, cy + 63, Theme.TEXT)

		var y = cy + 82
		when (phase) {
			Phase.GUESS -> {
				timer(g, cx, y, 1f - t.toFloat() / BazaarBluff.GUESS_MS)
				y += 8
				if (me !in guesses) {
					val w = 140
					val x = cx - w / 2 - 26
					g.fill(x, y, x + w, y + 16, 0xFF100A1C.toInt())
					g.outline(x, y, w, 16, Theme.rainbow(0f, 0.5f))
					val caret = if ((now() / 500) % 2 == 0L) "_" else ""
					g.text(font, if (input.isEmpty()) "§8Your guess, e.g. 1.5k$caret" else input + caret, x + 4, y + 4, Theme.TEXT, false)
					button(g, x + w + 4, y, 48, 16, "Lock in", mouseX, mouseY, color = GameColors.GREEN) { submit() }
					centered(g, error?.let { "§c$it" } ?: "§7Nobody sees your guess until the reveal", cx, y + 22, Theme.TEXT)
				} else centered(g, "§aLocked in: §f${PriceAlerts.formatCoins(guesses.getValue(me))} §8· waiting for others", cx, y + 4, Theme.TEXT)
				y += 36
				// Who has guessed already (without the numbers)
				val status = names.entries.joinToString("  ") { (id, n) -> (if (id in guesses) "§a✔ " else "§8… ") + font.plainSubstrByWidth(n, 70) }
				centered(g, font.plainSubstrByWidth(status, width - 20), cx, y, Theme.TEXT)
			}
			Phase.REVEAL, Phase.OVER -> {
				val price = p.price
				val ranked = guesses.entries.sortedBy { miss(it.value, price) }
				val w = minOf(300, width - 20)
				val x = cx - w / 2
				for ((id, v) in ranked) {
					if (y + 14 > height - 30) break
					g.fill(x, y, x + w, y + 13, if (id == me) GameColors.CARD_HOVER else GameColors.CARD)
					val pct = ((v / price - 1) * 100)
					val off = if (abs(pct) < 0.5) "§a§lspot on" else (if (pct > 0) "§c+" else "§9") + "%.0f%%".format(pct)
					g.text(font, (if (id == me) "§e" else "§f") + font.plainSubstrByWidth(names[id] ?: "?", 90), x + 4, y + 3, Theme.TEXT, false)
					g.text(font, "§6${PriceAlerts.formatCoins(v)}", x + 104, y + 3, Theme.TEXT, false)
					g.text(font, off, x + 170, y + 3, Theme.TEXT, false)
					val pts = gained[id] ?: 0
					val ptsText = if (pts > 0) "§a+$pts" else "§80"
					g.text(font, ptsText, x + w - 6 - font.width(ptsText), y + 3, Theme.TEXT, false)
					y += 15
				}
				if (names.keys.any { it !in guesses }) {
					centered(g, "§8No guess: " + names.filterKeys { it !in guesses }.values.joinToString(), cx, y + 2, Theme.TEXT); y += 12
				}
				if (phase == Phase.OVER) drawOver(g, cx, y + 6, mouseX, mouseY)
				else centered(g, "§7Next item in ${((BazaarBluff.REVEAL_MS - t) / 1000 + 1)}s…", cx, y + 6, Theme.TEXT)
			}
			else -> {}
		}
	}

	private fun drawOver(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		val best = scores.values.maxOrNull() ?: 0
		val winners = scores.filterValues { it == best }.keys.map { names[it] ?: "?" }
		centered(g, "§6§l${winners.joinToString(" & ")} ${if (winners.size == 1) "wins" else "win"} with $best points!", cx, y, Theme.TEXT)
		if (hostLike) {
			button(g, cx - 100, y + 14, 92, 16, "Play again", mx, my, color = GameColors.GREEN) { match++; round = -1; enter(Phase.WAITING) }
		} else centered(g, "§7Waiting for the host…", cx, y + 18, Theme.TEXT)
		button(g, cx + 8, y + 14, 92, 16, "Leave", mx, my, color = GameColors.RED) { room.quit(); onClose() }
	}

	private fun drawScores(g: GuiGraphicsExtractor) {
		val x = width - 110
		val y = 30
		panel(g, x, y, 102, 16 + names.size * 11, "Scores")
		names.entries.sortedByDescending { scores[it.key] ?: 0 }.forEachIndexed { i, (id, name) ->
			val s = (scores[id] ?: 0).toString()
			g.text(font, (if (id == me) "§e" else "§f") + font.plainSubstrByWidth(name, 70), x + 6, y + 19 + i * 11, Theme.TEXT, false)
			g.text(font, s, x + 96 - font.width(s), y + 19 + i * 11, GameColors.GOLD, false)
		}
	}

	private fun timer(g: GuiGraphicsExtractor, cx: Int, y: Int, frac: Float) {
		val f = frac.coerceIn(0f, 1f)
		g.fill(cx - 90, y, cx + 90, y + 3, 0xFF2A1A44.toInt())
		g.fill(cx - 90, y, cx - 90 + (180 * f).toInt(), y + 3, if (f > 0.3f) GameColors.GREEN else GameColors.RED)
	}

	/** Test hooks. */
	val debugPhase get() = phase
	val debugRound get() = round
	fun debugGuess(text: String) { input = text; submit() }

	override fun removed() {
		room.leave()
		super.removed()
	}
}
