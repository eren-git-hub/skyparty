package dev.skyparty.minigames.chess

import com.google.gson.JsonObject
import dev.skyparty.Skyparty
import dev.skyparty.net.Net
import net.minecraft.client.Minecraft
import java.util.UUID
import kotlin.random.Random

/**
 * One chess session (solo vs AI, local hot-seat, or online in a [Net.Room]). Lives independently of the
 * screen so an online game keeps running when the board is closed and can be reopened with /sp chess.
 *
 * Online protocol (all messages echo to the sender; every client validates everything):
 *  - "chess.setup.rng" {clock: seconds (0 = unlimited)} + server "roll": roll < 0.5 → sender is white.
 *  - "chess.move" {uci, ply, left: mover's remaining ms or -1}: applied only if ply matches, the sender
 *    owns the side to move and the move is legal.
 *  - "chess.resign", "chess.draw.offer", "chess.draw.accept", "chess.draw.decline".
 *  - "chess.flag" {white}: a clock hit zero (accepted if the local clock agrees within a tolerance).
 *  - "chess.claim": win claim after the opponent left the room for [CLAIM_TIMEOUT_MS].
 */
class ChessSession(val room: Net.Room?, val mode: Mode, val settings: Settings) {
	enum class Mode(val label: String) { AI("vs AI"), HOTSEAT("Hot-seat"), ONLINE("Online") }
	enum class ColorChoice(val label: String) { WHITE("White"), BLACK("Black"), RANDOM("Random") }
	enum class Clock(val label: String, val seconds: Int) { BLITZ5("5+0", 300), RAPID10("10+0", 600), NONE("Unlimited", 0) }

	data class Settings(
		val clock: Clock = Clock.NONE,
		val difficulty: ChessAI.Difficulty = ChessAI.Difficulty.MEDIUM,
		val color: ColorChoice = ColorChoice.WHITE,
		val startFen: String = Position.START_FEN,
	)

	var state: ChessState? = null
		private set

	/** Online player ids (both = self in a loopback room). */
	var whiteId: UUID? = null
		private set
	var blackId: UUID? = null
		private set
	private val names = HashMap<UUID, String>()

	/** For AI mode: which color the AI plays. */
	var aiWhite = false
		private set
	@Volatile private var aiToken = 0
	var aiThinking = false
		private set

	// Clocks (ms). [turnStart] is when the side to move started thinking.
	var clockMs = 0L
		private set
	private var whiteMs = 0L
	private var blackMs = 0L
	private var turnStart = 0L
	private var flagSent = false

	/** Color that currently offers a draw, or null. */
	var drawOfferWhite: Boolean? = null
		private set
	var opponentGoneSince: Long? = null
		private set

	/** Short notification shown under the move list; cleared after a few seconds. */
	var notice: String? = null
		private set
	private var noticeUntil = 0L

	/** Incremented on every applied move (screen uses it for animations and auto-scroll). */
	var moveCounter = 0
		private set
	var lastMoveAnimated = true
		private set

	init {
		room?.let { r ->
			r.onMessage = { msg -> runCatching { onMessage(msg) }.onFailure { Skyparty.LOGGER.warn("chess: bad message {}", msg.type, it) } }
			r.onMembersChanged = { checkMembers() }
		}
		if (mode != Mode.ONLINE) startLocal(settings)
	}

	val online get() = mode == Mode.ONLINE
	val started get() = state != null

	// ------------------------------------------------------------------ ownership

	/** Can this client move pieces of the given color right now? */
	fun controls(white: Boolean): Boolean = when (mode) {
		Mode.HOTSEAT -> true
		Mode.AI -> white != aiWhite
		Mode.ONLINE -> (if (white) whiteId else blackId) == Net.selfId
	}

	/** The color shown at the bottom by default. */
	val bottomWhite: Boolean get() = when (mode) {
		Mode.HOTSEAT -> true
		Mode.AI -> !aiWhite
		Mode.ONLINE -> whiteId == Net.selfId || blackId != Net.selfId
	}

