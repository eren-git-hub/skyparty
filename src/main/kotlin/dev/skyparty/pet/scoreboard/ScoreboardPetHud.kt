package dev.skyparty.pet.scoreboard

import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.module.impl.pets.ScoreboardPet
import dev.skyparty.pet.PetData
import dev.skyparty.util.ColorUtil
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.util.Mth
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The 2D pet that lives on top of the sidebar scoreboard. Logic runs per client tick,
 * drawing interpolates between ticks. Mouse interaction works while the chat is open.
 */
object ScoreboardPetHud {
	enum class Mode { IDLE, WALK, SLEEP, HAPPY, SCARED, CHASE, DRAGGED, FALLING }
	enum class Reaction { HAPPY, SCARED }

	private class Floater(val text: String, var x: Float, var y: Float, val color: Int, var age: Int = 0)
	private class Ball(var x: Float, var height: Float, var vx: Float = 0f, var vy: Float = 0f, var life: Int = 400)
	private class Food(var x: Float, var height: Float, var vy: Float = 0f, var life: Int = 600)

	private var food: Food? = null
	private var lastFedAt = 0L

	/** Number of snacks eaten this session, shown in the name tag. */
	var snacks = 0
		private set

	private var mode = Mode.IDLE
	private var modeTicks = 60

	/** Absolute GUI x of the sprite's left edge, and height above the scoreboard's top edge. */
	private var x = Float.NaN
	private var prevX = 0f
	private var height = 0f
	private var prevHeight = 0f
	private var vy = 0f
	private var facingRight = true
	private var targetX = 0f
	private var age = 0

	private var box: SidebarBounds.Box? = null
	private var spriteW = 8f
	private var spriteH = 9f
	private var ball: Ball? = null
	private val floaters = mutableListOf<Floater>()
	private var wasDead = false

	// Drag state (GUI coordinates)
	private var dragOffsetX = 0f
	private var dragOffsetY = 0f
	private var dragMouseX = 0f
	private var dragMouseY = 0f

	private val mc get() = Minecraft.getInstance()
	private val pixel get() = ScoreboardPet.size.floatValue

	// ---------------------------------------------------------------- ticking

	fun tick() {
		if (!ScoreboardPet.enabled) return
		val b = box ?: return
		age++
		prevX = x
		prevHeight = height

		detectDeath()
		tickBall(b)
		tickFood()
		floaters.removeAll { it.age++ > 30 }
		floaters.forEach { it.y -= 0.6f }

		val minX = b.left.toFloat()
		val maxX = b.right - spriteW * pixel
		if (x.isNaN()) {
			x = (minX + maxX) / 2
			prevX = x
		}

		when (mode) {
			Mode.DRAGGED -> return
			Mode.FALLING -> {
				vy -= 0.5f
				height += vy
				if (height <= 0f) {
					height = 0f
					vy = 0f
					setMode(Mode.IDLE, 30)
				}
			}
			else -> tickBehaviour(minX, maxX)
		}
		x = x.coerceIn(minX, maxX.coerceAtLeast(minX))
	}

	private fun tickBehaviour(minX: Float, maxX: Float) {
		val speed = 0.6f * ScoreboardPet.speed.floatValue * pixel

		if (PetData.isAfk && food == null && mode != Mode.SLEEP) setMode(Mode.SLEEP, Int.MAX_VALUE)
		if (mode == Mode.SLEEP) {
			if (!PetData.isAfk || food != null) setMode(Mode.IDLE, 20)
			else if (age % 25 == 0) floaters += Floater("z", x + spriteW * pixel, topY() - 2, 0xFFB0C4FF.toInt())
			return
		}

		val food = food
		if (food != null && mode != Mode.HAPPY && mode != Mode.SCARED) {
			// Run to the snack and eat it once it is on the ground.
			mode = Mode.CHASE
			val center = x + spriteW * pixel / 2
			val diff = food.x - center
			if (abs(diff) > speed * 1.5f) {
				facingRight = diff > 0
				x += speed * 1.8f * Mth.sign(diff.toDouble()).toFloat()
			} else if (food.height <= 0f) {
				this.food = null
				snacks++
				floaters += Floater("nom!", x, topY() - 2, 0xFFFFD27F.toInt())
				setMode(Mode.HAPPY, 50)
			}
			return
		}

		val ball = ball
		if (ball != null && mode != Mode.HAPPY && mode != Mode.SCARED) {
			mode = Mode.CHASE
			val center = x + spriteW * pixel / 2
			val diff = ball.x - center
			facingRight = diff > 0
			if (abs(diff) > speed * 1.5f) {
				x += speed * 1.6f * Mth.sign(diff.toDouble()).toFloat()
			} else if (ball.height < 4f) {
				// Kick it the way we're facing
				ball.vx = (if (facingRight) 2.5f else -2.5f) + Random.nextFloat() - 0.5f
				ball.vy = 3f
				if (Random.nextInt(3) == 0) react(Reaction.HAPPY)
			}
			return
		}

		when (mode) {
			Mode.HAPPY -> {
				if (height <= 0f) vy = 2.6f // little hops
				applyGravity()
				if (age % 6 == 0) floaters += Floater("❤", x + spriteW * pixel / 2, topY(), 0xFFFF6B8A.toInt())
			}
			Mode.SCARED -> applyGravity()
			Mode.WALK -> {
				val diff = targetX - x
				if (abs(diff) <= speed) {
					setMode(Mode.IDLE, Random.nextInt(40, 140))
				} else {
					facingRight = diff > 0
					x += speed * Mth.sign(diff.toDouble()).toFloat()
				}
			}
			else -> {}
		}

		if (--modeTicks <= 0) {
			if (Random.nextInt(3) != 0 && maxX > minX + 4) {
				targetX = minX + Random.nextFloat() * (maxX - minX)
				setMode(Mode.WALK, 400)
			} else {
				if (Random.nextInt(4) == 0) facingRight = !facingRight
				setMode(Mode.IDLE, Random.nextInt(40, 140))
			}
		}
	}

