package dev.skyparty.minigames.board

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
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rules of a simple "get N in a row" grid game. Cells are indexed `row * cols + col`, row 0 at the top.
 * With [gravity] a move is a column and the piece drops to the lowest free row (Connect Four).
 */
class GridRules(val cols: Int, val rows: Int, val win: Int, val gravity: Boolean) {
	val size get() = cols * rows

	/** Target cell for a click on [cell] (column drop with gravity), or -1 if illegal. */
	fun target(board: IntArray, cell: Int): Int {
		if (cell !in 0 until size) return -1
		if (!gravity) return if (board[cell] == 0) cell else -1
		val col = cell % cols
		for (r in rows - 1 downTo 0) if (board[r * cols + col] == 0) return r * cols + col
		return -1
	}

	fun moves(board: IntArray): List<Int> =
		if (gravity) (0 until cols).map { target(board, it) }.filter { it >= 0 }
		else (0 until size).filter { board[it] == 0 }

	/** The winning line through the last move, or null. */
	fun winLine(board: IntArray, last: Int): List<Int>? {
		val p = board[last]
		if (p == 0) return null
		val r0 = last / cols
		val c0 = last % cols
		for ((dr, dc) in DIRS) {
			val line = mutableListOf(last)
			for (sgn in intArrayOf(1, -1)) {
				var r = r0 + dr * sgn
				var c = c0 + dc * sgn
				while (r in 0 until rows && c in 0 until cols && board[r * cols + c] == p) {
					line += r * cols + c
					r += dr * sgn; c += dc * sgn
				}
			}
			if (line.size >= win) return line
		}
		return null
	}

	fun full(board: IntArray) = board.none { it == 0 }

	companion object {
		private val DIRS = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
	}
}

/** Alpha-beta AI for [GridRules] games. Player values are 1 and 2. */
object GridAI {
	fun bestMove(rules: GridRules, board: IntArray, me: Int, depth: Int, blunder: Int, rng: Random): Int {
		val moves = rules.moves(board)
		if (moves.isEmpty()) return -1
		if (rng.nextInt(100) < blunder) return moves.random(rng)
		// Center columns first (better pruning, nicer play), random tie-break.
		val center = (rules.cols - 1) / 2.0
		val ordered = moves.shuffled(rng).sortedBy { abs(it % rules.cols - center) }
		var best = ordered.first()
		var bestScore = Int.MIN_VALUE
		for (m in ordered) {
			board[m] = me
			val score = if (rules.winLine(board, m) != null) 1_000_000 + depth
			else -search(rules, board, 3 - me, depth - 1, -Int.MAX_VALUE, Int.MAX_VALUE)
			board[m] = 0
			if (score > bestScore) { bestScore = score; best = m }
		}
		return best
	}

	private fun search(rules: GridRules, board: IntArray, p: Int, depth: Int, alphaIn: Int, beta: Int): Int {
		val moves = rules.moves(board)
		if (moves.isEmpty()) return 0
		if (depth <= 0) return evaluate(rules, board, p)
		var alpha = alphaIn
		val center = (rules.cols - 1) / 2.0
		for (m in moves.sortedBy { abs(it % rules.cols - center) }) {
			board[m] = p
			val score = if (rules.winLine(board, m) != null) 1_000_000 + depth
			else -search(rules, board, 3 - p, depth - 1, -beta, -alpha)
			board[m] = 0
			if (score > alpha) alpha = score
			if (alpha >= beta) break
		}
		return alpha
	}

	/** Window heuristic from [p]'s point of view: open lines with 2 or 3 pieces count. */
	private fun evaluate(rules: GridRules, board: IntArray, p: Int): Int {
		var score = 0
		val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
		for (r in 0 until rules.rows) for (c in 0 until rules.cols) for ((dr, dc) in dirs) {
			val er = r + dr * (rules.win - 1)
			val ec = c + dc * (rules.win - 1)
			if (er !in 0 until rules.rows || ec !in 0 until rules.cols) continue
			var mine = 0
			var theirs = 0
			for (i in 0 until rules.win) {
				when (board[(r + dr * i) * rules.cols + c + dc * i]) {
					p -> mine++
					0 -> {}
					else -> theirs++
				}
			}
			if (theirs == 0) score += WEIGHT[mine]
			if (mine == 0) score -= WEIGHT[theirs]
		}
		return score
	}