	fun playerName(white: Boolean): String = when (mode) {
		Mode.HOTSEAT -> if (white) "White" else "Black"
		Mode.AI -> if (white == aiWhite) "Skyparty AI (${settings.difficulty.label})" else Net.selfName
		Mode.ONLINE -> (if (white) whiteId else blackId)?.let { names[it] ?: if (it == Net.selfId) Net.selfName else "Opponent" } ?: "?"
	}

	private fun opponentId(): UUID? = when {
		whiteId == Net.selfId -> blackId
		blackId == Net.selfId -> whiteId
		else -> null
	}

	// ------------------------------------------------------------------ setup

	private fun startLocal(s: Settings) {
		aiWhite = when (s.color) {
			ColorChoice.WHITE -> false
			ColorChoice.BLACK -> true
			ColorChoice.RANDOM -> Random.nextBoolean()
		}
		begin(s.startFen, s.clock.seconds)
	}

	private fun begin(fen: String, clockSeconds: Int) {
		state = ChessState(fen)
		clockMs = clockSeconds * 1000L
		whiteMs = clockMs
		blackMs = clockMs
		turnStart = System.currentTimeMillis()
		flagSent = false
		drawOfferWhite = null
		aiToken++
		aiThinking = false
		moveCounter++
		lastMoveAnimated = false
	}

	/** Starts a new game: locally for solo modes, or by sending a setup roll in online mode. */
	fun newGame(clock: Clock = settings.clock) {
		if (online) {
			val p = JsonObject()
			p.addProperty("clock", clock.seconds)
			room?.send("chess.setup.rng", p)
		} else startLocal(settings.copy(clock = clock))
	}

	// ------------------------------------------------------------------ player actions

	/** Called by the screen when the local player makes a move. */
	fun submitMove(m: Move, animate: Boolean): Boolean {
		val st = state ?: return false
		if (st.over || !controls(st.pos.whiteToMove) || m !in st.legal) return false
		if (online) {
			val p = JsonObject()
			p.addProperty("uci", m.uci())
			p.addProperty("ply", st.ply)
			p.addProperty("left", if (clockMs > 0) remaining(st.pos.whiteToMove) else -1L)
			room?.send("chess.move", p)
			return true
		}
		applyMove(m, if (clockMs > 0) remaining(st.pos.whiteToMove) else -1L, animate)
		return true
	}

	fun resign() {
		val st = state ?: return
		if (st.over) return
		when (mode) {
			Mode.ONLINE -> room?.send("chess.resign")
			Mode.AI -> st.end(Result(aiWhite, "Resignation"))
			Mode.HOTSEAT -> st.end(Result(!st.pos.whiteToMove, "Resignation"))
		}
	}

	fun offerDraw() {
		val st = state ?: return
		if (st.over || drawOfferWhite != null) return
		when (mode) {
			Mode.ONLINE -> room?.send("chess.draw.offer")
			Mode.AI -> {
				// The AI accepts when it thinks it's worse (eval from its own point of view).
				val p = st.pos.copy()
				val eval = ChessAI.evaluate(p).let { if (p.whiteToMove == aiWhite) it else -it }
				if (eval < -150) st.end(Result(null, "Draw by agreement")) else toast("The AI declines the draw.")
			}
			Mode.HOTSEAT -> drawOfferWhite = st.pos.whiteToMove
		}
	}

	val canAcceptDraw: Boolean get() {
		val offer = drawOfferWhite ?: return false
		if (state?.over != false) return false
		return when (mode) {
			Mode.HOTSEAT -> state?.pos?.whiteToMove != offer
			Mode.ONLINE -> controls(!offer)
			Mode.AI -> false
		}
	}

	fun acceptDraw() {
		if (!canAcceptDraw) return
		if (online) room?.send("chess.draw.accept") else state?.end(Result(null, "Draw by agreement"))
	}

