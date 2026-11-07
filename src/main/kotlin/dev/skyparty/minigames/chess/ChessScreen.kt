package dev.skyparty.minigames.chess

import dev.skyparty.ui.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The chess board screen. [session] null = solo setup panel (a session is created on "Start game").
 * Click-to-move and drag-and-drop, legal-move dots, last-move/check highlights, promotion picker,
 * move list, captured pieces, clocks, resign/draw buttons, board flip and a game-over overlay.
 */
class ChessScreen(private var session: ChessSession?) : Screen(Component.literal("Chess")) {
	private class Btn(val x: Int, val y: Int, val w: Int, val h: Int, val label: String, val enabled: Boolean,
		val primary: Boolean, val action: (Int) -> Unit) {
		fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h
	}

	// Layout (GUI pixels)
	private var bx = 0
	private var by = 0
	private var sq = 24
	private val boardSize get() = sq * 8
	private var px = 0
	private val pw = PANEL_W

	private val buttons = ArrayList<Btn>()
	private var flipped = false
	private var selected = -1
	private var dragFrom = -1
	private var dragging = false
	private var pressX = 0.0
	private var pressY = 0.0
	private var promoFrom = -1
	private var promoTo = -1
	private var overlayHidden = false
	private var moveScroll = 0
	private var seenCounter = -1
	private var animFrom = -1
	private var animTo = -1
	private var animStart = 0L
	private var onlineClock = ChessSession.Clock.RAPID10
	private val preview = Position.fromFen(Position.START_FEN)

	private val gs: Int get() = minecraft.window.guiScale

	override fun isPauseScreen() = false

	/** Esc / Back / Close return to the games overview (a running online game keeps going in the background). */
	override fun onClose() {
		minecraft.gui.setScreen(dev.skyparty.minigames.ui.HubScreen())
	}

	override fun init() {
		super.init()
		layout()
	}

	private fun layout() {
		val maxBoard = minOf(height - 44, width - 2 * MARGIN - pw - GAP - COORD)
		sq = (maxBoard / 8).coerceAtLeast(12)
		val total = COORD + boardSize + GAP + pw
		bx = (width - total) / 2 + COORD
		by = ((height - boardSize) / 2 + 4).coerceAtLeast(22)
		px = bx + boardSize + GAP
	}

	// ------------------------------------------------------------------ coordinates

	/** True if white is drawn at the bottom. */
	private val whiteBottom: Boolean get() = (session?.bottomWhite ?: (ChessGame.soloSettings.color != ChessSession.ColorChoice.BLACK || ChessGame.soloMode != ChessSession.Mode.AI)) != flipped

	private fun squareX(s: Int) = bx + (if (whiteBottom) Square.file(s) else 7 - Square.file(s)) * sq
	private fun squareY(s: Int) = by + (if (whiteBottom) 7 - Square.rank(s) else Square.rank(s)) * sq

	private fun squareAt(mx: Double, my: Double): Int {
		if (mx < bx || my < by || mx >= bx + boardSize || my >= by + boardSize) return -1
		val col = ((mx - bx) / sq).toInt()
		val row = ((my - by) / sq).toInt()
		return if (whiteBottom) Square.of(col, 7 - row) else Square.of(7 - col, row)
	}

	/** Center of a square in GUI coordinates (used by gametests to simulate real clicks). */
	fun squareCenter(s: Int): Pair<Double, Double> = Pair(squareX(s) + sq / 2.0, squareY(s) + sq / 2.0)

	fun flip() { flipped = !flipped }

	// ------------------------------------------------------------------ rendering

