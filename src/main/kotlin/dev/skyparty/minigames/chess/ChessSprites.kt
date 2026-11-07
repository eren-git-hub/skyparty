package dev.skyparty.minigames.chess

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Hand-made 16x16 pixel-art pieces drawn with rectangle fills (no textures, no glyphs).
 * Symmetric pieces are defined as their left half and mirrored. Highlights/shading are derived
 * automatically from the body runs so every piece is lit from the top-left.
 *
 * Legend: '.' transparent, 'X' outline, 'o' body, 'd' engraved detail.
 */
object ChessSprites {
	private const val SIZE = 16

	private val HALVES = mapOf(
		Piece.PAWN to listOf(
			"........",
			"........",
			"......XX",
			".....Xoo",
			"....Xooo",
			"....Xooo",
			".....Xoo",
			"....XXXX",
			".....Xoo",
			".....Xoo",
			"....Xooo",
			"...Xoooo",
			"..XXXXXX",
			"..Xooooo",
			"..XXXXXX",
			"........",
		),
		Piece.ROOK to listOf(
			"........",
			"..XXX.XX",
			"..XoXXXo",
			"..Xooooo",
			"..XXXXXX",
			"...Xoooo",
			"...Xoooo",
			"...Xoooo",
			"...Xoooo",
			"...Xoooo",
			"..XXXXXX",
			"..Xooooo",
			".Xoooooo",
			".Xoooooo",
			".XXXXXXX",
			"........",
		),
		Piece.BISHOP to listOf(
			"......XX",
			".....Xoo",
			"......XX",
			".....Xoo",
			"....Xood",
			"...Xoddd",
			"...Xoood",
			"...Xoooo",
			"....Xooo",
			".....XXX",
			"....Xooo",
			"....XXXX",
			"..XXoooo",
			".Xoooooo",
			".XXXXXXX",
			"........",
		),
		Piece.QUEEN to listOf(
			"........",
			".X..X..X",
			".X..X..X",
			".XX.XX.X",
			".XoXXoXo",
			"..Xooooo",
			"..Xooooo",
			"...Xoooo",
			"...XXXXX",
			"....Xooo",
			"....Xooo",
			"...XXXXX",
			"..Xooooo",
			".Xoooooo",
			".XXXXXXX",
			"........",
		),
		Piece.KING to listOf(
			".......X",
			"......XX",
			".......X",
			"......XX",
			"..XXX.Xo",
			".XoooXXo",
			".Xoooooo",
			".Xoooooo",
			"..Xooooo",
			"...Xoooo",
			"...XXXXX",
			"....Xooo",
			"..XXXXXX",
			".Xoooooo",
			".XXXXXXX",
			"........",
		),
	)

	private val KNIGHT = listOf(
		"................",
		"......X.X.......",
		".....XoXoX......",
		"....XoooooXX....",
		"...XoodoooooX...",
		"..XoooooooooX...",
		".XooooooooooX...",
		".XoooXXoooooX...",
		".XXXX.XoooooX...",
		".....XooooooX...",
		"....XoooooooX...",
		"...XXXXXXXXXXX..",
		"..XooooooooooX..",
		".XooooooooooooX.",
		".XXXXXXXXXXXXXX.",
		"................",
	)

	/** Per-pixel roles after shading: 0 none, 1 outline, 2 body, 3 highlight, 4 shade, 5 detail. */
	private val GRIDS: Map<Int, Array<IntArray>> = buildMap {
		for ((type, half) in HALVES) put(type, shade(half.map { require(it.length == 8) { "sprite $type" }; it + it.reversed() }))
		put(Piece.KNIGHT, shade(KNIGHT.onEach { require(it.length == SIZE) { "knight sprite" } }))
	}

	private fun shade(rows: List<String>): Array<IntArray> {
		require(rows.size == SIZE)
		return Array(SIZE) { y ->
			val row = rows[y]
			val out = IntArray(SIZE) { x ->
				when (row[x]) { 'X' -> 1; 'o' -> 2; 'd' -> 5; else -> 0 }
			}
			// Light from the top-left: first body pixel of each run is highlighted, the last one(s) shaded.
			var x = 0
			while (x < SIZE) {
				if (out[x] != 2) { x++; continue }
				var end = x
				while (end + 1 < SIZE && out[end + 1] == 2) end++
				val len = end - x + 1
				if (len >= 3) out[x] = 3
				if (len >= 2) out[end] = 4
				if (len >= 7) out[end - 1] = 4
				x = end + 1
			}
			// Top row of a body area under an outline gets a highlight too.
			if (y > 0) for (i in 0 until SIZE) if (out[i] == 2 && rows[y - 1][i] == 'X' && y < 6) out[i] = 3
			out
		}
	}

	private val WHITE = intArrayOf(0, 0xFF1C1B22.toInt(), 0xFFEEE8DC.toInt(), 0xFFFFFFFF.toInt(), 0xFFC4BBAA.toInt(), 0xFF8E8676.toInt())
	private val BLACK = intArrayOf(0, 0xFF0A0A0E.toInt(), 0xFF34343F.toInt(), 0xFF575768.toInt(), 0xFF22222A.toInt(), 0xFF8A8AA0.toInt())

	/**
	 * Draws [piece] (signed) with its top-left at (x, y) in GUI coordinates, [size] GUI pixels wide.
	 * Pixels are snapped to the real framebuffer grid (pose scaled by 1/guiScale) to stay crisp.
	 */
	fun draw(g: GuiGraphicsExtractor, piece: Int, x: Float, y: Float, size: Float, guiScale: Int, alpha: Int = 0xFF, shadow: Boolean = true) {
		val grid = GRIDS[Piece.type(piece)] ?: return
		val pal = if (piece > 0) WHITE else BLACK
		val gs = guiScale.coerceAtLeast(1)
		val px = maxOf(1, (size * gs / SIZE).toInt())
		val total = px * SIZE
		val ox = Math.round(x * gs + (size * gs - total) / 2f)
		val oy = Math.round(y * gs + (size * gs - total) / 2f)
		g.pose().pushMatrix()
		g.pose().scale(1f / gs, 1f / gs)
		if (shadow) {
			val sa = (0x50 * alpha / 255) shl 24
			val off = maxOf(1, px / 2)
			for (row in 0 until SIZE) runs(grid[row]) { a, b, _ ->
				g.fill(ox + a * px + off, oy + row * px + off, ox + (b + 1) * px + off, oy + (row + 1) * px + off, sa)
			}
		}
		for (row in 0 until SIZE) runs(grid[row]) { a, b, role ->
			val c = (pal[role] and 0xFFFFFF) or ((pal[role] ushr 24) * alpha / 255 shl 24)
			g.fill(ox + a * px, oy + row * px, ox + (b + 1) * px, oy + (row + 1) * px, c)
		}
		g.pose().popMatrix()
	}

	/** Calls [block] for each horizontal run of equal non-zero roles: (startX, endX inclusive, role). */
	private inline fun runs(row: IntArray, block: (Int, Int, Int) -> Unit) {
		var x = 0
		while (x < SIZE) {
			val r = row[x]
			if (r == 0) { x++; continue }
			var e = x
			while (e + 1 < SIZE && row[e + 1] == r) e++
			block(x, e, r)
			x = e + 1
		}
	}
}
