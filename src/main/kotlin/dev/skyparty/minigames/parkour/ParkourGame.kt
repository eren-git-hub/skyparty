package dev.skyparty.minigames.parkour

import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameRegistry
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.parkour.ParkourPhysics.FP
import dev.skyparty.minigames.parkour.ParkourPhysics.Input
import dev.skyparty.minigames.parkour.ParkourPhysics.Outcome
import dev.skyparty.minigames.parkour.ParkourPhysics.State
import dev.skyparty.minigames.parkour.ParkourPhysics.TILE
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import dev.skyparty.util.Chat
import dev.skyparty.util.ColorUtil
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

/** 2D Parkour Race: seeded, solver-verified courses, players drawn as pixel versions of their skins. */
object ParkourGame : Minigame {
	override val id = "parkour"
	override val name = "Parkour Race"
	override val description = "Race your party through a random (always beatable) 2D course."
	override val minPlayers = 1
	override val maxPlayers = 5
	override val realtime = true
	override val length = dev.skyparty.minigames.GameLength.MEDIUM

	override fun open(room: Net.Room?) = MinigameHub.openScreen(ParkourScreen(LobbyRoom.of(room, id), null))

	fun init() {
		MinigameRegistry.register(this)
		SkypartyCommands.sub {
			ClientCommands.literal("parkour")
				.executes { MinigameHub.openScreen(ParkourScreen(LobbyRoom.solo(id), null)); 1 }
				.then(ClientCommands.argument("seed", LongArgumentType.longArg(1)).executes { ctx ->
					MinigameHub.openScreen(ParkourScreen(LobbyRoom.solo(id), LongArgumentType.getLong(ctx, "seed")))
					1
				})
				.then(ClientCommands.literal("check").then(
					ClientCommands.argument("count", IntegerArgumentType.integer(1, 500)).executes { ctx ->
						checkSeedsAsync(IntegerArgumentType.getInteger(ctx, "count"))
						1
					}
				))
		}
	}

	/** Generates and solves [count] seeds off-thread and reports how many are beatable. */
	fun checkSeedsAsync(count: Int) {
		Chat.info("§7Checking $count parkour seeds…")
		Thread({
			val report = ParkourSolver.checkSeeds(1, count) {}
			Minecraft.getInstance().execute {
				Chat.info("§f${report.solvable}/${report.seeds} §7seeds beatable §8(${report.totalRetries} re-rolls, ${report.millis} ms)")
				if (report.failures.isNotEmpty()) Chat.info("§cUnsolved: ${report.failures.joinToString()}")
			}
		}, "Skyparty-ParkourCheck").apply { isDaemon = true }.start()
	}
}

class ParkourScreen(val room: LobbyRoom, private val fixedSeed: Long?) : GameScreen("Parkour Race", room) {
	enum class Phase { WAITING, COUNTDOWN, RUN, FINISHED }

	override fun inProgress() = phase != Phase.FINISHED

	private class Racer(var x: Float, var y: Float, var px: Float, var py: Float, var facing: Int, var anim: Int, var at: Long)
	private class GhostFrame(val x: Int, val y: Int, val facing: Int)

	private var phase = Phase.WAITING
	private var seed = 0L
	private var level: ParkourLevel? = null
	private var state = State(0, 0)
	private var prevX = 0
	private var prevY = 0
	private var steps = 0
	private var countdownUntil = 0L
	private var lastStepNanos = 0L
	private var deaths = 0
	private var checkpoint = 0
	private var finishSteps = -1
	private var newBest = false

	private val others = mutableMapOf<UUID, Racer>()
	private val finished = linkedMapOf<UUID, Int>()
	private val recording = ArrayList<GhostFrame>()
	private var bestGhost: List<GhostFrame>? = null

	/** Debug/test hook: inputs to play instead of the keyboard (e.g. the solver's path). */
	var autoInputs: List<Input>? = null

	private class View(val camX: Float, val camY: Float, val scale: Float, val top: Float)
	private var view = View(0f, 0f, 1f, 0f)

	/** Debug/test hook: current simulation state. */
	val debugState get() = "phase=$phase steps=$steps x=${state.x / FP} y=${state.y / FP} grounded=${state.grounded} deaths=$deaths cp=$checkpoint"

