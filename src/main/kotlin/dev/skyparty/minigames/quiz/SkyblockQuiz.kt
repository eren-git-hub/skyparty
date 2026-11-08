package dev.skyparty.minigames.quiz

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.skyparty.mayor.Mayor
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
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.random.Random

/**
 * SkyBlock Quiz: multiple-choice questions made from live data (Bazaar prices, item rarities and kinds,
 * mayor perks). The faster you answer right, the more points. Solo: three bots.
 *
 * Protocol (echoed to the sender):
 *  - "quiz.q" {r, q, o:[4], a, ic?}  (host) question r with options, the right index and an optional item icon
 *  - "quiz.a" {r, i}                  a player's answer; the order answers arrive in decides the points
 *  - "quiz.reveal" {r}                (host) time is up
 */
object SkyblockQuiz : Minigame {
	override val id = "quiz"
	override val name = "SkyBlock Quiz"
	override val description = "Questions from live SkyBlock data: prices, rarities, mayors. Fastest right answer wins."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = false
	override val length = GameLength.SHORT

	const val ROUNDS = 8
	const val ANSWER_MS = 15_000L
	const val REVEAL_MS = 4_000L
	/** Points for the 1st, 2nd, 3rd right answer; later right answers get 1. */
	val POINTS = listOf(3, 2, 1)

	override fun open(room: Net.Room?) = MinigameHub.openScreen(QuizScreen(LobbyRoom.of(room, id)))

	class Question(val text: String, val options: List<String>, val answer: Int, val icon: String?)

	/** A random question from whatever data is loaded (null until some is). */
	fun makeQuestion(rng: Random, used: MutableSet<String>): Question? {
		val makers = buildList<() -> Question?> {
			if ((Bazaar.products?.size ?: 0) >= 8) { add { priceHighest(rng) }; add { priceGuess(rng) } }
			if ((SkyblockItems.items?.size ?: 0) >= 20) { add { rarityOf(rng) }; add { whichRarity(rng) }; add { kindOf(rng) } }
			if ((Mayor.data?.let { (it.current ?: it.lastElection).size } ?: 0) >= 4) add { mayorPerk(rng) }
		}
		if (makers.isEmpty()) return null
		repeat(12) {
			val q = makers.random(rng)() ?: return@repeat
			if (used.add(q.text)) return q
		}
		return null
	}

	private fun withIcon(id: String) = id.takeIf { !Bazaar.icon(it).isEmpty }

	private fun shuffled(rng: Random, right: String, wrong: List<String>, text: String, icon: String?): Question {
		val opts = (wrong.take(3) + right).shuffled(rng)
		return Question(text, opts, opts.indexOf(right), icon)
	}

	private fun priceHighest(rng: Random): Question? {
		val pool = Bazaar.products!!.filter { it.price > 1 && !Bazaar.icon(it.id).isEmpty }.shuffled(rng)
		val picks = mutableListOf<Bazaar.Product>()
		for (p in pool) if (picks.all { o -> p.price / o.price !in 0.6..1.7 }) { picks += p; if (picks.size == 4) break }
		if (picks.size < 4) return null
		val best = picks.maxBy { it.price }
		return shuffled(rng, best.name, (picks - best).map { it.name }, "Which costs the most on the Bazaar?", null)
	}

	private fun priceGuess(rng: Random): Question? {
		val p = Bazaar.products!!.filter { it.price >= 5 && !Bazaar.icon(it.id).isEmpty }.randomOrNull(rng) ?: return null
		val wrong = listOf(0.2, 3.5, 15.0).map { PriceAlerts.formatCoins(p.price * it) }
		return shuffled(rng, PriceAlerts.formatCoins(p.price), wrong, "About how much is ${p.name} (insta-buy)?", withIcon(p.id))
	}

	private fun rarityOf(rng: Random): Question? {
		val item = SkyblockItems.items!!.randomOrNull(rng) ?: return null
		val wrong = (SkyblockItems.TIERS - item.tier).shuffled(rng).map { SkyblockItems.tierColor(it) + SkyblockItems.prettyTier(it) }
		return shuffled(rng, SkyblockItems.tierColor(item.tier) + SkyblockItems.prettyTier(item.tier), wrong, "What rarity is ${item.name}?", withIcon(item.id))
	}

	private fun whichRarity(rng: Random): Question? {
		val all = SkyblockItems.items!!
		val tier = listOf("RARE", "EPIC", "LEGENDARY").random(rng)
		val right = all.filter { it.tier == tier }.randomOrNull(rng) ?: return null
		val wrong = all.filter { it.tier != tier && it.category == right.category }.shuffled(rng).map { it.name }.distinct().take(3)
		if (wrong.size < 3) return null
		return shuffled(rng, right.name, wrong, "Which of these is ${SkyblockItems.tierColor(tier)}${SkyblockItems.prettyTier(tier)}§f?", null)
	}

