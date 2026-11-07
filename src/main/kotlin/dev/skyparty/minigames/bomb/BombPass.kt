package dev.skyparty.minigames.bomb

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
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Bomb Pass: a bomb with a hidden fuse goes around the circle. Whoever holds it must solve a quick task
 * (arrow-key sequence, mental math, or "click the GREEN one" with lying labels) to pass it on. When the
 * fuse runs out the holder is out; the last player standing wins. Solo: you against three bots.
 *
 * Protocol (echoed to the sender):
 *  - "bomb.start.rng" (host)        seed for the whole match; everyone builds the same player order
 *  - "bomb.round.rng" {holder}      (host) new round, roll -> fuse length
 *  - "bomb.pass" {to, n}            from the current holder only; n = pass counter (drops stale passes)
 *  - "bomb.boom" {victim}           (host) the fuse ran out on the host's clock — authoritative
 */
object BombPass : Minigame {
	override val id = "bomb"
	override val name = "Bomb Pass"
	override val description = "Solve a quick task to pass the bomb before it blows up. Last one standing wins."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = true

	const val FUSE_MIN = 7_000L
	const val FUSE_EXTRA = 9_000L
	const val INTRO_MS = 2_000L
	const val BOOM_MS = 2_600L
	const val WIN_TOKENS = 10

	override fun open(room: Net.Room?) = MinigameHub.openScreen(BombScreen(LobbyRoom.of(room, id)))
}

class BombScreen(val room: LobbyRoom) : GameScreen("Bomb Pass") {
	enum class Phase { WAITING, INTRO, LIVE, BOOM, OVER }

	sealed class Task {
		class Keys(val keys: List<Int>, var done: Int = 0) : Task()
		class MathQ(val question: String, val options: List<Int>, val answer: Int) : Task()
		class Colors(val target: Int, val labels: List<Int>, val shown: List<Int>) : Task()
	}

	private var phase = Phase.WAITING
	private var phaseStart = 0L
	private var seed = 0L
	private var round = 0
	private var order = listOf<UUID>()
	private val names = linkedMapOf<UUID, String>()
	private val alive = linkedSetOf<UUID>()
	private val bots = mutableSetOf<UUID>()
	private var holder: UUID? = null
	private var prevHolder: UUID? = null
	private var passedAt = 0L
	private var passCount = 0
	private var fuseMs = 10_000L
	private var victim: UUID? = null
	private var boomSent = false
	private var task: Task? = null
	private var wrongAt = 0L
	private var botSolveAt = 0L
	private var lastTick = 0L
	private var rewarded = false

	init {
		room.onMessage = ::onMessage
		if (room.isHost) requestStart()
	}

	private fun now() = System.currentTimeMillis()

	// ------------------------------------------------------------------ protocol

	private fun requestStart() = room.send("bomb.start.rng", JsonObject())

	private fun onMessage(msg: Net.Message) {
		when (msg.type) {
			"bomb.start.rng" -> {
				if (msg.from != room.host) return
				startMatch(((msg.payload["roll"]?.asDouble ?: Math.random()) * Long.MAX_VALUE).toLong())
			}
			"bomb.round.rng" -> {
				if (msg.from != room.host) return
				val h = msg.payload["holder"]?.asString?.let(UUID::fromString) ?: return
				val roll = msg.payload["roll"]?.asDouble ?: Math.random()
				startRound(h, BombPass.FUSE_MIN + (roll * BombPass.FUSE_EXTRA).toLong())
			}
			"bomb.pass" -> {
				if (msg.from != holder || phase != Phase.LIVE) return
				if ((msg.payload["n"]?.asInt ?: -1) != passCount) return
				val to = msg.payload["to"]?.asString?.let(UUID::fromString) ?: return
				applyPass(to)
			}
			"bomb.boom" -> {
				if (msg.from != room.host) return
				val v = msg.payload["victim"]?.asString?.let(UUID::fromString) ?: return
				explode(v)
			}
		}
	}

	private fun startMatch(s: Long) {
		seed = s
		round = 0
		names.clear()
		bots.clear()
		names.putAll(room.names)
		if (room.solo) {
			val botNames = listOf("Bombardier", "Fuse Frank", "Kaboom Kim")
			val rng = Random(seed)
			for (n in botNames) {
				val id = UUID(rng.nextLong(), rng.nextLong())
				names[id] = n
				bots += id
			}
		}
		order = names.keys.toList()
		alive.clear()
		alive += order
		rewarded = false
		if (room.isHost) sendRound(order[Random(seed).nextInt(order.size)])
	}

