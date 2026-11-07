package dev.skyparty.minigames.parkour

/**
 * Tile grid of a generated race course. Column 0 is the left wall; rows grow downwards and
 * everything below the last row is void. Out-of-range columns are solid walls.
 */
class ParkourLevel(val width: Int, val height: Int) {
	private val tiles = ByteArray(width * height)

	/** Tile column of the finish line. */
	var finishX = width + 100

	/** Respawn platforms in course order (the first one is the spawn). */
	val checkpoints = mutableListOf<Checkpoint>()

	/** Generated segments (for logs / debug overlays). */
	val segments = mutableListOf<SegmentInfo>()

	/** Total solver retries needed while generating (re-rolled segments). */
	var retries = 0

	/** Steps of the verified solver path (lower bound-ish for a good time), 0 if unknown. */
	var solverSteps = 0

	data class Checkpoint(val col: Int, val floorRow: Int) {
		/** Rest state on this platform. */
		fun spawnState() = ParkourPhysics.State(
			x = col * ParkourPhysics.TILE_FP + 4 * ParkourPhysics.FP,
			y = floorRow * ParkourPhysics.TILE_FP - ParkourPhysics.H_FP,
			grounded = true,
		)
	}

	data class SegmentInfo(val name: String, val startCol: Int, val endCol: Int, val difficulty: Int, val tries: Int)

	fun tile(x: Int, y: Int): Byte {
		if (y < 0 || y >= height) return EMPTY
		if (x < 0 || x >= width) return SOLID
		return tiles[y * width + x]
	}

	/** Solid for collision: ground blocks and jump pads. */
	fun solid(x: Int, y: Int) = tile(x, y).let { it == SOLID || it == PAD }

	fun set(x: Int, y: Int, t: Byte) {
		if (x in 0 until width && y in 0 until height) tiles[y * width + x] = t
	}

	/** Top-most solid row of a column, or [height] if the column is a pit. */
	fun surface(x: Int): Int {
		for (y in 0 until height) if (solid(x, y)) return y
		return height
	}

	fun copyOfRange(cols: Int): ParkourLevel {
		val l = ParkourLevel(cols, height)
		for (x in 0 until cols) for (y in 0 until height) l.set(x, y, tile(x, y))
		l.finishX = finishX
		return l
	}

	companion object {
		const val EMPTY: Byte = 0
		const val SOLID: Byte = 1
		const val SPIKE: Byte = 2
		/** Jump pad: solid, and landing on it launches you about twice as high as a jump. */
		const val PAD: Byte = 3
	}
}

/** SplitMix64: tiny, fast and specified bit-for-bit (unlike platform RNGs we don't control). */
class ParkourRng(seed: Long) {
	private var state = seed

	fun nextLong(): Long {
		state += -0x61c8864680b583ebL
		var z = state
		z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
		z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
		return z xor (z ushr 31)
	}

	/** Uniform int in [a, b] (inclusive). */
	fun range(a: Int, b: Int): Int {
		if (b <= a) return a
		return a + java.lang.Long.remainderUnsigned(nextLong(), (b - a + 1).toLong()).toInt()
	}

	fun chance(percent: Int) = range(0, 99) < percent

	fun <T> pick(list: List<T>): T = list[range(0, list.size - 1)]
}
