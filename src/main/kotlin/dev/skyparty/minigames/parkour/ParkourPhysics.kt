package dev.skyparty.minigames.parkour

/**
 * Deterministic platformer physics. All positions/velocities are integers in 1/256 world pixels
 * ([FP]) and the simulation runs at a fixed [STEPS_PER_SECOND], so every client (and the solver)
 * produces bit-identical results for the same level and input sequence.
 *
 * World: y grows downwards, one tile is [TILE] pixels. The player's (x, y) is the top-left corner
 * of its hitbox ([W] x [H] pixels).
 */
object ParkourPhysics {
	const val STEPS_PER_SECOND = 60
	const val FP = 256
	const val TILE = 16
	const val TILE_FP = TILE * FP

	const val W = 8
	const val H = 32
	const val W_FP = W * FP
	const val H_FP = H * FP

	const val MAX_RUN = 512          // 2.0 px/step = 7.5 tiles/s
	const val ACCEL_GROUND = 77
	const val DECEL_GROUND = 102
	const val ACCEL_AIR = 51
	const val DECEL_AIR = 13
	const val GRAVITY = 64           // 0.25 px/step²
	const val MAX_FALL = 1536        // 6 px/step
	const val JUMP_V = -1331         // ~3.4 tiles jump height
	const val JUMP_CUT = 384         // releasing jump while rising caps upward speed
	const val PAD_V = -1900           // jump pad launch, ~6.9 tiles high
	const val COYOTE = 6             // steps you may still jump after leaving a ledge
	const val BUFFER = 6             // steps a jump press is remembered before landing
	/** Extra spike reach (2 px) in the generator's human check. */
	const val HUMAN_PAD = 2 * FP

	/** One step of player input. */
	data class Input(val dir: Int, val jump: Boolean) {
		companion object {
			val NONE = Input(0, false)
			/** The six inputs the solver searches over. */
			val ALL = listOf(-1, 0, 1).flatMap { d -> listOf(Input(d, false), Input(d, true)) }
		}
	}

	enum class Outcome { NONE, DIED, FINISHED }

	/** Physics state (value type; [copy] for search). */
	data class State(
		var x: Int,
		var y: Int,
		var vx: Int = 0,
		var vy: Int = 0,
		var grounded: Boolean = false,
		var coyote: Int = 0,
		var buffer: Int = 0,
		var jumpHeld: Boolean = false,
		var facing: Int = 1,
		/** Rising from a jump pad (jump release must not cut the launch). */
		var padBoost: Boolean = false,
	) {
		fun copyState() = State(x, y, vx, vy, grounded, coyote, buffer, jumpHeld, facing, padBoost)
	}

