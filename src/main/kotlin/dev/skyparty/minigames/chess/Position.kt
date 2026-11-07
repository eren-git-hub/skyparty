package dev.skyparty.minigames.chess

import kotlin.math.abs

/**
 * Pure-Kotlin chess rules engine (no Minecraft dependencies).
 *
 * Board: 64 squares, index = rank * 8 + file (a1 = 0, h1 = 7, a8 = 56).
 * Pieces: positive = white, negative = black; 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king.
 * Legal moves are generated pseudo-legally and filtered with make/unmake + attack test.
 */
object Piece {
	const val EMPTY = 0
	const val PAWN = 1
	const val KNIGHT = 2
	const val BISHOP = 3
	const val ROOK = 4
	const val QUEEN = 5
	const val KING = 6

	fun type(p: Int) = abs(p)
	fun isWhite(p: Int) = p > 0

	private const val LETTERS = ".PNBRQK"
	fun letter(type: Int) = LETTERS[type]
	fun fromLetter(c: Char): Int {
		val t = LETTERS.indexOf(c.uppercaseChar())
		require(t > 0) { "Bad piece letter '$c'" }
		return if (c.isUpperCase()) t else -t
	}

	/** Material values in centipawns (king = 0, it can't be traded). */
	val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)
}

/** A move: [promo] is the piece type a pawn promotes to (0 if none). */
data class Move(val from: Int, val to: Int, val promo: Int = 0) {
	fun uci(): String = Square.name(from) + Square.name(to) + (if (promo != 0) Piece.letter(promo).lowercaseChar().toString() else "")
	override fun toString() = uci()

	companion object {
		fun parseUci(s: String): Move? {
			if (s.length !in 4..5) return null
			val from = Square.parse(s.substring(0, 2)) ?: return null
			val to = Square.parse(s.substring(2, 4)) ?: return null
			val promo = if (s.length == 5) when (s[4].lowercaseChar()) {
				'n' -> Piece.KNIGHT; 'b' -> Piece.BISHOP; 'r' -> Piece.ROOK; 'q' -> Piece.QUEEN
				else -> return null
			} else 0
			return Move(from, to, promo)
		}
	}
}

object Square {
	fun file(sq: Int) = sq and 7
	fun rank(sq: Int) = sq shr 3
	fun of(file: Int, rank: Int) = rank * 8 + file
	fun name(sq: Int) = "${'a' + file(sq)}${'1' + rank(sq)}"
	fun parse(s: String): Int? {
		if (s.length != 2) return null
		val f = s[0] - 'a'
		val r = s[1] - '1'
		return if (f in 0..7 && r in 0..7) of(f, r) else null
	}
	/** True for light squares (h1 is light). */
	fun isLight(sq: Int) = (file(sq) + rank(sq)) % 2 == 1
}

class Position {
	val board = IntArray(64)
	var whiteToMove = true
	/** Bits: 1 = white O-O, 2 = white O-O-O, 4 = black O-O, 8 = black O-O-O. */
	var castling = 0
	/** En-passant target square (the square the capturing pawn moves to), or -1. */
	var ep = -1
	var halfmove = 0
	var fullmove = 1

	private class Undo(val move: Move, val piece: Int, val captured: Int, val capSq: Int,
		val castling: Int, val ep: Int, val halfmove: Int, val fullmove: Int)

	private val undo = ArrayList<Undo>()

	fun copy(): Position {
		val p = Position()
		board.copyInto(p.board)
		p.whiteToMove = whiteToMove
		p.castling = castling
		p.ep = ep
		p.halfmove = halfmove
		p.fullmove = fullmove
		return p
	}

	// ---------------------------------------------------------------- FEN

	fun toFen(): String {
		val sb = StringBuilder()
		for (r in 7 downTo 0) {
			var empty = 0
			for (f in 0..7) {
				val p = board[Square.of(f, r)]
				if (p == 0) { empty++; continue }
				if (empty > 0) { sb.append(empty); empty = 0 }
				val c = Piece.letter(Piece.type(p))
				sb.append(if (p > 0) c else c.lowercaseChar())
			}
			if (empty > 0) sb.append(empty)
			if (r > 0) sb.append('/')
		}
		sb.append(if (whiteToMove) " w " else " b ")
		if (castling == 0) sb.append('-') else {
			if (castling and 1 != 0) sb.append('K')
			if (castling and 2 != 0) sb.append('Q')
			if (castling and 4 != 0) sb.append('k')
			if (castling and 8 != 0) sb.append('q')
		}
		sb.append(' ').append(if (ep >= 0) Square.name(ep) else "-")
		sb.append(' ').append(halfmove).append(' ').append(fullmove)
		return sb.toString()
	}

