package dev.skyparty.minigames.reaction

import com.google.gson.JsonObject
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.sin

/**
 * Reaction Duel: the host sends "reaction.round.rng"; every client derives the same random delay from the
 * roll, shows red, then green. Clients measure their own reaction time and send "reaction.click".
 * Multiplayer is best of 5 (first to 3 points, a false start costs a point). Solo tracks your personal best.
 */
object ReactionDuel : Minigame {
	override val id = "reaction"
	override val name = "Reaction Duel"
	override val description = "Wait for green, then click faster than everyone else."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = true
	override val length = dev.skyparty.minigames.GameLength.SHORT

	override fun open(room: Net.Room?) = MinigameHub.openScreen(ReactionScreen(LobbyRoom.of(room, id)))
}

class ReactionScreen(val room: LobbyRoom) : GameScreen("Reaction Duel", room) {
	enum class Phase { READY, WAITING, GO, ROUND_END, MATCH_END }

	// Solo is endless practice: nothing to keep when you close it.
	override fun inProgress() = !room.solo && phase != Phase.MATCH_END
	class Result(val ms: Int, val foul: Boolean)

	var phase = Phase.READY
		private set
	var round = 0
		private set

	private var waitStart = 0L
	private var delayMs = 0L
	private var goNanos = 0L
	private var goAt = 0L
	private var roundEndAt = 0L
	private var startAt = room.now() + 2500
	private var myClicked = false
	private var myResult: Result? = null
	private var roundWinner: UUID? = null
	private var newPb = false

	private val results = linkedMapOf<UUID, Result>()
	private val points = mutableMapOf<UUID, Int>()
	private val lastTimes = mutableMapOf<UUID, String>()
	private val soloTimes = ArrayDeque<Int>()

	private val solo get() = room.solo
	/** Best of 5, first to 3. */
	private val rounds = 5
	private val winPoints = 3

	init {
		room.onMessage = ::onMessage
		room.onMembersChanged = { if (phase == Phase.WAITING || phase == Phase.GO) checkComplete() }
	}

	// ------------------------------------------------------------------ protocol

	private fun onMessage(msg: Net.Message) {
		val p = msg.payload
		when (msg.type) {
			"reaction.round.rng" -> {
				if (msg.from != room.host) return
				round = p["round"]?.asInt ?: (round + 1)
				val roll = p["roll"]?.asDouble ?: Math.random()
				delayMs = 1200L + (roll * 3300).toLong()
				waitStart = room.now()
				results.clear()
				myClicked = false
				myResult = null
				roundWinner = null
				newPb = false
				phase = Phase.WAITING
			}
			"reaction.click" -> {
				if (p["round"]?.asInt != round || msg.from in results) return
				val res = Result(p["ms"]?.asInt ?: 9999, p["foul"]?.asBoolean == true)
				results[msg.from] = res
				if (res.foul && !solo) points[msg.from] = ((points[msg.from] ?: 0) - 1).coerceAtLeast(0)
				checkComplete()
			}
			"reaction.timeout" -> if (p["round"]?.asInt == round && (phase == Phase.WAITING || phase == Phase.GO)) endRound()
			"reaction.reset" -> if (msg.from == room.host) {
				points.clear()
				lastTimes.clear()
				round = 0
				phase = Phase.READY
				startAt = room.now() + 2000
			}
		}
	}

	private fun sendRound(n: Int) {
		if (!room.isHost) return
		room.send("reaction.round.rng", JsonObject().apply { addProperty("round", n) })
	}

	private fun checkComplete() {
		if (phase != Phase.WAITING && phase != Phase.GO) return
		if (results.keys.containsAll(room.members)) endRound()
	}

