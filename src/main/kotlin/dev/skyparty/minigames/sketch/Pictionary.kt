package dev.skyparty.minigames.sketch

import com.google.gson.JsonArray
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
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.random.Random

/**
 * Skyblock Pictionary: one player draws a SkyBlock thing on a small pixel canvas, everyone else guesses.
 * Everybody draws once. Fast correct guesses score more; the drawer scores for every correct guesser.
 * Solo is a sketch practice: you get words and a timer, nobody guesses.
 *
 * Protocol (echoed to the sender; order and words come from the host's seed):
 *  - "pict.start.rng" (host)  roll -> seed
 *  - "pict.px" {r, p:[cell*16+color, ...]}  drawer's painted cells since the last batch (color 15 = eraser)
 *  - "pict.clear" {r}         drawer wiped the canvas
 *  - "pict.guess" {r, text}   a guess (clients check it themselves; correct guesses are never shown as text)
 *  - "pict.end" {r}           (host) time is up or everyone got it
 */
object Pictionary : Minigame {
	override val id = "sketch"
	override val name = "Skyblock Pictionary"
	override val description = "Draw a SkyBlock item, mob or place on a pixel canvas while the others guess it."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = false
	override val length = dev.skyparty.minigames.GameLength.MEDIUM

	const val DRAW_MS = 75_000L
	const val REVEAL_MS = 5_000L
	const val COLS = 48
	const val ROWS = 32
	const val ERASER = 15

	val PALETTE = intArrayOf(
		0xFF1B1A1F.toInt(), 0xFFFFFFFF.toInt(), 0xFF8A8A94.toInt(), 0xFFD9484A.toInt(),
		0xFFFF9A3C.toInt(), 0xFFFFD94A.toInt(), 0xFF3FBF6A.toInt(), 0xFF2E7D4F.toInt(),
		0xFF4AB3FF.toInt(), 0xFF2E4FD9.toInt(), 0xFFA05CFF.toInt(), 0xFFFF7EB6.toInt(),
		0xFF8B5A2B.toInt(), 0xFFE8C99A.toInt(),
	)

	/** Things that are drawable in a few pixels and that SkyBlock players know. */
	val WORDS = listOf(
		"Hyperion", "Terminator", "Aspect of the End", "Grappling Hook", "Booster Cookie", "Bonemerang",
		"Golden Dragon", "Griffin", "Elephant", "Yeti", "Zealot", "Mimic", "Necron", "Kuudra", "Bonzo",
		"Dwarven Mines", "Crystal Hollows", "Garden", "The End", "Spider's Den", "Crimson Isle", "The Park",
		"Minion", "Bazaar", "Auction House", "Jerry", "Summoning Eye", "Ender Dragon", "Revenant Horror",
		"Tarantula", "Sven Packmaster", "Voidgloom Seraph", "Blaze", "Dark Auction", "Fishing Rod", "Sea Creature",
		"Pumpkin", "Melon", "Sugar Cane", "Cactus", "Mushroom", "Diamond", "Emerald", "Gemstone", "Mithril",
		"Treecapitator", "Rabbit Hat", "Wither Skull", "Golden Apple", "Enchanting Table", "Pigman Sword",
		"Wise Dragon Armor", "Superior Dragon", "Crypt Ghoul", "Slime", "Magma Cube", "Lava Fishing",
		"Pest", "Visitor", "Mayor", "Hub Fountain", "Portal", "Spooky Festival", "Jerry's Workshop", "Snowman",
	)

	override fun open(room: Net.Room?) = MinigameHub.openScreen(PictionaryScreen(LobbyRoom.of(room, id)))

	/** Letters shown as blanks, spaces and punctuation kept: "Aspect of the End" -> "_ _ _ _ _ _   _ _ ..." */
	fun mask(word: String, revealed: Set<Int>): String = word.mapIndexed { i, c ->
		when { !c.isLetterOrDigit() -> if (c == ' ') "  " else "$c "; i in revealed -> "$c "; else -> "_ " }
	}.joinToString("").trimEnd()

	fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "").removePrefix("the")
}

class PictionaryScreen(val room: LobbyRoom) : GameScreen("Skyblock Pictionary", room) {
	enum class Phase { WAITING, DRAW, REVEAL, OVER }

	override fun inProgress() = phase != Phase.OVER

	private class Line(val text: String)