	/** Key for threefold repetition: placement, side, castling rights and a *usable* en-passant square. */
	fun repetitionKey(): String {
		val parts = toFen().split(' ')
		val epUsable = ep >= 0 && legalMoves().any { it.to == ep && Piece.type(board[it.from]) == Piece.PAWN }
		return "${parts[0]} ${parts[1]} ${parts[2]} ${if (epUsable) parts[3] else "-"}"
	}

	// ---------------------------------------------------------------- attacks

	fun kingSquare(white: Boolean): Int {
		val k = if (white) Piece.KING else -Piece.KING
		for (i in 0..63) if (board[i] == k) return i
		return -1
	}

	fun inCheck(white: Boolean = whiteToMove): Boolean {
		val k = kingSquare(white)
		return k >= 0 && isAttacked(k, !white)
	}

	/** Is [sq] attacked by any piece of the given color? */
	fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
		val f = Square.file(sq)
		val r = Square.rank(sq)
		val s = if (byWhite) 1 else -1
		// Pawns
		val pr = r - s
		if (pr in 0..7) {
			if (f > 0 && board[Square.of(f - 1, pr)] == s * Piece.PAWN) return true
			if (f < 7 && board[Square.of(f + 1, pr)] == s * Piece.PAWN) return true
		}
		for ((df, dr) in KNIGHT_DIRS) {
			val nf = f + df; val nr = r + dr
			if (nf in 0..7 && nr in 0..7 && board[Square.of(nf, nr)] == s * Piece.KNIGHT) return true
		}
		for ((df, dr) in KING_DIRS) {
			val nf = f + df; val nr = r + dr
			if (nf in 0..7 && nr in 0..7 && board[Square.of(nf, nr)] == s * Piece.KING) return true
		}
		for ((df, dr) in ROOK_DIRS) if (slideHits(f, r, df, dr, s * Piece.ROOK, s * Piece.QUEEN)) return true
		for ((df, dr) in BISHOP_DIRS) if (slideHits(f, r, df, dr, s * Piece.BISHOP, s * Piece.QUEEN)) return true
		return false
	}

	private fun slideHits(f0: Int, r0: Int, df: Int, dr: Int, a: Int, b: Int): Boolean {
		var f = f0 + df; var r = r0 + dr
		while (f in 0..7 && r in 0..7) {
			val p = board[Square.of(f, r)]
			if (p != 0) return p == a || p == b
			f += df; r += dr
		}
		return false
	}

	// ---------------------------------------------------------------- move generation

	fun pseudoMoves(out: MutableList<Move> = ArrayList(48)): MutableList<Move> {
		val s = if (whiteToMove) 1 else -1
		for (sq in 0..63) {
			val p = board[sq]
			if (p == 0 || (p > 0) != whiteToMove) continue
			val f = Square.file(sq); val r = Square.rank(sq)
			when (Piece.type(p)) {
				Piece.PAWN -> {
					val dir = s
					val start = if (whiteToMove) 1 else 6
					val last = if (whiteToMove) 7 else 0
					val r1 = r + dir
					if (r1 in 0..7) {
						val one = Square.of(f, r1)
						if (board[one] == 0) {
							addPawn(out, sq, one, r1 == last)
							if (r == start) {
								val two = Square.of(f, r + 2 * dir)
								if (board[two] == 0) out += Move(sq, two)
							}
						}
						for (df in intArrayOf(-1, 1)) {
							val nf = f + df
							if (nf !in 0..7) continue
							val t = Square.of(nf, r1)
							val tp = board[t]
							if ((tp != 0 && (tp > 0) != whiteToMove) || t == ep) addPawn(out, sq, t, r1 == last)
						}
					}
				}
				Piece.KNIGHT -> steps(out, sq, f, r, KNIGHT_DIRS)
				Piece.KING -> {
					steps(out, sq, f, r, KING_DIRS)
					castles(out, sq)
				}
				Piece.BISHOP -> slides(out, sq, f, r, BISHOP_DIRS)
				Piece.ROOK -> slides(out, sq, f, r, ROOK_DIRS)
				Piece.QUEEN -> { slides(out, sq, f, r, BISHOP_DIRS); slides(out, sq, f, r, ROOK_DIRS) }
			}
		}
		return out
	}

	private fun addPawn(out: MutableList<Move>, from: Int, to: Int, promo: Boolean) {
		if (promo) for (t in intArrayOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)) out += Move(from, to, t)
		else out += Move(from, to)
	}

	private fun steps(out: MutableList<Move>, sq: Int, f: Int, r: Int, dirs: Array<IntArray>) {
		for ((df, dr) in dirs) {
			val nf = f + df; val nr = r + dr
			if (nf !in 0..7 || nr !in 0..7) continue
			val t = Square.of(nf, nr)
			val tp = board[t]
			if (tp == 0 || (tp > 0) != whiteToMove) out += Move(sq, t)
		}
	}

	private fun slides(out: MutableList<Move>, sq: Int, f0: Int, r0: Int, dirs: Array<IntArray>) {
		for ((df, dr) in dirs) {
			var f = f0 + df; var r = r0 + dr
			while (f in 0..7 && r in 0..7) {
				val t = Square.of(f, r)
				val tp = board[t]
				if (tp == 0) out += Move(sq, t)
				else {
					if ((tp > 0) != whiteToMove) out += Move(sq, t)
					break
				}
				f += df; r += dr
			}
		}
	}

	/** Castling: rights, king/rook on home squares, empty path, king not in/through/into check. */
	private fun castles(out: MutableList<Move>, sq: Int) {
		val white = whiteToMove
		val home = if (white) 4 else 60
		if (sq != home) return
		val s = if (white) 1 else -1
		val kBit = if (white) 1 else 4
		val qBit = if (white) 2 else 8
		if (castling and (kBit or qBit) == 0) return
		if (isAttacked(home, !white)) return
		if (castling and kBit != 0 && board[home + 3] == s * Piece.ROOK &&
			board[home + 1] == 0 && board[home + 2] == 0 &&
			!isAttacked(home + 1, !white) && !isAttacked(home + 2, !white)
		) out += Move(home, home + 2)
		if (castling and qBit != 0 && board[home - 4] == s * Piece.ROOK &&
			board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0 &&
			!isAttacked(home - 1, !white) && !isAttacked(home - 2, !white)
		) out += Move(home, home - 2)
	}

	fun legalMoves(): List<Move> {
		val pseudo = pseudoMoves()
		val legal = ArrayList<Move>(pseudo.size)
		val white = whiteToMove
		for (m in pseudo) {
			make(m)
			if (!inCheck(white)) legal += m
			unmake()
		}
		return legal
	}

	fun isLegal(m: Move) = legalMoves().contains(m)

	// ---------------------------------------------------------------- make / unmake

	/** Applies [m] without legality checks. Returns the captured piece (0 if none). */
	fun make(m: Move): Int {
		val piece = board[m.from]
		val type = Piece.type(piece)
		val capSq = if (type == Piece.PAWN && m.to == ep && board[m.to] == 0) m.to - (if (piece > 0) 8 else -8) else m.to
		val captured = board[capSq]
		undo += Undo(m, piece, captured, capSq, castling, ep, halfmove, fullmove)

		board[capSq] = 0
		board[m.from] = 0
		board[m.to] = if (m.promo != 0) (if (piece > 0) m.promo else -m.promo) else piece
		if (type == Piece.KING && abs(m.to - m.from) == 2) {
			if (m.to > m.from) { board[m.from + 1] = board[m.from + 3]; board[m.from + 3] = 0 }
			else { board[m.from - 1] = board[m.from - 4]; board[m.from - 4] = 0 }
		}
		castling = castling and CASTLE_MASK[m.from] and CASTLE_MASK[m.to]
		ep = if (type == Piece.PAWN && abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
		halfmove = if (type == Piece.PAWN || captured != 0) 0 else halfmove + 1
		if (!whiteToMove) fullmove++
		whiteToMove = !whiteToMove
		return captured
	}

	fun unmake() {
		val u = undo.removeAt(undo.size - 1)
		val m = u.move
		board[m.from] = u.piece
		board[m.to] = 0
		board[u.capSq] = u.captured
		if (Piece.type(u.piece) == Piece.KING && abs(m.to - m.from) == 2) {
			if (m.to > m.from) { board[m.from + 3] = board[m.from + 1]; board[m.from + 1] = 0 }
			else { board[m.from - 4] = board[m.from - 1]; board[m.from - 1] = 0 }
		}
		castling = u.castling
		ep = u.ep
		halfmove = u.halfmove
		fullmove = u.fullmove
		whiteToMove = !whiteToMove
	}

	// ---------------------------------------------------------------- SAN

	/** Standard algebraic notation for a legal move in this position (with +/#). */
	fun san(m: Move): String {
		val piece = board[m.from]
		val type = Piece.type(piece)
		val sb = StringBuilder()
		if (type == Piece.KING && abs(m.to - m.from) == 2) {
			sb.append(if (m.to > m.from) "O-O" else "O-O-O")
		} else {
			val capture = board[m.to] != 0 || (type == Piece.PAWN && m.to == ep)
			if (type == Piece.PAWN) {
				if (capture) sb.append('a' + Square.file(m.from))
			} else {
				sb.append(Piece.letter(type))
				val others = legalMoves().filter { it.to == m.to && it.from != m.from && board[it.from] == piece }
				if (others.isNotEmpty()) {
					val sameFile = others.any { Square.file(it.from) == Square.file(m.from) }
					val sameRank = others.any { Square.rank(it.from) == Square.rank(m.from) }
					when {
						!sameFile -> sb.append('a' + Square.file(m.from))
						!sameRank -> sb.append('1' + Square.rank(m.from))
						else -> sb.append(Square.name(m.from))
					}
				}
			}
			if (capture) sb.append('x')
			sb.append(Square.name(m.to))
			if (m.promo != 0) sb.append('=').append(Piece.letter(m.promo))
		}
		make(m)
		if (inCheck()) sb.append(if (legalMoves().isEmpty()) '#' else '+')
		unmake()
		return sb.toString()
	}

	/** True if neither side can possibly checkmate (K v K, K+minor v K, bishops all on one color). */
	fun insufficientMaterial(): Boolean {
		var minors = 0
		var knights = 0
		var lightB = 0
		var darkB = 0
		for (sq in 0..63) {
			when (Piece.type(board[sq])) {
				Piece.PAWN, Piece.ROOK, Piece.QUEEN -> return false
				Piece.KNIGHT -> { minors++; knights++ }
				Piece.BISHOP -> { minors++; if (Square.isLight(sq)) lightB++ else darkB++ }
			}
		}
		if (minors <= 1) return true
		return knights == 0 && (lightB == 0 || darkB == 0)
	}

	/** True if the given side has mating material at all (used for flag-fall: timeout vs. insufficient = draw). */
	fun hasMatingMaterial(white: Boolean): Boolean {
		var minors = 0
		for (p in board) {
			if (p == 0 || (p > 0) != white) continue
			when (Piece.type(p)) {
				Piece.PAWN, Piece.ROOK, Piece.QUEEN -> return true
				Piece.KNIGHT, Piece.BISHOP -> minors++
			}
		}
		return minors >= 2
	}

	companion object {
		const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

		val KNIGHT_DIRS = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
			intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2))
		val KING_DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
			intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
		val ROOK_DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
		val BISHOP_DIRS = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))

		/** AND-mask applied to castling rights when a move touches a square (king/rook home squares). */
		private val CASTLE_MASK = IntArray(64) { 15 }.also {
			it[4] = 15 and 3.inv(); it[7] = 15 and 1.inv(); it[0] = 15 and 2.inv()
			it[60] = 15 and 12.inv(); it[63] = 15 and 4.inv(); it[56] = 15 and 8.inv()
		}

		fun fromFen(fen: String): Position {
			val parts = fen.trim().split(Regex("\\s+"))
			require(parts.isNotEmpty()) { "Empty FEN" }
			val p = Position()
			val rows = parts[0].split('/')
			require(rows.size == 8) { "FEN needs 8 ranks" }
			for ((i, row) in rows.withIndex()) {
				val r = 7 - i
				var f = 0
				for (c in row) {
					if (c.isDigit()) f += c - '0'
					else {
						require(f < 8) { "FEN rank too long" }
						p.board[Square.of(f, r)] = Piece.fromLetter(c)
						f++
					}
				}
				require(f == 8) { "FEN rank ${8 - i} has $f files" }
			}
			p.whiteToMove = parts.getOrElse(1) { "w" } != "b"
			val c = parts.getOrElse(2) { "-" }
			p.castling = (if ('K' in c) 1 else 0) or (if ('Q' in c) 2 else 0) or (if ('k' in c) 4 else 0) or (if ('q' in c) 8 else 0)
			p.ep = parts.getOrNull(3)?.let { Square.parse(it) } ?: -1
			p.halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
			p.fullmove = parts.getOrNull(5)?.toIntOrNull() ?: 1
			require(p.kingSquare(true) >= 0 && p.kingSquare(false) >= 0) { "FEN needs both kings" }
			return p
		}

		fun perft(p: Position, depth: Int): Long {
			if (depth == 0) return 1
			val moves = p.pseudoMoves()
			val white = p.whiteToMove
			var n = 0L
			for (m in moves) {
				p.make(m)
				if (!p.inCheck(white)) n += if (depth == 1) 1 else perft(p, depth - 1)
				p.unmake()
			}
			return n
		}
	}
}
