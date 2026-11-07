package dev.skyparty.minigames.parkour

import dev.skyparty.minigames.parkour.ParkourPhysics.Input
import dev.skyparty.minigames.parkour.ParkourPhysics.MAX_RUN
import dev.skyparty.minigames.parkour.ParkourPhysics.Outcome
import dev.skyparty.minigames.parkour.ParkourPhysics.State
import dev.skyparty.minigames.parkour.ParkourPhysics.TILE_FP
import dev.skyparty.minigames.parkour.ParkourPhysics.W_FP
import java.util.PriorityQueue

/**
 * Proves levels completable by searching the input space with the real [ParkourPhysics].
 *
 * Weighted A* over macro-actions (one of the six inputs held for [MACRO] steps). Visited states are
 * deduplicated on a coarse grid (2 px position, 1/4 px/step velocity, plus timer flags); the grid only
 * prunes the search, every returned path is an exact simulation, so a "solvable" verdict is never wrong.
 */
object ParkourSolver {
	const val MACRO = 3
	const val HUMAN_MACRO = 6
	private const val MAX_NODES = 120_000

	private class Node(val state: State, val parent: Int, val input: Input, val g: Int, val f: Int)

	/**
	 * Finds inputs (one per step) that bring [start] to standing on columns [goalCol0]..[goalCol1]
	 * (or across the finish line). Returns null if no path was found within the node budget.
	 */
	fun search(level: ParkourLevel, start: State, goalCol0: Int, goalCol1: Int, fine: Boolean = false, human: Boolean = false): List<Input>? {
		// Human mode: inputs only change every 6 steps (100 ms, no frame-perfect taps) with the weaker margin physics.
		val macro = if (human) HUMAN_MACRO else if (fine) 2 else MACRO
		val maxNodes = if (fine) 400_000 else MAX_NODES
		val shift = if (fine) 1 else 0
		val goalX0 = goalCol0 * TILE_FP
		val goalX1 = (goalCol1 + 1) * TILE_FP
		val nodes = ArrayList<Node>()
		val open = PriorityQueue<Int>(compareBy({ nodes[it].f }, { -nodes[it].state.x }))
		val seen = HashSet<Long>()

		fun h(s: State) = maxOf(0, goalX0 - s.x) / MAX_RUN
		nodes += Node(start.copyState(), -1, Input.NONE, 0, h(start) * 2)
		open += 0
		seen += key(start, shift)

		while (open.isNotEmpty()) {
			val idx = open.poll()
			val node = nodes[idx]
			for (input in Input.ALL) {
				val s = node.state.copyState()
				var outcome = Outcome.NONE
				var steps = 0
				var reached = false
				while (steps < macro) {
					outcome = ParkourPhysics.step(level, s, input, human)
					steps++
					if (outcome != Outcome.NONE) break
					if (s.grounded && s.x >= goalX0 && s.x + W_FP <= goalX1) { reached = true; break }
				}
				if (outcome == Outcome.DIED) continue
				if (outcome == Outcome.FINISHED || reached) {
					val path = ArrayList<Input>()
					repeat(steps) { path += input }
					var i = idx
					while (nodes[i].parent >= 0) {
						repeat(macro) { path += nodes[i].input }
						i = nodes[i].parent
					}
					path.reverse()
					return path
				}
				if (!seen.add(key(s, shift))) continue
				if (nodes.size >= maxNodes) return null
				val g = node.g + macro
				nodes += Node(s, idx, input, g, g + h(s) * 2)
				open += nodes.size - 1
			}
		}
		return null
	}

	private fun key(s: State, fine: Int): Long {
		var k = (s.x shr (9 - fine)).toLong() and 0xFFFF
		k = k shl 10 or ((s.y shr (9 - fine)).toLong() and 0x3FF)
		k = k shl 7 or (((s.vx shr (6 - fine)) + 32).toLong() and 0x7F)
		k = k shl 7 or (((s.vy shr (7 - fine)) + 32).toLong() and 0x7F)
		k = k shl 1 or (if (s.grounded) 1L else 0L)
		k = k shl 1 or (if (s.jumpHeld) 1L else 0L)
		k = k shl 1 or (if (s.coyote > 0) 1L else 0L)
		k = k shl 1 or (if (s.buffer > 0) 1L else 0L)
		return k
	}