	private val WEIGHT = intArrayOf(0, 1, 8, 60, 0, 0)
}

/** Base for the two grid games: registration + opening the shared screen. */
abstract class GridGame(
	override val id: String,
	override val name: String,
	override val description: String,
	val rules: GridRules,
	val aiDepth: Int,
	val aiBlunder: Int,
	val winTokens: Int,
) : Minigame {
	override val minPlayers = 1
	override val maxPlayers = 2
	override val realtime = false

	override fun open(room: Net.Room?) = MinigameHub.openScreen(GridScreen(this, LobbyRoom.of(room, id)))
}

object ConnectFour : GridGame(
	"connect4", "Connect Four", "Drop discs and connect four in a row. Play the AI or a party member.",
	GridRules(7, 6, 4, gravity = true), aiDepth = 6, aiBlunder = 8, winTokens = 6,
) {
	override val length = dev.skyparty.minigames.GameLength.MEDIUM
}

object TicTacToe : GridGame(
	"tictactoe", "Tic-Tac-Toe", "The classic 3x3. Quick rounds against the AI or a party member.",
	GridRules(3, 3, 3, gravity = false), aiDepth = 9, aiBlunder = 25, winTokens = 3,
) {
	override val length = dev.skyparty.minigames.GameLength.SHORT
}

/**
 * Shared screen. Online protocol (echoed to the sender, applied on receipt):
 *  - "<id>.start.rng" (host)  roll decides who starts; the first two players play, others spectate
 *  - "<id>.move" {cell}       only accepted from the player whose turn it is
 * Solo: you vs the AI, starting player alternates each round.
 */
class GridScreen(val game: GridGame, val room: LobbyRoom) : GameScreen(game.name, room) {
	private val rules = game.rules
	private var board = IntArray(rules.size)
	private var turn = 1
	private var started = false
	private var winner = 0          // 0 = running, 1/2 = player, 3 = draw
	private var winLine: List<Int> = emptyList()
	private var lastMove = -1
	private var lastMoveAt = 0L
	private var rewarded = false
	private var aiThinkUntil = 0L
	private var soloRound = 0
	private val rng = Random(room.nanos())

	/** Player ids for piece 1 and 2 (solo: you and the AI = null). */
	private var p1: UUID? = null
	private var p2: UUID? = null

	private val score = IntArray(3)
	override fun inProgress() = started && winner == 0

	init {
		room.onMessage = ::onMessage
		if (room.solo) startSolo() else if (room.isHost) requestStart()
	}

	private val players: List<UUID> get() = room.names.keys.take(2)
	private val myPiece: Int get() = when (Net.selfId) { p1 -> 1; p2 -> 2; else -> 0 }
	private val aiPiece: Int get() = if (room.solo) (if (p1 == null) 1 else 2) else 0
	private fun nameOfPiece(p: Int): String {
		val id = if (p == 1) p1 else p2
		return if (id == null) "AI" else if (id == Net.selfId) "You" else room.nameOf(id)
	}

	// ------------------------------------------------------------------ protocol

	private fun requestStart() = room.send("${game.id}.start.rng", JsonObject())

	private fun onMessage(msg: Net.Message) {
		when (msg.type) {
			"${game.id}.start.rng" -> {
				if (msg.from != room.host || room.solo) return
				val roll = msg.payload["roll"]?.asDouble ?: Math.random()
				val ps = players
				val a = ps.getOrNull(0)
				val b = ps.getOrNull(1)
				if (roll < 0.5) { p1 = a; p2 = b } else { p1 = b; p2 = a }
				reset()
			}
			"${game.id}.move" -> {
				if (room.solo) return
				val mover = if (turn == 1) p1 else p2
				if (msg.from != mover) return
				apply(msg.payload["cell"]?.asInt ?: return)
			}
		}
	}