	init {
		room.onMessage = ::onMessage
		if (room.solo) startSolo(fixedSeed ?: (1..1_000_000).random().toLong())
		else if (room.isHost) room.send("parkour.start.rng", JsonObject())
	}

	// ------------------------------------------------------------------ protocol

	private fun onMessage(msg: Net.Message) {
		val p = msg.payload
		when (msg.type) {
			"parkour.start.rng" -> if (msg.from == room.host && !room.solo) {
				begin(((p["roll"]?.asDouble ?: Math.random()) * 1_000_000_000).toLong() + 1)
			}
			"parkour.state" -> if (msg.from != Net.selfId) {
				val x = p["x"].asFloat
				val y = p["y"].asFloat
				val r = others.getOrPut(msg.from) { Racer(x, y, x, y, 1, 0, 0) }
				r.px = r.x; r.py = r.y
				r.x = x; r.y = y
				r.facing = p["f"]?.asInt ?: 1
				r.anim = p["a"]?.asInt ?: 0
				r.at = room.now()
			}
			"parkour.finish" -> finished.putIfAbsent(msg.from, p["steps"]?.asInt ?: Int.MAX_VALUE)
		}
	}

	private fun startSolo(s: Long) = begin(s)

	private fun begin(s: Long) {
		seed = s
		// The PB ghost belongs to one course: it only shows up again when you press Retry on it.
		bestGhost = null
		level = null
		phase = Phase.WAITING
		finished.clear()
		others.clear()
		Thread({
			val l = ParkourGenerator.generate(s)
			Minecraft.getInstance().execute {
				level = l
				reset()
				countdownUntil = room.now() + 3000
				phase = Phase.COUNTDOWN
			}
		}, "Skyparty-ParkourGen").apply { isDaemon = true }.start()
	}

	private fun reset() {
		val l = level ?: return
		state = l.checkpoints.first().spawnState()
		prevX = state.x; prevY = state.y
		steps = 0; deaths = 0; checkpoint = 0; finishSteps = -1; newBest = false
		recording.clear()
	}

	// ------------------------------------------------------------------ simulation

	private fun readInput(index: Int): Input {
		autoInputs?.let { return it.getOrElse(index) { Input.NONE } }
		val w = Minecraft.getInstance().window
		fun down(vararg keys: Int) = keys.any { InputConstants.isKeyDown(w, it) }
		val dir = (if (down(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT)) 1 else 0) - (if (down(GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT)) 1 else 0)
		return Input(dir, down(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP))
	}

	private fun simulate() {
		val l = level ?: return
		val now = room.nanos()
		if (phase == Phase.COUNTDOWN && room.now() >= countdownUntil) {
			phase = Phase.RUN
			lastStepNanos = now
		}
		if (phase != Phase.RUN) return
		val stepNanos = 1_000_000_000L / ParkourPhysics.STEPS_PER_SECOND
		var budget = 8 // never spiral after a lag spike
		while (now - lastStepNanos >= stepNanos && budget-- > 0) {
			lastStepNanos += stepNanos
			prevX = state.x; prevY = state.y
			val outcome = ParkourPhysics.step(l, state, readInput(steps))
			steps++
			if (steps % 2 == 0) recording += GhostFrame(state.x, state.y, state.facing)
			// Furthest checkpoint reached
			while (checkpoint + 1 < l.checkpoints.size && state.x >= l.checkpoints[checkpoint + 1].col * ParkourPhysics.TILE_FP) checkpoint++
			when (outcome) {
				Outcome.DIED -> {
					deaths++
					state = l.checkpoints[checkpoint].spawnState()
					prevX = state.x; prevY = state.y
				}
				Outcome.FINISHED -> { finish(); return }
				Outcome.NONE -> {}
			}
			// 4 updates/s: the server allows 5 messages/s per player and room (shared with lobby, wheel, pings).
			if (!room.solo && steps % 15 == 0) {
				room.send("parkour.state", JsonObject().apply {
					addProperty("x", state.x / FP.toFloat()); addProperty("y", state.y / FP.toFloat())
					addProperty("f", state.facing); addProperty("a", if (state.grounded && state.vx != 0) 1 else 0)
				})
			}
		}
		if (now - lastStepNanos > stepNanos * 8) lastStepNanos = now
	}