	/** Replays [inputs] from the level spawn; returns the step count when the finish is crossed, else -1. */
	fun replay(level: ParkourLevel, inputs: List<Input>): Int {
		val s = level.checkpoints.first().spawnState()
		for ((i, input) in inputs.withIndex()) {
			when (ParkourPhysics.step(level, s, input)) {
				Outcome.FINISHED -> return i + 1
				Outcome.DIED -> return -1
				Outcome.NONE -> {}
			}
		}
		return -1
	}

	/**
	 * Full-course path from the spawn: segment by segment from the exact state the previous part ended in.
	 * Used for the gametest playback and as the "par" time.
	 */
	fun solveCourse(level: ParkourLevel): List<Input>? {
		val path = ArrayList<Input>()
		val s = level.checkpoints.first().spawnState()
		val goals = level.checkpoints.drop(1).map { it.col to it.col + 2 } + listOf(level.finishX to level.finishX + 3)
		for ((i, goal) in goals.withIndex()) {
			val (g0, g1) = goal
			var part = search(level, s, g0, g1)
			if (part == null) {
				// Arrived with awkward momentum: settle on the checkpoint platform (flat and safe) and retry.
				val settle = settle(level, s, level.checkpoints[i].spawnState().x)
				path += settle
				part = search(level, s, g0, g1) ?: search(level, s, g0, g1, fine = true) ?: return null
			}
			for (input in part) ParkourPhysics.step(level, s, input)
			path += part
		}
		return path
	}

	/** Walks [s] (standing on a checkpoint platform) to roughly [targetX] and stops; returns the inputs used. */
	private fun settle(level: ParkourLevel, s: State, targetX: Int): List<Input> {
		val inputs = ArrayList<Input>()
		var guard = 0
		while (guard++ < 240) {
			val dx = targetX - s.x
			val input = when {
				!s.grounded -> Input.NONE
				dx > 6 * 256 && s.vx <= 256 -> Input(1, false)
				dx < -6 * 256 && s.vx >= -256 -> Input(-1, false)
				else -> Input.NONE
			}
			if (s.grounded && s.vx == 0 && kotlin.math.abs(dx) <= 6 * 256) break
			ParkourPhysics.step(level, s, input)
			inputs += input
		}
		return inputs
	}

	/** Re-verifies the finished level: from rest on every checkpoint, the next checkpoint (or finish) is reachable. */
	fun verifyCheckpoints(level: ParkourLevel, human: Boolean = true): Boolean {
		val cps = level.checkpoints
		for ((i, cp) in cps.withIndex()) {
			val (g0, g1) = if (i + 1 < cps.size) cps[i + 1].col to cps[i + 1].col + 2 else level.finishX to level.finishX + 3
			if (search(level, cp.spawnState(), g0, g1, human = human) == null) return false
		}
		return true
	}

	data class CheckReport(val seeds: Int, val solvable: Int, val totalRetries: Int, val millis: Long, val failures: List<Long>)

	/** Generates and independently re-verifies [count] seeds starting at [firstSeed]. */
	fun checkSeeds(firstSeed: Long, count: Int, log: (String) -> Unit): CheckReport {
		val t0 = System.currentTimeMillis()
		var ok = 0
		var retries = 0
		val failures = ArrayList<Long>()
		for (i in 0 until count) {
			val seed = firstSeed + i
			val level = ParkourGenerator.generate(seed)
			// 1) every checkpoint (respawn state) can reach the next one, 2) one uninterrupted run exists.
			val fromRest = verifyCheckpoints(level)
			val path = solveCourse(level)
			val steps = path?.let { replay(level, it) } ?: -1
			retries += level.retries
			val solvable = fromRest && steps > 0
			if (solvable) ok++ else failures += seed
			log("seed $seed: ${if (solvable) "SOLVABLE" else "UNSOLVED (checkpoints=$fromRest run=${steps > 0})"} width=${level.finishX} segments=${level.segments.size} " +
				"retries=${level.retries} par=${"%.2f".format(steps / ParkourPhysics.STEPS_PER_SECOND.toDouble())}s " +
				"[${level.segments.joinToString(",") { it.name + it.difficulty }}]")
		}
		return CheckReport(count, ok, retries, System.currentTimeMillis() - t0, failures)
	}
}