	private fun startSolo() {
		// Alternate who starts; the AI is p1 (null) on odd rounds.
		if (soloRound % 2 == 0) { p1 = Net.selfId; p2 = null } else { p1 = null; p2 = Net.selfId }
		soloRound++
		reset()
		if (aiPiece == 1) aiThinkUntil = room.now() + 500
	}

	private fun reset() {
		board = IntArray(rules.size)
		turn = 1
		winner = 0
		winLine = emptyList()
		lastMove = -1
		rewarded = false
		started = true
	}

	/** A click on [cell] by the local player (public for gametests). */
	fun clickCell(cell: Int) {
		if (!started || winner != 0 || turn != myPiece) return
		val t = rules.target(board, cell)
		if (t < 0) return
		if (room.solo) apply(t) else room.send("${game.id}.move", JsonObject().apply { addProperty("cell", t) })
	}

	private fun apply(cell: Int) {
		val t = rules.target(board, cell)
		if (t < 0 || winner != 0) return
		board[t] = turn
		lastMove = t
		lastMoveAt = room.now()
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.WOOD_PLACE, if (turn == 1) 1.2f else 0.9f))
		val line = rules.winLine(board, t)
		when {
			line != null -> { winner = turn; winLine = line; onGameOver() }
			rules.full(board) -> { winner = 3; onGameOver() }
			else -> {
				turn = 3 - turn
				if (turn == aiPiece) aiThinkUntil = room.now() + 450 + rng.nextLong(400)
			}
		}
	}

	private fun onGameOver() {
		if (winner in 1..2) score[winner]++
		val won = winner == myPiece
		val sound = if (won) SoundEvents.PLAYER_LEVELUP else if (winner == 3) SoundEvents.NOTE_BLOCK_PLING.value() else SoundEvents.VILLAGER_NO
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, 1f))
		if (won && !rewarded) {
			rewarded = true
			MinigameStore.addTokens(game.winTokens)
		}
	}

	private fun tickAi() {
		if (!room.solo || winner != 0 || turn != aiPiece || room.now() < aiThinkUntil) return
		val m = GridAI.bestMove(rules, board.copyOf(), aiPiece, game.aiDepth, game.aiBlunder, rng)
		if (m >= 0) apply(m)
	}

	// ------------------------------------------------------------------ layout

	private var cell = 20
	private var bx = 0
	private var by = 0

	private fun layout(top: Int) {
		val panelW = 120
		val availW = width - panelW - 36
		val availH = height - top - 24 - (if (rules.gravity) 18 else 0)
		cell = minOf(availW / rules.cols, availH / rules.rows, if (rules.gravity) 34 else 52).coerceAtLeast(12)
		val total = rules.cols * cell + 14 + panelW
		bx = (width - total) / 2
		by = top + (availH - rules.rows * cell) / 2 + 8 + (if (rules.gravity) 18 else 0)
	}

	private fun cellAt(mx: Int, my: Int): Int {
		val c = Math.floorDiv(mx - bx, cell)
		val r = Math.floorDiv(my - by, cell)
		if (c !in 0 until rules.cols) return -1
		if (rules.gravity) return if (my >= by - cell && my < by + rules.rows * cell) c else -1
		if (r !in 0 until rules.rows) return -1
		return r * rules.cols + c
	}

	override fun onClickAnywhere(mx: Double, my: Double): Boolean {
		val c = cellAt(mx.toInt(), my.toInt())
		if (c < 0) return false
		clickCell(c)
		return true
	}

	// ------------------------------------------------------------------ drawing

	private val colors = intArrayOf(0, 0xFFFF5FA2.toInt(), 0xFFFFD84D.toInt())
	private val tttColors = intArrayOf(0, 0xFFFF5FA2.toInt(), 0xFF5FE3FF.toInt())

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		tickAi()
		val sub = if (room.solo) "vs AI" else if (myPiece == 0) "spectating" else "${room.members.size} players"
		val top = header(g, game.name, sub)
		layout(top)
		if (!started) {
			centered(g, "§7Waiting for the host…", width / 2, height / 2, Theme.TEXT)
			return
		}
		if (rules.gravity) drawConnectFour(g, mouseX, mouseY) else drawTicTacToe(g, mouseX, mouseY)
		drawPanel(g, mouseX, mouseY)
	}

	private fun drawConnectFour(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val w = rules.cols * cell
		val h = rules.rows * cell
		val r = cell / 2 - 3
		// Hover preview above the board
		val hoverCol = if (winner == 0 && turn == myPiece) cellAt(mx, my) else -1
		if (hoverCol >= 0 && rules.target(board, hoverCol) >= 0) {
			disc(g, bx + hoverCol * cell + cell / 2, by - cell / 2 - 2, r, ColorUtil.withAlpha(colors[myPiece], 0x90))
			g.fill(bx + hoverCol * cell, by, bx + hoverCol * cell + cell, by + h, 0x18FFFFFF)
		}
		g.fill(bx - 4 + 3, by - 4 + 3, bx + w + 4 + 3, by + h + 4 + 3, Theme.SHADOW)
		g.fill(bx - 4, by - 4, bx + w + 4, by + h + 4, 0xFF5A2E9C.toInt())
		g.outline(bx - 4, by - 4, w + 8, h + 8, Theme.rainbow(0f, 0.5f))
		val now = room.now()
		for (i in 0 until rules.size) {
			val cx = bx + (i % rules.cols) * cell + cell / 2
			val cy = by + (i / rules.cols) * cell + cell / 2
			val p = board[i]
			if (p == 0) { disc(g, cx, cy, r, 0xFF241238.toInt()); continue }
			disc(g, cx, cy, r, 0xFF241238.toInt())
			// Drop animation for the last disc
			var dy = cy
			if (i == lastMove) {
				val t = ((now - lastMoveAt) / 260f).coerceIn(0f, 1f)
				val startY = by - cell / 2
				dy = (startY + (cy - startY) * t * t).toInt()
			}
			val win = i in winLine
			val c = if (win) ColorUtil.shade(colors[p], 1f + 0.25f * sin(Theme.time * 8)) else colors[p]
			disc(g, cx, dy, r, ColorUtil.shade(c, 0.7f))
			disc(g, cx, dy - 1, r - 1, c)
			disc(g, cx - r / 3, dy - r / 3, r / 4, ColorUtil.withAlpha(0xFFFFFFFF.toInt(), 0x70))
		}
	}

	private fun drawTicTacToe(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val w = rules.cols * cell
		g.fill(bx + 3, by + 3, bx + w + 3, by + w + 3, Theme.SHADOW)
		g.fill(bx, by, bx + w, by + w, GameColors.PANEL)
		val hover = if (winner == 0 && turn == myPiece) cellAt(mx, my) else -1
		for (i in 0 until rules.size) {
			val x = bx + (i % 3) * cell
			val y = by + (i / 3) * cell
			if (i == hover && board[i] == 0) g.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, 0x22FFFFFF)
			if (i in winLine) g.fill(x + 2, y + 2, x + cell - 2, y + cell - 2,
				ColorUtil.withAlpha(tttColors[winner.coerceIn(1, 2)], (0x40 + 0x30 * sin(Theme.time * 8)).toInt()))
			val pop = if (i == lastMove) ((room.now() - lastMoveAt) / 180f).coerceIn(0f, 1f) else 1f
			val size = ((cell - 16) * (0.4f + 0.6f * pop)).toInt()
			when (board[i]) {
				1 -> drawX(g, x + cell / 2, y + cell / 2, size, tttColors[1])
				2 -> ring(g, x + cell / 2, y + cell / 2, size / 2, maxOf(3, cell / 10), tttColors[2])
			}
		}
		// Rainbow grid lines
		for (k in 1..2) {
			g.fill(bx + k * cell - 1, by + 4, bx + k * cell + 1, by + w - 4, Theme.rainbow(k * 0.2f, 0.5f))
			g.fill(bx + 4, by + k * cell - 1, bx + w - 4, by + k * cell + 1, Theme.rainbow(k * 0.2f + 0.4f, 0.5f))
		}
	}

	private fun drawPanel(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val x = bx + rules.cols * cell + 14
		val y = by - 4
		val w = 120
		panel(g, x, y, w, 132, "Players")
		for (p in 1..2) {
			val ry = y + 22 + (p - 1) * 16
			val c = if (rules.gravity) colors[p] else tttColors[p]
			if (winner == 0 && turn == p) g.fill(x + 3, ry - 3, x + w - 3, ry + 11, 0x30FFFFFF)
			if (rules.gravity) disc(g, x + 11, ry + 4, 4, c) else g.text(font, if (p == 1) "X" else "O", x + 8, ry, c, true)
			g.text(font, font.plainSubstrByWidth(nameOfPiece(p), 70), x + 20, ry, Theme.TEXT, false)
			val s = score[p].toString()
			g.text(font, s, x + w - 8 - font.width(s), ry, GameColors.GOLD, false)
		}
		val status = when {
			winner == 3 -> "§eDraw!"
			winner != 0 && winner == myPiece -> "§a§lYou win! ✦"
			winner != 0 -> "§c${nameOfPiece(winner)} wins"
			turn == myPiece -> "§fYour turn"
			turn == aiPiece -> "§7AI is thinking" + ".".repeat(((room.now() / 250) % 4).toInt())
			else -> "§7${nameOfPiece(turn)}'s turn"
		}
		centered(g, status, x + w / 2, y + 62, Theme.TEXT)
		if (winner != 0 && winner == myPiece) centered(g, "§6+${game.winTokens} Tokens", x + w / 2, y + 74, Theme.TEXT)
		if (winner != 0) {
			if (room.solo || room.isHost) {
				button(g, x + 8, y + 88, w - 16, 16, "Play again", mx, my, color = GameColors.GREEN) {
					if (room.solo) startSolo() else requestStart()
				}
			} else centered(g, "§7Waiting for the host…", x + w / 2, y + 92, Theme.TEXT)
		}
		button(g, x + 8, y + 110, w - 16, 16, "Leave", mx, my, color = GameColors.RED) { room.quit(); onClose() }
	}

	private fun disc(g: GuiGraphicsExtractor, cx: Int, cy: Int, r: Int, color: Int) {
		for (dy in -r..r) {
			val hw = sqrt((r * r - dy * dy).toDouble()).toInt()
			g.fill(cx - hw, cy + dy, cx + hw + 1, cy + dy + 1, color)
		}
	}

	private fun ring(g: GuiGraphicsExtractor, cx: Int, cy: Int, r: Int, thick: Int, color: Int) {
		val inner = (r - thick).coerceAtLeast(0)
		for (dy in -r..r) {
			val ho = sqrt((r * r - dy * dy).toDouble()).toInt()
			if (abs(dy) >= inner) { g.fill(cx - ho, cy + dy, cx + ho + 1, cy + dy + 1, color); continue }
			val hi = sqrt((inner * inner - dy * dy).toDouble()).toInt()
			g.fill(cx - ho, cy + dy, cx - hi, cy + dy + 1, color)
			g.fill(cx + hi + 1, cy + dy, cx + ho + 1, cy + dy + 1, color)
		}
	}

	private fun drawX(g: GuiGraphicsExtractor, cx: Int, cy: Int, size: Int, color: Int) {
		val half = size / 2
		val t = maxOf(2, size / 7)
		for (i in -half..half) {
			g.fill(cx + i - t / 2, cy + i - t / 2, cx + i + t - t / 2, cy + i + t - t / 2, color)
			g.fill(cx + i - t / 2, cy - i - t / 2, cx + i + t - t / 2, cy - i + t - t / 2, color)
		}
	}

	override fun removed() {
		room.leave()
		super.removed()
	}
}
