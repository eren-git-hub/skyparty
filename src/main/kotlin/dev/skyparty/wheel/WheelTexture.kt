package dev.skyparty.wheel

import dev.skyparty.Skyparty
import dev.skyparty.util.ColorUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The wheel face drawn once into a texture and then only rotated each frame. Drawing the slices row by row
 * every frame cost ~14 ms (thousands of fills), which made the spin stutter on a busy client (Hypixel).
 * The image is rebuilt only when the slices, colors, size or GUI scale change.
 *
 * [Layer.FACE]: gold rim + slices (outer band darker, inner disk lighter) + white borders.
 * [Layer.HIGHLIGHT]: everything but the winning slice darkened, the winner brightened.
 */
class WheelTexture(private val name: String, private val layer: Layer) {
	enum class Layer { FACE, HIGHLIGHT }

	private val id = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "dynamic/$name")
	private var tex: DynamicTexture? = null
	private var size = 0
	private var key = ""

	/** Draws the layer centered at ([cx], [cy]) for a wheel of radius [r] (GUI units), turned by [rotation] turns. */
	fun draw(g: GuiGraphicsExtractor, cx: Double, cy: Double, r: Double, starts: DoubleArray, colors: IntArray, rotation: Double, winner: Int = -1) {
		val s = Shapes.scale
		var w = ceil((r + 7) * 2).toInt()
		if (w % 2 == 1) w++
		val px = w * s
		val k = "$px|$r|$winner|${starts.joinToString(",")}|${colors.joinToString(",")}"
		if (k != key || tex == null) rebuild(px, r * s, s, starts, colors, winner, k)
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(cx.toFloat(), cy.toFloat())
		pose.rotate((rotation * PI * 2).toFloat())
		g.blit(RenderPipelines.GUI_TEXTURED, id, -w / 2, -w / 2, 0f, 0f, w, w, w, w)
		pose.popMatrix()
	}

	private fun rebuild(px: Int, rr: Double, s: Int, starts: DoubleArray, colors: IntArray, winner: Int, k: String) {
		key = k
		val t = tex?.takeIf { size == px } ?: run {
			tex?.close()
			DynamicTexture({ "skyparty $name" }, px, px, true).also {
				Minecraft.getInstance().textureManager.register(id, it)
				tex = it
				size = px
			}
		}
		val img = t.pixels ?: return
		val c = px / 2.0
		val n = starts.size - 1
		for (y in 0 until px) for (x in 0 until px) {
			val dx = x + 0.5 - c
			val dy = y + 0.5 - c
			val d = sqrt(dx * dx + dy * dy)
			val argb = if (layer == Layer.FACE) face(d, dx, dy, rr, s, starts, colors, n) else highlight(d, dx, dy, rr, starts, n, winner)
			img.setPixel(x, y, argb)
		}
		t.upload()
	}

	/** Same layers the wheel used to draw with fills: rim rings (r+6 … r) and the slice bands. */
	private fun face(d: Double, dx: Double, dy: Double, rr: Double, s: Int, starts: DoubleArray, colors: IntArray, n: Int): Int {
		val outer = rr + 6 * s
		return when {
			d > outer -> 0
			d > rr + 5 * s -> edge(0xFF241C2E.toInt(), outer - d)
			d > rr + 4 * s -> 0xFFB98A2E.toInt()
			d > rr + 1 * s -> 0xFFE9BE55.toInt()
			d > rr -> 0xFF241C2E.toInt()
			n <= 0 -> 0xFF2A2A36.toInt()
			else -> {
				val a = Shapes.angleOf(dx, dy)
				val i = Shapes.sliceAt(starts, a)
				val base = colors[i]
				var col = when {
					d > rr - 3 * s -> ColorUtil.shade(base, 0.72f)
					d <= rr * 0.5 -> ColorUtil.shade(base, 1.1f)
					else -> base
				}
				// White border between slices (1 GUI px wide), from 20 % of the radius outwards.
				if (n > 1 && d > rr * 0.2) {
					val gap = minOf(angularGap(a, starts[i]), angularGap(a, starts[i + 1])) * PI * 2 * d
					if (gap < s * 0.5) col = blend(col, 0x90FFFFFF.toInt())
				}
				col
			}
		}
	}

	private fun highlight(d: Double, dx: Double, dy: Double, rr: Double, starts: DoubleArray, n: Int, winner: Int): Int {
		if (d > rr || n <= 0) return 0
		val i = Shapes.sliceAt(starts, Shapes.angleOf(dx, dy))
		return if (i == winner) 0x50FFFFFF else 0x78000000
	}

	private fun angularGap(a: Double, boundary: Double): Double {
		val diff = abs(a - (boundary - kotlin.math.floor(boundary)))
		return minOf(diff, 1 - diff)
	}

	/** Anti-aliased outer edge: fade the last pixel. */
	private fun edge(color: Int, coverage: Double) =
		if (coverage >= 1) color else ColorUtil.withAlpha(color, (coverage.coerceIn(0.0, 1.0) * 255).toInt())

	private fun blend(base: Int, over: Int): Int {
		val a = (over ushr 24) / 255f
		fun ch(sh: Int) = (((base shr sh) and 0xFF) * (1 - a) + ((over shr sh) and 0xFF) * a).toInt()
		return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
	}
}
