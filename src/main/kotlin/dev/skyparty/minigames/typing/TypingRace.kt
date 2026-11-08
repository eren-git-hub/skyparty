package dev.skyparty.minigames.typing

import com.google.gson.JsonObject
import dev.skyparty.minigames.GameLength
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.common.RoundGameScreen
import dev.skyparty.minigames.price.Bazaar
import dev.skyparty.minigames.quiz.SkyblockItems
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.random.Random

/**
 * Item Typing: a SkyBlock item name appears, the first to type it exactly wins the round. Ten rounds.
 * Solo: three bots.
 *
 * Protocol (echoed to the sender):
 *  - "type.word" {r, w}  (host) round r: type this
 *  - "type.done" {r}     a player typed it; the order these arrive in decides the points
 *  - "type.end"  {r}     (host) time is up
 */
object TypingRace : Minigame {
	override val id = "typing"
	override val name = "Item Typing"
	override val description = "A SkyBlock item name appears. Type it first to win the round."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = true
	override val length = GameLength.SHORT

	const val ROUNDS = 10
	const val TYPE_MS = 20_000L
	const val REVEAL_MS = 3_000L
	val POINTS = listOf(3, 2, 1)

	/** Used when no item data is loaded (offline). */
	private val FALLBACK = listOf(
		"Hyperion", "Terminator", "Aspect of the End", "Grappling Hook", "Booster Cookie", "Enchanted Diamond",
		"Summoning Eye", "Treecapitator", "Bonemerang", "Juju Shortbow", "Wither Blade", "Spirit Sceptre",
		"Enchanted Iron Block", "Golden Dragon", "Rabbit Hat", "Midas Staff", "Giant's Sword", "Livid Dagger",
	)

	override fun open(room: Net.Room?) = MinigameHub.openScreen(TypingScreen(LobbyRoom.of(room, id)))

	/** Names that type well: letters, spaces and a few marks, 6 to 24 chars. */
	fun words(): List<String> {
		val fromItems = SkyblockItems.items?.map { it.name }.orEmpty()
		val fromBazaar = Bazaar.products?.map { it.name }.orEmpty()
		val all = (fromItems + fromBazaar).filter { it.length in 6..24 && it.all { c -> c.isLetterOrDigit() || c in " '-" } }.distinct()
		return all.ifEmpty { FALLBACK }
	}

	/** Lenient compare: case and extra spaces don't matter. */
	fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
}

class TypingScreen(room: LobbyRoom) : RoundGameScreen("Item Typing", room, listOf("Speedy Fingers", "Typo Tim", "Keyboard Kat")) {
	enum class Phase { WAITING, TYPE, REVEAL, OVER }

	override fun inProgress() = phase != Phase.OVER

	private var phase = Phase.WAITING
	private var phaseStart = 0L
	private var round = -1
	private var word = ""
	private var input = ""
	/** Finishers in arrival order with the time it took (on this client). */
	private val done = linkedMapOf<UUID, Long>()
	private val botDoneAt = HashMap<UUID, Long>()
	private val used = mutableSetOf<String>()
	private var sent = false

	init {
		Bazaar.ensureLoaded()
		SkyblockItems.ensureLoaded()
		room.onMessage = ::onMessage
	}

	private fun startRound(r: Int) {
		val pool = TypingRace.words().filter { it !in used }.ifEmpty { TypingRace.words() }
		val w = pool.random()
		if (room.solo) beginRound(r, w) else room.send("type.word", JsonObject().apply { addProperty("r", r); addProperty("w", w) })
	}

	private fun onMessage(msg: Net.Message) {
		val p = msg.payload
		val r = p["r"]?.asInt ?: return
		when (msg.type) {
			"type.word" -> if (msg.from == room.host) beginRound(r, p["w"]?.asString?.take(32) ?: return)
			"type.done" -> if (phase == Phase.TYPE && r == round) addDone(msg.from)
			"type.end" -> if (msg.from == room.host && r == round) reveal()
		}
	}

	private fun beginRound(r: Int, w: String) {
		if (r == 0) resetMatch()
		round = r
		word = w
		used += w
		input = ""
		sent = false
		done.clear(); botDoneAt.clear()
		// Bots type ~6-10 chars per second, sometimes they fumble.
		for (b in bots) botDoneAt[b] = now() + (w.length * Random.nextLong(100, 170)) + Random.nextLong(600, 1800) + (if (Random.nextInt(4) == 0) 3000 else 0)
		enter(Phase.TYPE)
		play(SoundEvents.NOTE_BLOCK_CHIME.value())
	}

	private fun enter(p: Phase) { phase = p; phaseStart = now() }

	private fun addDone(from: UUID) {
		if (from in done || from !in names) return
		done[from] = now() - phaseStart
		addScore(from, TypingRace.POINTS.getOrElse(done.size - 1) { 1 })
		play(if (from == me) SoundEvents.EXPERIENCE_ORB_PICKUP else SoundEvents.NOTE_BLOCK_HAT.value(), 1.3f)
		if (done.size >= names.size && hostLike) endRound()
	}