	private var phase = Phase.WAITING
	private var phaseStart = 0L
	private var seed = 0L
	private var order = listOf<UUID>()
	private var words = listOf<String>()
	private var round = 0
	private val canvas = IntArray(Pictionary.COLS * Pictionary.ROWS) { Pictionary.ERASER }
	private val pending = mutableListOf<Int>()
	private var lastFlush = 0L
	private var color = 0
	private var brush = 1
	private var painting = false
	private var lastCell = -1
	private val solved = linkedMapOf<UUID, Int>() // guesser -> points this round
	private val scores = linkedMapOf<UUID, Int>()
	private val feed = ArrayDeque<Line>()
	private var input = ""
	private var rewarded = false

	// canvas layout (set every frame)
	private var cx0 = 0
	private var cy0 = 0
	private var cell = 5

	init {
		room.onMessage = ::onMessage
		if (room.isHost) requestStart()
	}

	private fun now() = room.now()
	private val me get() = Net.selfId
	private val drawer get() = order.getOrNull(round)
	private val word get() = words.getOrNull(round) ?: ""
	private val iDraw get() = drawer == me
	private val guessers get() = order.distinct().filter { it != drawer }
	private val drawMs = Pictionary.DRAW_MS

	// ------------------------------------------------------------------ protocol

	private fun requestStart() = room.send("pict.start.rng", JsonObject())

	private fun onMessage(msg: Net.Message) {
		when (msg.type) {
			"pict.start.rng" -> if (msg.from == room.host) start(((msg.payload["roll"]?.asDouble ?: Math.random()) * Long.MAX_VALUE).toLong())
		}
		if (msg.payload["r"]?.asInt != round) return
		when (msg.type) {
			"pict.px" -> if (msg.from == drawer && msg.from != me && phase == Phase.DRAW) msg.payload["p"]?.asJsonArray?.forEach {
				val v = it.asInt
				val idx = v shr 4
				if (idx in canvas.indices) canvas[idx] = v and 15
			}
			"pict.clear" -> if (msg.from == drawer && msg.from != me) canvas.fill(Pictionary.ERASER)
			"pict.guess" -> if (phase == Phase.DRAW) onGuess(msg.from, msg.fromName, msg.payload["text"]?.asString ?: "")
			"pict.end" -> if (msg.from == room.host) endRound()
		}
	}

	private fun start(s: Long) {
		seed = s
		val rng = Random(seed)
		order = room.names.keys.toList().shuffled(rng)
		words = Pictionary.WORDS.shuffled(rng).take(maxOf(order.size, if (room.solo) 3 else 1))
		if (room.solo) order = List(words.size) { me }
		scores.clear()
		order.distinct().forEach { scores[it] = 0 }
		rewarded = false
		beginRound(0)
	}