	private fun finish() {
		phase = Phase.FINISHED
		finishSteps = steps
		val seconds = steps / ParkourPhysics.STEPS_PER_SECOND.toDouble()
		newBest = MinigameStore.submit("parkour:$seed", seconds, true, formatTime(steps))
		if (newBest) bestGhost = recording.toList()
		MinigameStore.addTokens(if (room.solo) 3 else 5)
		finished.putIfAbsent(Net.selfId, steps)
		if (!room.solo) room.send("parkour.finish", JsonObject().apply { addProperty("steps", steps) })
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		simulate()
		val top = header(g, "Parkour Race", if (room.solo) "seed $seed" else "${room.members.size} racers · seed $seed")
		val l = level
		if (l == null) {
			centered(g, "Building a beatable course…", width / 2, height / 2, Theme.TEXT)
			return
		}

		val areaTop = top
		val areaH = height - top - 18
		// Show ~10 tile rows so the pixel skins are big enough; the camera follows in both axes.
		val scale = areaH / (10f * TILE)
		val stepFrac = if (phase == Phase.RUN) ((room.nanos() - lastStepNanos).toFloat() * ParkourPhysics.STEPS_PER_SECOND / 1e9f).coerceIn(0f, 1f) else 1f
		val px = (prevX + (state.x - prevX) * stepFrac) / FP
		val py = (prevY + (state.y - prevY) * stepFrac) / FP
		val viewW = width / scale
		val camX = (px - viewW * 0.35f).coerceIn(0f, (l.width * TILE - viewW).coerceAtLeast(0f))
		val viewH = areaH / scale
		val camY = (py - viewH * 0.55f).coerceIn(0f, (l.height * TILE - viewH).coerceAtLeast(0f))

		drawBackground(g, areaTop, areaH, camX)

		val pose = g.pose()
		g.enableScissor(0, areaTop, width, areaTop + areaH)
		pose.pushMatrix()
		pose.translate(0f, areaTop.toFloat())
		pose.scale(scale, scale)
		pose.translate(-camX, -camY)
		drawTiles(g, l, camX, viewW)
		pose.popMatrix()
		view = View(camX, camY, scale, areaTop.toFloat())
		// Ghost of your best run (solo) and the other racers
		bestGhost?.let { ghost ->
			val f = ghost.getOrNull(steps / 2) ?: ghost.lastOrNull()
			if (f != null && phase != Phase.WAITING) drawSkin(g, selfSkin(), f.x / FP.toFloat(), f.y / FP.toFloat(), f.facing, steps, 0x66FFFFFF, "§7PB ghost")
		}
		for ((id, r) in others) {
			val t = ((room.now() - r.at) / 250f).coerceIn(0f, 1f)
			drawSkin(g, skinOf(id), r.px + (r.x - r.px) * t, r.py + (r.y - r.py) * t, r.facing, if (r.anim == 1) (room.now() / 16).toInt() else 0,
				0xAAFFFFFF.toInt(), room.nameOf(id))
		}
		val moving = state.grounded && state.vx != 0
		drawSkin(g, selfSkin(), px, py, state.facing, if (moving) steps else 0, 0xFFFFFFFF.toInt(), null, air = !state.grounded)
		g.disableScissor()

		drawHud(g, l, areaTop, areaH, mouseX, mouseY)
	}