	private fun kindOf(rng: Random): Question? {
		val all = SkyblockItems.items!!
		// Skip items whose name gives the answer away ("Runaan's Bow" -> Bow).
		val item = all.shuffled(rng).take(40).firstOrNull { !SkyblockItems.nameGivesKind(it) } ?: return null
		val wrong = all.map { it.category }.distinct().filter { it != item.category }.shuffled(rng).map { SkyblockItems.prettyKind(it) }
		return shuffled(rng, SkyblockItems.prettyKind(item.category), wrong, "What kind of item is ${item.name}?", withIcon(item.id))
	}

	private fun mayorPerk(rng: Random): Question? {
		val d = Mayor.data ?: return null
		val cands = (d.current ?: d.lastElection).filter { it.perks.isNotEmpty() }
		if (cands.size < 4) return null
		val right = cands.random(rng)
		val perk = right.perks.random(rng)
		val wrong = (cands - right).shuffled(rng).map { it.name }
		return shuffled(rng, right.name, wrong, "Which mayor has the perk \"${perk.name}\"?", null)
	}
}

class QuizScreen(room: LobbyRoom) : RoundGameScreen("SkyBlock Quiz", room, listOf("Quiz Wizard", "Lucky Guess", "Bazaar Brain")) {
	enum class Phase { WAITING, ASK, REVEAL, OVER }

	override fun inProgress() = phase != Phase.OVER

	private var phase = Phase.WAITING
	private var phaseStart = 0L
	private var round = -1
	private var question: SkyblockQuiz.Question? = null
	/** Answers in arrival order (that order decides the points). */
	private val answers = linkedMapOf<UUID, Int>()
	private val gained = HashMap<UUID, Int>()
	private val used = mutableSetOf<String>()
	private val botAnswerAt = HashMap<UUID, Long>()

	init {
		Bazaar.ensureLoaded()
		SkyblockItems.ensureLoaded()
		Mayor.ensureLoaded()
		room.onMessage = ::onMessage
	}

	private val dataReady get() = Bazaar.products != null || SkyblockItems.items != null

	// ------------------------------------------------------------------ protocol

	private fun startRound(r: Int) {
		val q = SkyblockQuiz.makeQuestion(Random, used) ?: return
		if (room.solo) beginRound(r, q)
		else room.send("quiz.q", JsonObject().apply {
			addProperty("r", r); addProperty("q", q.text); addProperty("a", q.answer)
			add("o", JsonArray().apply { q.options.forEach { add(it) } })
			q.icon?.let { addProperty("ic", it) }
		})
	}

	private fun onMessage(msg: Net.Message) {
		val p = msg.payload
		val r = p["r"]?.asInt ?: return
		when (msg.type) {
			"quiz.q" -> if (msg.from == room.host) {
				val opts = p["o"]?.asJsonArray?.map { it.asString } ?: return
				if (opts.size != 4) return
				beginRound(r, SkyblockQuiz.Question(p["q"]?.asString ?: return, opts, p["a"]?.asInt ?: return, p["ic"]?.asString))
			}
			"quiz.a" -> if (phase == Phase.ASK && r == round) addAnswer(msg.from, p["i"]?.asInt ?: return)
			"quiz.reveal" -> if (msg.from == room.host && r == round) reveal()
		}
	}

	private fun beginRound(r: Int, q: SkyblockQuiz.Question) {
		if (r == 0) resetMatch()
		round = r
		question = q
		answers.clear(); gained.clear(); botAnswerAt.clear()
		for (b in bots) botAnswerAt[b] = now() + Random.nextLong(2_500, 11_000)
		enter(Phase.ASK)
		play(SoundEvents.NOTE_BLOCK_CHIME.value())
	}

	private fun enter(p: Phase) { phase = p; phaseStart = now() }