	private fun endRound() {
		val valid = results.filterValues { !it.foul }
		val winner = valid.minByOrNull { it.value.ms }?.key
		roundWinner = winner
		for (id in room.members) {
			val r = results[id]
			lastTimes[id] = when {
				r == null -> "—"
				r.foul -> "§cfalse start"
				else -> "${r.ms} ms"
			}
		}
		roundEndAt = room.now()
		val mine = results[Net.selfId]
		if (solo) {
			if (mine != null && !mine.foul) {
				soloTimes.addFirst(mine.ms)
				while (soloTimes.size > 5) soloTimes.removeLast()
				newPb = MinigameStore.submit(ReactionDuel.id, mine.ms.toDouble(), lowerIsBetter = true, display = "${mine.ms} ms")
				if (newPb) MinigameStore.addTokens(5)
			}
			phase = Phase.ROUND_END
			return
		}
		if (winner != null) {
			points[winner] = (points[winner] ?: 0) + 1
			if (winner == Net.selfId) MinigameStore.addTokens(2)
		}
		if (mine != null && !mine.foul) {
			MinigameStore.submit(ReactionDuel.id, mine.ms.toDouble(), lowerIsBetter = true, display = "${mine.ms} ms")
		}
		val over = points.values.any { it >= winPoints } || round >= rounds
		phase = if (over) Phase.MATCH_END else Phase.ROUND_END
		if (over && matchWinner() == Net.selfId) MinigameStore.addTokens(15)
	}

	private fun matchWinner(): UUID? = room.members.maxByOrNull { points[it] ?: 0 }
		?.takeIf { best -> room.members.count { (points[it] ?: 0) == (points[best] ?: 0) } == 1 }

	/** The player's click / key press. */
	fun react() {
		val now = room.now()
		updatePhase()
		when (phase) {
			Phase.READY -> if (solo) sendRound(round + 1)
			Phase.WAITING -> if (!myClicked) {
				myClicked = true
				myResult = Result(9999, true)
				room.send("reaction.click", JsonObject().apply {
					addProperty("round", round)
					addProperty("ms", 9999)
					addProperty("foul", true)
				})
			}
			Phase.GO -> if (!myClicked) {
				myClicked = true
				val ms = ((room.nanos() - goNanos) / 1_000_000).toInt()
				myResult = Result(ms, false)
				room.send("reaction.click", JsonObject().apply {
					addProperty("round", round)
					addProperty("ms", ms)
				})
			}
			Phase.ROUND_END -> if (solo && now - roundEndAt > 350) sendRound(round + 1)
			Phase.MATCH_END -> {}
		}
	}

	private fun updatePhase() {
		if (phase == Phase.WAITING && room.now() >= waitStart + delayMs) {
			phase = Phase.GO
			goNanos = room.nanos()
			goAt = room.now()
		}
	}

	override fun gameTick() {
		updatePhase()
		if (!room.isHost || solo) return
		val now = room.now()
		when (phase) {
			Phase.READY -> if (now >= startAt && round == 0) {
				startAt = Long.MAX_VALUE
				sendRound(1)
			}
			Phase.ROUND_END -> if (now - roundEndAt > 2500 && roundEndAt != 0L) {
				roundEndAt = 0L
				sendRound(round + 1)
			}
			Phase.GO -> if (now - goAt > 3000 && goAt != 0L) {
				goAt = 0L
				room.send("reaction.timeout", JsonObject().apply { addProperty("round", round) })
			}
			else -> {}
		}
	}

	// ------------------------------------------------------------------ input

	override fun onClickAnywhere(mx: Double, my: Double): Boolean {
		if (mx >= arena[0] && mx < arena[0] + arena[2] && my >= arena[1] && my < arena[1] + arena[3]) {
			react()
			return true
		}
		return false
	}

	override fun onKey(event: KeyEvent): Boolean {
		if (event.key() == GLFW.GLFW_KEY_SPACE) {
			react()
			return true
		}
		return super.onKey(event)
	}

	override fun removed() {
		room.leave()
		super.removed()
	}

	// ------------------------------------------------------------------ drawing

