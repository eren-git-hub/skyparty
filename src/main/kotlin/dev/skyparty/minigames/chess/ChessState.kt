package dev.skyparty.minigames.chess

/** Outcome of a game. [winnerWhite] is null for draws. */
data class Result(val winnerWhite: Boolean?, val reason: String) {
	val headline: String get() = when (winnerWhite) {
		true -> "White wins"
		false -> "Black wins"
		null -> "Draw"
	}
}

/**
 * A game on top of [Position]: move history with SAN, captured pieces, repetition tracking
 * and automatic game end (checkmate, stalemate, 50-move rule, threefold repetition, insufficient material).
 */
class ChessState(val startFen: String = Position.START_FEN) {
	val pos: Position = Position.fromFen(startFen)
	val moves = ArrayList<Move>()
	val sans = ArrayList<String>()
	/** Captured piece per ply (0 if none), signed like board pieces. */
	val captures = ArrayList<Int>()
	private val repetitions = HashMap<String, Int>()
	var result: Result? = null
		private set
	/** Legal moves cache for the current position. */
	var legal: List<Move> = pos.legalMoves()
		private set

	init {
		repetitions[pos.repetitionKey()] = 1
		updateResult()
	}

	val ply: Int get() = moves.size
	val over: Boolean get() = result != null
	val lastMove: Move? get() = moves.lastOrNull()

	/** Plays [m] if legal and the game isn't over; returns false otherwise. */
	fun play(m: Move): Boolean {
		if (over || m !in legal) return false
		sans += pos.san(m)
		captures += pos.make(m)
		moves += m
		val key = pos.repetitionKey()
		repetitions[key] = (repetitions[key] ?: 0) + 1
		legal = pos.legalMoves()
		updateResult()
		return true
	}

	fun playUci(uci: String): Boolean = Move.parseUci(uci)?.let { play(it) } ?: false

	/** Plays a SAN move (exact match against generated SAN, ignoring +/#). */
	fun playSan(san: String): Boolean {
		val clean = san.trimEnd('+', '#', '!', '?')
		val m = legal.firstOrNull { pos.san(it).trimEnd('+', '#') == clean } ?: return false
		return play(m)
	}

	fun end(r: Result) {
		if (result == null) result = r
	}

	private fun updateResult() {
		if (result != null) return
		val white = pos.whiteToMove
		result = when {
			legal.isEmpty() && pos.inCheck() -> Result(!white, "Checkmate")
			legal.isEmpty() -> Result(null, "Stalemate")
			pos.insufficientMaterial() -> Result(null, "Insufficient material")
			pos.halfmove >= 100 -> Result(null, "Fifty-move rule")
			(repetitions[pos.repetitionKey()] ?: 0) >= 3 -> Result(null, "Threefold repetition")
			else -> null
		}
	}

	/** Pieces captured BY the given color (i.e. opponent pieces), sorted by value. */
	fun capturedBy(white: Boolean): List<Int> =
		captures.filter { it != 0 && (it > 0) != white }.sortedByDescending { Piece.VALUE[Piece.type(it)] * 10 + Piece.type(it) }

	/** Material balance in pawns from white's view. */
	fun materialDiff(): Int {
		var d = 0
		for (p in pos.board) if (p != 0) d += (if (p > 0) 1 else -1) * Piece.VALUE[Piece.type(p)]
		return Math.round(d / 100.0).toInt()
	}

	/** Moves in "1. e4 e5 2. Nf3" form, one entry per full move. */
	fun moveRows(): List<Triple<Int, String, String>> {
		val rows = ArrayList<Triple<Int, String, String>>()
		val blackFirst = !Position.fromFen(startFen).whiteToMove
		var i = 0
		var n = Position.fromFen(startFen).fullmove
		if (blackFirst && sans.isNotEmpty()) { rows += Triple(n, "...", sans[0]); i = 1; n++ }
		while (i < sans.size) {
			rows += Triple(n, sans[i], sans.getOrElse(i + 1) { "" })
			i += 2; n++
		}
		return rows
	}
}