	override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		g.fill(0, 0, width, height, 0xC00A0A0F.toInt())
	}

	override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.extractRenderState(g, mouseX, mouseY, partialTick)
		layout()
		buttons.clear()
		button(4, 4, 42, 14, "◀ Back", true) { onClose() }
		val s = session
		val st = s?.state
		if (s != null && s.moveCounter != seenCounter) {
			seenCounter = s.moveCounter
			selected = -1
			if (!dragging) dragFrom = -1
			promoFrom = -1
			val last = st?.lastMove
			if (s.lastMoveAnimated && last != null) {
				animFrom = last.from; animTo = last.to; animStart = System.currentTimeMillis()
			} else animTo = -1
			moveScroll = Int.MAX_VALUE
			if (st?.over == false) overlayHidden = false
		}

		drawBoard(g, st, mouseX, mouseY)
		drawPanel(g, s, st, mouseX, mouseY)

		when {
			s == null -> drawSetup(g)
			s.online && st == null -> drawOnlineLobby(g)
			st != null && st.over && !overlayHidden -> drawGameOver(g, s, st)
		}
		drawButtons(g, mouseX, mouseY)
	}

	private fun drawBoard(g: GuiGraphicsExtractor, st: ChessState?, mouseX: Int, mouseY: Int) {
		val b = boardSize
		val pos = st?.pos ?: preview
		// Frame
		g.fill(bx - 4, by - 4, bx + b + 4, by + b + 4, Theme.SHADOW)
		g.fill(bx - 3, by - 3, bx + b + 3, by + b + 3, Theme.HEADER)
		g.fill(bx - 1, by - 1, bx + b + 1, by + b + 1, Theme.accent(0x90))

		val last = st?.lastMove
		for (s in 0..63) {
			val x = squareX(s); val y = squareY(s)
			g.fill(x, y, x + sq, y + sq, if (Square.isLight(s)) LIGHT else DARK)
			if (last != null && (s == last.from || s == last.to)) g.fill(x, y, x + sq, y + sq, Theme.accent(0x58))
			if (s == selected || (dragging && s == dragFrom)) g.fill(x, y, x + sq, y + sq, Theme.accent(0x90))
		}
		// Check glow on the king
		if (st != null && pos.inCheck()) {
			val k = pos.kingSquare(pos.whiteToMove)
			val x = squareX(k); val y = squareY(k)
			// Nested translucent layers: strongest in the center, like a soft glow.
			for (i in 0 until 4) {
				val inset = i * sq / 10
				g.fill(x + inset, y + inset, x + sq - inset, y + sq - inset, 0x38FF2A2A)
			}
		}
		// Coordinates
		for (i in 0..7) {
			val rankLabel = if (whiteBottom) "${8 - i}" else "${i + 1}"
			g.text(font, rankLabel, bx - 4 - font.width(rankLabel) - 1, by + i * sq + (sq - 8) / 2 + 1, Theme.TEXT_DIM, false)
			val fileLabel = "${if (whiteBottom) 'a' + i else 'h' - i}"
			g.text(font, fileLabel, bx + i * sq + (sq - font.width(fileLabel)) / 2, by + b + 5, Theme.TEXT_DIM, false)
		}

		// Pieces
		val now = System.currentTimeMillis()
		val animT = if (animTo >= 0) ((now - animStart) / ANIM_MS.toFloat()).coerceIn(0f, 1f) else 1f
		if (animT >= 1f) animTo = -1
		for (s in 0..63) {
			val p = pos.board[s]
			if (p == 0) continue
			if (dragging && s == dragFrom) {
				ChessSprites.draw(g, p, squareX(s).toFloat(), squareY(s).toFloat(), sq.toFloat(), gs, alpha = 0x50, shadow = false)
				continue
			}
			var x = squareX(s).toFloat(); var y = squareY(s).toFloat()
			if (s == animTo) {
				val e = 1f - (1f - animT) * (1f - animT)
				x = squareX(animFrom) + (x - squareX(animFrom)) * e
				y = squareY(animFrom) + (y - squareY(animFrom)) * e
			}
			ChessSprites.draw(g, p, x, y, sq.toFloat(), gs)
		}

		// Legal move hints
		val from = if (dragging) dragFrom else selected
		if (st != null && from >= 0) {
			val targets = st.legal.filter { it.from == from }.map { it.to }.toSet()
			for (t in targets) {
				val cx = squareX(t) + sq / 2f; val cy = squareY(t) + sq / 2f
				val capture = pos.board[t] != 0 || (Piece.type(pos.board[from]) == Piece.PAWN && t == pos.ep)
				if (capture) circle(g, cx, cy, sq * 0.48f, sq * 0.38f, 0x70141420)
				else circle(g, cx, cy, sq * 0.15f, 0f, 0x70141420)
			}
			if (dragging) {
				val hover = squareAt(mouseX.toDouble(), mouseY.toDouble())
				if (hover in targets) g.outline(squareX(hover), squareY(hover), sq, sq, 0xC0FFFFFF.toInt())
			}
		}
		// Dragged piece follows the cursor
		if (dragging && dragFrom >= 0 && pos.board[dragFrom] != 0) {
			ChessSprites.draw(g, pos.board[dragFrom], mouseX - sq / 2f, mouseY - sq / 2f, sq.toFloat(), gs)
		}
		if (promoFrom >= 0 && st != null) drawPromotion(g, st, mouseX, mouseY)
	}

	private fun promoSquares(): List<Pair<Int, Int>> {
		// Column of 4 cells starting at the target square, extending towards the board center.
		val x = squareX(promoTo)
		val y0 = squareY(promoTo)
		val down = y0 < by + boardSize / 2
		return PROMO_TYPES.indices.map { i -> Pair(x, if (down) y0 + i * sq else y0 - i * sq) }
	}

	private fun drawPromotion(g: GuiGraphicsExtractor, st: ChessState, mouseX: Int, mouseY: Int) {
		g.fill(bx, by, bx + boardSize, by + boardSize, 0x80000000.toInt())
		val white = st.pos.whiteToMove
		for ((i, cell) in promoSquares().withIndex()) {
			val (x, y) = cell
			val hover = mouseX >= x && mouseX < x + sq && mouseY >= y && mouseY < y + sq
			g.fill(x - 1, y - 1, x + sq + 1, y + sq + 1, Theme.accent(0xFF))
			g.fill(x, y, x + sq, y + sq, if (hover) 0xFFE8E8F0.toInt() else 0xFFB8BCCB.toInt())
			ChessSprites.draw(g, if (white) PROMO_TYPES[i] else -PROMO_TYPES[i], x.toFloat(), y.toFloat(), sq.toFloat(), gs)
		}
	}

	/** Filled circle or ring drawn on the real pixel grid (inner radius 0 = disc). */
	private fun circle(g: GuiGraphicsExtractor, cx: Float, cy: Float, rOut: Float, rIn: Float, color: Int) {
		val k = gs.toFloat()
		val ro = rOut * k; val ri = rIn * k
		val ccx = cx * k; val ccy = cy * k
		g.pose().pushMatrix()
		g.pose().scale(1f / k, 1f / k)
		var dy = -ro.toInt()
		while (dy <= ro.toInt()) {
			val yy = dy + 0.5f
			if (abs(yy) <= ro) {
				val wo = sqrt(ro * ro - yy * yy)
				val y = (ccy + dy).toInt()
				if (abs(yy) < ri) {
					val wi = sqrt(ri * ri - yy * yy)
					g.fill(Math.round(ccx - wo), y, Math.round(ccx - wi), y + 1, color)
					g.fill(Math.round(ccx + wi), y, Math.round(ccx + wo), y + 1, color)
				} else g.fill(Math.round(ccx - wo), y, Math.round(ccx + wo), y + 1, color)
			}
			dy++
		}
		g.pose().popMatrix()
	}

	// ------------------------------------------------------------------ side panel

	private fun drawPanel(g: GuiGraphicsExtractor, s: ChessSession?, st: ChessState?, mouseX: Int, mouseY: Int) {
		val top = by
		val bottom = by + boardSize
		drawCard(g, s, st, px, top, !whiteBottom)
		drawCard(g, s, st, px, bottom - CARD_H, whiteBottom)

		val y0 = top + CARD_H + 4
		val y1 = bottom - CARD_H - 4
		val btnH = 14
		val btnTop = y1 - (btnH * 2 + 2)
		val statusY = btnTop - 12
		val listBottom = statusY - 2

		// Move list
		g.fill(px, y0, px + pw, listBottom, Theme.SETTING)
		g.fill(px, y0, px + pw, y0 + 11, Theme.HEADER)
		g.text(font, "Moves", px + 4, y0 + 2, Theme.TEXT_DIM, false)
		val rows = st?.moveRows() ?: emptyList()
		val listTop = y0 + 12
		val listH = listBottom - listTop - 1
		val contentH = rows.size * ROW_H
		moveScroll = moveScroll.coerceIn(0, (contentH - listH).coerceAtLeast(0))
		if (listH > 0) {
			g.enableScissor(px, listTop, px + pw, listTop + listH)
			val lastIdx = (st?.sans?.size ?: 0) - 1
			val blackFirst = st != null && !Position.fromFen(st.startFen).whiteToMove
			for ((i, row) in rows.withIndex()) {
				val y = listTop + 1 + i * ROW_H - moveScroll
				if (y + ROW_H < listTop || y > listTop + listH) continue
				if (i % 2 == 1) g.fill(px, y - 1, px + pw, y + ROW_H - 1, 0x18FFFFFF)
				g.text(font, "${row.first}.", px + 4, y, Theme.TEXT_DIM, false)
				val wIdx = if (blackFirst) i * 2 - 1 else i * 2
				g.text(font, row.second, px + 26, y, if (wIdx == lastIdx) Theme.accent(0xFF) else Theme.TEXT, false)
				g.text(font, row.third, px + 26 + (pw - 30) / 2, y, if (wIdx + 1 == lastIdx) Theme.accent(0xFF) else Theme.TEXT, false)
			}
			g.disableScissor()
			if (rows.isEmpty()) g.text(font, "No moves yet", px + 4, listTop + 2, Theme.TEXT_DIM, false)
		}

		// Status line
		val status = statusText(s, st)
		if (status != null) {
			// A leading "!" marks important lines (accent colored).
			val important = status.startsWith("!")
			g.text(font, font.plainSubstrByWidth(status.removePrefix("!"), pw - 4), px + 2, statusY + 2,
				if (important) Theme.accent(0xFF) else Theme.TEXT_DIM, false)
		}

		// Buttons
		val half = (pw - 2) / 2
		val third = (pw - 4) / 3
		if (s == null) return
		if (st == null) {
			button(px, btnTop + btnH + 2, half, btnH, "Flip", true) { flip() }
			return
		}
		val r1 = btnTop
		val r2 = btnTop + btnH + 2
		when {
			st.over -> {
				button(px, r1, half, btnH, "Rematch", true, primary = true) { rematch() }
				button(px + half + 2, r1, half, btnH, if (overlayHidden) "Result" else "Board", true) { overlayHidden = !overlayHidden }
			}
			s.canAcceptDraw -> {
				button(px, r1, half, btnH, "Accept draw", true, primary = true) { s.acceptDraw() }
				button(px + half + 2, r1, half, btnH, "Decline", true) { s.declineDraw() }
			}
			s.canClaimWin -> {
				button(px, r1, half, btnH, "Claim win", true, primary = true) { s.claimWin() }
				button(px + half + 2, r1, half, btnH, "Resign", true) { s.resign() }
			}
			else -> {
				button(px, r1, half, btnH, "Resign", st.ply > 0 || s.mode != ChessSession.Mode.ONLINE) { s.resign() }
				button(px + half + 2, r1, half, btnH, if (s.drawOfferWhite != null) "Draw offered" else "Offer draw",
					s.drawOfferWhite == null && st.ply > 0) { s.offerDraw() }
			}
		}
		if (s.online) {
			button(px, r2, half, btnH, "Flip", true) { flip() }
			button(px + half + 2, r2, half, btnH, "Close", true) { onClose() }
		} else {
			button(px, r2, third, btnH, "Flip", true) { flip() }
			button(px + third + 2, r2, third, btnH, "Restart", true) { rematch() }
			button(px + 2 * third + 4, r2, third, btnH, "Setup", true) { ChessGame.clearSession(); session = null; flipped = false }
		}
	}

	private fun statusText(s: ChessSession?, st: ChessState?): String? {
		if (s == null || st == null) return null
		s.notice?.let { return "!$it" }
		if (st.over) return "${st.result?.headline} · ${st.result?.reason}"
		val gone = s.claimSecondsLeft()
		if (gone != null) return if (gone > 0) "!Opponent left. Claim win in ${gone}s" else "!Opponent left. You may claim the win."
		s.drawOfferWhite?.let { w ->
			return if (s.canAcceptDraw) "!${s.playerName(w)} offers a draw" else "Draw offered…"
		}
		if (s.aiThinking) return "AI is thinking…"
		val side = if (st.pos.whiteToMove) "White" else "Black"
		return if (st.pos.inCheck()) "!$side to move · Check!" else "$side to move"
	}

	private fun drawCard(g: GuiGraphicsExtractor, s: ChessSession?, st: ChessState?, x: Int, y: Int, white: Boolean) {
		val active = st != null && !st.over && st.pos.whiteToMove == white
		g.fill(x, y, x + pw, y + CARD_H, Theme.MODULE)
		if (active) g.fill(x, y, x + 2, y + CARD_H, Theme.accent(0xFF))
		// Color chip
		g.fill(x + 5, y + 5, x + 12, y + 12, if (white) 0xFFEEE8DC.toInt() else 0xFF2A2A33.toInt())
		g.outline(x + 5, y + 5, 7, 7, 0xFF000000.toInt())
		val name = s?.playerName(white) ?: when {
			ChessGame.soloMode == ChessSession.Mode.HOTSEAT -> if (white) "White" else "Black"
			else -> if (white == whiteBottom) "You" else "Skyparty AI"
		}
		val clockW = if (s != null && s.clockMs > 0) 40 else 0
		g.text(font, font.plainSubstrByWidth(name, pw - 20 - clockW), x + 16, y + 5, Theme.TEXT, false)
		// Captured pieces + material advantage
		if (st != null) {
			var cx = x + 14
			for (p in st.capturedBy(white)) {
				ChessSprites.draw(g, p, cx.toFloat() - 1, y + 15f, 11f, gs, shadow = false)
				cx += 6
			}
			val diff = st.materialDiff() * (if (white) 1 else -1)
			if (diff > 0) g.text(font, "+$diff", cx + 7, y + 18, Theme.TEXT_DIM, false)
		}
		// Clock
		if (s != null && s.clockMs > 0) {
			val ms = s.remaining(white)
			val running = s.clockRunning(white)
			val cx0 = x + pw - clockW - 3
			val low = ms in 0..20_000
			val bg = when {
				running && low -> 0xFFB02A2A.toInt()
				running -> Theme.accent(0xE0)
				else -> Theme.SETTING
			}
			g.fill(cx0, y + 4, cx0 + clockW, y + CARD_H - 4, bg)
			val text = formatClock(ms)
			g.text(font, text, cx0 + (clockW - font.width(text)) / 2, y + (CARD_H - 8) / 2 + 1,
				if (running || !low) Theme.TEXT else 0xFFFF6060.toInt(), running)
		}
	}

	private fun formatClock(ms: Long): String {
		if (ms < 0) return "--:--"
		return if (ms < 10_000) {
			val tenths = ms / 100
			"0:%02d.%d".format(tenths / 10, tenths % 10)
		} else {
			val secs = (ms + 999) / 1000
			"%d:%02d".format(secs / 60, secs % 60)
		}
	}

	// ------------------------------------------------------------------ overlays

	private fun panelBox(g: GuiGraphicsExtractor, w: Int, h: Int): Pair<Int, Int> {
		val x = bx + (boardSize - w) / 2
		val y = by + (boardSize - h) / 2
		g.fill(bx, by, bx + boardSize, by + boardSize, 0x90000000.toInt())
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, Theme.accent(0xA0))
		g.fill(x, y, x + w, y + h, 0xF0101016.toInt())
		g.fill(x, y, x + w, y + 2, Theme.accent(0xFF))
		return Pair(x, y)
	}

	private fun bigText(g: GuiGraphicsExtractor, text: String, cx: Int, y: Int, color: Int, scale: Float) {
		g.pose().pushMatrix()
		g.pose().translate(cx.toFloat(), y.toFloat())
		g.pose().scale(scale, scale)
		g.text(font, text, -font.width(text) / 2, 0, color, true)
		g.pose().popMatrix()
	}

	private fun drawSetup(g: GuiGraphicsExtractor) {
		val w = minOf(170, boardSize - 8)
		val ai = ChessGame.soloMode == ChessSession.Mode.AI
		val rowsN = if (ai) 4 else 2
		val h = 36 + rowsN * 16 + 22
		val (x, y) = panelBox(g, w, h)
		bigText(g, "Chess", x + w / 2, y + 8, Theme.TEXT, 2f)
		var ry = y + 32
		val st = ChessGame.soloSettings
		fun row(label: String, value: String, onClick: (Int) -> Unit) {
			g.text(font, label, x + 8, ry + 3, Theme.TEXT_DIM, false)
			button(x + w - 88, ry, 80, 14, value, true, action = onClick)
			ry += 16
		}
		row("Mode", ChessGame.soloMode.label) {
			ChessGame.soloMode = if (ChessGame.soloMode == ChessSession.Mode.AI) ChessSession.Mode.HOTSEAT else ChessSession.Mode.AI
		}
		if (ai) {
			row("Difficulty", st.difficulty.label) { d -> ChessGame.soloSettings = st.copy(difficulty = cycle(st.difficulty, d)) }
			row("Play as", st.color.label) { d -> ChessGame.soloSettings = st.copy(color = cycle(st.color, d)) }
		}
		row("Clock", st.clock.label) { d -> ChessGame.soloSettings = st.copy(clock = cycle(st.clock, d)) }
		button(x + 8, ry + 4, w - 16, 16, "Start game", true, primary = true) {
			session = ChessGame.startSolo()
			flipped = false
			seenCounter = -1
		}
	}

	private fun drawOnlineLobby(g: GuiGraphicsExtractor) {
		val s = session ?: return
		val w = minOf(180, boardSize - 8)
		val h = 96
		val (x, y) = panelBox(g, w, h)
		bigText(g, "Online chess", x + w / 2, y + 8, Theme.TEXT, 1.5f)
		val others = s.room?.members?.size?.minus(1) ?: 0
		g.centeredText(font, if (others > 0) "Opponent connected" else "Waiting for an opponent…", x + w / 2, y + 26, Theme.TEXT_DIM)
		g.centeredText(font, "Either player can start. Colors are random.", x + w / 2, y + 38, Theme.TEXT_DIM)
		g.text(font, "Clock", x + 8, y + 55, Theme.TEXT_DIM, false)
		button(x + w - 88, y + 52, 80, 14, onlineClock.label, true) { d -> onlineClock = cycle(onlineClock, d) }
		button(x + 8, y + 72, w - 16, 16, "Start game", true, primary = true) { s.newGame(onlineClock) }
	}

	private fun drawGameOver(g: GuiGraphicsExtractor, s: ChessSession, st: ChessState) {
		val r = st.result ?: return
		val w = minOf(170, boardSize - 8)
		val h = 78
		val (x, y) = panelBox(g, w, h)
		val color = when {
			r.winnerWhite == null -> Theme.TEXT
			s.controls(r.winnerWhite) && !s.controls(!r.winnerWhite) -> 0xFF6BE07A.toInt()
			s.controls(!r.winnerWhite) && !s.controls(r.winnerWhite) -> 0xFFFF6B6B.toInt()
			else -> Theme.accent(0xFF)
		}
		val headline = when {
			r.winnerWhite != null && s.controls(r.winnerWhite) && !s.controls(!r.winnerWhite) -> "You win!"
			r.winnerWhite != null && s.controls(!r.winnerWhite) && !s.controls(r.winnerWhite) -> "You lose"
			else -> r.headline
		}
		bigText(g, headline, x + w / 2, y + 10, color, 2f)
		g.centeredText(font, if (headline == r.headline) r.reason else "${r.headline} · ${r.reason}", x + w / 2, y + 32, Theme.TEXT_DIM)
		val bw = (w - 20) / 3
		button(x + 6, y + h - 22, bw, 16, "Rematch", true, primary = true) { rematch() }
		button(x + 10 + bw, y + h - 22, bw, 16, "Board", true) { overlayHidden = true }
		button(x + 14 + 2 * bw, y + h - 22, bw, 16, "Close", true) { onClose() }
	}

	private fun rematch() {
		val s = session ?: return
		if (s.online) {
			s.newGame(ChessSession.Clock.entries.firstOrNull { it.seconds * 1000L == s.clockMs } ?: ChessSession.Clock.NONE)
		} else {
			session = ChessGame.startSolo()
			seenCounter = -1
		}
		selected = -1
		overlayHidden = false
	}

	private fun <E : Enum<E>> cycle(v: E, dir: Int): E {
		val all = v.declaringJavaClass.enumConstants
		return all[(v.ordinal + (if (dir == 1) all.size - 1 else 1)) % all.size]
	}

	// ------------------------------------------------------------------ buttons

	private fun button(x: Int, y: Int, w: Int, h: Int, label: String, enabled: Boolean, primary: Boolean = false, action: (Int) -> Unit) {
		buttons += Btn(x, y, w, h, label, enabled, primary, action)
	}

	private fun drawButtons(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
		for (b in buttons) {
			val hover = b.enabled && b.contains(mouseX.toDouble(), mouseY.toDouble())
			val bg = when {
				!b.enabled -> 0xC0141418.toInt()
				b.primary -> Theme.accent(if (hover) 0xFF else 0xC8)
				hover -> Theme.MODULE_HOVER
				else -> Theme.MODULE
			}
			g.fill(b.x, b.y, b.x + b.w, b.y + b.h, bg)
			if (!b.primary) g.fill(b.x, b.y + b.h - 1, b.x + b.w, b.y + b.h, if (hover) Theme.accent(0xFF) else Theme.accent(0x50))
			val text = font.plainSubstrByWidth(b.label, b.w - 4)
			g.text(font, text, b.x + (b.w - font.width(text)) / 2, b.y + (b.h - 8) / 2 + 1,
				if (b.enabled) Theme.TEXT else Theme.TEXT_DIM, b.primary)
		}
	}

	// ------------------------------------------------------------------ input

	private val modal: Boolean get() {
		val s = session ?: return true
		val st = s.state ?: return s.online
		return st.over && !overlayHidden
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val mx = event.x(); val my = event.y()
		buttons.lastOrNull { it.contains(mx, my) }?.let { b ->
			if (b.enabled) b.action(event.button())
			return true
		}
		if (modal) return true
		val s = session ?: return true
		val st = s.state ?: return true
		if (promoFrom >= 0) {
			val idx = promoSquares().indexOfFirst { (x, y) -> mx >= x && mx < x + sq && my >= y && my < y + sq }
			if (idx >= 0) s.submitMove(Move(promoFrom, promoTo, PROMO_TYPES[idx]), true)
			promoFrom = -1
			selected = -1
			return true
		}
		if (event.button() != 0) { selected = -1; return true }
		val target = squareAt(mx, my)
		if (target < 0) { selected = -1; return super.mouseClicked(event, doubleClick) }
		if (selected >= 0 && selected != target && tryMove(selected, target, true)) return true
		val p = st.pos.board[target]
		if (p != 0 && (p > 0) == st.pos.whiteToMove && s.controls(p > 0) && !st.over) {
			selected = target
			dragFrom = target
			dragging = false
			pressX = mx; pressY = my
		} else selected = -1
		return true
	}

	override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
		if (dragFrom >= 0 && !dragging && (abs(event.x() - pressX) > 2 || abs(event.y() - pressY) > 2)) dragging = true
		return true
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (dragFrom >= 0) {
			val from = dragFrom
			val wasDragging = dragging
			dragFrom = -1
			dragging = false
			if (wasDragging) {
				val target = squareAt(event.x(), event.y())
				if (target >= 0 && target != from) tryMove(from, target, false)
			}
			return true
		}
		return super.mouseReleased(event)
	}

	/** Tries the move from→to; opens the promotion picker when needed. */
	private fun tryMove(from: Int, to: Int, animate: Boolean): Boolean {
		val s = session ?: return false
		val st = s.state ?: return false
		val candidates = st.legal.filter { it.from == from && it.to == to }
		if (candidates.isEmpty()) return false
		if (candidates.any { it.promo != 0 }) {
			promoFrom = from
			promoTo = to
			selected = from
			return true
		}
		selected = -1
		return s.submitMove(candidates.first(), animate)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		if (mouseX >= px && mouseX < px + pw) moveScroll -= (scrollY * ROW_H * 2).toInt()
		return true
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		when (event.key()) {
			GLFW.GLFW_KEY_ESCAPE -> if (promoFrom >= 0) { promoFrom = -1; selected = -1; return true }
			GLFW.GLFW_KEY_F -> { flip(); return true }
		}
		return super.keyPressed(event)
	}

	companion object {
		private const val PANEL_W = 150
		private const val MARGIN = 8
		private const val GAP = 12
		private const val COORD = 12
		private const val CARD_H = 30
		private const val ROW_H = 10
		private const val ANIM_MS = 140
		private val PROMO_TYPES = intArrayOf(Piece.QUEEN, Piece.KNIGHT, Piece.ROOK, Piece.BISHOP)
		private val LIGHT = 0xFFF1D7F5.toInt() // party lavender
		private val DARK = 0xFF9468C2.toInt() // party purple
	}
}
