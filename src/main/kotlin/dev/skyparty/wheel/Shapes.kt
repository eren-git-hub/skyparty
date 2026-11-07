package dev.skyparty.wheel

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filled circles, rings and pie sectors built from horizontal pixel spans (GUI has no triangle API).
 *
 * All functions take GUI coordinates but draw at the window's native resolution
 * (pose scaled by 1/guiScale) so edges stay smooth at any GUI scale.
 * Angles are "turns" (0..1), clockwise, 0 = 12 o'clock.
 */
object Shapes {
	private const val TAU = PI * 2

	val scale: Int get() = Minecraft.getInstance().window.guiScale.coerceAtLeast(1)

	/** Runs [block] with the pose scaled to native pixels; [block] receives the native scale factor. */
	inline fun native(g: GuiGraphicsExtractor, block: (s: Int) -> Unit) {
		val s = scale
		g.pose().pushMatrix()
		g.pose().scale(1f / s, 1f / s)
		block(s)
		g.pose().popMatrix()
	}

	/** Filled disk (or ring when [inner] > 0) in one color. */
	fun disk(g: GuiGraphicsExtractor, cx: Double, cy: Double, r: Double, color: Int, inner: Double = 0.0) {
		native(g) { s -> spans(g, cx * s, cy * s, r * s, inner * s) { x0, x1, y -> g.fill(x0, y, x1, y + 1, color) } }
	}

	/**
	 * Pie chart: slice i covers [starts[i], starts[i+1]) in wheel space, the wheel is turned by [rotation].
	 * [color] returns the ARGB for a slice index (alpha 0 = skip).
	 */
	fun sectors(
		g: GuiGraphicsExtractor,
		cx: Double, cy: Double, r: Double, inner: Double,
		starts: DoubleArray, rotation: Double,
		color: (Int) -> Int,
	) {
		val n = starts.size - 1
		if (n <= 0) return
		native(g) { s ->
			val ncx = cx * s
			val ncy = cy * s
			val dirs = DoubleArray(n * 2)
			for (k in 0 until n) {
				val a = (starts[k] + rotation) * TAU
				dirs[k * 2] = sin(a)
				dirs[k * 2 + 1] = -cos(a)
			}
			val cuts = DoubleArray(n + 2)
			spansRow(ncx, ncy, r * s, inner * s) { x0, x1, y, dy ->
				// Where each slice boundary ray crosses this row
				var m = 0
				cuts[m++] = x0
				for (k in 0 until n) {
					val dirY = dirs[k * 2 + 1]
					if (dirY > -1e-9 && dirY < 1e-9) continue
					val t = dy / dirY
					if (t <= 0) continue
					val x = ncx + t * dirs[k * 2]
					if (x > x0 && x < x1) cuts[m++] = x
				}
				cuts[m++] = x1
				java.util.Arrays.sort(cuts, 1, m - 1)
				for (i in 0 until m - 1) {
					val a = cuts[i]
					val b = cuts[i + 1]
					val ia = a.roundToInt()
					val ib = b.roundToInt()
					if (ib <= ia) continue
					val slice = sliceAt(starts, angleOf((a + b) / 2 - ncx, dy) - rotation)
					val c = color(slice)
					if ((c ushr 24) != 0) g.fill(ia, y, ib, y + 1, c)
				}
			}
		}
	}

	/** Index of the slice containing wheel-space angle [turns]. */
	fun sliceAt(starts: DoubleArray, turns: Double): Int {
		val u = turns - floor(turns)
		val n = starts.size - 1
		for (i in 0 until n) if (u < starts[i + 1]) return i
		return n - 1
	}

	/** Clockwise angle from 12 o'clock in turns for an offset (dx right, dy down). */
	fun angleOf(dx: Double, dy: Double): Double {
		val a = atan2(dx, -dy) / TAU
		return if (a < 0) a + 1 else a
	}

	/** Thick line from the center outward at [turns], from radius [r0] to [r1] (GUI units). */
	fun ray(g: GuiGraphicsExtractor, cx: Double, cy: Double, turns: Double, r0: Double, r1: Double, width: Double, color: Int) {
		native(g) { s ->
			g.pose().pushMatrix()
			g.pose().translate((cx * s).toFloat(), (cy * s).toFloat())
			g.pose().rotate(((turns - 0.25) * TAU).toFloat())
			val hw = (width * s / 2).coerceAtLeast(0.5)
			g.pose().scale(1f, hw.toFloat())
			g.fill((r0 * s).roundToInt(), -1, (r1 * s).roundToInt(), 1, color)
			g.pose().popMatrix()
		}
	}

	/** Isosceles triangle with its tip at (tx, ty) pointing in direction [turns], GUI units. */
	fun triangle(g: GuiGraphicsExtractor, tx: Double, ty: Double, turns: Double, length: Double, halfWidth: Double, color: Int) {
		native(g) { s ->
			g.pose().pushMatrix()
			g.pose().translate((tx * s).toFloat(), (ty * s).toFloat())
			// Local frame: tip at origin, body extends toward -y (so the tip points "up" before rotating)
			g.pose().rotate((turns * TAU).toFloat())
			val len = (length * s).roundToInt()
			val hw = halfWidth * s
			for (row in 0 until len) {
				val w = hw * (row + 0.5) / len
				g.fill((-w).roundToInt(), row, w.roundToInt().coerceAtLeast(1), row + 1, color)
			}
			g.pose().popMatrix()
		}
	}

	/** Rounded rectangle (GUI units, corner radius in GUI px). */
	fun roundRect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, radius: Int, color: Int) {
		if (radius <= 0) {
			g.fill(x0, y0, x1, y1, color)
			return
		}
		native(g) { s ->
			val r = radius * s
			val ax = x0 * s
			val ay = y0 * s
			val bx = x1 * s
			val by = y1 * s
			g.fill(ax, ay + r, bx, by - r, color)
			for (i in 0 until r) {
				val dy = r - i - 0.5
				val inset = (r - sqrt((r * r - dy * dy).coerceAtLeast(0.0))).roundToInt()
				g.fill(ax + inset, ay + i, bx - inset, ay + i + 1, color)
				g.fill(ax + inset, by - i - 1, bx - inset, by - i, color)
			}
		}
	}

	/** Calls [out] with native pixel spans (x0, x1, y) covering a disk/ring at native coordinates. */
	private inline fun spans(g: GuiGraphicsExtractor, cx: Double, cy: Double, r: Double, inner: Double, out: (Int, Int, Int) -> Unit) {
		spansRow(cx, cy, r, inner) { x0, x1, y, _ ->
			val a = x0.roundToInt()
			val b = x1.roundToInt()
			if (b > a) out(a, b, y)
		}
	}

	private inline fun spansRow(cx: Double, cy: Double, r: Double, inner: Double, out: (Double, Double, Int, Double) -> Unit) {
		val top = floor(cy - r).toInt()
		val bottom = ceil(cy + r).toInt()
		for (y in top..bottom) {
			val dy = y + 0.5 - cy
			if (dy <= -r || dy >= r) continue
			val half = sqrt(r * r - dy * dy)
			if (inner > 0 && dy > -inner && dy < inner) {
				val hi = sqrt(inner * inner - dy * dy)
				out(cx - half, cx - hi, y, dy)
				out(cx + hi, cx + half, y, dy)
			} else {
				out(cx - half, cx + half, y, dy)
			}
		}
	}
}
