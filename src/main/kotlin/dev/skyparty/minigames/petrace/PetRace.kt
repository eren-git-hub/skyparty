package dev.skyparty.minigames.petrace

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.PetDraw
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.net.Net
import dev.skyparty.pet.scoreboard.PetSprites
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Mth
import kotlin.random.Random

/**
 * Pet Race: every player's 2D pet runs in its own lane (bots fill up to 4 lanes). The host sends
 * "petrace.step.rng" every [PetRaceScreen.STEP_MS]; all clients turn the roll into the same advances,
 * so everyone sees the identical race. Before the start you can bet Tokens on a racer.
 */
object PetRace : Minigame {
	override val id = "petrace"
	override val name = "Pet Race"
	override val description = "Bet Tokens on a pet and watch the race. Same race for everyone."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = false

	override fun open(room: Net.Room?) = MinigameHub.openScreen(PetRaceScreen(LobbyRoom.of(room, id)))
}

class PetRaceScreen(val room: LobbyRoom) : GameScreen("Pet Race") {
	enum class Phase { SETUP, BETTING, RACING, FINISHED }

	class Racer(val id: String, val name: String, val bot: Boolean, val self: Boolean, val colors: PetDraw.Colors) {
		var pos = 0f
		var prevPos = 0f
		var finishStep = -1
		var boostUntil = 0L
		var stumbleUntil = 0L
		val finished get() = finishStep >= 0
	}

	var phase = Phase.SETUP
		private set
	val racers = mutableListOf<Racer>()

	private var step = 0
	private var sentStep = 0
	private var nextStepAt = 0L
	private var lastStepAt = 0L
	private var bettingEnds = 0L
	private var finishedAt = 0L
	private var setupAt = System.currentTimeMillis() + 300

	private var betRacer: String? = null
	private var betAmount = 25
	private var placedBet = 0
	private var placedOn: String? = null
	private var payout = 0
	private val otherBets = linkedMapOf<String, String>()

	private val solo get() = room.solo

	init {
		room.onMessage = ::onMessage
	}

	// ------------------------------------------------------------------ host

	private fun sendSetup() {
		if (!room.isHost) return
		val arr = JsonArray()
		for ((id, name) in room.names) arr.add(JsonObject().apply {
			addProperty("id", id.toString())
			addProperty("name", name)
		})
		val botNames = BOTS.shuffled()
		var i = 0
		while (arr.size() < 4 && i < botNames.size) {
			arr.add(JsonObject().apply {
				addProperty("id", "bot:${botNames[i]}")
				addProperty("name", botNames[i])
				addProperty("bot", true)
			})
			i++
		}
		room.send("petrace.setup", JsonObject().apply {
			add("racers", arr)
			addProperty("betMs", if (solo) 0 else BET_MS)
		})
	}

	fun startRace() {
		if (!room.isHost || phase != Phase.BETTING) return
		room.send("petrace.go")
	}

	override fun tick() {
		val now = System.currentTimeMillis()
		if (!room.isHost) return
		when (phase) {
			Phase.SETUP -> if (now >= setupAt) {
				setupAt = Long.MAX_VALUE
				sendSetup()
			}
			Phase.BETTING -> if (!solo && now >= bettingEnds) {
				bettingEnds = Long.MAX_VALUE
				startRace()
			}
			Phase.RACING -> if (now >= nextStepAt && sentStep == step && racers.any { !it.finished }) {
				sentStep = step + 1
				nextStepAt = now + STEP_MS
				room.send("petrace.step.rng", JsonObject().apply { addProperty("step", sentStep) })
			}
			Phase.FINISHED -> {}
		}
	}

	// ------------------------------------------------------------------ protocol

	private fun onMessage(msg: Net.Message) {
		val p = msg.payload
		when (msg.type) {
			"petrace.setup" -> {
				if (msg.from != room.host) return
				racers.clear()
				p["racers"]?.asJsonArray?.forEach { el ->
					val o = el.asJsonObject
					val id = o["id"].asString
					val name = o["name"].asString
					val self = id == Net.selfId.toString()
					val colors = if (self) PetDraw.own() else PetDraw.forName(name)
					val label = if (self) CustomPets.petName.value.ifBlank { name } else name
					racers += Racer(id, label, o["bot"]?.asBoolean == true, self, colors)
				}
				step = 0
				sentStep = 0
				placedBet = 0
				placedOn = null
				payout = 0
				otherBets.clear()
				if (betRacer != null && racers.none { it.id == betRacer }) betRacer = null
				val betMs = p["betMs"]?.asLong ?: 0L
				bettingEnds = if (betMs > 0) System.currentTimeMillis() + betMs else Long.MAX_VALUE
				phase = Phase.BETTING
			}
			"petrace.bet" -> if (msg.from != Net.selfId) {
				val racer = racers.firstOrNull { it.id == p["racer"]?.asString } ?: return
				otherBets[room.nameOf(msg.from)] = racer.name
			}
			"petrace.go" -> {
				if (msg.from != room.host || phase != Phase.BETTING) return
				placeBet()
				phase = Phase.RACING
				nextStepAt = System.currentTimeMillis() + 600
				lastStepAt = System.currentTimeMillis()
			}
			"petrace.step.rng" -> {
				if (msg.from != room.host || phase != Phase.RACING) return
				val s = p["step"]?.asInt ?: return
				if (s != step + 1) return
				applyStep(s, p["roll"]?.asDouble ?: Math.random())
			}
		}
	}

