package dev.skyparty.minigames.parkour

import org.slf4j.LoggerFactory

/**
 * Builds a course from a seed by chaining segment templates with a difficulty ramp.
 *
 * Every segment starts on the previous segment's 3-tile exit platform (which is also a checkpoint)
 * and ends on its own exit platform. Each candidate segment is verified with [ParkourSolver] from
 * rest on its start platform (= the respawn state), so every checkpoint is provably completable.
 * Unsolvable candidates are re-rolled (counted in [ParkourLevel.retries]).
 */
object ParkourGenerator {
	private val log = LoggerFactory.getLogger("Skyparty/Parkour")

	const val HEIGHT = 18
	const val SEGMENTS = 12
	const val PLATFORM = 3
	private const val MIN_TOP = 5
	private const val MAX_TOP = HEIGHT - 2
	private const val START_ROW = 12
	private const val MAX_TRIES = 24

	/** Cache: generation + verification takes a few ms per segment, the screen and solver reuse it. */
	private val cache = object : LinkedHashMap<Long, ParkourLevel>(8, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ParkourLevel>?) = size > 6
	}

	fun generate(seed: Long): ParkourLevel = synchronized(cache) { cache[seed] } ?: buildVerified(seed).also {
		synchronized(cache) { cache[seed] = it }
	}

	/**
	 * Builds the course and verifies the WHOLE thing (every checkpoint from rest + one uninterrupted run).
	 * Rare failures are re-rolled with a derived seed, so a given seed still always yields the same course.
	 */
	private fun buildVerified(seed: Long): ParkourLevel {
		var last: ParkourLevel? = null
		for (attempt in 0 until 8) {
			val level = build(if (attempt == 0) seed else seed * 31 + attempt * 7919L)
			val path = if (ParkourSolver.verifyCheckpoints(level)) ParkourSolver.solveCourse(level) else null
			if (path != null && ParkourSolver.replay(level, path) > 0) {
				level.retries += attempt
				return level
			}
			last = level
		}
		return last!!
	}

	private fun build(seed: Long): ParkourLevel {
		val rng = ParkourRng(ParkourRng(seed).nextLong())
		val level = ParkourLevel(SEGMENTS * 26 + 40, HEIGHT)
		// Start area: 6 columns of ground, the spawn checkpoint is on its last 3.
		var col = 0
		for (x in 0 until 6) fillGround(level, x, START_ROW)
		col = 6
		var exitRow = START_ROW
		level.checkpoints += ParkourLevel.Checkpoint(col - PLATFORM, exitRow)

		for (i in 0 until SEGMENTS) {
			val difficulty = 1 + (i * 4 + SEGMENTS / 2) / (SEGMENTS - 1)
			var placed = false
			var tries = 0
			while (!placed) {
				tries++
				val d = when {
					tries > MAX_TRIES -> 0
					tries > MAX_TRIES / 2 -> maxOf(1, difficulty - 1)
					else -> difficulty
				}
				val template = if (d == 0) FLAT else pickTemplate(rng, d)
				val b = Builder(level, col, exitRow)
				val ok = runCatching { template.build(b, rng, d) }.isSuccess && b.valid()
				if (ok) {
					b.platform(b.lastTop)
					val start = level.checkpoints.last()
					val path = ParkourSolver.search(level, start.spawnState(), b.col0 + b.x - PLATFORM, b.col0 + b.x - 1, human = true)
					if (path != null) {
						level.segments += ParkourLevel.SegmentInfo(template.name, col, col + b.x, d, tries)
						col += b.x
						exitRow = b.lastTop
						level.checkpoints += ParkourLevel.Checkpoint(col - PLATFORM, exitRow)
						placed = true
						continue
					}
				}
				b.clear()
				level.retries++
				if (tries > 1 && tries % 8 == 0) log.info("Parkour seed {} segment {}: {} re-rolls so far", seed, i, tries)
			}
		}

		// Finish stretch: 10 columns of ground, finish line in the middle, wall behind.
		for (x in col until col + 12) fillGround(level, x, exitRow)
		level.finishX = col + 5
		val last = ParkourLevel(col + 12, HEIGHT)
		for (x in 0 until col + 12) for (y in 0 until HEIGHT) last.set(x, y, level.tile(x, y))
		last.finishX = level.finishX
		last.checkpoints += level.checkpoints
		last.segments += level.segments
		last.retries = level.retries
		if (last.retries > 0) log.info("Parkour seed {}: generated with {} segment re-rolls", seed, last.retries)
		return last
	}

	private fun fillGround(level: ParkourLevel, x: Int, top: Int) {
		for (y in top until HEIGHT) level.set(x, y, ParkourLevel.SOLID)
	}

	private fun pickTemplate(rng: ParkourRng, d: Int): Template {
		val options = TEMPLATES.filter { d in it.minD..it.maxD }
		return rng.pick(options)
	}

	/** Cursor-based writer for one segment; all coordinates are local columns from [col0]. */
	class Builder(val level: ParkourLevel, val col0: Int, val entry: Int) {
		var x = 0
		var lastTop = entry
		private var bad = false

		fun clampTop(row: Int) = row.coerceIn(MIN_TOP, MAX_TOP)

		fun valid() = !bad && x in 4..30 && lastTop in MIN_TOP..MAX_TOP

		private fun put(cx: Int, y: Int, t: Byte) {
			if (col0 + cx >= level.width - 14) { bad = true; return }
			level.set(col0 + cx, y, t)
		}

		fun ground(n: Int, top: Int) {
			val t = clampTop(top)
			repeat(n) { for (y in t until HEIGHT) put(x, y, ParkourLevel.SOLID); x++ }
			lastTop = t
		}

		fun pit(n: Int) { x += n }

		fun spikes(n: Int, top: Int) {
			val t = clampTop(top)
			repeat(n) {
				for (y in t until HEIGHT) put(x, y, ParkourLevel.SOLID)
				put(x, t - 1, ParkourLevel.SPIKE)
				x++
			}
		}

		/** Floating platform (1 tile thick, void below). */
		fun floating(n: Int, row: Int) {
			val r = clampTop(row)
			repeat(n) { put(x, r, ParkourLevel.SOLID); x++ }
			lastTop = r
		}

		/** Solid block between local columns [cx0, cx1) and rows [y0, y1]. */
		fun block(cx0: Int, cx1: Int, y0: Int, y1: Int) {
			for (cx in cx0 until cx1) for (y in maxOf(0, y0)..y1) put(cx, y, ParkourLevel.SOLID)
		}

		fun spikeAt(cx: Int, row: Int) = put(cx, row, ParkourLevel.SPIKE)

		fun platform(top: Int) = ground(PLATFORM, top)

		fun clear() {
			for (cx in 0 until x + 48) for (y in 0 until HEIGHT) {
				if (col0 + cx < level.width) level.set(col0 + cx, y, ParkourLevel.EMPTY)
			}
		}
	}

	class Template(val name: String, val minD: Int, val maxD: Int, val build: Builder.(ParkourRng, Int) -> Unit)

	private val FLAT = Template("flat", 0, 0) { _, _ -> ground(3, entry) }

	/** ASCII template: '#' solid, 'G' ground (solid down to the bottom), '^' spike on ground, '.' air/pit. */
	private fun ascii(name: String, minD: Int, maxD: Int, vararg rows: String) = Template(name, minD, maxD) { _, _ ->
		val h = rows.size
		val w = rows[0].length
		val entryRowInTemplate = (0 until h).first { rows[it][0] == 'G' }
		val offset = entry - entryRowInTemplate
		for (cx in 0 until w) {
			var groundFrom = -1
			for (ty in 0 until h) {
				val y = ty + offset
				when (rows[ty][cx]) {
					'#' -> block(x + cx, x + cx + 1, y, y)
					'G' -> if (groundFrom < 0) groundFrom = y
					'^' -> { spikeAt(x + cx, y); if (groundFrom < 0) groundFrom = y + 1 }
				}
			}
			if (groundFrom >= 0) block(x + cx, x + cx + 1, groundFrom, HEIGHT - 1)
		}
		val exitRowInTemplate = (0 until h).firstOrNull { rows[it][w - 1] == 'G' } ?: error("no exit")
		x += w
		lastTop = exitRowInTemplate + offset
		if (lastTop !in MIN_TOP..MAX_TOP || offset + h - 1 >= HEIGHT || offset < 0) error("out of bounds")
	}

	private val TEMPLATES = listOf(
		Template("run", 1, 2) { r, _ ->
			ground(r.range(2, 3), entry)
			ground(r.range(2, 3), entry - r.range(0, 1))
			ground(r.range(2, 3), entry)
		},
		Template("gap", 1, 5) { r, d ->
			ground(2, entry)
			val gap = when (d) { 1 -> r.range(1, 2); 2 -> r.range(2, 3); 3 -> r.range(2, 4); else -> r.range(3, 4) }
			val dh = if (gap >= 4) r.range(0, 2) else r.range(-minOf(d, 2), 2)
			pit(gap)
			ground(r.range(1, 2), entry + dh)
		},
		Template("stairs", 1, 4) { r, d ->
			val up = if (entry - 4 < MIN_TOP) false else if (entry + 4 > MAX_TOP) true else r.chance(55)
			val n = r.range(2, 3 + d / 2)
			val stepH = if (d >= 3) r.range(1, 2) else 1
			var top = entry
			ground(1, top)
			repeat(n) {
				top = clampTop(if (up) top - stepH else top + stepH)
				ground(if (d >= 3) 1 else 2, top)
			}
		},
		Template("pillars", 2, 5) { r, d ->
			ground(1, entry)
			val k = r.range(2, 2 + d / 2)
			var top = entry
			repeat(k) {
				pit(r.range(1, minOf(3, d)))
				top = clampTop(top + r.range(-1, 1))
				ground(if (d >= 4) 1 else 2, top)
			}
			pit(r.range(1, 2))
		},
		Template("spikes", 1, 5) { r, d ->
			ground(2, entry)
			spikes(r.range(1 + d / 3, 2 + d / 2), entry)
			ground(r.range(2, 3), entry)
			if (d >= 3) {
				spikes(r.range(2, 2 + d / 3), entry)
				ground(1, entry)
			}
		},
		Template("tunnel", 3, 5) { r, d ->
			ground(2, entry)
			val len = r.range(7, 9)
			val start = x
			ground(len, entry)
			block(start, start + len, entry - 6, entry - 4)
			// Spikes inside the tunnel: you only have one tile of head room to hop over them.
			spikeAt(start + 2, entry - 1)
			spikeAt(start + len - 3, entry - 1)
			if (d >= 5) spikeAt(start + len - 2, entry - 1)
			ground(1, entry)
		},
		Template("climb", 2, 5) { r, d ->
			ground(1, entry)
			var row = entry
			val k = r.range(2, 2 + d / 2)
			repeat(k) {
				pit(r.range(1, if (d >= 4) 3 else 2))
				row = clampTop(row - r.range(1, 2))
				floating(r.range(2, 3), row)
			}
			pit(r.range(1, 2))
			ground(1, clampTop(row + r.range(0, 3)))
		},
		Template("drop", 2, 5) { r, d ->
			ground(2, entry)
			val low = clampTop(entry + r.range(2, 4))
			spikes(r.range(1, 2), low)
			ground(r.range(2, 3), low)
			spikes(r.range(1, 1 + d / 2), low)
			ground(1, low - r.range(0, 1))
		},
		ascii("castle", 2, 3,
			"......GGGG",
			"...GG.GGGG",
			"GG.GG.GGGG",
		),
		ascii("garden", 3, 4,
			"....##......##....",
			"..................",
			"..................",
			"GG^^^^GG^^^^GG^^GG",
		),
		ascii("lowroof", 4, 5,
			"##############",
			"..............",
			"..............",
			"..............",
			"GGGGG^GGGG^GGG",
		),
		ascii("tower", 4, 5,
			".........###....",
			"................",
			".....###........",
			"................",
			"GG..........^^GG",
		),
		ascii("zigzag", 3, 5,
			"......##.....",
			".............",
			"...##....##..",
			".............",
			"GG.........GG",
		),
	)
}