	/** Advances [s] in place by one step. Returns whether the player died or crossed the finish line. */
	/**
	 * [margin] = the generator's "human" check: 10% slower running and a ~15% lower jump. A course that is
	 * solvable like that leaves real players room for sloppy timing with the full-strength physics.
	 */
	fun step(level: ParkourLevel, s: State, input: Input, margin: Boolean = false): Outcome {
		val maxRun = if (margin) MAX_RUN * 9 / 10 else MAX_RUN
		val jumpV = if (margin) JUMP_V * 92 / 100 else JUMP_V
		// Jump buffering (press edge only)
		val pressed = input.jump && !s.jumpHeld
		if (pressed) s.buffer = BUFFER else if (s.buffer > 0) s.buffer--

		// Horizontal acceleration
		val target = input.dir * maxRun
		val accel = when {
			input.dir == 0 -> if (s.grounded) DECEL_GROUND else DECEL_AIR
			s.grounded -> if (s.vx != 0 && sign(s.vx) != input.dir) DECEL_GROUND + ACCEL_GROUND / 2 else ACCEL_GROUND
			else -> ACCEL_AIR
		}
		s.vx = approach(s.vx, target, accel)
		if (input.dir != 0) s.facing = input.dir

		// Coyote time + jump
		if (s.grounded) s.coyote = COYOTE else if (s.coyote > 0) s.coyote--
		if (s.buffer > 0 && s.coyote > 0) {
			s.vy = jumpV
			s.buffer = 0
			s.coyote = 0
			s.grounded = false
		}
		if (!input.jump && !s.padBoost && s.vy < -JUMP_CUT) s.vy = -JUMP_CUT
		s.jumpHeld = input.jump

		s.vy = minOf(s.vy + GRAVITY, MAX_FALL)

		// Move X, resolve against solid tiles
		if (s.vx != 0) {
			val nx = s.x + s.vx
			if (collides(level, nx, s.y)) {
				s.x = if (s.vx > 0) floorDiv(nx + W_FP - 1, TILE_FP) * TILE_FP - W_FP
				else (floorDiv(nx, TILE_FP) + 1) * TILE_FP
				s.vx = 0
			} else s.x = nx
		}
		s.x = s.x.coerceAtLeast(0)

		// Move Y
		s.grounded = false
		val ny = s.y + s.vy
		if (collides(level, s.x, ny)) {
			if (s.vy > 0) {
				s.y = floorDiv(ny + H_FP - 1, TILE_FP) * TILE_FP - H_FP
				s.grounded = true
			} else {
				s.y = (floorDiv(ny, TILE_FP) + 1) * TILE_FP
			}
			s.vy = 0
		} else s.y = ny

		// Jump pads: standing on one launches you (holding jump does not matter, releasing does not cut it).
		if (s.grounded && onPad(level, s.x, s.y)) {
			s.vy = if (margin) PAD_V * 92 / 100 else PAD_V
			s.grounded = false
			s.coyote = 0
			s.buffer = 0
			s.padBoost = true
		}
		if (s.vy >= 0) s.padBoost = false

		if (s.y > level.height * TILE_FP) return Outcome.DIED
		if (touchesHazard(level, s.x, s.y, if (margin) HUMAN_PAD else 0)) return Outcome.DIED
		if (s.x + W_FP >= level.finishX * TILE_FP) return Outcome.FINISHED
		return Outcome.NONE
	}

	/** Whether any tile right under the feet is a jump pad. */
	fun onPad(level: ParkourLevel, x: Int, y: Int): Boolean {
		val row = floorDiv(y + H_FP, TILE_FP)
		for (tx in floorDiv(x, TILE_FP)..floorDiv(x + W_FP - 1, TILE_FP)) if (level.tile(tx, row) == ParkourLevel.PAD) return true
		return false
	}

	fun collides(level: ParkourLevel, x: Int, y: Int): Boolean {
		val tx0 = floorDiv(x, TILE_FP)
		val tx1 = floorDiv(x + W_FP - 1, TILE_FP)
		val ty0 = floorDiv(y, TILE_FP)
		val ty1 = floorDiv(y + H_FP - 1, TILE_FP)
		for (tx in tx0..tx1) for (ty in ty0..ty1) if (level.solid(tx, ty)) return true
		return false
	}

	/**
	 * Spike hitbox: lower 10 px of the tile, inset 3 px horizontally (forgiving). [pad] grows it on every side;
	 * the generator's human check uses [HUMAN_PAD], so no course needs sub-pixel clearance over spikes.
	 */
	fun touchesHazard(level: ParkourLevel, x: Int, y: Int, pad: Int = 0): Boolean {
		val tx0 = floorDiv(x, TILE_FP)
		val tx1 = floorDiv(x + W_FP - 1 + pad, TILE_FP)
		val ty0 = floorDiv(y, TILE_FP)
		val ty1 = floorDiv(y + H_FP - 1 + pad, TILE_FP)
		for (tx in floorDiv(x - pad, TILE_FP)..tx1) for (ty in ty0..ty1) {
			if (level.tile(tx, ty) != ParkourLevel.SPIKE) continue
			val hx0 = tx * TILE_FP + 3 * FP - pad
			val hx1 = (tx + 1) * TILE_FP - 3 * FP + pad
			val hy0 = ty * TILE_FP + 6 * FP - pad
			val hy1 = (ty + 1) * TILE_FP
			if (x < hx1 && x + W_FP > hx0 && y < hy1 && y + H_FP > hy0) return true
		}
		return false
	}

	private fun approach(v: Int, target: Int, delta: Int) =
		if (v < target) minOf(v + delta, target) else maxOf(v - delta, target)

	private fun sign(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0

	fun floorDiv(a: Int, b: Int) = Math.floorDiv(a, b)
}