	private fun placeBet() {
		val target = betRacer ?: return
		if (MinigameStore.trySpend(betAmount)) {
			placedBet = betAmount
			placedOn = target
			room.send("petrace.bet", JsonObject().apply { addProperty("racer", target) })
		}
	}

	/** Deterministic from (roll, step): every client computes the same advances. */
	private fun applyStep(s: Int, roll: Double) {
		step = s
		val now = System.currentTimeMillis()
		lastStepAt = now
		val rnd = Random((roll * (1L shl 52)).toLong() xor (s.toLong() * 0x9E3779B97F4A7C15uL.toLong()))
		for (r in racers) {
			r.prevPos = r.pos
			var adv = 0.8f + rnd.nextFloat() * 2.2f
			val event = rnd.nextInt(100)
			if (event < 6) {
				adv += 3f
				r.boostUntil = now + 600
			} else if (event < 10) {
				adv = 0.2f
				r.stumbleUntil = now + 600
			}
			if (!r.finished) {
				r.pos += adv
				if (r.pos >= TRACK) r.finishStep = s
			}
		}
		if (racers.all { it.finished }) finish()
	}

	/** Final order: finish step, then distance past the line, then lane. */
	fun standings(): List<Racer> = racers.withIndex()
		.sortedWith(compareBy<IndexedValue<Racer>> { if (it.value.finished) it.value.finishStep else Int.MAX_VALUE }
			.thenByDescending { it.value.pos }
			.thenBy { it.index })
		.map { it.value }

	private fun finish() {
		phase = Phase.FINISHED
		finishedAt = System.currentTimeMillis()
		val winner = standings().first()
		if (placedOn == winner.id && placedBet > 0) {
			payout = placedBet * racers.size
			MinigameStore.addTokens(payout)
			val profit = payout - placedBet
			MinigameStore.submit(PetRace.id, profit.toDouble(), lowerIsBetter = false, display = "+$profit Tokens")
		}
	}

	override fun removed() {
		room.leave()
		super.removed()
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val sub = when (phase) {
			Phase.SETUP -> "Preparing…"
			Phase.BETTING -> "Place your bets"
			Phase.RACING -> "Racing!"
			Phase.FINISHED -> "Finished"
		}
		val top = header(g, "Pet Race", sub)
		val margin = 8
		val bottomH = 66
		val tx = margin
		val ty = top + margin
		val tw = width - margin * 2
		val th = height - ty - bottomH - margin * 2
		drawTrack(g, tx, ty, tw, th)
		val by = ty + th + margin
		when (phase) {
			Phase.SETUP -> centered(g, "Waiting for the host…", width / 2, by + 20, Theme.TEXT_DIM)
			Phase.BETTING -> drawBetting(g, tx, by, tw, bottomH, mouseX, mouseY)
			Phase.RACING -> drawLive(g, tx, by, tw, bottomH)
			Phase.FINISHED -> {
				drawLive(g, tx, by, tw, bottomH)
				if (System.currentTimeMillis() - finishedAt > 700) drawPodium(g, mouseX, mouseY)
			}
		}
	}

