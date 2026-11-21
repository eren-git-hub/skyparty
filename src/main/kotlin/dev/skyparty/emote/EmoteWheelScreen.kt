package dev.skyparty.emote

import com.mojang.blaze3d.platform.InputConstants
import dev.skyparty.module.impl.pets.EmotesModule
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Emote wheel in the Skyparty look: a ring of segments with pixel icons, numbers around it, a rainbow rim and
 * the pointed emote in the middle. Hold the key (B), point, and let go – or left-click, or press 1-7.
 * [holdKey] is the key that opened it (null = opened by command: click or number only).
 */
class EmoteWheelScreen(private val holdKey: Int?) : Screen(Component.literal("Emotes")) {
	private class Slot(val label: String, val icon: List<String>, val on: () -> Boolean, val action: () -> Unit)

	private val slots = Emotes.Emote.entries.map { e -> Slot(e.label, ICONS.getValue(e.id), { Emotes.active == e }) { Emotes.toggle(e) } } +
		Slot("Campfire", ICONS.getValue("campfire"), { Campfire.own != null }) { Campfire.toggle() }

	private var hovered = -1
	private val openedAt = System.currentTimeMillis()

	// Ring geometry, rebuilt when the window size changes: horizontal runs of pixels of the same kind.
	private var builtFor = -1
	private var runs = IntArray(0)
	private var runCount = 0
	private var rOut = 0
	private var rIn = 0
	private var rCenter = 0

	override fun isPauseScreen() = false