	private fun applyGravity() {
		vy -= 0.5f
		height = (height + vy).coerceAtLeast(0f)
		if (height == 0f) vy = 0f
	}

	private fun tickBall(b: SidebarBounds.Box) {
		val ball = ball ?: return
		ball.vy -= 0.4f
		ball.height += ball.vy
		if (ball.height <= 0f) {
			ball.height = 0f
			ball.vy = if (abs(ball.vy) > 1f) -ball.vy * 0.45f else 0f
			ball.vx *= 0.85f
		}
		ball.x += ball.vx
		val minX = b.left + 2f
		val maxX = b.right - 2f
		if (ball.x < minX || ball.x > maxX) {
			ball.x = ball.x.coerceIn(minX, maxX)
			ball.vx = -ball.vx * 0.6f
		}
		if (--ball.life <= 0) this.ball = null
	}

	private fun tickFood() {
		val food = food ?: return
		if (food.height > 0f) {
			food.vy -= 0.35f
			food.height = (food.height + food.vy).coerceAtLeast(0f)
		}
		if (--food.life <= 0) this.food = null
	}

	/**
	 * Drops a snack onto the scoreboard; the pet runs over and eats it. Returns false when there is no
	 * scoreboard pet on screen right now (module off or no sidebar) or a snack is still on its way.
	 */
	val visible get() = ScoreboardPet.enabled && box != null

	fun feed(): Boolean {
		if (!ScoreboardPet.enabled) return false
		val b = box ?: return false
		val now = System.currentTimeMillis()
		if (food != null || now - lastFedAt < 800) return false
		lastFedAt = now
		// The pet's center can only reach half a sprite in from either edge, so keep the snack within that.
		val margin = spriteW * pixel / 2 + pixel
		val left = b.left + margin
		val right = (b.right - margin).coerceAtLeast(left)
		food = Food(left + Random.nextFloat() * (right - left), 40f + Random.nextFloat() * 10f)
		return true
	}

	private fun detectDeath() {
		val dead = mc.player?.isDeadOrDying == true
		if (dead && !wasDead) react(Reaction.SCARED)
		wasDead = dead
	}

	private fun setMode(newMode: Mode, ticks: Int) {
		mode = newMode
		modeTicks = ticks
	}

	/** Lets other features (PB, rare drop, stage up, death) make the pet react. */
	fun react(reaction: Reaction) {
		if (!ScoreboardPet.enabled || !ScoreboardPet.reactions.value) return
		if (mode == Mode.DRAGGED) return
		when (reaction) {
			Reaction.HAPPY -> setMode(Mode.HAPPY, 50)
			Reaction.SCARED -> {
				setMode(Mode.SCARED, 50)
				vy = 3.5f
				floaters += Floater("!", x + spriteW * pixel / 2, topY() - 4, 0xFFFF5555.toInt())
			}
		}
	}

	// ---------------------------------------------------------------- input (chat open)

	fun onMouseClick(mx: Double, my: Double, button: Int): Boolean {
		if (!ScoreboardPet.enabled || !ScoreboardPet.interactions.value) return false
		if (mc.gui.screen() !is ChatScreen) return false
		val b = box ?: return false
		val px = mx.toFloat()
		val py = my.toFloat()
		val drawX = x
		val drawY = topY()
		val onPet = px >= drawX && px <= drawX + spriteW * pixel && py >= drawY && py <= drawY + spriteH * pixel
		if (onPet && button == 0) {
			mode = Mode.DRAGGED
			dragOffsetX = px - drawX
			dragOffsetY = py - drawY
			dragMouseX = px
			dragMouseY = py
			return true
		}
		if (onPet && button == 1) {
			react(Reaction.HAPPY)
			floaters += Floater("nom!", drawX, drawY - 2, 0xFFFFD27F.toInt())
			return true
		}
		val inBox = px >= b.left && px <= b.right && py >= b.top - 20 && py <= b.bottom
		if (inBox && button == 0) {
			ball = Ball(px, (b.top - py).coerceAtLeast(6f))
			return true
		}
		return false
	}