	private fun sendRound(h: UUID) = room.send("bomb.round.rng", JsonObject().apply { addProperty("holder", h.toString()) })

	private fun startRound(h: UUID, fuse: Long) {
		round++
		holder = h
		prevHolder = null
		passCount = 0
		fuseMs = fuse
		victim = null
		boomSent = false
		enter(Phase.INTRO)
		onNewHolder()
	}

	private fun enter(p: Phase) {
		phase = p
		phaseStart = now()
	}

	private fun nextAlive(after: UUID): UUID {
		val i = order.indexOf(after)
		for (k in 1..order.size) {
			val c = order[(i + k) % order.size]
			if (c in alive) return c
		}
		return after
	}

	/** Called by the holder when their task is solved. */
	private fun passOn() {
		val h = holder ?: return
		val to = nextAlive(h)
		if (room.solo || h in bots) applyPass(to)
		else room.send("bomb.pass", JsonObject().apply { addProperty("to", to.toString()); addProperty("n", passCount) })
	}

	private fun applyPass(to: UUID) {
		prevHolder = holder
		holder = to
		passedAt = now()
		passCount++
		play(SoundEvents.ITEM_PICKUP, 1.5f)
		onNewHolder()
	}

	private fun onNewHolder() {
		val h = holder
		task = if (h == Net.selfId) makeTask() else null
		if (h in bots) {
			val r = Random(seed xor (round * 7919L) xor (passCount * 104729L))
			botSolveAt = now() + (if (phase == Phase.INTRO) BombPass.INTRO_MS else 0) + r.nextLong(1_100, 3_600)
		}
	}

	private fun explode(v: UUID) {
		if (phase == Phase.BOOM || phase == Phase.OVER) return
		victim = v
		alive -= v
		task = null
		enter(Phase.BOOM)
		play(SoundEvents.FIREWORK_ROCKET_BLAST, 0.6f)
		play(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.5f)
	}