	override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		g.fillGradient(0, 0, width, height, 0x50100820, 0x80100820.toInt())
	}

	override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		super.extractRenderState(g, mouseX, mouseY, partialTick)
		val cx = width / 2
		val cy = height / 2
		build()
		val n = slots.size
		val dx = mouseX - cx.toDouble()
		val dy = mouseY - cy.toDouble()
		hovered = if (hypot(dx, dy) < 12) -1 else segmentAt(dx, dy, n)

		// Ring
		val accent = Theme.accent
		for (k in 0 until runCount) {
			val y = runs[k * 4]; val x1 = runs[k * 4 + 1]; val x2 = runs[k * 4 + 2]; val kind = runs[k * 4 + 3]
			val color = when {
				kind == CENTER -> 0xE8150F22.toInt()
				kind >= RIM -> ColorUtil.withAlpha(Theme.rainbow((kind - RIM) / 72f), 0xE0)
				kind >= INNER -> {
					val i = kind - INNER
					when { i == hovered -> 0xFFFFFFFF.toInt(); slots[i].on() -> 0xFF7CE38B.toInt(); else -> 0xFF3A2E58.toInt() }
				}
				else -> {
					val i = kind - SEG
					when {
						i == hovered -> ColorUtil.withAlpha(accent, 0xC8)
						slots[i].on() -> 0xD0234032.toInt()
						i % 2 == 0 -> 0xD81E1630.toInt()
						else -> 0xD8241B3A.toInt()
					}
				}
			}
			g.fill(cx + x1, cy + y, cx + x2 + 1, cy + y + 1, color)
		}

		// Icons and numbers
		val ps = (rOut / 42).coerceIn(1, 3)
		val mid = (rIn + rOut) / 2f
		for ((i, s) in slots.withIndex()) {
			val a = i * 2 * PI / n
			val ix = cx + (sin(a) * mid).toInt()
			val iy = cy - (cos(a) * mid).toInt()
			val hot = i == hovered
			drawIcon(g, s.icon, ix, iy, if (hot) ps + 1 else ps, if (hot) 1f else 0.78f)
			val nx = cx + (sin(a) * (rOut + 9)).toInt()
			val ny = cy - (cos(a) * (rOut + 9)).toInt()
			g.centeredText(font, "${i + 1}", nx, ny - 4, if (hot) Theme.TEXT else Theme.TEXT_DIM)
		}

		// Middle: the pointed emote, big, with its name.
		val center = slots.getOrNull(hovered)
		if (center != null) {
			drawIcon(g, center.icon, cx, cy - 5, (ps + 1).coerceAtMost(rCenter / 9), 1f)
			val label = if (center.on()) "§a${center.label} §7(stop)" else center.label
			g.centeredText(font, label, cx, cy + rCenter / 2 - 2, Theme.TEXT)
		} else {
			g.centeredText(font, "§d✦", cx, cy - 9, Theme.TEXT)
			g.centeredText(font, "Emotes", cx, cy + 2, Theme.TEXT_DIM)
		}
		val hint = if (holdKey != null) "Point and let go · click · 1-${n}" else "Click or press 1-${n}"
		g.centeredText(font, "§7$hint §8· moving ends emotes", cx, (cy - rOut - 24).coerceAtLeast(4), Theme.TEXT)

		// Let go of the key: pick what is pointed at.
		if (holdKey != null && System.currentTimeMillis() - openedAt > 120 && !InputConstants.isKeyDown(minecraft.window, holdKey)) choose(hovered)
	}

	private fun segmentAt(dx: Double, dy: Double, n: Int): Int {
		val a = (Math.toDegrees(atan2(dx, -dy)) + 360) % 360
		return ((a + 180.0 / n) / (360.0 / n)).toInt() % n
	}

	/** Splits the ring into runs (row, x1, x2, kind) once per window size. */
	private fun build() {
		val key = width * 10_000 + height
		if (key == builtFor) return
		builtFor = key
		val n = slots.size
		rOut = (minOf(width, height) * 0.36f).toInt().coerceIn(62, 118)
		rIn = (rOut * 0.44f).toInt()
		rCenter = rIn - 4
		val list = ArrayList<Int>()
		for (y in -rOut..rOut) {
			var runStart = 0
			var runKind = SKIP
			for (x in -rOut..rOut + 1) {
				val kind = if (x > rOut) SKIP else kindAt(x + 0.5, y + 0.5, n)
				if (kind != runKind) {
					if (runKind != SKIP) { list += y; list += runStart; list += x - 1; list += runKind }
					runStart = x
					runKind = kind
				}
			}
		}
		runs = list.toIntArray()
		runCount = runs.size / 4
	}

	private fun kindAt(x: Double, y: Double, n: Int): Int {
		val r = sqrt(x * x + y * y)
		if (r < rCenter) return CENTER
		if (r < rIn || r >= rOut) return SKIP
		val a = (Math.toDegrees(atan2(x, -y)) + 360) % 360
		val seg = 360.0 / n
		val rel = (a + seg / 2) % seg
		// A thin gap between segments (in pixels along the ring).
		if (minOf(rel, seg - rel) * PI / 180 * r < 1.1) return SKIP
		val i = segmentAt(x, y, n)
		return when {
			r < rIn + 3 -> INNER + i
			r >= rOut - 2 -> RIM + ((a / 5).toInt() % 72)
			else -> SEG + i
		}
	}

	private fun drawIcon(g: GuiGraphicsExtractor, icon: List<String>, cx: Int, cy: Int, ps: Int, bright: Float) {
		val h = icon.size
		val w = icon.maxOf { it.length }
		val x0 = cx - w * ps / 2
		val y0 = cy - h * ps / 2
		for ((row, line) in icon.withIndex()) for ((col, ch) in line.withIndex()) {
			val c = when (ch) {
				'#' -> 0xFFEDE8F7.toInt()
				'b' -> 0xFFB8834F.toInt()
				'o' -> 0xFFFF8A2A.toInt()
				'y' -> 0xFFFFD84A.toInt()
				'z' -> 0xFFAFC8FF.toInt()
				'c' -> EmotesModule.chairColor.value or 0xFF000000.toInt()
				'w' -> 0xFFFFFFFF.toInt()
				'g' -> 0xFF8A8A96.toInt()
				else -> continue
			}
			g.fill(x0 + col * ps, y0 + row * ps, x0 + (col + 1) * ps, y0 + (row + 1) * ps, ColorUtil.shade(c, bright))
		}
	}

	private fun choose(i: Int) {
		onClose()
		slots.getOrNull(i)?.action?.invoke()
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		// Left-click does the same as letting go of the key; anything else just closes.
		if (event.button() == 0) { choose(hovered); return true }
		return super.mouseClicked(event, doubleClick)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val k = event.key()
		if (k in GLFW.GLFW_KEY_1 until GLFW.GLFW_KEY_1 + slots.size) { choose(k - GLFW.GLFW_KEY_1); return true }
		return super.keyPressed(event)
	}

	companion object {
		private const val SKIP = -1
		private const val CENTER = 0
		private const val SEG = 100
		private const val INNER = 200
		private const val RIM = 300

		/** Pixel icons: # figure, c chair fabric, w stripe, b wood, o/y fire, z sleep, g stone. */
		private val ICONS = mapOf(
			"chair" to listOf(
				"..###.......",
				"..###.......",
				"#.###.#.....",
				".#.#.#......",
				"c..###......",
				"cw..#######.",
				".cw.###...#.",
				".cw........",
				"..bbbbbbbb..",
				"..b......b..",
			),
			"wave" to listOf(
				"#..........",
				".#.###.....",
				".#.###.....",
				"..####.....",
				"...###.....",
				"...####....",
				"...###.#...",
				"...###.#...",
				"...#.#.....",
				"...#.#.....",
				"..##.##....",
			),
			"dance" to listOf(
				"....###..#.",
				"....###.#..",
				"....###.#..",
				".....#.#...",
				"...#####...",
				"..#.###....",
				".#..###....",
				"....###....",
				"....#..#...",
				"...#....#..",
				"..##....##.",
			),
			"sleep" to listOf(
				"..........zz",
				"...........z",
				"........z...",
				"............",
				"###.........",
				"###########.",
				"###.#....###",
			),
			"facepalm" to listOf(
				"...###.....",
				"..####.....",
				"..####.....",
				"..#.#......",
				"..#####....",
				"...###.#...",
				"...###.#...",
				"...###.....",
				"...#.#.....",
				"...#.#.....",
				"..##.##....",
			),
			"cheer" to listOf(
				"#.......#",
				"#..###..#",
				".#.###.#.",
				".#.###.#.",
				"..#####..",
				"...###...",
				"...###...",
				"...###...",
				"...#.#...",
				"...#.#...",
				"..##.##..",
			),
			"campfire" to listOf(
				"....y....",
				"...yoy...",
				"..yooy...",
				"..yoooy..",
				".yooooy..",
				"..yoooy..",
				".bbbbbbb.",
				"g.b.b.b.g",
				".bbbbbbb.",
			),
		)
	}
}