	fun onMouseRelease(mx: Double, my: Double, button: Int): Boolean {
		if (mode != Mode.DRAGGED || button != 0) return false
		val b = box
		x = mx.toFloat() - dragOffsetX
		prevX = x
		height = if (b != null) (b.top - (my.toFloat() - dragOffsetY + spriteH * pixel)).coerceAtLeast(0f) else 0f
		prevHeight = height
		vy = 0f
		setMode(Mode.FALLING, 0)
		return true
	}

	// ---------------------------------------------------------------- drawing

	private fun topY(): Float {
		val b = box ?: return 0f
		return b.top - spriteH * pixel - height
	}

	fun render(g: GuiGraphicsExtractor, delta: DeltaTracker) {
		val currentBox = SidebarBounds.consume()
		box = currentBox
		if (!ScoreboardPet.enabled || currentBox == null) return
		if (x.isNaN()) return

		val sprites = PetSprites.adult
		val sprite = when {
			mode == Mode.SLEEP -> sprites.sleep
			mode == Mode.WALK || mode == Mode.CHASE -> sprites.walk[(age / 5) % sprites.walk.size]
			else -> sprites.idle
		}
		spriteW = sprite.width.toFloat()
		spriteH = sprite.height.toFloat()

		val partial = delta.getGameTimeDeltaPartialTick(true)
		var drawX: Float
		var drawY: Float
		if (mode == Mode.DRAGGED) {
			val window = mc.window
			dragMouseX = mc.mouseHandler.getScaledXPos(window).toFloat()
			dragMouseY = mc.mouseHandler.getScaledYPos(window).toFloat()
			drawX = dragMouseX - dragOffsetX
			drawY = dragMouseY - dragOffsetY
		} else {
			drawX = Mth.lerp(partial, prevX, x)
			val h = Mth.lerp(partial, prevHeight, height)
			drawY = currentBox.top - spriteH * pixel - h
		}

		drawSprite(g, sprite, drawX, drawY, pixel, facingRight)
		ball?.let { drawSprite(g, PetSprites.BALL, it.x - 2 * pixel, currentBox.top - 4 * pixel - it.height, pixel, true) }
		food?.let {
			val fh = if (it.height > 0f) (it.height - it.vy * (1f - partial)).coerceAtLeast(0f) else 0f
			drawSprite(g, PetSprites.FOOD, it.x - 2.5f * pixel, currentBox.top - 5 * pixel - fh, pixel, true)
		}

		val font = mc.font
		for (f in floaters) {
			val alpha = (255 * (1f - f.age / 30f)).toInt().coerceIn(0, 255)
			g.text(font, f.text, f.x.toInt(), f.y.toInt(), ColorUtil.withAlpha(f.color, alpha), true)
		}

		// Name tag while hovering with chat open
		if (mc.gui.screen() is ChatScreen) {
			val mx = mc.mouseHandler.getScaledXPos(mc.window)
			val my = mc.mouseHandler.getScaledYPos(mc.window)
			if (mx >= drawX && mx <= drawX + spriteW * pixel && my >= drawY && my <= drawY + spriteH * pixel) {
				val label = CustomPets.petName.value.ifBlank { "Pet" }
				val lx = (drawX + spriteW * pixel / 2 - font.width(label) / 2f).toInt()
				g.text(font, label, lx, (drawY - 10).toInt(), 0xFFFFFFFF.toInt(), true)
			}
		}
	}

	private fun drawSprite(g: GuiGraphicsExtractor, sprite: Sprite, x: Float, y: Float, scale: Float, right: Boolean) {
		val colors = palette()
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		pose.scale(scale, scale)
		for ((row, line) in sprite.rows.withIndex()) {
			for ((col, ch) in line.withIndex()) {
				val color = colors[ch] ?: continue
				val px = if (right) col else sprite.width - 1 - col
				g.fill(px, row, px + 1, row + 1, color)
			}
		}
		pose.popMatrix()
	}

	private fun palette(): Map<Char, Int> {
		val body = CustomPets.bodyColor.value or 0xFF000000.toInt()
		return mapOf(
			'K' to 0xFF1A1A22.toInt(),
			'B' to body,
			'S' to ColorUtil.shade(body, 0.78f),
			'A' to (CustomPets.accentColor.value or 0xFF000000.toInt()),
			'W' to 0xFFF3E9D2.toInt(),
			'E' to 0xFF1A1A22.toInt(),
			'M' to 0xFFC8743A.toInt(),
			'm' to 0xFF8E4A22.toInt(),
		)
	}
}