	private fun play(sound: SoundEvent, pitch: Float) =
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch))

	// ------------------------------------------------------------------ tasks

	private fun makeTask(): Task {
		val r = Random(seed xor (round * 31L) xor (passCount * 977L) xor now())
		return when (r.nextInt(3)) {
			0 -> Task.Keys(List(3 + minOf(round, 3)) { r.nextInt(4) })
			1 -> {
				val a = r.nextInt(2, 13)
				val b = r.nextInt(2, 13)
				val (q, ans) = when (r.nextInt(3)) {
					0 -> "$a + $b" to a + b
					1 -> "${a + b} - $b" to a
					else -> "${minOf(a, 9)} × ${minOf(b, 6)}" to minOf(a, 9) * minOf(b, 6)
				}
				val opts = mutableSetOf(ans)
				while (opts.size < 3) opts += ans + listOf(-3, -2, -1, 1, 2, 3, 10)[r.nextInt(7)]
				Task.MathQ("$q = ?", opts.shuffled(r), ans)
			}
			else -> {
				val target = r.nextInt(COLORS.size)
				val shown = (COLORS.indices).shuffled(r)
				// Labels lie: each button's text names a different color than it is drawn in.
				val labels = shown.map { s -> (COLORS.indices - s).random(r) }
				Task.Colors(target, labels, shown)
			}
		}
	}

	private fun wrong() {
		wrongAt = now()
		play(SoundEvents.VILLAGER_NO, 1.3f)
		task = makeTask()
	}

	private fun canAct() = phase == Phase.LIVE && holder == Net.selfId

	override fun keyPressed(event: KeyEvent): Boolean {
		val t = task
		if (canAct() && t is Task.Keys) {
			val dir = when (event.key()) {
				GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> 0
				GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> 1
				GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> 2
				GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> 3
				else -> -1
			}
			if (dir >= 0) {
				if (t.keys[t.done] == dir) {
					t.done++
					play(SoundEvents.NOTE_BLOCK_PLING.value(), 1f + t.done * 0.15f)
					if (t.done == t.keys.size) passOn()
				} else wrong()
				return true
			}
		}
		return super.keyPressed(event)
	}

	// ------------------------------------------------------------------ loop

	private fun update() {
		val t = now() - phaseStart
		when (phase) {
			Phase.INTRO -> if (t >= BombPass.INTRO_MS) { enter(Phase.LIVE); lastTick = 0 }
			Phase.LIVE -> {
				val h = holder
				if (h in bots && now() >= botSolveAt) passOn()
				// Ticking speeds up as the fuse burns (also a hint!).
				val frac = (t.toFloat() / fuseMs).coerceIn(0f, 1f)
				val interval = (700 - 580 * frac).toLong()
				if (now() - lastTick > interval) {
					lastTick = now()
					play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.2f + frac)
				}
				if (room.isHost && !boomSent && t >= fuseMs && h != null) {
					boomSent = true
					room.send("bomb.boom", JsonObject().apply { addProperty("victim", h.toString()) })
				}
			}
			Phase.BOOM -> if (t >= BombPass.BOOM_MS) {
				if (alive.size <= 1) finish()
				else if (room.isHost && phase == Phase.BOOM) {
					enter(Phase.WAITING)
					sendRound(nextAlive(victim ?: order.first()))
				}
			}
			else -> {}
		}
	}

	private fun finish() {
		enter(Phase.OVER)
		val won = alive.firstOrNull() == Net.selfId
		if (won && !rewarded) {
			rewarded = true
			MinigameStore.addTokens(BombPass.WIN_TOKENS)
		}
		play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.VILLAGER_NO, 1f)
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val sub = if (room.solo) "vs 3 bots" else "${room.members.size} players"
		val top = header(g, "Bomb Pass", sub)
		val cx = width / 2
		if (order.isEmpty()) {
			centered(g, "§7Waiting for the host…", cx, height / 2, Theme.TEXT)
			return
		}
		val cy = top + 20 + (height - top - 110) / 2
		val radius = ((height - top - 120) / 2 + 12).coerceIn(40, 90)
		radiusX = (width / 2 - 70).coerceIn(radius, 170)
		drawCircle(g, cx, cy, radius)
		when (phase) {
			Phase.INTRO -> { backdrop(g, cx, cy, 60, 16); bigText(g, "Round $round", cx, cy - 6, 2f, Theme.rainbow(0f, 0.55f)) }
			Phase.BOOM -> { drawBoom(g, cx, cy, radius) }
			Phase.OVER -> { backdrop(g, cx, cy, 110, 36); drawOver(g, cx, cy, mouseX, mouseY) }
			else -> {}
		}
		val panelY = cy + radius + 26
		if (phase == Phase.LIVE || phase == Phase.INTRO) {
			if (holder == Net.selfId) drawTask(g, cx, panelY, mouseX, mouseY)
			else holder?.let { centered(g, "§f${names[it] ?: "?"} §7has the bomb!", cx, panelY + 6, Theme.TEXT) }
		}
	}

	private var radiusX = 120

	private fun seatPos(i: Int, cx: Int, cy: Int, radius: Int): Pair<Float, Float> {
		val a = -Math.PI / 2 + i * 2 * Math.PI / order.size
		return Pair(cx + (cos(a) * radiusX).toFloat(), cy + (sin(a) * radius).toFloat())
	}

	private fun drawCircle(g: GuiGraphicsExtractor, cx: Int, cy: Int, radius: Int) {
		for ((i, id) in order.withIndex()) {
			val (x, y) = seatPos(i, cx, cy, radius)
			val out = id !in alive
			val isHolder = id == holder && phase != Phase.OVER
			if (isHolder) g.fill(x.toInt() - 13, y.toInt() - 13, x.toInt() + 13, y.toInt() + 13, ColorUtil.withAlpha(GameColors.RED, (0x60 + 0x40 * sin(Theme.time * 10)).toInt()))
			face(g, id, x.toInt() - 10, y.toInt() - 10, 20, if (out) 0xFF555555.toInt() else 0xFFFFFFFF.toInt())
			if (out) centered(g, "§c✖", x.toInt(), y.toInt() - 4, Theme.TEXT)
			val label = (if (id == Net.selfId) "§e" else if (out) "§8" else "§f") + font.plainSubstrByWidth(names[id] ?: "?", 70)
			centered(g, label, x.toInt(), y.toInt() + 13, Theme.TEXT)
		}
		// The bomb flies from the previous holder to the new one.
		val h = holder ?: return
		if (phase != Phase.LIVE && phase != Phase.INTRO) return
		val (tx, ty) = seatPos(order.indexOf(h), cx, cy, radius)
		var bx = tx + (cx - tx) * 0.38f
		var by = ty + (cy - ty) * 0.38f
		val p = prevHolder
		val k = ((now() - passedAt) / 260f).coerceIn(0f, 1f)
		if (p != null && k < 1f) {
			val (px, py) = seatPos(order.indexOf(p), cx, cy, radius)
			val sx = px + (cx - px) * 0.38f
			val sy = py + (cy - py) * 0.38f
			bx = sx + (bx - sx) * k
			by = sy + (by - sy) * k - sin(k * Math.PI).toFloat() * 24
		}
		val frac = if (phase == Phase.LIVE) ((now() - phaseStart).toFloat() / fuseMs).coerceIn(0f, 1f) else 0f
		drawBomb(g, bx.toInt(), by.toInt(), frac)
	}

	private fun drawBomb(g: GuiGraphicsExtractor, x: Int, y: Int, heat: Float) {
		val shake = (heat * heat * 3 * sin(Theme.time * 60)).toInt()
		val bx = x + shake
		val body = ColorUtil.argb(255, (40 + 180 * heat * heat).toInt().coerceAtMost(255), 30, 40)
		disc(g, bx, y, 9, 0xFF101018.toInt())
		disc(g, bx, y, 8, body)
		disc(g, bx - 3, y - 3, 2, 0x70FFFFFF)
		g.fill(bx + 4, y - 11, bx + 7, y - 7, 0xFF6A6A6A.toInt())
		g.fill(bx + 6, y - 14, bx + 7, y - 11, 0xFFB08850.toInt())
		if ((Theme.time * 12).toInt() % 2 == 0) {
			g.fill(bx + 5, y - 17, bx + 9, y - 14, 0xFFFFD84D.toInt())
			g.fill(bx + 6, y - 18, bx + 8, y - 13, 0xFFFF7A2A.toInt())
		}
	}

	private fun drawBoom(g: GuiGraphicsExtractor, cx: Int, cy: Int, radius: Int) {
		val v = victim ?: return
		val (x, y) = seatPos(order.indexOf(v), cx, cy, radius)
		val t = ((now() - phaseStart) / 700f).coerceIn(0f, 1f)
		val r = (6 + 40 * t).toInt()
		val a = ((1 - t) * 255).toInt()
		disc(g, x.toInt(), y.toInt(), r, ColorUtil.withAlpha(0xFFFF7A2A.toInt(), a))
		disc(g, x.toInt(), y.toInt(), (r * 0.6f).toInt(), ColorUtil.withAlpha(0xFFFFD84D.toInt(), a))
		backdrop(g, cx, cy, 80, 24)
		bigText(g, "BOOM!", cx, cy - 14, 2.5f, GameColors.RED)
		centered(g, "§f${names[v] ?: "?"} §7is out!", cx, cy + 12, Theme.TEXT)
	}

	private fun drawOver(g: GuiGraphicsExtractor, cx: Int, cy: Int, mx: Int, my: Int) {
		val w = alive.firstOrNull()
		val won = w == Net.selfId
		bigText(g, if (won) "You survived!" else "${names[w] ?: "Nobody"} wins!", cx, cy - 22, 2f, Theme.rainbow(0f, 0.55f))
		if (won) centered(g, "§6+${BombPass.WIN_TOKENS} Tokens", cx, cy, Theme.TEXT)
		if (room.solo || room.isHost) {
			button(g, cx - 100, cy + 14, 92, 18, "Play again", mx, my, color = GameColors.GREEN) {
				if (room.solo) startMatch(Random.nextLong()) else requestStart()
			}
		} else centered(g, "§7Waiting for the host…", cx, cy + 19, Theme.TEXT)
		button(g, cx + 8, cy + 14, 92, 18, "Leave", mx, my, color = GameColors.RED) { onClose() }
	}

	private fun drawTask(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		val pw = 260
		val ph = 58
		panel(g, cx - pw / 2, y, pw, ph)
		val flash = now() - wrongAt < 300
		if (flash) g.fill(cx - pw / 2, y, cx + pw / 2, y + ph, 0x50FF3030)
		val enabled = phase == Phase.LIVE
		centered(g, if (phase == Phase.INTRO) "§e§lYou start with the bomb! §7Get ready…" else "§e§lYou have the bomb!", cx, y + 5, Theme.TEXT)
		when (val t = task) {
			is Task.Keys -> {
				centered(g, "§7Press the arrows (or WASD):", cx, y + 16, Theme.TEXT)
				val total = t.keys.size * 22
				for ((i, k) in t.keys.withIndex()) {
					val x = cx - total / 2 + i * 22
					val done = i < t.done
					g.fill(x, y + 29, x + 18, y + 47, if (done) GameColors.GREEN else if (i == t.done) Theme.accent else 0xFF2A2A36.toInt())
					centered(g, ARROWS[k], x + 9, y + 34, Theme.TEXT)
				}
			}
			is Task.MathQ -> {
				centered(g, "§f${t.question}", cx, y + 16, Theme.TEXT)
				for ((i, o) in t.options.withIndex()) {
					button(g, cx - 120 + i * 82, y + 29, 76, 20, o.toString(), mx, my, enabled = enabled) {
						if (o == t.answer) passOn() else wrong()
					}
				}
			}
			is Task.Colors -> {
				val pre = "§7Click the button that IS "
				val name = "§l" + COLOR_NAMES[t.target]
				val tx = cx - (font.width(pre) + font.width(name)) / 2
				g.text(font, pre, tx, y + 16, Theme.TEXT, true)
				g.text(font, name, tx + font.width(pre), y + 16, COLORS[t.target], true)
				for ((i, s) in t.shown.withIndex()) {
					button(g, cx - 122 + i * 62, y + 29, 56, 20, COLOR_NAMES[t.labels[i]], mx, my, enabled = enabled, color = COLORS[s]) {
						if (s == t.target) passOn() else wrong()
					}
				}
			}
			null -> {}
		}
	}

	private fun face(g: GuiGraphicsExtractor, id: UUID, x: Int, y: Int, size: Int, tint: Int) {
		val skin: Identifier = Minecraft.getInstance().connection?.getPlayerInfo(id)?.skin?.body()?.texturePath()
			?: if (id == Net.selfId) Minecraft.getInstance().player?.skin?.body()?.texturePath() ?: DefaultPlayerSkin.get(id).body().texturePath()
			else DefaultPlayerSkin.get(id).body().texturePath()
		g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8f, 8f, size, size, 8, 8, 64, 64, tint)
		g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40f, 8f, size, size, 8, 8, 64, 64, tint)
	}

	private fun backdrop(g: GuiGraphicsExtractor, cx: Int, cy: Int, hw: Int, hh: Int) {
		g.fill(cx - hw, cy - hh - 6, cx + hw, cy + hh + 6, 0xC8100A1E.toInt())
		g.outline(cx - hw, cy - hh - 6, hw * 2, hh * 2 + 12, Theme.rainbow(0f, 0.5f))
	}

	private fun disc(g: GuiGraphicsExtractor, cx: Int, cy: Int, r: Int, color: Int) {
		for (dy in -r..r) {
			val hw = sqrt((r * r - dy * dy).toDouble()).toInt()
			g.fill(cx - hw, cy + dy, cx + hw + 1, cy + dy + 1, color)
		}
	}

	/** Test hooks. */
	val debugPhase get() = phase
	val debugAlive get() = alive.size
	val debugMine get() = holder == Net.selfId
	fun debugSolve(): Boolean {
		if (!canAct()) return false
		when (val t = task) {
			is Task.Keys -> passOn()
			is Task.MathQ -> passOn()
			is Task.Colors -> passOn()
			null -> return false
		}
		return true
	}

	override fun removed() {
		room.leave()
		super.removed()
	}

	companion object {
		private val ARROWS = listOf("↑", "→", "↓", "←")
		private val COLORS = listOf(0xFFE04848.toInt(), 0xFF3FBF6A.toInt(), 0xFF4A7BE0.toInt(), 0xFFE8C53A.toInt())
		private val COLOR_NAMES = listOf("RED", "GREEN", "BLUE", "YELLOW")
	}
}
