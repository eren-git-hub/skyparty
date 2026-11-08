package dev.skyparty.wheel

import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.HubTabs
import dev.skyparty.module.impl.party.DecisionWheel
import dev.skyparty.party.PartyTracker
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** `/sp wheel` (a tab of the main menu): the wheel, the entry editor and the room panel with players. */
class WheelScreen : GameScreen("Decision Wheel") {
	override val backToHub = false

	override fun init() {
		super.init()
		PartyTracker.autoCheck()
	}
	private class Button(
		val x: Int, val y: Int, val w: Int, val h: Int,
		val label: String, val enabled: Boolean, val style: Int, val action: () -> Unit,
	) {
		fun hit(mx: Int, my: Int) = mx >= x && mx < x + w && my >= y && my < y + h
	}

	private class Confetti(
		var x: Double, var y: Double, var vx: Double, var vy: Double,
		val color: Int, var phase: Double, val spin: Double, var life: Double,
	)

	private val buttons = mutableListOf<Button>()
	private val confetti = mutableListOf<Confetti>()
	private var input = ""
	private var inputFocused = false
	private var scroll = 0
	private var lastFrameNs = System.nanoTime()

	/** Tests: frames drawn and the longest gap between two frames while the wheel spins. */
	var debugFrames = 0
	var debugMaxGapMs = 0.0
	var debugWheelNs = 0L
	private var seenSerial = WheelSession.resultSerial
	private var resultShownAt = 0L
	private var lastPointerSlice = -1
	private var kick = 0.0
	private var toast: String? = null
	private var toastAt = 0L

	// Layout (GUI px), computed every frame
	private var cx = 0.0
	private var cy = 0.0
	private var radius = 60.0
	private var panelX = 0
	private var roomX = 0
	private var top = 40
	private var listTop = 0
	private var listBottom = 0
	private val rowH = 12

