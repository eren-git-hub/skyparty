package dev.skyparty.minigames.chess

import kotlin.random.Random

/** Small built-in opponent: alpha-beta minimax over material + piece-square tables. */
object ChessAI {
	enum class Difficulty(val label: String, val depth: Int, val noise: Int) {
		EASY("Easy", 1, 60), MEDIUM("Medium", 2, 15), HARD("Hard", 3, 0)
	}

	private const val MATE = 100_000

	/** Piece-square tables from white's view, index = rank * 8 + file (a1 = 0). */
	private val PAWN_T = table(
		0, 0, 0, 0, 0, 0, 0, 0,
		5, 10, 10, -20, -20, 10, 10, 5,
		5, -5, -10, 0, 0, -10, -5, 5,
		0, 0, 0, 20, 20, 0, 0, 0,
		5, 5, 10, 25, 25, 10, 5, 5,
		10, 10, 20, 30, 30, 20, 10, 10,
		50, 50, 50, 50, 50, 50, 50, 50,
		0, 0, 0, 0, 0, 0, 0, 0,
	)
	private val KNIGHT_T = table(
		-50, -40, -30, -30, -30, -30, -40, -50,
		-40, -20, 0, 5, 5, 0, -20, -40,
		-30, 5, 10, 15, 15, 10, 5, -30,
		-30, 0, 15, 20, 20, 15, 0, -30,
		-30, 5, 15, 20, 20, 15, 5, -30,
		-30, 0, 10, 15, 15, 10, 0, -30,
		-40, -20, 0, 0, 0, 0, -20, -40,
		-50, -40, -30, -30, -30, -30, -40, -50,
	)
	private val BISHOP_T = table(
		-20, -10, -10, -10, -10, -10, -10, -20,
		-10, 5, 0, 0, 0, 0, 5, -10,
		-10, 10, 10, 10, 10, 10, 10, -10,
		-10, 0, 10, 10, 10, 10, 0, -10,
		-10, 5, 5, 10, 10, 5, 5, -10,
		-10, 0, 5, 10, 10, 5, 0, -10,
		-10, 0, 0, 0, 0, 0, 0, -10,
		-20, -10, -10, -10, -10, -10, -10, -20,
	)
	private val ROOK_T = table(
		0, 0, 0, 5, 5, 0, 0, 0,
		-5, 0, 0, 0, 0, 0, 0, -5,
		-5, 0, 0, 0, 0, 0, 0, -5,
		-5, 0, 0, 0, 0, 0, 0, -5,
		-5, 0, 0, 0, 0, 0, 0, -5,
		-5, 0, 0, 0, 0, 0, 0, -5,
		5, 10, 10, 10, 10, 10, 10, 5,
		0, 0, 0, 0, 0, 0, 0, 0,
	)
	private val QUEEN_T = table(
		-20, -10, -10, -5, -5, -10, -10, -20,
		-10, 0, 5, 0, 0, 0, 0, -10,
		-10, 5, 5, 5, 5, 5, 0, -10,
		0, 0, 5, 5, 5, 5, 0, -5,
		-5, 0, 5, 5, 5, 5, 0, -5,
		-10, 0, 5, 5, 5, 5, 0, -10,
		-10, 0, 0, 0, 0, 0, 0, -10,
		-20, -10, -10, -5, -5, -10, -10, -20,
	)
	private val KING_T = table(
		20, 30, 10, 0, 0, 10, 30, 20,
		20, 20, 0, 0, 0, 0, 20, 20,
		-10, -20, -20, -20, -20, -20, -20, -10,
		-20, -30, -30, -40, -40, -30, -30, -20,
		-30, -40, -40, -50, -50, -40, -40, -30,
		-30, -40, -40, -50, -50, -40, -40, -30,
		-30, -40, -40, -50, -50, -40, -40, -30,
		-30, -40, -40, -50, -50, -40, -40, -30,
	)
	private val TABLES = arrayOf(IntArray(64), PAWN_T, KNIGHT_T, BISHOP_T, ROOK_T, QUEEN_T, KING_T)

	private fun table(vararg v: Int): IntArray {
		require(v.size == 64)
		return v
	}

	/** Static evaluation from the side to move's point of view. */
	fun evaluate(p: Position): Int {
		var score = 0
		for (sq in 0..63) {
			val pc = p.board[sq]
			if (pc == 0) continue
			val t = Piece.type(pc)
			val idx = if (pc > 0) sq else sq xor 56
			val v = Piece.VALUE[t] + TABLES[t][idx]
			score += if (pc > 0) v else -v
		}
		return if (p.whiteToMove) score else -score
	}

	/** Picks a move for the side to move, or null if there is none. Runs on any thread (uses its own copy). */
	fun bestMove(position: Position, difficulty: Difficulty, rng: Random = Random.Default): Move? {
		val p = position.copy()
		val moves = ordered(p, p.legalMoves())
		if (moves.isEmpty()) return null
		var best: Move? = null
		var bestScore = Int.MIN_VALUE
		for (m in moves) {
			p.make(m)
			var score = -search(p, difficulty.depth - 1, -MATE - 1, MATE + 1, 1)
			p.unmake()
			if (difficulty.noise > 0) score += rng.nextInt(-difficulty.noise, difficulty.noise + 1)
			if (score > bestScore) { bestScore = score; best = m }
		}
		return best
	}

	private fun search(p: Position, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
		val moves = p.legalMoves()
		if (moves.isEmpty()) return if (p.inCheck()) -MATE + ply else 0
		if (p.halfmove >= 100 || p.insufficientMaterial()) return 0
		if (depth <= 0) return quiesce(p, alphaIn, beta, 0)
		var alpha = alphaIn
		for (m in ordered(p, moves)) {
			p.make(m)
			val score = -search(p, depth - 1, -beta, -alpha, ply + 1)
			p.unmake()
			if (score >= beta) return score
			if (score > alpha) alpha = score
		}
		return alpha
	}

	/** Captures-only extension (limited depth) so the AI doesn't hang pieces at the horizon. */
	private fun quiesce(p: Position, alphaIn: Int, beta: Int, qd: Int): Int {
		val stand = evaluate(p)
		if (stand >= beta || qd >= 4) return stand
		var alpha = maxOf(alphaIn, stand)
		val white = p.whiteToMove
		val caps = p.pseudoMoves().filter { p.board[it.to] != 0 || it.promo != 0 }
		for (m in caps.sortedByDescending { mvvLva(p, it) }) {
			p.make(m)
			if (p.inCheck(white)) { p.unmake(); continue }
			val score = -quiesce(p, -beta, -alpha, qd + 1)
			p.unmake()
			if (score >= beta) return score
			if (score > alpha) alpha = score
		}
		return alpha
	}

	private fun mvvLva(p: Position, m: Move): Int =
		Piece.VALUE[Piece.type(p.board[m.to])] * 10 - Piece.VALUE[Piece.type(p.board[m.from])] / 10 + Piece.VALUE[m.promo]

	private fun ordered(p: Position, moves: List<Move>) = moves.sortedByDescending { mvvLva(p, it) }
}