	private fun beginRound(r: Int) {
		round = r
		canvas.fill(Pictionary.ERASER)
		pending.clear()
		solved.clear()
		feed.clear()
		input = ""
		enter(Phase.DRAW)
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1f)
	}

	private fun enter(p: Phase) { phase = p; phaseStart = now() }

	private fun onGuess(from: UUID, name: String, text: String) {
		if (from == drawer || from in solved) return
		if (Pictionary.normalize(text) == Pictionary.normalize(word) && text.isNotBlank()) {
			val pts = maxOf(1, 4 - solved.size) // 3, 2, 1, 1, …  (+1 for the very first)
			val bonus = if (solved.isEmpty()) 1 else 0
			solved[from] = pts + bonus
			scores[from] = (scores[from] ?: 0) + pts + bonus
			drawer?.let { scores[it] = (scores[it] ?: 0) + 1 }
			push("§a✔ ${if (from == me) "You" else name} guessed it!")
			play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f)
			if (solved.size >= guessers.size && room.isHost) once("$seed e$round") { room.send("pict.end", JsonObject().apply { addProperty("r", round) }) }
		} else push("§7$name: §f${text.take(24)}")
	}

	private fun push(s: String) {
		feed.addLast(Line(s))
		while (feed.size > 9) feed.removeFirst()
	}

	private fun sendGuess() {
		val t = input.trim()
		input = ""
		if (t.isEmpty() || iDraw || phase != Phase.DRAW || me in solved) return
		room.send("pict.guess", JsonObject().apply { addProperty("r", round); addProperty("text", t) })
	}

	private fun endRound() {
		if (phase != Phase.DRAW) return
		flush(force = true)
		enter(Phase.REVEAL)
		play(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8f)
	}

	private fun finish() {
		enter(Phase.OVER)
		if (rewarded) return
		rewarded = true
		if (room.solo) { MinigameStore.addTokens(2); return }
		val best = scores.values.maxOrNull() ?: 0
		val won = (scores[me] ?: 0) == best && best > 0
		MinigameStore.addTokens(if (won) 8 else 2)
		play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.NOTE_BLOCK_BASS.value(), 1f)
	}

	private fun play(sound: SoundEvent, pitch: Float) = Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch))

	// ------------------------------------------------------------------ painting

	private fun paintAt(mx: Double, my: Double) {
		if (!iDraw || phase != Phase.DRAW) return
		val c = ((mx - cx0) / cell).toInt()
		val r = ((my - cy0) / cell).toInt()
		if (c !in 0 until Pictionary.COLS || r !in 0 until Pictionary.ROWS) return
		val idx = r * Pictionary.COLS + c
		// Fill the gap to the previous cell so fast strokes stay connected.
		if (lastCell >= 0) {
			val pc = lastCell % Pictionary.COLS
			val pr = lastCell / Pictionary.COLS
			val steps = maxOf(kotlin.math.abs(c - pc), kotlin.math.abs(r - pr))
			for (s in 1 until steps) stamp(pc + (c - pc) * s / steps, pr + (r - pr) * s / steps)
		}
		stamp(c, r)
		lastCell = idx
	}

	private fun stamp(c: Int, r: Int) {
		val value = if (color == Pictionary.ERASER) Pictionary.ERASER else color
		for (dy in 0 until brush) for (dx in 0 until brush) {
			val cc = c + dx - brush / 2
			val rr = r + dy - brush / 2
			if (cc !in 0 until Pictionary.COLS || rr !in 0 until Pictionary.ROWS) continue
			val i = rr * Pictionary.COLS + cc
			if (canvas[i] == value) continue
			canvas[i] = value
			pending += (i shl 4) or value
		}
	}

	/** Sends painted cells in batches (at most ~3 messages/s, well under the server's rate limit). */
	private fun flush(force: Boolean = false) {
		if (pending.isEmpty() || (!force && now() - lastFlush < 300)) return
		lastFlush = now()
		if (!room.solo) for (chunk in pending.chunked(400)) {
			val arr = JsonArray().apply { chunk.forEach { add(it) } }
			room.send("pict.px", JsonObject().apply { addProperty("r", round); add("p", arr) })
		}
		pending.clear()
	}

	private fun clearCanvas() {
		canvas.fill(Pictionary.ERASER)
		pending.clear()
		if (!room.solo) room.send("pict.clear", JsonObject().apply { addProperty("r", round) })
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (super.mouseClicked(event, doubleClick)) return true
		if (event.button() == 0 && iDraw && phase == Phase.DRAW) {
			painting = true
			lastCell = -1
			paintAt(event.x(), event.y())
			return true
		}
		return false
	}

	override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
		if (painting) { paintAt(event.x(), event.y()); return true }
		return super.mouseDragged(event, dx, dy)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (painting) { painting = false; lastCell = -1; flush(force = true); return true }
		return super.mouseReleased(event)
	}

	// ------------------------------------------------------------------ loop

	private fun update() {
		flush()
		val t = now() - phaseStart
		when (phase) {
			Phase.DRAW -> if ((room.solo || room.isHost) && t >= drawMs) {
				if (room.solo) endRound() else once("$seed e$round") { room.send("pict.end", JsonObject().apply { addProperty("r", round) }) }
			}
			Phase.REVEAL -> if (t >= Pictionary.REVEAL_MS) {
				if (round + 1 >= order.size) finish() else beginRound(round + 1)
			}
			else -> {}
		}
	}

	// ------------------------------------------------------------------ input

	override fun onKey(event: KeyEvent): Boolean {
		if (phase == Phase.DRAW && !iDraw) when (event.key()) {
			GLFW.GLFW_KEY_BACKSPACE -> { input = input.dropLast(1); return true }
			GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { sendGuess(); return true }
		}
		return super.onKey(event)
	}

	override fun onChar(event: CharacterEvent): Boolean {
		if (phase != Phase.DRAW || iDraw || me in solved || !event.isAllowedChatCharacter()) return false
		if (input.length < 24) input += event.codepointAsString()
		return true
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val top = header(g, "Skyblock Pictionary", if (room.solo) "sketch practice" else "${order.distinct().size} players")
		val cx = width / 2
		if (phase == Phase.WAITING) { centered(g, "§7Waiting for the host…", cx, height / 2, Theme.TEXT); return }
		val t = now() - phaseStart

		// Canvas, as big as fits between the palette column (left) and the side panel (right)
		val sideW = 120
		val paletteW = 30
		cell = minOf((width - sideW - paletteW - 30) / Pictionary.COLS, (height - top - 62) / Pictionary.ROWS).coerceIn(2, 8)
		val cw = cell * Pictionary.COLS
		val ch = cell * Pictionary.ROWS
		cx0 = paletteW + (width - sideW - 10 - paletteW - cw) / 2
		cy0 = top + 32
		val wx = cx0 + cw / 2

		// Word line: the drawer (and everyone after the round) sees the word, guessers see blanks + hints over time.
		val wordLine = when {
			phase == Phase.OVER -> "§6§lGame over!"
			iDraw || phase == Phase.REVEAL || me in solved -> (if (phase == Phase.REVEAL) "§7The word was " else if (iDraw) "§7Draw: " else "§a✔ ") + "§f§l$word"
			else -> "§f" + Pictionary.mask(word, hintLetters(t))
		}
		centered(g, wordLine, wx, top + 5, Theme.TEXT)
		val sub = when {
			phase == Phase.OVER -> ""
			room.solo -> "§8Round ${round + 1}/${order.size} · practice"
			iDraw -> "§8Round ${round + 1}/${order.size} · you are drawing"
			else -> "§8Round ${round + 1}/${order.size} · §7${room.nameOf(drawer ?: me)}§8 is drawing"
		}
		if (sub.isNotEmpty()) centered(g, font.plainSubstrByWidth(sub, cw + paletteW), wx, top + 15, Theme.TEXT)
		if (phase == Phase.DRAW) {
			val half = minOf(90, cw / 2)
			val f = (1f - t.toFloat() / drawMs).coerceIn(0f, 1f)
			g.fill(wx - half, top + 25, wx + half, top + 27, 0xFF2A1A44.toInt())
			g.fill(wx - half, top + 25, wx - half + (2 * half * f).toInt(), top + 27, if (f > 0.3f) GameColors.GREEN else GameColors.RED)
		}
		g.fill(cx0 - 2, cy0 - 2, cx0 + cw + 2, cy0 + ch + 2, GameColors.LINE)
		g.fill(cx0, cy0, cx0 + cw, cy0 + ch, 0xFFF4F1E8.toInt())
		for (r in 0 until Pictionary.ROWS) for (c in 0 until Pictionary.COLS) {
			val v = canvas[r * Pictionary.COLS + c]
			if (v == Pictionary.ERASER) continue
			val x = cx0 + c * cell
			val y = cy0 + r * cell
			g.fill(x, y, x + cell, y + cell, Pictionary.PALETTE[v])
		}

		// Tools (drawer) or guess box (guessers) under the canvas
		val by = cy0 + ch + 6
		if (phase == Phase.DRAW && iDraw) drawTools(g, by, mouseX, mouseY)
		else if (phase == Phase.DRAW && !room.solo) drawGuessBox(g, by, mouseX, mouseY)

		// Side panel: scores and the guess feed
		val sx = width - sideW - 8
		val players = order.distinct()
		panel(g, sx, top + 4, sideW, 16 + players.size * 11, "Scores")
		players.sortedByDescending { scores[it] ?: 0 }.forEachIndexed { i, id ->
			val mark = when { id == drawer && phase == Phase.DRAW -> "§d✎ "; id in solved -> "§a✔ "; else -> "" }
			val s = (scores[id] ?: 0).toString()
			g.text(font, mark + (if (id == me) "§e" else "§f") + font.plainSubstrByWidth(room.nameOf(id), sideW - 30), sx + 6, top + 23 + i * 11, Theme.TEXT, false)
			g.text(font, s, sx + sideW - 6 - font.width(s), top + 23 + i * 11, GameColors.GOLD, false)
		}
		val fy = top + 26 + players.size * 11
		if (!room.solo) {
			panel(g, sx, fy, sideW, 16 + 9 * 10, "Guesses")
			feed.forEachIndexed { i, l -> g.text(font, font.plainSubstrByWidth(l.text, sideW - 10), sx + 5, fy + 19 + i * 10, Theme.TEXT, false) }
		}

		if (phase == Phase.OVER) drawOver(g, cx0 + cw / 2, cy0 + ch / 2 - 20, mouseX, mouseY)
	}

	/** Guessers get a letter every 20 s (never more than a third of the word). */
	private fun hintLetters(t: Long): Set<Int> {
		val letters = word.indices.filter { word[it].isLetterOrDigit() }
		val count = minOf((t / 20_000).toInt(), letters.size / 3)
		return letters.shuffled(Random(seed + round)).take(count).toSet()
	}

	private fun drawTools(g: GuiGraphicsExtractor, y: Int, mx: Int, my: Int) {
		// Palette: two columns of swatches left of the canvas
		val px = cx0 - 28
		for (i in Pictionary.PALETTE.indices) {
			val sx = px + (i % 2) * 12
			val sy = cy0 + (i / 2) * 12
			g.fill(sx, sy, sx + 11, sy + 11, if (color == i) Theme.TEXT else GameColors.LINE)
			g.fill(sx + 1, sy + 1, sx + 10, sy + 10, Pictionary.PALETTE[i])
			clickArea(sx, sy, 11, 11) { color = i }
		}
		// Tools row under the canvas
		var x = cx0
		button(g, x, y, 38, 14, "Erase", mx, my, color = if (color == Pictionary.ERASER) Theme.accent else 0xFF3A3050.toInt()) { color = Pictionary.ERASER }
		x += 42
		for (b in listOf(1, 2, 3)) {
			button(g, x, y, 14, 14, "$b", mx, my, color = if (brush == b) Theme.accent else 0xFF3A3050.toInt(), hint = "Brush size") { brush = b }
			x += 16
		}
		button(g, x + 4, y, 32, 14, "Clear", mx, my, color = GameColors.RED) { clearCanvas() }
		if (room.solo) button(g, x + 40, y, 32, 14, "Done", mx, my, color = GameColors.GREEN, hint = "Next word") { endRound() }
	}

	private fun drawGuessBox(g: GuiGraphicsExtractor, y: Int, mx: Int, my: Int) {
		val w = minOf(200, Pictionary.COLS * cell - 56)
		val x = cx0
		if (me in solved) { g.text(font, "§aYou got it! §7Waiting for the others…", x, y + 4, Theme.TEXT, false); return }
		g.fill(x, y, x + w, y + 16, 0xFF100A1C.toInt())
		g.outline(x, y, w, 16, Theme.rainbow(0f, 0.5f))
		val caret = if ((now() / 500) % 2 == 0L) "_" else ""
		g.text(font, if (input.isEmpty()) "§8Type your guess…$caret" else font.plainSubstrByWidth(input, w - 10) + caret, x + 4, y + 4, Theme.TEXT, false)
		button(g, x + w + 4, y, 48, 16, "Guess", mx, my, color = GameColors.GREEN) { sendGuess() }
	}

	private fun drawOver(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		g.fill(cx - 110, y - 6, cx + 110, y + 40, 0xE0121218.toInt())
		if (room.solo) centered(g, "§aNice sketches! §6+2 Tokens", cx, y, Theme.TEXT)
		else {
			val best = scores.values.maxOrNull() ?: 0
			val winners = scores.filterValues { it == best }.keys.map { room.nameOf(it) }
			centered(g, "§6§l${winners.joinToString(" & ")} ${if (winners.size == 1) "wins" else "win"}! §7($best)", cx, y, Theme.TEXT)
		}
		if (room.solo || room.isHost) {
			button(g, cx - 100, y + 16, 92, 16, "Play again", mx, my, color = GameColors.GREEN) { if (room.solo) start(Random.nextLong()) else requestStart() }
		} else centered(g, "§7Waiting for the host…", cx, y + 20, Theme.TEXT)
		button(g, cx + 8, y + 16, 92, 16, "Leave", mx, my, color = GameColors.RED) { room.quit(); onClose() }
	}

	/** Test hooks. */
	val debugPhase get() = phase
	fun debugDone() = endRound()
	fun debugScribble() {
		// A little sword: a diagonal blade with a guard and handle.
		color = 2
		for (i in 0 until 18) stamp(14 + i, 26 - i)
		color = 12
		for (i in 0 until 5) stamp(12 + i, 22 + i)
		color = 5
		stamp(12, 28); stamp(13, 27)
		flush(force = true)
	}

	override fun removed() {
		room.leave()
		super.removed()
	}
}