	fun declineDraw() {
		if (!canAcceptDraw) return
		if (online) room?.send("chess.draw.decline") else drawOfferWhite = null
	}

	val canClaimWin: Boolean get() {
		val gone = opponentGoneSince ?: return false
		return online && state?.over == false && System.currentTimeMillis() - gone >= CLAIM_TIMEOUT_MS
	}

	fun claimSecondsLeft(): Int? = opponentGoneSince?.let { ((CLAIM_TIMEOUT_MS - (System.currentTimeMillis() - it)) / 1000).toInt().coerceAtLeast(0) }

	fun claimWin() {
		if (canClaimWin) room?.send("chess.claim")
	}

	fun leave() {
		aiToken++
		room?.leave()
	}

	// ------------------------------------------------------------------ clocks

	fun remaining(white: Boolean): Long {
		if (clockMs <= 0) return -1
		val st = state ?: return clockMs
		val base = if (white) whiteMs else blackMs
		val running = !st.over && st.pos.whiteToMove == white && st.ply >= 1
		return if (running) (base - (System.currentTimeMillis() - turnStart)).coerceAtLeast(0) else base
	}

	fun clockRunning(white: Boolean): Boolean {
		val st = state ?: return false
		return clockMs > 0 && !st.over && st.pos.whiteToMove == white && st.ply >= 1
	}

	private fun flagResult(white: Boolean, st: ChessState): Result =
		if (st.pos.hasMatingMaterial(!white)) Result(!white, "Time out") else Result(null, "Time out vs. insufficient material")

	// ------------------------------------------------------------------ applying

	private fun applyMove(m: Move, left: Long, animate: Boolean) {
		val st = state ?: return
		val moverWhite = st.pos.whiteToMove
		val now = System.currentTimeMillis()
		if (clockMs > 0 && st.ply >= 1) {
			val t = if (left >= 0) left else remaining(moverWhite)
			if (moverWhite) whiteMs = t else blackMs = t
		}
		if (!st.play(m)) return
		turnStart = now
		// A move answers (declines) a pending draw offer from the opponent.
		if (drawOfferWhite != null && drawOfferWhite != moverWhite) drawOfferWhite = null
		moveCounter++
		lastMoveAnimated = animate
	}

	fun tick() {
		val st = state ?: return
		if (noticeUntil in 1..System.currentTimeMillis()) { notice = null; noticeUntil = 0 }
		if (st.over) return
		// Clock flag
		if (clockMs > 0) {
			for (white in listOf(true, false)) {
				if (clockRunning(white) && remaining(white) <= 0) {
					if (online) {
						if (!flagSent) {
							flagSent = true
							val p = JsonObject(); p.addProperty("white", white)
							room?.send("chess.flag", p)
						}
					} else st.end(flagResult(white, st))
				}
			}
		}
		if (online) checkMembers()
		// AI turn
		if (mode == Mode.AI && st.pos.whiteToMove == aiWhite && !aiThinking && !st.over) {
			aiThinking = true
			val token = ++aiToken
			val snapshot = st.pos.copy()
			val difficulty = settings.difficulty
			val ply = st.ply
			Thread({
				val started = System.currentTimeMillis()
				val move = runCatching { ChessAI.bestMove(snapshot, difficulty) }.getOrNull()
				val wait = 350 - (System.currentTimeMillis() - started)
				if (wait > 0) Thread.sleep(wait)
				Minecraft.getInstance().execute {
					if (token != aiToken) return@execute
					aiThinking = false
					val cur = state ?: return@execute
					if (move != null && cur.ply == ply) applyMove(move, if (clockMs > 0) remaining(aiWhite) else -1L, true)
				}
			}, "Skyparty-ChessAI").apply { isDaemon = true }.start()
		}
	}

	private fun checkMembers() {
		val r = room ?: return
		val opp = opponentId() ?: return
		if (opp == Net.selfId) return
		if (opp in r.members) opponentGoneSince = null
		else if (opponentGoneSince == null) opponentGoneSince = System.currentTimeMillis()
	}