	override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		g.fillGradient(0, 0, width, height, 0xF00B0B12.toInt(), 0xF4140F1E.toInt())
	}

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val now = System.nanoTime()
		val dt = ((now - lastFrameNs) / 1e9).coerceIn(0.0, 0.1)
		lastFrameNs = now
		if (WheelSession.spinning) { debugFrames++; debugMaxGapMs = maxOf(debugMaxGapMs, dt * 1000) }
		buttons.clear()

		top = hubChrome(g, HubTabs.WHEEL)
		layout()
		if (WheelSession.resultSerial != seenSerial) {
			seenSerial = WheelSession.resultSerial
			resultShownAt = System.currentTimeMillis()
			burstConfetti()
		}
		val t0 = System.nanoTime()
		drawWheel(g, dt)
		if (WheelSession.spinning) debugWheelNs += System.nanoTime() - t0
		drawWheelControls(g)
		drawPanel(g, mouseX, mouseY)
		drawRoomPanel(g, mouseX, mouseY)
		drawConfetti(g, dt)
		drawButtons(g, mouseX, mouseY)
		drawToast(g)
	}

	private fun layout() {
		val roomW = (width * 0.25).toInt().coerceIn(112, 170)
		roomX = width - roomW - 8
		val panelW = (width * 0.30).toInt().coerceIn(128, 200)
		panelX = roomX - panelW - 6
		val areaW = panelX - 8 - 8
		val top = this.top + 8.0
		val availH = height - top - 40
		radius = min(areaW / 2.0 - 12, availH / 2.0 - 6).coerceAtLeast(30.0)
		cx = 8 + areaW / 2.0
		cy = top + 6 + radius
	}

	// --- Room panel (rightmost) -----------------------------------------------------------------

	private var playerScroll = 0

	/** Wheel rooms (like the game rooms in the games tab), host settings and the players with "+" buttons. */
	private fun drawRoomPanel(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val x0 = roomX
		val x1 = width - 8
		val w = x1 - x0
		val y0 = top + 6
		val y1 = height - 8
		Shapes.roundRect(g, x0, y0, x1, y1, 4, 0xC0101016.toInt())
		val ix = x0 + 5
		val iw = w - 10
		var y = y0 + 5
		g.text(font, "§lWheel rooms", ix, y, Theme.accent, false)
		y += 12

		// One row per room you can be in: the party wheel (when in a party), your code room, or local.
		fun row(label: String, sub: String, current: Boolean) {
			g.fill(ix, y, ix + iw, y + 22, if (current) ColorUtil.withAlpha(Theme.accent, 0x40) else GameColors.CARD)
			if (current) g.fill(ix, y, ix + 2, y + 22, Theme.accent(0xFF))
			g.text(font, font.plainSubstrByWidth(label, iw - 8), ix + 5, y + 3, Theme.TEXT, false)
			g.text(font, font.plainSubstrByWidth(sub, iw - 8), ix + 5, y + 12, Theme.TEXT_DIM, false)
			y += 24
		}
		val mode = WheelSession.mode
		if (PartyTracker.inParty && DecisionWheel.autoParty.value) {
			row("§a● §fParty wheel", "§7${PartyTracker.members.size} players", mode == WheelSession.Mode.PARTY)
		}
		if (mode == WheelSession.Mode.CODE) {
			row("§fRoom §l${WheelSession.roomCode}", "§7${WheelSession.players.size} players", true)
			val bw = (iw - 3) / 2
			buttons += Button(ix, y, bw, 13, "Copy code", true, 1) {
				minecraft.keyboardHandler.clipboard = WheelSession.roomCode ?: ""
				showToast("Room code copied to clipboard")
			}
			buttons += Button(ix + bw + 3, y, bw, 13, "Leave", true, 3) { WheelSession.leaveCode() }
			y += 16
		} else {
			if (mode == WheelSession.Mode.LOCAL) row("§7● Local wheel", "§8Only you see it", true)
			buttons += Button(ix, y, iw, 13, newRoomLabel(), true, 1) { createRoom() }
			y += 16
		}

		// Host + who may edit
		val host = WheelSession.host
		val hostText = when {
			mode == WheelSession.Mode.LOCAL -> null
			host == null -> "§7Host: §8waiting…"
			WheelSession.isHost -> "§7Host: §ayou"
			else -> "§7Host: §f$host"
		}
		if (hostText != null) {
			y += 2
			g.text(font, font.plainSubstrByWidth(hostText, iw), ix, y, Theme.TEXT, false)
			y += 11
			g.text(font, "§7Edit:", ix, y + 2, Theme.TEXT, false)
			val ex = ix + font.width("Edit:") + 4
			val bw = (ix + iw - ex - 2) / 2
			val only = WheelSession.hostOnlyEdit
			val canSet = WheelSession.isHost
			buttons += Button(ex, y, bw, 12, "Host", canSet || only, if (only) 2 else 4) { WheelSession.setHostOnlyEdit(true) }
			buttons += Button(ex + bw + 2, y, bw, 12, "All", canSet || !only, if (!only) 2 else 4) { WheelSession.setHostOnlyEdit(false) }
			y += 15
		}

		// Players: "+" puts a name on the wheel.
		y += 2
		g.fill(ix, y, ix + iw, y + 1, GameColors.LINE)
		y += 4
		val players = WheelSession.players
		val canEdit = WheelSession.canEdit
		g.text(font, "§lPlayers §r§7(${players.size})", ix, y, Theme.TEXT, false)
		val missing = players.filter { !WheelSession.hasEntry(it) }
		if (canEdit && missing.size > 1) {
			val aw = font.width("+ All") + 8
			buttons += Button(ix + iw - aw, y - 2, aw, 11, "+ All", true, 2) { missing.forEach { WheelSession.add(it) } }
		}
		y += 12
		val visible = ((y1 - 4 - y) / 12).coerceAtLeast(1)
		playerScroll = playerScroll.coerceIn(0, (players.size - visible).coerceAtLeast(0))
		for ((vi, name) in players.drop(playerScroll).take(visible).withIndex()) {
			val ry = y + vi * 12
			val on = WheelSession.hasEntry(name)
			g.text(font, font.plainSubstrByWidth((if (on) "§a✔ §f" else "§f") + name, iw - 16), ix + 2, ry + 2, Theme.TEXT, false)
			if (canEdit && !on) buttons += Button(ix + iw - 12, ry + 1, 11, 10, "+", true, 2) { WheelSession.add(name) }
		}
		if (mode == WheelSession.Mode.LOCAL && players.size <= 1) {
			g.text(font, font.plainSubstrByWidth("§8Join a party or a code room", iw), ix + 2, y + 14, Theme.TEXT, false)
		}
	}

	/** "New room" needs a second click within 3 s, so a stray click never throws you out of your room. */
	private var confirmNewRoomUntil = 0L

	private fun newRoomLabel() = if (System.currentTimeMillis() < confirmNewRoomUntil) "Sure? Click again" else "New room"

	/** The result already sent to party chat (one message per result). */
	private var sentSerial = -1

	private fun createRoom() {
		if (System.currentTimeMillis() >= confirmNewRoomUntil) {
			confirmNewRoomUntil = System.currentTimeMillis() + 3000
			return
		}
		confirmNewRoomUntil = 0L
		val code = WheelSession.createRoom()
		minecraft.keyboardHandler.clipboard = code
		showToast("Room $code created – code copied")
	}

	private fun displayTitle(): String {
		val s = WheelSession.spin
		return if (s != null && (WheelSession.spinning || WheelSession.displayNames() == s.names)) s.title else WheelSession.title
	}

	// --- Wheel ----------------------------------------------------------------------------------

	private fun drawWheel(g: GuiGraphicsExtractor, dt: Double) {
		val names = WheelSession.displayNames()
		val starts = WheelSession.displayStarts()
		val rot = WheelSession.rotation()
		val r = radius
		val ms = System.currentTimeMillis()
		val spin = WheelSession.spin
		val showWinner = spin != null && spin.done && names == spin.names
		val winner = if (showWinner) spin!!.result else -1

		// Shadow, rim, light ring
		Shapes.disk(g, cx + 1.5, cy + 3.5, r + 6, 0x60000000)
		// Rim, slices and borders come from a cached texture that is only rotated here (cheap every frame).
		val sliceColors = IntArray(names.size) { sliceColor(it, names.size) }
		faceTexture.draw(g, cx, cy, r, if (names.isEmpty()) DoubleArray(1) else starts, sliceColors, rot)

		if (names.isEmpty()) {
			g.centeredText(font, "Add entries", cx.toInt(), (cy + r * 0.45).toInt(), Theme.TEXT_DIM)
		} else {
			if (winner >= 0) highlightTexture.draw(g, cx, cy, r, starts, sliceColors, rot, winner)
			drawLabels(g, names, starts, rot, winner)
		}

		// Bulbs on the gold ring: chase while spinning, blink on the result
		val bulbs = 24
		for (i in 0 until bulbs) {
			val a = i.toDouble() / bulbs * 2 * PI
			val bx = cx + sin(a) * (r + 2.6)
			val by = cy - cos(a) * (r + 2.6)
			val on = when {
				WheelSession.spinning -> (i + (ms / 70).toInt()) % 3 == 0
				winner >= 0 && ms - resultShownAt < 4000 -> (i + (ms / 250).toInt()) % 2 == 0
				else -> i % 2 == 0
			}
			Shapes.disk(g, bx, by, 1.4, if (on) 0xFFFFF7D6.toInt() else 0xFF7A5A1E.toInt())
		}

		// Hub
		val hubR = r * 0.2
		Shapes.disk(g, cx, cy + 1, hubR + 2, 0x50000000)
		Shapes.disk(g, cx, cy, hubR + 1.5, 0xFF241C2E.toInt())
		Shapes.disk(g, cx, cy, hubR, 0xFFF3F1F7.toInt())
		Shapes.disk(g, cx, cy, hubR * 0.62, Theme.accent(0xFF))
		Shapes.disk(g, cx - hubR * 0.18, cy - hubR * 0.2, hubR * 0.2, 0x60FFFFFF)

		// Pointer at 12 o'clock; ticks against the pegs while spinning
		if (names.size > 1) {
			val under = Shapes.sliceAt(starts, -rot)
			if (WheelSession.spinning && under != lastPointerSlice && lastPointerSlice != -1) {
				kick = 1.0
				if (DecisionWheel.sound.value) {
					minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.6f + Random.nextFloat() * 0.2f, 0.35f))
				}
			}
			lastPointerSlice = under
		}
		kick *= exp(-dt * 9)
		val pivotY = cy - r - 9
		val dir = 0.5 - kick * 0.045
		val len = 18.0
		val tipX = cx + sin(dir * 2 * PI) * len
		val tipY = pivotY - cos(dir * 2 * PI) * len
		Shapes.triangle(g, tipX + 1, tipY + 2, dir, len, 8.0, 0x70000000)
		Shapes.triangle(g, tipX, tipY + 1.5, dir, len + 2.5, 9.5, 0xFF241C2E.toInt())
		Shapes.triangle(g, tipX, tipY, dir, len, 7.5, 0xFFFF4D5E.toInt())
		Shapes.triangle(g, tipX - 1.2, tipY - 3, dir, len - 6, 2.5, 0x60FFFFFF)
		Shapes.disk(g, cx, pivotY, 5.5, 0xFF241C2E.toInt())
		Shapes.disk(g, cx, pivotY, 4.2, 0xFFE9BE55.toInt())
		Shapes.disk(g, cx - 1, pivotY - 1, 1.5, 0xA0FFFFFF.toInt())

		// Result banner
		if (winner >= 0 && ms - resultShownAt < 7000) drawBanner(g, spin!!, ms - resultShownAt)
	}

	private fun drawLabels(g: GuiGraphicsExtractor, names: List<String>, starts: DoubleArray, rot: Double, winner: Int) {
		val r = radius
		val inner = r * 0.25
		val outer = r * 0.88
		for (i in names.indices) {
			val mid = (starts[i] + starts[i + 1]) / 2 + rot
			val sweep = (starts[i + 1] - starts[i]) * 2 * PI
			val arc = sweep * (inner + outer) / 2 // slice height at the label's middle
			if (arc < 5) continue
			var text = names[i]
			var tw = font.width(text).toDouble()
			val maxW = outer - inner
			var s = min(1.0, min(arc / 10.0, maxW / tw)).coerceAtLeast(0.6)
			if (tw * s > maxW) {
				text = font.plainSubstrByWidth(text, (maxW / s).toInt() - font.width("…")) + "…"
				tw = font.width(text).toDouble()
			}
			val base = sliceColor(i, names.size)
			val dark = luminance(base) > 0.62
			val color = when {
				winner >= 0 && i != winner -> 0xFFB8B8C0.toInt()
				dark -> 0xFF1C1424.toInt()
				else -> 0xFFFFFFFF.toInt()
			}
			g.pose().pushMatrix()
			g.pose().translate(cx.toFloat(), cy.toFloat())
			g.pose().rotate(((mid - 0.25) * 2 * PI).toFloat())
			g.pose().translate((outer - tw * s).toFloat(), 0f)
			g.pose().scale(s.toFloat(), s.toFloat())
			g.text(font, text, 0, -4, color, !dark || (winner >= 0 && i != winner))
			g.pose().popMatrix()
		}
	}

	private fun drawBanner(g: GuiGraphicsExtractor, spin: WheelSession.Spin, age: Long) {
		val pop = min(1.0, age / 220.0)
		val scale = (0.6 + 0.4 * easeBack(pop)).toFloat()
		val name = spin.resultName
		val nameW = font.width(name) * 2
		val sub = "spun by ${spin.spinner}"
		val w = maxOf(nameW, font.width(sub)) + 24
		val h = 38
		g.pose().pushMatrix()
		g.pose().translate(cx.toFloat(), (cy + radius * 0.48).toFloat())
		g.pose().scale(scale, scale)
		Shapes.roundRect(g, -w / 2 - 2, -h / 2 - 2 + 2, w / 2 + 2, h / 2 + 2 + 2, 7, 0x70000000)
		Shapes.roundRect(g, -w / 2 - 1, -h / 2 - 1, w / 2 + 1, h / 2 + 1, 7, 0xFFE9BE55.toInt())
		Shapes.roundRect(g, -w / 2, -h / 2, w / 2, h / 2, 6, 0xF0201828.toInt())
		g.pose().pushMatrix()
		g.pose().translate(0f, -h / 2f + 6)
		g.pose().scale(2f, 2f)
		g.centeredText(font, name, 0, 0, 0xFFFFE27A.toInt())
		g.pose().popMatrix()
		g.centeredText(font, sub, 0, h / 2 - 12, Theme.TEXT_DIM)
		g.pose().popMatrix()
	}

	private fun drawWheelControls(g: GuiGraphicsExtractor) {
		val y = (cy + radius + 12).toInt()
		val showParty = DecisionWheel.sendToParty.value && WheelSession.lastResult != null && !WheelSession.spinning
		// Both buttons always fit next to each other inside the wheel area.
		val areaW = panelX - 16
		val spinW = minOf(84, if (showParty) (areaW - 6) / 2 else areaW)
		val partyW = minOf(92, (areaW - 6) / 2)
		val total = spinW + if (showParty) partyW + 6 else 0
		var x = cx.toInt() - total / 2
		val spinLabel = if (WheelSession.spinning) "Spinning…" else "SPIN"
		buttons += Button(x, y, spinW, 18, spinLabel, WheelSession.canSpin, 2) { doSpin() }
		x += spinW + 6
		if (showParty) {
			val sent = sentSerial == WheelSession.resultSerial
			buttons += Button(x, y, partyW, 18, if (sent) "Sent ✔" else "Send to party", !sent, 1) {
				sentSerial = WheelSession.resultSerial
				WheelSession.sendResultToParty()
				showToast("Sent to party chat")
			}
		}
		val status = when {
			WheelSession.spinning -> "§7Spun by §f${WheelSession.spin?.spinner}"
			!WheelSession.canSpin && WheelSession.validEntries().size >= 2 -> "§7Only the host §f${WheelSession.host} §7can spin"
			else -> null
		}
		if (status != null && y + 22 + font.lineHeight < height) g.centeredText(font, status, cx.toInt(), y + 22, Theme.TEXT)
	}

	private fun doSpin() {
		WheelSession.requestSpin()?.let { showToast(it) }
	}

	// --- Right panel ----------------------------------------------------------------------------

	private fun drawPanel(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val x0 = panelX
		val x1 = roomX - 6
		val y0 = top + 6
		val y1 = height - 8
		Shapes.roundRect(g, x0, y0, x1, y1, 4, 0xC0101016.toInt())
		var y = y0 + 5

		val n = WheelSession.entries.size
		val shownTitle = fitBold(displayTitle(), x1 - x0 - 14 - font.width(" ($n)"))
		g.text(font, bold(shownTitle), x0 + 5, y, Theme.TEXT, false)
		g.text(font, "§7($n)", x0 + 9 + boldWidth(shownTitle), y, Theme.TEXT, false)
		if (!WheelSession.canEdit) {
			y += 10
			g.text(font, font.plainSubstrByWidth("§8Only ${WheelSession.host} can edit", x1 - x0 - 10), x0 + 5, y, Theme.TEXT, false)
		}
		y += 11

		// Reserve the bottom: add row, presets, history
		val historyLines = if (height > 300) 4 else 2
		val bottomH = 16 + 16 + 12 + historyLines * 10 + 6 + (if (presetRows(x1 - x0) > 1) 15 else 0)
		listTop = y
		listBottom = y1 - bottomH
		val visible = ((listBottom - listTop) / rowH).coerceAtLeast(1)
		scroll = scroll.coerceIn(0, (n - visible).coerceAtLeast(0))
		val valid = WheelSession.validEntries()
		val canEdit = WheelSession.canEdit
		for (vi in 0 until visible) {
			val i = scroll + vi
			if (i >= n) break
			val e = WheelSession.entries[i]
			val ry = listTop + vi * rowH
			val hovered = mx in x0 until x1 && my in ry until ry + rowH
			if (hovered) g.fill(x0 + 2, ry, x1 - 2, ry + rowH, 0x20FFFFFF)
			val colorIdx = valid.indexOf(e)
			g.fill(x0 + 5, ry + 3, x0 + 11, ry + 9, if (colorIdx >= 0) sliceColor(colorIdx, valid.size) else Theme.TRACK)
			val controlsW = if (canEdit) 50 else 22
			val nameW = x1 - x0 - 18 - controlsW
			g.text(font, font.plainSubstrByWidth(e.name, nameW), x0 + 15, ry + 2, Theme.TEXT, false)
			// weight: [-] n [+]  [x]
			var bx = x1 - 6
			if (canEdit) {
				bx -= 10
				buttons += Button(bx, ry + 1, 10, 10, "×", true, 3) { WheelSession.remove(i) }
				bx -= 13
				buttons += Button(bx, ry + 1, 10, 10, "+", e.weight < WheelSession.MAX_WEIGHT, 4) { WheelSession.setWeight(i, e.weight + 1) }
				bx -= 14
				g.centeredText(font, "${e.weight}", bx + 7, ry + 2, if (e.weight > 1) 0xFFFFE27A.toInt() else Theme.TEXT_DIM)
				bx -= 10
				buttons += Button(bx, ry + 1, 10, 10, "-", e.weight > 1, 4) { WheelSession.setWeight(i, e.weight - 1) }
			} else {
				g.text(font, "×${e.weight}", bx - font.width("×${e.weight}"), ry + 2, Theme.TEXT_DIM, false)
			}
		}
		if (n > visible) {
			// Scrollbar
			val trackH = listBottom - listTop
			val thumbH = (trackH * visible / n).coerceAtLeast(8)
			val thumbY = listTop + (trackH - thumbH) * scroll / (n - visible)
			g.fill(x1 - 3, listTop, x1 - 2, listBottom, 0x30FFFFFF)
			g.fill(x1 - 3, thumbY, x1 - 2, thumbY + thumbH, Theme.accent(0xFF))
		}
		if (n == 0) g.text(font, "§8No entries yet", x0 + 6, listTop + 2, Theme.TEXT_DIM, false)

		// Add row
		y = listBottom + 4
		val fieldX1 = x1 - 5 - 30
		val fieldColor = if (inputFocused) Theme.accent(0xFF) else 0xFF3A3A48.toInt()
		g.fill(x0 + 5, y, fieldX1, y + 14, 0xFF0A0A0F.toInt())
		g.outline(x0 + 5, y, fieldX1 - x0 - 5, 14, fieldColor)
		val shown = if (input.isEmpty() && !inputFocused) "§8Add entry…" else input
		val visibleText = font.plainSubstrByWidth(shown.reversed(), fieldX1 - x0 - 14).reversed()
		g.text(font, visibleText, x0 + 9, y + 3, Theme.TEXT, false)
		if (inputFocused && (System.currentTimeMillis() / 500) % 2 == 0L) {
			val cxp = x0 + 9 + font.width(visibleText)
			g.fill(cxp, y + 2, cxp + 1, y + 12, Theme.TEXT)
		}
		fieldRect = intArrayOf(x0 + 5, y, fieldX1, y + 14)
		buttons += Button(fieldX1 + 3, y, 27, 14, "Add", canEdit && input.isNotBlank(), 2) { submitInput() }
		y += 17

		// Presets (+ Clear): one row when the labels fit, otherwise two.
		val presets = Presets.all
		val gap = 3
		val labels = presets.map { it.button } + "Clear"
		val perRow = if (presetRows(x1 - x0) > 1) (labels.size + 1) / 2 else labels.size
		val bw = (x1 - x0 - 10 - gap * (perRow - 1)) / perRow
		labels.forEachIndexed { i, label ->
			val bx = x0 + 5 + (i % perRow) * (bw + gap)
			val by = y + (i / perRow) * 15
			if (i < presets.size) buttons += Button(bx, by, bw, 13, label, canEdit, 1) {
				WheelSession.applyPreset(presets[i])
				scroll = 0
			} else buttons += Button(bx, by, bw, 13, label, canEdit, 0) { WheelSession.clear() }
		}
		y += 17 + if (perRow < labels.size) 15 else 0

		// History
		g.text(font, "§lHistory", x0 + 5, y, Theme.TEXT, false)
		y += 11
		val hist = WheelSession.history.take(historyLines)
		if (hist.isEmpty()) g.text(font, "§8No spins yet", x0 + 6, y, Theme.TEXT_DIM, false)
		hist.forEachIndexed { i, h ->
			val line = "§f${h.name} §8· ${h.spinner}"
			g.text(font, font.plainSubstrByWidth(line, x1 - x0 - 14), x0 + 6, y + i * 10, if (i == 0) Theme.TEXT else Theme.TEXT_DIM, false)
		}
	}

	private var fieldRect = intArrayOf(0, 0, 0, 0)

	private fun submitInput() {
		if (input.isBlank()) return
		if (WheelSession.add(input)) {
			input = ""
			scroll = Int.MAX_VALUE
		} else {
			showToast(if (!WheelSession.canEdit) "Only the host can edit" else "Wheel is full (${WheelSession.MAX_ENTRIES})")
		}
	}

	// --- Buttons / toast / confetti -------------------------------------------------------------

	private fun drawButtons(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		for (b in buttons) {
			val hover = b.enabled && b.hit(mx, my)
			val base = when (b.style) {
				2 -> Theme.accent(0xFF)
				1 -> 0xFF2C2C3A.toInt()
				3 -> 0xFF5A2430.toInt()
				4 -> 0xFF262632.toInt()
				else -> 0xFF22222C.toInt()
			}
			val fill = when {
				!b.enabled -> ColorUtil.withAlpha(ColorUtil.shade(base, 0.55f), 0xFF)
				hover -> ColorUtil.shade(base, 1.25f)
				else -> base
			}
			val radius = if (b.h >= 14) 3 else 2
			if (b.style == 2 && b.h >= 18) Shapes.roundRect(g, b.x, b.y + 2, b.x + b.w, b.y + b.h + 2, radius, ColorUtil.shade(base, 0.5f))
			Shapes.roundRect(g, b.x, b.y, b.x + b.w, b.y + b.h, radius, fill)
			val textColor = if (b.enabled) 0xFFFFFFFF.toInt() else 0xFF8A8A96.toInt()
			val label = if (b.style == 2 && b.h >= 18) "§l${b.label}" else b.label
			g.centeredText(font, fitLabel(label, b.w - 4), b.x + b.w / 2, b.y + (b.h - 8) / 2 + 1, textColor)
		}
	}

	/** 1 when the preset buttons (+ Clear) fit in one row of a panel [w] wide, else 2. */
	private fun presetRows(w: Int): Int {
		val labels = Presets.all.map { it.button } + "Clear"
		val bw = (w - 10 - 3 * (labels.size - 1)) / labels.size
		return if (labels.all { font.width(it) + 6 <= bw }) 1 else 2
	}

	private fun fitLabel(s: String, w: Int) = if (font.width(s) <= w) s else font.plainSubstrByWidth(s, w)

	private fun showToast(text: String) {
		toast = text
		toastAt = System.currentTimeMillis()
	}

	private fun drawToast(g: GuiGraphicsExtractor) {
		val t = toast ?: return
		val age = System.currentTimeMillis() - toastAt
		if (age > 2500) {
			toast = null
			return
		}
		val alpha = if (age > 2000) ((2500 - age) / 500.0 * 255).toInt() else 255
		val w = font.width(t) + 16
		val x = (width - w) / 2
		val y = top + 6
		Shapes.roundRect(g, x, y, x + w, y + 16, 4, ColorUtil.withAlpha(0xFF1A1A24.toInt(), (alpha * 0.9).toInt()))
		g.fill(x + 3, y + 15, x + w - 3, y + 16, Theme.accent(alpha))
		g.centeredText(font, t, width / 2, y + 4, ColorUtil.withAlpha(0xFFFFFFFF.toInt(), alpha.coerceAtLeast(5)))
	}

	private fun burstConfetti() {
		val colors = PALETTE
		repeat(110) {
			val a = (Random.nextDouble() - 0.5) * PI * 1.1 // mostly upward
			val speed = 120 + Random.nextDouble() * 170
			confetti += Confetti(
				cx + (Random.nextDouble() - 0.5) * 20, cy - radius - 6,
				sin(a) * speed, -cos(a) * speed,
				colors[Random.nextInt(colors.size)], Random.nextDouble() * PI * 2, 6 + Random.nextDouble() * 10,
				2.2 + Random.nextDouble() * 1.2,
			)
		}
	}

	private fun drawConfetti(g: GuiGraphicsExtractor, dt: Double) {
		if (confetti.isEmpty()) return
		val it = confetti.iterator()
		Shapes.native(g) { s ->
			while (it.hasNext()) {
				val c = it.next()
				c.vy += 260 * dt
				c.vx *= 1 - 0.9 * dt
				c.x += c.vx * dt
				c.y += c.vy * dt
				c.phase += c.spin * dt
				c.life -= dt
				if (c.life <= 0 || c.y > height + 10) {
					it.remove()
					continue
				}
				val alpha = (min(1.0, c.life / 0.6) * 255).toInt()
				val w = (abs(cos(c.phase)) * 3.0 * s).roundToInt().coerceAtLeast(1)
				val h = (2.2 * s).roundToInt()
				val px = (c.x * s).roundToInt()
				val py = (c.y * s).roundToInt()
				g.fill(px - w / 2, py - h / 2, px + w - w / 2, py + h - h / 2, ColorUtil.withAlpha(c.color, alpha))
			}
		}
	}

	// --- Input ----------------------------------------------------------------------------------

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val mx = event.x().toInt()
		val my = event.y().toInt()
		if (dev.skyparty.util.FocusGuard.ignoreClicks) return true
		if (event.button() == 0) {
			buttons.firstOrNull { it.hit(mx, my) }?.let { b ->
				if (b.enabled) {
					minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
					b.action()
				}
				return true
			}
			inputFocused = mx >= fieldRect[0] && mx < fieldRect[2] && my >= fieldRect[1] && my < fieldRect[3]
			if (inputFocused) return true
			// Clicking the hub spins
			val dx = mx - cx
			val dy = my - cy
			if (dx * dx + dy * dy <= (radius * 0.2) * (radius * 0.2)) {
				doSpin()
				return true
			}
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun onScroll(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		if (mouseX >= roomX) {
			playerScroll -= scrollY.toInt().coerceIn(-3, 3)
			return true
		}
		if (mouseX >= panelX) {
			scroll -= scrollY.toInt().coerceIn(-3, 3)
			return true
		}
		return super.onScroll(mouseX, mouseY, scrollX, scrollY)
	}

	override fun onKey(event: KeyEvent): Boolean {
		val key = event.key()
		if (inputFocused) {
			when (key) {
				GLFW.GLFW_KEY_ESCAPE -> inputFocused = false
				GLFW.GLFW_KEY_BACKSPACE -> input = input.dropLast(1)
				GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> submitInput()
				else -> return super.onKey(event).let { true }
			}
			return true
		}
		if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) {
			doSpin()
			return true
		}
		return super.onKey(event)
	}

	override fun onChar(event: CharacterEvent): Boolean {
		if (!inputFocused || !event.isAllowedChatCharacter()) return false
		if (input.length < WheelSession.MAX_NAME) input += event.codepointAsString()
		return true
	}

	companion object {
		// Shared by every wheel screen, so reopening the wheel reuses the same textures.
		private val faceTexture by lazy { WheelTexture("wheel_face", WheelTexture.Layer.FACE) }
		private val highlightTexture by lazy { WheelTexture("wheel_highlight", WheelTexture.Layer.HIGHLIGHT) }

		val PALETTE = intArrayOf(
			0xFFFF5D73.toInt(), // coral red
			0xFFFF9F43.toInt(), // orange
			0xFFFFD447.toInt(), // sun yellow
			0xFF7ED957.toInt(), // green
			0xFF2EC4B6.toInt(), // teal
			0xFF4D96FF.toInt(), // blue
			0xFF8E6CFF.toInt(), // violet
			0xFFFF6FD8.toInt(), // pink
			0xFF3DDCFF.toInt(), // sky
			0xFFB8E356.toInt(), // lime
		)

		/** Neighbouring slices never share a color, also across the wrap from last to first. */
		fun sliceColor(i: Int, n: Int): Int {
			var idx = i % PALETTE.size
			if (n > 1 && i == n - 1 && idx == 0) idx = 5
			return PALETTE[idx]
		}

		fun luminance(c: Int): Double {
			val r = ((c shr 16) and 0xFF) / 255.0
			val g = ((c shr 8) and 0xFF) / 255.0
			val b = (c and 0xFF) / 255.0
			return 0.2126 * r + 0.7152 * g + 0.0722 * b
		}

		private fun easeBack(t: Double): Double {
			val c1 = 1.70158
			val c3 = c1 + 1
			return 1 + c3 * (t - 1) * (t - 1) * (t - 1) + c1 * (t - 1) * (t - 1)
		}
	}
}