	private fun drawBackground(g: GuiGraphicsExtractor, top: Int, h: Int, camX: Float) {
		g.fillGradient(0, top, width, top + h, 0xFF3A1C63.toInt(), 0xFF7A3E8C.toInt())
		// Twinkling stars (slow parallax)
		for (i in 0 until 40) {
			val sx = ((i * 97 + 13) % 400 - camX * 0.05f).mod(width.toFloat())
			val sy = top + (i * 53 % (h * 2 / 3))
			val a = (128 + 127 * sin(Theme.time * 2 + i)).toInt().coerceIn(40, 255)
			g.fill(sx.toInt(), sy, sx.toInt() + 1, sy + 1, ColorUtil.withAlpha(0xFFFFFFFF.toInt(), a))
		}
		// Big moon and drifting clouds
		val moonX = (width * 0.78f - camX * 0.02f).toInt()
		val moonY = top + h / 6
		g.fill(moonX - 9, moonY - 12, moonX + 9, moonY + 12, 0x30FFF2C8)
		g.fill(moonX - 12, moonY - 9, moonX + 12, moonY + 9, 0x30FFF2C8)
		g.fill(moonX - 7, moonY - 9, moonX + 7, moonY + 9, 0xFFFFF2C8.toInt())
		g.fill(moonX - 9, moonY - 7, moonX + 9, moonY + 7, 0xFFFFF2C8.toInt())
		g.fill(moonX - 3, moonY - 4, moonX, moonY - 1, 0xFFE8D8A8.toInt())
		g.fill(moonX + 3, moonY + 2, moonX + 5, moonY + 4, 0xFFE8D8A8.toInt())
		for (i in 0 until 7) {
			val span = width + 120f
			val cx = ((i * 211 + 37) % width - camX * (0.08f + i % 3 * 0.04f) - Theme.time * (3 + i % 3)).mod(span) - 60f
			val cy = top + 10 + (i * 37) % (h / 3)
			val w = 26 + (i * 13) % 30
			val c = 0x30FFFFFF + ((i % 3) shl 24)
			g.fill(cx.toInt(), cy + 4, cx.toInt() + w, cy + 10, c)
			g.fill(cx.toInt() + w / 5, cy, cx.toInt() + w * 3 / 5, cy + 5, c)
			g.fill(cx.toInt() + w / 2, cy + 2, cx.toInt() + w * 4 / 5, cy + 5, c)
		}
		// Two layers of rolling hills
		for (layer in 0..1) {
			val speed = if (layer == 0) 0.15f else 0.35f
			val base = top + h - (if (layer == 0) h / 3 else h / 5)
			val color = if (layer == 0) 0xFF5B2C7A.toInt() else 0xFF8E4A9C.toInt()
			for (x in 0 until width step 2) {
				val wx = x + camX * speed
				val hy = base + (sin(wx * 0.02f + layer * 2) * 8 + sin(wx * 0.007f) * 12).toInt()
				g.fill(x, hy, x + 2, top + h, color)
			}
		}
	}

	/** Purely visual props on grass (no hitbox): picked from the tile position, so every racer sees the same. */
	private fun drawDecoration(g: GuiGraphicsExtractor, tx: Int, ty: Int, x: Int, y: Int) {
		val h = ((tx * 73856093) xor (ty * 19349663)).let { (it ushr 3) and 0xFF }
		val sway = (sin(Theme.time * 2 + tx) * 1f).toInt()
		when {
			h < 60 -> { // grass tufts
				val c = 0xFF9AE07A.toInt()
				g.fill(x + 3, y - 3, x + 4, y, c); g.fill(x + 4 + sway, y - 5, x + 5 + sway, y, c); g.fill(x + 6, y - 3, x + 7, y, c)
				g.fill(x + 11, y - 4, x + 12, y, c); g.fill(x + 12 + sway, y - 2, x + 13 + sway, y, c)
			}
			h < 90 -> { // flower
				val petal = listOf(0xFFFF7EB6, 0xFFFFE066, 0xFF8EC5FF, 0xFFFFFFFF)[h % 4].toInt()
				g.fill(x + 8, y - 6, x + 9, y, 0xFF5FB04A.toInt())
				g.fill(x + 7 + sway, y - 9, x + 10 + sway, y - 6, petal)
				g.fill(x + 8 + sway, y - 8, x + 9 + sway, y - 7, 0xFFFFB020.toInt())
			}
			h < 105 -> { // mushroom
				g.fill(x + 5, y - 3, x + 7, y, 0xFFF3E9D2.toInt())
				g.fill(x + 3, y - 6, x + 9, y - 3, 0xFFE0485A.toInt())
				g.fill(x + 4, y - 5, x + 5, y - 4, 0xFFFFFFFF.toInt()); g.fill(x + 7, y - 6, x + 8, y - 5, 0xFFFFFFFF.toInt())
			}
			h < 115 -> { // little crystal
				g.fill(x + 10, y - 7, x + 12, y, 0xFF8EE8FF.toInt())
				g.fill(x + 9, y - 4, x + 10, y, 0xFF5CC8F0.toInt()); g.fill(x + 12, y - 5, x + 13, y, 0xFF5CC8F0.toInt())
				g.fill(x + 10, y - 7, x + 11, y - 5, 0xFFE8FBFF.toInt())
			}
			h < 122 -> { // signpost
				g.fill(x + 7, y - 10, x + 8, y, 0xFF8A5A34.toInt())
				g.fill(x + 3, y - 11, x + 13, y - 6, 0xFFB07A48.toInt())
				g.fill(x + 10, y - 9, x + 12, y - 8, 0xFF5A3A20.toInt()); g.fill(x + 4, y - 9, x + 9, y - 8, 0xFF5A3A20.toInt())
			}
		}
	}