	private fun drawTrack(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, Theme.SHADOW)
		g.fill(x, y, x + w, y + h, 0xFF2C4A2A.toInt())
		g.outline(x, y, w, h, 0xFF1A2E19.toInt())
		if (racers.isEmpty()) return
		val now = System.currentTimeMillis()
		val laneH = h / racers.size
		val scale = when {
			laneH >= 26 -> 2f
			laneH >= 15 -> 1.25f
			else -> 1f
		}
		val labelW = 0
		val startX = x + 6 + labelW
		val finishX = x + w - 14
		// Finish line (checkered)
		for (cy in y until y + h step 3) for (c in 0..1) {
			val dark = ((cy - y) / 3 + c) % 2 == 0
			g.fill(finishX + c * 3, cy, finishX + c * 3 + 3, (cy + 3).coerceAtMost(y + h), if (dark) 0xFF111111.toInt() else 0xFFF0F0F0.toInt())
		}
		val t = ((now - lastStepAt).toFloat() / STEP_MS).coerceIn(0f, 1f)
		for ((i, r) in racers.withIndex()) {
			val ly = y + i * laneH
			if (i % 2 == 1) g.fill(x + 1, ly, x + w - 1, ly + laneH, 0x18FFFFFF)
			if (i > 0) for (dx in x + 4 until x + w - 4 step 10) g.fill(dx, ly, dx + 5, ly + 1, 0x50FFFFFF)
			val sprites = PetSprites.adult
			val moving = phase == Phase.RACING && !r.finished
			val frame = if (moving) sprites.walk[((now / 130 + i) % sprites.walk.size).toInt()] else sprites.idle
			val sw = frame.width * scale
			val sh = frame.height * scale
			val display = Mth.lerp(t, r.prevPos, r.pos).coerceAtMost(TRACK + 2f)
			val px = startX + (display / TRACK) * (finishX - startX - sw * 0.6f)
			val hop = if (r.boostUntil > now) -2f * scale else 0f
			val py = ly + (laneH - sh) / 2f + hop + if (laneH >= 20) 3f else 0f
			PetDraw.draw(g, frame, px, py, scale, true, r.colors)
			if (r.boostUntil > now) g.text(font, "§e»", (px - 8).toInt(), py.toInt(), Theme.TEXT, true)
			if (r.stumbleUntil > now) g.text(font, "§c!", (px + sw).toInt() + 1, py.toInt(), Theme.TEXT, true)
			// Name tag
			val tag = (if (r.self) "§b" else if (r.bot) "§7" else "§f") + r.name + if (placedOn == r.id || (phase == Phase.BETTING && betRacer == r.id)) " §6◆" else ""
			if (laneH >= 20) g.text(font, tag, (px + sw / 2 - font.width(tag) / 2).toInt().coerceAtLeast(x + 2), ly + 1, Theme.TEXT, true)
			else g.text(font, tag, (px + sw + 4).toInt(), (ly + (laneH - 8) / 2), Theme.TEXT, true)
		}
	}

	private fun drawBetting(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
		panel(g, x, y, w, h)
		val odds = racers.size
		g.text(font, "§lPick a winner", x + 6, y + 5, Theme.accent, true)
		g.text(font, "§7pays §ex$odds", x + 10 + font.width("§lPick a winner"), y + 5, Theme.TEXT, false)
		// Racer chips
		var cx = x + 6
		val cy = y + 18
		for (r in racers) {
			val label = r.name
			val cw = font.width(label) + 18
			if (cx + cw > x + w - 6) break
			val selected = betRacer == r.id
			val hover = hovered(cx, cy, cw, 16, mx, my)
			g.fill(cx, cy, cx + cw, cy + 16, if (selected) ColorUtil.withAlpha(Theme.accent, 0x60) else if (hover) GameColors.CARD_HOVER else GameColors.CARD)
			g.outline(cx, cy, cw, 16, if (selected) Theme.accent(0xFF) else GameColors.LINE)
			g.fill(cx + 4, cy + 4, cx + 12, cy + 12, r.colors.body)
			g.outline(cx + 4, cy + 4, 8, 8, r.colors.accent)
			g.text(font, label, cx + 15, cy + 4, if (r.self) 0xFF8FE3FF.toInt() else Theme.TEXT, false)
			val id = r.id
			clickArea(cx, cy, cw, 16) { betRacer = if (betRacer == id) null else id }
			cx += cw + 4
		}
		// Amount chips
		var ax = x + 6
		val ay = y + 40
		g.text(font, "§7Bet:", ax, ay + 4, Theme.TEXT, false)
		ax += font.width("Bet:") + 6
		val balance = MinigameStore.balance()
		for (amount in listOf(10, 25, 50, 100)) {
			val enabled = amount <= balance
			val selected = betAmount == amount
			button(g, ax, ay, 30, 14, "$amount", mx, my, enabled = enabled, color = if (selected) GameColors.TOKEN else 0xFF5A5A6A.toInt()) {
				betAmount = amount
			}
			ax += 33
		}
		val status = when {
			betRacer == null -> "§8No bet"
			betAmount > balance -> "§cNot enough Tokens"
			else -> "§e$betAmount §7on §f${racers.firstOrNull { it.id == betRacer }?.name}"
		}
		g.text(font, status, ax + 4, ay + 4, Theme.TEXT, false)

		val bw = 76
		if (room.isHost) {
			val label = if (solo) "Start race" else "Start (${((bettingEnds - System.currentTimeMillis()) / 1000 + 1).coerceIn(0, 99)}s)"
			button(g, x + w - bw - 6, y + h - 22, bw, 16, label, mx, my, color = GameColors.GREEN) { startRace() }
		} else {
			val left = ((bettingEnds - System.currentTimeMillis()) / 1000 + 1).coerceAtLeast(0)
			g.text(font, "§7Starts in §f${left}s", x + w - 70, y + h - 18, Theme.TEXT, false)
		}
		if (otherBets.isNotEmpty()) {
			val line = otherBets.entries.joinToString("  ") { "§7${it.key}→§f${it.value}" }
			g.text(font, font.plainSubstrByWidth(line, w - bw - 20), x + 6, y + h - 12, Theme.TEXT, false)
		}
	}

	private fun drawLive(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
		panel(g, x, y, w, h)
		val order = standings()
		g.text(font, "§lStandings", x + 6, y + 5, Theme.accent, true)
		var cx = x + 6
		for ((i, r) in order.withIndex()) {
			val txt = "${i + 1}. ${r.name}"
			val cw = font.width(txt) + 10
			if (cx + cw > x + w - 6) break
			val color = when (i) { 0 -> GameColors.GOLD; 1 -> GameColors.SILVER; 2 -> GameColors.BRONZE; else -> Theme.TEXT_DIM }
			g.text(font, txt, cx, y + 20, color, true)
			cx += cw
		}
		val betLine = when {
			placedBet == 0 -> "§8You didn't bet this race."
			phase == Phase.FINISHED && payout > 0 -> "§aYour bet won! §e+$payout Tokens"
			phase == Phase.FINISHED -> "§cYour bet lost (-$placedBet Tokens)."
			else -> "§7Your bet: §e$placedBet §7on §f${racers.firstOrNull { it.id == placedOn }?.name}"
		}
		g.text(font, betLine, x + 6, y + 36, Theme.TEXT, false)
		val progress = (order.firstOrNull()?.pos ?: 0f) / TRACK
		g.fill(x + 6, y + h - 10, x + w - 6, y + h - 7, 0xFF2A2A36.toInt())
		g.fill(x + 6, y + h - 10, x + 6 + ((w - 12) * progress.coerceIn(0f, 1f)).toInt(), y + h - 7, Theme.accent(0xFF))
	}

	private fun drawPodium(g: GuiGraphicsExtractor, mx: Int, my: Int) {
		val order = standings()
		val pw = 220
		val ph = 150
		val px = (width - pw) / 2
		val py = ((height - ph) / 2).coerceAtLeast(26)
		g.fill(0, 22, width, height, 0x90000000.toInt())
		panel(g, px, py, pw, ph)
		val winner = order.first()
		bigText(g, "${winner.name} wins!", px + pw / 2, py + 8, 1.5f, GameColors.GOLD)
		val baseY = py + ph - 38
		val colW = 56
		val slots = listOf(1 to 26, 0 to 40, 2 to 16) // index in order, block height
		for ((slot, pair) in slots.withIndex()) {
			val (idx, blockH) = pair
			val r = order.getOrNull(idx) ?: continue
			val cx = px + pw / 2 + (slot - 1) * (colW + 6)
			val bx = cx - colW / 2
			val color = when (idx) { 0 -> GameColors.GOLD; 1 -> GameColors.SILVER; else -> GameColors.BRONZE }
			g.fill(bx, baseY - blockH, bx + colW, baseY, ColorUtil.shade(color, 0.55f))
			g.fill(bx, baseY - blockH, bx + colW, baseY - blockH + 2, color)
			centered(g, "${idx + 1}", cx, baseY - blockH + 5, color)
			val sprite = PetSprites.adult.idle
			val bounce = if (idx == 0) (Math.abs(Math.sin(System.currentTimeMillis() / 180.0)) * -3).toFloat() else 0f
			PetDraw.draw(g, sprite, cx - sprite.width.toFloat(), baseY - blockH - sprite.height * 2f - 1 + bounce, 2f, true, r.colors)
			val name = font.plainSubstrByWidth(r.name, colW + 4)
			centered(g, (if (r.self) "§b" else "§f") + name, cx, baseY + 3, Theme.TEXT)
		}
		val result = when {
			placedBet == 0 -> "§7No bet placed"
			payout > 0 -> "§aBet won! §e+$payout Tokens"
			else -> "§cBet lost §7(-$placedBet)"
		}
		centered(g, result, px + pw / 2, baseY + 14, Theme.TEXT)
		val bw = 70
		if (room.isHost) {
			button(g, px + pw / 2 - bw - 3, py + ph + 4, bw, 15, "Race again", mx, my, color = GameColors.GREEN) { sendSetup() }
		}
		button(g, if (room.isHost) px + pw / 2 + 3 else px + pw / 2 - bw / 2, py + ph + 4, bw, 15, "Close", mx, my, color = GameColors.RED) { onClose() }
	}

	companion object {
		const val TRACK = 60f
		const val STEP_MS = 300L
		const val BET_MS = 15_000L
		val BOTS = listOf("Mochi", "Biscuit", "Nugget", "Pip", "Waffles", "Sprout")
	}
}