	private fun toast(text: String) {
		notice = text
		noticeUntil = System.currentTimeMillis() + 4000
	}

	// ------------------------------------------------------------------ network

	private fun colorOf(id: UUID, st: ChessState): Boolean? = when {
		whiteId == id && blackId == id -> st.pos.whiteToMove // loopback: acting for the side to move
		whiteId == id -> true
		blackId == id -> false
		else -> null
	}

	private fun onMessage(msg: Net.Message) {
		names[msg.from] = msg.fromName
		val p = msg.payload
		when (msg.type) {
			"chess.setup.rng" -> {
				val cur = state
				if (cur != null && !cur.over) return // a game is running: ignore late/duplicate setups
				val roll = p.get("roll")?.asDouble ?: return
				val other = room?.members?.firstOrNull { it != msg.from } ?: msg.from
				if (roll < 0.5) { whiteId = msg.from; blackId = other } else { whiteId = other; blackId = msg.from }
				begin(Position.START_FEN, p.get("clock")?.asInt ?: 0)
				opponentGoneSince = null
				toast(if (whiteId == Net.selfId && blackId == Net.selfId) "Game started (you play both sides)."
				else "Game started. You play ${if (whiteId == Net.selfId) "White" else "Black"}.")
			}
			"chess.move" -> {
				val st = state ?: return
				if (st.over) return
				val ply = p.get("ply")?.asInt ?: return
				if (ply != st.ply) return
				val owner = if (st.pos.whiteToMove) whiteId else blackId
				if (owner != msg.from) return
				val m = Move.parseUci(p.get("uci")?.asString ?: return) ?: return
				if (m !in st.legal) {
					Skyparty.LOGGER.warn("chess: rejected illegal move {} from {}", m, msg.fromName)
					return
				}
				val left = p.get("left")?.asLong ?: -1L
				applyMove(m, if (clockMs > 0 && left >= 0) left.coerceAtMost(clockMs) else -1L, msg.from != Net.selfId)
			}
			"chess.resign" -> {
				val st = state ?: return
				if (st.over) return
				val c = colorOf(msg.from, st) ?: return
				st.end(Result(!c, "Resignation"))
			}
			"chess.draw.offer" -> {
				val st = state ?: return
				if (st.over || drawOfferWhite != null) return
				drawOfferWhite = colorOf(msg.from, st) ?: return
				if (msg.from != Net.selfId) toast("${msg.fromName} offers a draw.")
			}
			"chess.draw.accept" -> {
				val st = state ?: return
				val offer = drawOfferWhite ?: return
				val c = colorOf(msg.from, st) ?: return
				if (st.over) return
				if (whiteId == blackId || c != offer) st.end(Result(null, "Draw by agreement"))
			}
			"chess.draw.decline" -> {
				val st = state ?: return
				val offer = drawOfferWhite ?: return
				val c = colorOf(msg.from, st) ?: return
				if (whiteId == blackId || c != offer) {
					drawOfferWhite = null
					if (msg.from != Net.selfId) toast("Draw offer declined.")
				}
			}
			"chess.flag" -> {
				val st = state ?: return
				if (st.over || clockMs <= 0) return
				val white = p.get("white")?.asBoolean ?: return
				if (remaining(white) <= FLAG_TOLERANCE_MS) st.end(flagResult(white, st))
			}
			"chess.claim" -> {
				val st = state ?: return
				if (st.over) return
				val c = colorOf(msg.from, st) ?: return
				val opp = if (c) blackId else whiteId
				if (opp != null && opp != msg.from && room?.members?.contains(opp) == false) st.end(Result(c, "Opponent left"))
			}
		}
	}

	companion object {
		const val CLAIM_TIMEOUT_MS = 60_000L
		const val FLAG_TOLERANCE_MS = 3_000L
	}
}