	private fun endRound() {
		if (room.solo) reveal() else once("m$match e$round") { room.send("type.end", JsonObject().apply { addProperty("r", round) }) }
	}

	private fun reveal() {
		if (phase != Phase.TYPE) return
		enter(Phase.REVEAL)
	}

	private fun finish() {
		enter(Phase.OVER)
		val won = rewardMatch(TypingRace.id)
		play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.VILLAGER_NO)
	}

	private fun checkInput() {
		if (sent || phase != Phase.TYPE || TypingRace.normalize(input) != TypingRace.normalize(word)) return
		sent = true
		if (room.solo) addDone(me) else room.send("type.done", JsonObject().apply { addProperty("r", round) })
	}

	private fun update() {
		val t = now() - phaseStart
		when (phase) {
			Phase.WAITING -> if (hostLike && round < 0) once("m$match r0") { startRound(0) }
			Phase.TYPE -> {
				for ((b, at) in botDoneAt.toMap()) if (now() >= at) { botDoneAt.remove(b); addDone(b) }
				if (phase == Phase.TYPE && hostLike && t >= TypingRace.TYPE_MS) endRound()
			}
			Phase.REVEAL -> if (t >= TypingRace.REVEAL_MS) {
				if (round + 1 >= TypingRace.ROUNDS) finish()
				else if (hostLike) once("m$match r${round + 1}") { startRound(round + 1) }
			}
			Phase.OVER -> {}
		}
	}

	override fun onKey(event: KeyEvent): Boolean {
		if (phase == Phase.TYPE && !sent && event.key() == GLFW.GLFW_KEY_BACKSPACE) {
			input = if (event.hasControlDownWithQuirk()) input.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " } else input.dropLast(1)
			return true
		}
		return super.onKey(event)
	}

	override fun onChar(event: CharacterEvent): Boolean {
		if (phase != Phase.TYPE || sent) return false
		if (event.isAllowedChatCharacter() && input.length < 40) input += event.codepointAsString()
		checkInput()
		return true
	}

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val top = header(g, "Item Typing", if (room.solo) "vs 3 bots" else "${names.size} players")
		val cx = width / 2
		if (phase == Phase.WAITING && round < 0) {
			centered(g, "§7Waiting for the host…", cx, height / 2, Theme.TEXT)
			return
		}
		val w = minOf(320, width - 140)
		drawScores(g, cx + w / 2)
		val t = now() - phaseStart
		var y = top + 14
		centered(g, "§7Round ${round + 1}/${TypingRace.ROUNDS}", cx, y, Theme.TEXT)
		y += 18

		// The word, big; while typing each letter turns green when right and red when wrong.
		val sb = StringBuilder()
		for ((i, c) in word.withIndex()) {
			val typed = input.getOrNull(i)
			sb.append(when {
				typed == null -> "§f"
				typed.equals(c, ignoreCase = true) -> "§a"
				else -> "§c"
			}).append(c)
		}
		val scale = if (font.width(word) * 2 > width - 40) 1.5f else 2f
		bigText(g, sb.toString(), cx, y, scale, Theme.TEXT)
		y += (12 * scale).toInt() + 10

		when (phase) {
			Phase.TYPE -> {
				timer(g, cx, y, 1f - t.toFloat() / TypingRace.TYPE_MS, w)
				y += 10
				val fw = w
				val fx = cx - fw / 2
				g.fill(fx, y, fx + fw, y + 18, 0xFF100A1C.toInt())
				g.outline(fx, y, fw, 18, if (sent) dev.skyparty.minigames.ui.GameColors.GREEN else Theme.rainbow(0f, 0.5f))
				val caret = if (!sent && (now() / 500) % 2 == 0L) "_" else ""
				val shown = if (sent) "§a✔ $input" else if (input.isEmpty()) "§8Start typing…$caret" else input + caret
				g.text(font, fit(shown, fw - 8), fx + 4, y + 5, Theme.TEXT, false)
				y += 26
				val status = names.entries.joinToString("  ") { (id, n) -> (if (id in done) "§a✔ " else "§8… ") + n }
				centered(g, fit(status, width - 20), cx, y, Theme.TEXT)
			}
			Phase.REVEAL, Phase.OVER -> {
				var rank = 0
				for ((id, ms) in done) {
					val pts = TypingRace.POINTS.getOrElse(rank) { 1 }
					val who = if (id == me) "§eYou" else "§f" + (names[id] ?: "?")
					centered(g, "$who §7%.2fs §a+$pts".format(ms / 1000.0), cx, y, Theme.TEXT)
					y += 11
					rank++
				}
				if (done.isEmpty()) { centered(g, "§7Nobody made it in time.", cx, y, Theme.TEXT); y += 11 }
				y += 6
				if (phase == Phase.OVER) drawOver(g, cx, y, mouseX, mouseY) { round = -1; enter(Phase.WAITING) }
				else centered(g, "§8Next word in ${(TypingRace.REVEAL_MS - t) / 1000 + 1}s…", cx, y, Theme.TEXT)
			}
			Phase.WAITING -> {}
		}
	}

	/** Test hooks. */
	val debugWord get() = word
	val debugRound get() = round
	fun debugType(s: String) { input = s; checkInput() }
}