	private fun drawTiles(g: GuiGraphicsExtractor, l: ParkourLevel, camX: Float, viewW: Float) {
		val x0 = (camX / TILE).toInt() - 1
		val x1 = ((camX + viewW) / TILE).toInt() + 1
		for (tx in x0..x1) for (ty in 0 until l.height) {
			val x = tx * TILE
			val y = ty * TILE
			when (l.tile(tx, ty)) {
				ParkourLevel.SOLID -> {
					val grassTop = !l.solid(tx, ty - 1)
					g.fill(x, y, x + TILE, y + TILE, if ((tx + ty) % 2 == 0) 0xFF6B4A8E.toInt() else 0xFF634486.toInt())
					if (grassTop) {
						g.fill(x, y, x + TILE, y + 4, Theme.rainbow(tx * 0.03f, 0.45f, 1f))
						g.fill(x, y + 4, x + TILE, y + 5, 0x40000000)
						if (l.tile(tx, ty - 1) == ParkourLevel.EMPTY) drawDecoration(g, tx, ty, x, y)
					}
				}
				ParkourLevel.PAD -> {
					g.fill(x, y, x + TILE, y + TILE, if ((tx + ty) % 2 == 0) 0xFF6B4A8E.toInt() else 0xFF634486.toInt())
					// Spring pad: coil + bouncing plate with an up arrow.
					val squash = ((sin(Theme.time * 6 + tx) + 1) * 1.5f).toInt()
					g.fill(x + 3, y - 3 + squash, x + 13, y + 1, 0xFF3A2A50.toInt())
					for (i in 0 until 2) g.fill(x + 4, y - 2 + squash + i * 2, x + 12, y - 1 + squash + i * 2, 0xFFB0B8C8.toInt())
					g.fill(x + 1, y - 6 + squash, x + 15, y - 3 + squash, 0xFFFFC94D.toInt())
					g.fill(x + 1, y - 3 + squash, x + 15, y - 2 + squash, 0xFFD08A2E.toInt())
					g.fill(x + 7, y - 13 + squash, x + 9, y - 8 + squash, 0xAAFFE38A.toInt())
					g.fill(x + 5, y - 11 + squash, x + 11, y - 10 + squash, 0xAAFFE38A.toInt())
					g.fill(x + 6, y - 12 + squash, x + 10, y - 11 + squash, 0xAAFFE38A.toInt())
				}
				ParkourLevel.SPIKE -> for (i in 0 until 4) {
					val sx = x + i * 4
					for (row in 0 until 8) g.fill(sx + row / 4, y + TILE - row - 1, sx + 4 - row / 4, y + TILE - row, 0xFFFF4D6D.toInt())
				}
			}
		}
		// Checkpoint flags
		for ((i, cp) in l.checkpoints.withIndex()) {
			if (i == 0) continue
			val fx = cp.col * TILE + 4
			val fy = cp.floorRow * TILE
			g.fill(fx, fy - 24, fx + 1, fy, 0xFFFFFFFF.toInt())
			g.fill(fx + 1, fy - 24, fx + 9, fy - 18, if (i <= checkpoint) GameColors.GREEN else GameColors.GOLD)
		}
		// Checkered finish line
		val fx = l.finishX * TILE
		for (ty in 0 until l.height) for (i in 0 until 2) {
			val dark = (ty + i) % 2 == 0
			g.fill(fx + i * 8, ty * TILE, fx + i * 8 + 8, ty * TILE + 8, if (dark) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
			g.fill(fx + i * 8, ty * TILE + 8, fx + i * 8 + 8, ty * TILE + 16, if (dark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt())
		}
	}

	private fun selfSkin(): Identifier =
		Minecraft.getInstance().player?.skin?.body()?.texturePath() ?: DefaultPlayerSkin.getDefaultTexture()

	private fun skinOf(id: UUID): Identifier =
		Minecraft.getInstance().connection?.getPlayerInfo(id)?.skin?.body()?.texturePath()
			?: DefaultPlayerSkin.get(id).body().texturePath()

	/**
	 * Chibi version of a Minecraft skin, 32 world pixels tall like the hitbox: big face, body, arms, legs,
	 * all cut from the 64x64 skin texture.
	 */
	private fun drawSkin(g: GuiGraphicsExtractor, skin: Identifier, x: Float, y: Float, facing: Int, anim: Int, color: Int, label: String?, air: Boolean = false) {
		// Blits are culled by their untransformed coordinates, so convert world -> screen here and keep
		// the blit coordinates tiny (never draw textures under a large pose translation).
		val v = view
		val sx = (x + 4f - v.camX) * v.scale
		val sy = v.top + (y - v.camY) * v.scale
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(sx, sy)
		pose.scale(v.scale, v.scale)
		// Chibi hopper: big front-facing head, small body, short legs. Instead of a walk cycle the figure
		// hops while running (squashes a little on each landing) and throws its arms up in the air.
		// Feet at y = 32.
		val hopPhase = abs(sin(anim * 0.3f))
		val running = anim != 0 && !air
		val lift = if (running) (hopPhase * 4f).toInt() else 0
		val squash = if (running && hopPhase < 0.25f) 1 else 0
		val look = if (facing >= 0) 1 else -1
		// dest (dx, dy, w, h) <- skin region (u, v, rw, rh). Facing left mirrors via flipped UVs (a negative
		// pose scale flips the quad and it gets culled).
		fun part(dx: Int, dy: Int, w: Int, h: Int, u: Int, v: Int, rw: Int, rh: Int) =
			if (facing >= 0) g.blit(RenderPipelines.GUI_TEXTURED, skin, dx, dy, u.toFloat(), v.toFloat(), w, h, rw, rh, 64, 64, color)
			else g.blit(RenderPipelines.GUI_TEXTURED, skin, -dx - w, dy, (u + rw).toFloat(), v.toFloat(), w, h, -rw, rh, 64, 64, color)

		// shadow on the ground, smaller while hopping
		if (!air) {
			val sw = 7 - lift
			g.fill(-sw, 32, sw, 33, ColorUtil.withAlpha(0xFF000000.toInt(), (color ushr 24) / 3))
		}
		val y0 = -lift + squash
		// legs (right leg classic layout, left leg 64x64 layout), shorter when squashed
		val legH = 7 - squash
		part(-5, 25 + y0, 4, legH, 4, 20, 4, 12)
		part(1, 25 + y0, 4, legH, 20, 52, 4, 12)
		// body
		part(-5, 15 + y0, 10, 10, 20, 20, 8, 12)
		// arms at the sides, raised while airborne
		val armY = if (air) 11 + y0 else 15 + y0
		part(-8, armY, 3, 8, 44, 20, 4, 12)
		part(5, armY, 3, 8, 36, 52, 4, 12)
		// big head (8x8 face drawn at 16x15) + hat layer, nudged toward the running direction
		part(-8 + look, y0, 16, 15, 8, 8, 8, 8)
		part(-9 + look, y0 - 1, 18, 17, 40, 8, 8, 8)
		pose.popMatrix()
		if (label != null) {
			val w = font.width(label)
			g.text(font, label, (sx - w / 2f).toInt(), (sy - 10).toInt(), 0xFFFFFFFF.toInt(), true)
		}
	}

	private fun drawHud(g: GuiGraphicsExtractor, l: ParkourLevel, areaTop: Int, areaH: Int, mx: Int, my: Int) {
		val cx = width / 2
		val barY = areaTop + areaH
		g.fill(0, barY, width, height, Theme.HEADER)
		// Progress bar
		val progress = (state.x / ParkourPhysics.TILE_FP.toFloat() / l.finishX).coerceIn(0f, 1f)
		g.fill(8, barY + 6, width - 160, barY + 10, Theme.TRACK)
		g.fill(8, barY + 6, 8 + ((width - 168) * progress).toInt(), barY + 10, Theme.rainbow(0f, 0.6f))
		val time = formatTime(if (finishSteps >= 0) finishSteps else steps)
		g.text(font, "⏱ $time  ☠ $deaths", width - 150, barY + 4, Theme.TEXT, true)
		val best = MinigameStore.best("parkour:$seed")?.display
		if (best != null) g.text(font, "PB $best", width - 50, barY + 4, GameColors.GOLD, true)

		when (phase) {
			Phase.COUNTDOWN -> {
				val left = ((countdownUntil - room.now()) / 1000 + 1).coerceAtLeast(1)
				bigText(g, "$left", cx, areaTop + areaH / 2 - 20, 4f, Theme.rainbow(left * 0.2f))
				centered(g, "§7A/D or ←/→ to run · Space/W to jump", cx, areaTop + areaH / 2 + 22, Theme.TEXT)
			}
			Phase.FINISHED -> drawResults(g, cx, areaTop + 20, mx, my)
			else -> {}
		}
	}

	private fun drawResults(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		val rows = finished.entries.sortedBy { it.value }
		val running = room.members.filter { it !in finished }
		val headline = if (room.solo) 0 else 14
		val h = 46 + headline + (rows.size + running.size) * 11
		panel(g, cx - 90, y, 180, h, "Finish!")
		// Winner: the fastest finisher (the first one to cross the line).
		if (!room.solo) rows.firstOrNull()?.let { (id, _) ->
			centered(g, if (id == Net.selfId) "§6§l♛ You win!" else "§6§l♛ ${room.nameOf(id)} wins!", cx, y + 20, Theme.TEXT)
		}
		val ly = y + 20 + headline
		rows.forEachIndexed { i, (id, s) ->
			val medal = when (i) { 0 -> GameColors.GOLD; 1 -> GameColors.SILVER; 2 -> GameColors.BRONZE; else -> Theme.TEXT }
			g.text(font, "${i + 1}. ${room.nameOf(id)}", cx - 82, ly + i * 11, medal, false)
			val t = formatTime(s)
			g.text(font, t, cx + 82 - font.width(t), ly + i * 11, medal, false)
		}
		running.forEachIndexed { i, id ->
			g.text(font, "§8–  ${room.nameOf(id)}", cx - 82, ly + (rows.size + i) * 11, Theme.TEXT, false)
			g.text(font, "§8running…", cx + 82 - font.width("running…"), ly + (rows.size + i) * 11, Theme.TEXT, false)
		}
		if (newBest) centered(g, "§e✦ New personal best! ✦", cx, ly + 2 + (rows.size + running.size) * 11, Theme.TEXT)
		val by = y + h - 18
		if (room.solo) {
			button(g, cx - 84, by, 80, 14, "Retry", mx, my) { reset(); countdownUntil = room.now() + 2000; phase = Phase.COUNTDOWN }
			button(g, cx + 4, by, 80, 14, "New course", mx, my) { startSolo((1..1_000_000).random().toLong()) }
		} else {
			if (room.isHost) button(g, cx - 84, by, 80, 14, "Race again", mx, my) { room.send("parkour.start.rng", JsonObject()) }
			else centered(g, "§7Waiting for the host…", cx - 44, by + 3, Theme.TEXT)
			button(g, cx + 4, by, 80, 14, "Leave", mx, my, color = GameColors.RED) { room.quit(); onClose() }
		}
	}

	override fun removed() {
		room.leave()
		super.removed()
	}

	companion object {
		fun formatTime(steps: Int): String {
			val ms = steps * 1000L / ParkourPhysics.STEPS_PER_SECOND
			return "%d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10)
		}
	}
}