	private fun addAnswer(from: UUID, i: Int) {
		val q = question ?: return
		if (from in answers || from !in names || i !in 0..3) return
		answers[from] = i
		if (i == q.answer) {
			val rank = answers.entries.count { it.value == q.answer } - 1
			gained[from] = SkyblockQuiz.POINTS.getOrElse(rank) { 1 }
		}
		if (from != me) play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.4f)
		if (answers.size >= names.size && hostLike) endRound()
	}

	private fun endRound() {
		if (room.solo) reveal() else once("m$match v$round") { room.send("quiz.reveal", JsonObject().apply { addProperty("r", round) }) }
	}

	private fun answer(i: Int) {
		if (phase != Phase.ASK || me in answers) return
		if (room.solo) addAnswer(me, i) else room.send("quiz.a", JsonObject().apply { addProperty("r", round); addProperty("i", i) })
	}

	private fun reveal() {
		if (phase != Phase.ASK) return
		for ((id, pts) in gained) addScore(id, pts)
		enter(Phase.REVEAL)
		play(if ((gained[me] ?: 0) > 0) SoundEvents.EXPERIENCE_ORB_PICKUP else SoundEvents.NOTE_BLOCK_BASS.value())
	}

	private fun finish() {
		enter(Phase.OVER)
		val won = rewardMatch(SkyblockQuiz.id)
		play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.VILLAGER_NO)
	}

	private fun update() {
		val t = now() - phaseStart
		when (phase) {
			Phase.WAITING -> if (hostLike && round < 0 && dataReady) once("m$match r0") { startRound(0) }
			Phase.ASK -> {
				val q = question ?: return
				for ((b, at) in botAnswerAt.toMap()) if (now() >= at) {
					botAnswerAt.remove(b)
					addAnswer(b, if (Random.nextInt(100) < 60) q.answer else Random.nextInt(4))
				}
				if (phase == Phase.ASK && hostLike && t >= SkyblockQuiz.ANSWER_MS) endRound()
			}
			Phase.REVEAL -> if (t >= SkyblockQuiz.REVEAL_MS) {
				if (round + 1 >= SkyblockQuiz.ROUNDS) finish()
				else if (hostLike) once("m$match r${round + 1}") { startRound(round + 1) }
			}
			Phase.OVER -> {}
		}
	}

	// ------------------------------------------------------------------ input / drawing

	override fun onKey(event: KeyEvent): Boolean {
		// 1-4 answer too.
		val k = event.key()
		if (phase == Phase.ASK && k in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_4) { answer(k - GLFW.GLFW_KEY_1); return true }
		return super.onKey(event)
	}

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val top = header(g, "SkyBlock Quiz", if (room.solo) "vs 3 bots" else "${names.size} players")
		val cx = width / 2
		if (phase == Phase.WAITING && round < 0) {
			centered(g, if (!dataReady) "§7Loading SkyBlock data…" else "§7Waiting for the host…", cx, height / 2, Theme.TEXT)
			return
		}
		val q = question ?: return
		val w = minOf(320, width - 140)
		drawScores(g, cx + w / 2)
		val t = now() - phaseStart
		var y = top + 10
		centered(g, "§7Question ${round + 1}/${SkyblockQuiz.ROUNDS}", cx, y, Theme.TEXT)
		y += 14
		q.icon?.let { id ->
			val icon = Bazaar.icon(id)
			if (!icon.isEmpty) {
				val pose = g.pose()
				pose.pushMatrix(); pose.translate((cx - 16).toFloat(), y.toFloat()); pose.scale(2f, 2f); g.item(icon, 0, 0); pose.popMatrix()
				y += 36
			}
		}
		for (line in font.split(net.minecraft.network.chat.Component.literal("§f§l" + q.text), w)) {
			g.text(font, line, cx - font.width(line) / 2, y, Theme.TEXT, true)
			y += 11
		}
		y += 6
		if (phase == Phase.ASK) { timer(g, cx, y, 1f - t.toFloat() / SkyblockQuiz.ANSWER_MS, w); y += 8 }

		// Four answer buttons in a 2x2 grid.
		val bw = (w - 6) / 2
		val bh = 24
		val mine = answers[me]
		for (i in 0..3) {
			val bx = cx - w / 2 + (i % 2) * (bw + 6)
			val by = y + (i / 2) * (bh + 6)
			val label = "${i + 1}  ${q.options[i]}"
			val color = when {
				phase != Phase.ASK && i == q.answer -> GameColors.GREEN
				phase != Phase.ASK && i == mine -> GameColors.RED
				mine == i -> Theme.accent
				else -> 0xFF3A3050.toInt()
			}
			if (phase == Phase.ASK && mine == null) button(g, bx, by, bw, bh, label, mouseX, mouseY, color = color) { answer(i) }
			else {
				g.fill(bx, by, bx + bw, by + bh, ColorUtil.withAlpha(ColorUtil.shade(color, 0.6f), 0xF0))
				g.fill(bx, by + bh - 1, bx + bw, by + bh, color)
				centered(g, fit(label, bw - 6), bx + bw / 2, by + (bh - 8) / 2, Theme.TEXT)
			}
		}
		y += 2 * (bh + 6) + 4

		when (phase) {
			Phase.ASK -> {
				val status = names.entries.joinToString("  ") { (id, n) -> (if (id in answers) "§a✔ " else "§8… ") + n }
				centered(g, fit(status, width - 20), cx, y, Theme.TEXT)
			}
			Phase.REVEAL -> {
				val right = answers.filterValues { it == q.answer }.keys.map { (if (it == me) "§eYou" else "§f" + (names[it] ?: "?")) + " §a+${gained[it] ?: 0}" }
				centered(g, fit(if (right.isEmpty()) "§7Nobody got it." else right.joinToString("  "), width - 20), cx, y, Theme.TEXT)
				centered(g, "§8Next question in ${(SkyblockQuiz.REVEAL_MS - t) / 1000 + 1}s…", cx, y + 12, Theme.TEXT)
			}
			Phase.OVER -> drawOver(g, cx, y, mouseX, mouseY) { round = -1; enter(Phase.WAITING) }
			Phase.WAITING -> {}
		}
	}

	/** Test hooks. */
	val debugPhase get() = phase
	val debugRound get() = round
	fun debugAnswer(i: Int) = answer(i)
}