	private var arena = intArrayOf(0, 0, 0, 0)

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		updatePhase()
		val sub = if (solo) "Practice" else "Round ${round.coerceAtLeast(1)} / $rounds · first to $winPoints"
		val top = header(g, "Reaction Duel", sub)
		val margin = 8
		val sideW = (width * 0.3f).toInt().coerceIn(110, 160)
		val ax = margin
		val ay = top + margin
		val aw = width - sideW - margin * 3
		val ah = height - ay - margin
		arena = intArrayOf(ax, ay, aw, ah)
		drawArena(g, ax, ay, aw, ah, mouseX, mouseY)
		val sx = width - sideW - margin
		if (solo) drawSoloSide(g, sx, ay, sideW, ah) else drawScoreboard(g, sx, ay, sideW, ah)
	}

	private fun drawArena(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
		val now = room.now()
		val cx = x + w / 2
		val cy = y + h / 2
		val color = when (phase) {
			Phase.WAITING -> 0xFFB8323A.toInt()
			Phase.GO -> 0xFF2FAE5A.toInt()
			else -> GameColors.PANEL
		}
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, Theme.SHADOW)
		g.fill(x, y, x + w, y + h, color)
		g.outline(x, y, w, h, ColorUtil.shade(if (phase == Phase.WAITING || phase == Phase.GO) color else GameColors.LINE, 1.25f))

		when (phase) {
			Phase.READY -> {
				if (solo) {
					bigText(g, "Ready?", cx, cy - 26, 3f, Theme.TEXT)
					centered(g, "Click (or press Space) to start.", cx, cy + 6, Theme.TEXT_DIM)
					centered(g, "Wait for §agreen§7, then click as fast as you can.", cx, cy + 18, Theme.TEXT_DIM)
				} else {
					val left = ((startAt - now) / 1000 + 1).coerceIn(0, 9)
					bigText(g, if (startAt == Long.MAX_VALUE) "…" else "$left", cx, cy - 30, 4f, Theme.accent)
					centered(g, "Get ready! Best of $rounds – a false start costs a point.", cx, cy + 10, Theme.TEXT_DIM)
				}
			}
			Phase.WAITING -> {
				if (myResult?.foul == true) {
					bigText(g, "False start!", cx, cy - 20, 3f, Theme.TEXT)
					centered(g, if (solo) "Too soon – wait for green." else "-1 point. Waiting for the others…", cx, cy + 12, 0xFFFFD0D0.toInt())
				} else {
					val dots = ".".repeat(((now / 300) % 4).toInt())
					bigText(g, "Wait$dots", cx, cy - 20, 3.5f, Theme.TEXT)
					centered(g, "Don't click yet!", cx, cy + 14, 0xFFFFD0D0.toInt())
				}
			}
			Phase.GO -> {
				val mine = myResult
				if (mine != null && !mine.foul) {
					bigText(g, "${mine.ms} ms", cx, cy - 20, 3.5f, Theme.TEXT)
					centered(g, "Waiting for the others…", cx, cy + 14, 0xFFD8FFE4.toInt())
				} else if (mine?.foul == true) {
					bigText(g, "False start!", cx, cy - 20, 3f, Theme.TEXT)
				} else {
					val pulse = 3.5f + sin(now / 60.0).toFloat() * 0.25f
					bigText(g, "CLICK!", cx, cy - 18, pulse, Theme.TEXT)
				}
			}
			Phase.ROUND_END -> drawRoundResult(g, cx, cy, now)
			Phase.MATCH_END -> drawMatchEnd(g, x, y, w, h, cx, cy, mx, my)
		}
	}

	private fun drawRoundResult(g: GuiGraphicsExtractor, cx: Int, cy: Int, now: Long) {
		val mine = results[Net.selfId]
		when {
			mine == null -> bigText(g, "Too slow!", cx, cy - 28, 3f, Theme.TEXT_DIM)
			mine.foul -> bigText(g, "False start!", cx, cy - 28, 3f, GameColors.RED)
			else -> bigText(g, "${mine.ms} ms", cx, cy - 30, 4f, rating(mine.ms).second)
		}
		if (mine != null && !mine.foul) centered(g, rating(mine.ms).first, cx, cy + 6, rating(mine.ms).second)
		if (solo) {
			if (newPb) centered(g, "§6§lNew personal best! §e+5 Tokens", cx, cy + 20, Theme.TEXT)
			centered(g, "Click to go again", cx, cy + 36, Theme.TEXT_DIM)
		} else {
			val w = roundWinner
			val text = if (w == null) "Nobody scored this round." else "${if (w == Net.selfId) "§aYou" else "§e" + room.nameOf(w)} §7won round $round"
			centered(g, text, cx, cy + 20, Theme.TEXT)
			val next = ((2500 - (now - roundEndAt)) / 1000 + 1).coerceIn(1, 3)
			centered(g, "Next round in $next…", cx, cy + 34, Theme.TEXT_DIM)
		}
	}

	private fun drawMatchEnd(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, cx: Int, cy: Int, mx: Int, my: Int) {
		val winner = matchWinner()
		val title = when (winner) {
			null -> "Draw!"
			Net.selfId -> "You win!"
			else -> "${room.nameOf(winner)} wins!"
		}
		bigText(g, title, cx, y + 16, 3f, if (winner == Net.selfId) GameColors.GOLD else Theme.TEXT)
		if (winner == Net.selfId) centered(g, "§e+15 Tokens", cx, y + 44, Theme.TEXT)
		val ranked = room.members.sortedByDescending { points[it] ?: 0 }
		var ry = y + 58
		for ((i, id) in ranked.withIndex()) {
			if (ry > y + h - 34) break
			val medal = when (i) { 0 -> GameColors.GOLD; 1 -> GameColors.SILVER; 2 -> GameColors.BRONZE; else -> Theme.TEXT_DIM }
			val line = "${i + 1}. ${room.nameOf(id)}  §7${points[id] ?: 0} pts"
			centered(g, line, cx, ry, medal)
			ry += 11
		}
		val bw = 70
		if (room.isHost) {
			button(g, cx - bw - 3, y + h - 22, bw, 15, "Rematch", mx, my, color = GameColors.GREEN) {
				room.send("reaction.reset")
			}
		}
		button(g, if (room.isHost) cx + 3 else cx - bw / 2, y + h - 22, bw, 15, "Close", mx, my, color = GameColors.RED) { room.quit(); onClose() }
	}

	private fun rating(ms: Int): Pair<String, Int> = when {
		ms < 180 -> "Lightning fast!" to GameColors.GOLD
		ms < 250 -> "Great!" to 0xFF7CF0A0.toInt()
		ms < 350 -> "Good" to Theme.TEXT
		else -> "Keep practicing" to Theme.TEXT_DIM
	}

	private fun drawSoloSide(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
		panel(g, x, y, w, h, "Practice")
		var cy = y + 22
		val best = MinigameStore.best(ReactionDuel.id)
		g.text(font, "Personal best", x + 6, cy, Theme.TEXT_DIM, false)
		cy += 11
		g.text(font, "§6§l" + (best?.display ?: "—"), x + 6, cy, Theme.TEXT, true)
		cy += 16
		g.text(font, "Last rounds", x + 6, cy, Theme.TEXT_DIM, false)
		cy += 11
		if (soloTimes.isEmpty()) g.text(font, "§8none yet", x + 8, cy, Theme.TEXT, false)
		for ((i, t) in soloTimes.withIndex()) {
			g.text(font, "${if (i == 0) "§f" else "§7"}$t ms", x + 8, cy, Theme.TEXT, false)
			cy += 10
		}
		if (soloTimes.isNotEmpty()) {
			cy += 4
			g.text(font, "§7Average §f${soloTimes.average().toInt()} ms", x + 6, cy, Theme.TEXT, false)
		}
		g.text(font, "§8Space works too", x + 6, y + h - 12, Theme.TEXT, false)
	}

	private fun drawScoreboard(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
		panel(g, x, y, w, h, "Scoreboard")
		var cy = y + 22
		val ranked = room.members.sortedByDescending { points[it] ?: 0 }
		for (id in ranked) {
			if (cy > y + h - 22) break
			val pts = points[id] ?: 0
			val clicked = id in results && (phase == Phase.WAITING || phase == Phase.GO)
			val name = font.plainSubstrByWidth(room.nameOf(id), w - 50)
			g.text(font, (if (id == Net.selfId) "§b" else "§f") + name, x + 6, cy, Theme.TEXT, false)
			// Point pips
			for (i in 0 until winPoints) {
				val px = x + w - 8 - (winPoints - i) * 7
				g.fill(px, cy + 1, px + 5, cy + 6, if (i < pts) GameColors.GOLD else 0xFF3A3A46.toInt())
			}
			cy += 10
			val sub = if (clicked) "§aclicked" else "§7" + (lastTimes[id] ?: "")
			g.text(font, sub, x + 10, cy, Theme.TEXT, false)
			cy += 13
		}
	}

	companion object {
	}
}
