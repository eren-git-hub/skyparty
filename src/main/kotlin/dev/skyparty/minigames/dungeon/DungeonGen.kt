package dev.skyparty.minigames.dungeon

import dev.skyparty.minigames.parkour.ParkourLevel
import dev.skyparty.minigames.parkour.ParkourPhysics.TILE
import dev.skyparty.minigames.parkour.ParkourRng

/**
 * Builds a side-scrolling dungeon from a seed: stone ceiling, corridors with pits, steps, floating platforms,
 * spike patches, bat caves and brute halls, a checkpoint torch at the start of every section, power-ups,
 * a miniboss room halfway (its gate opens when the miniboss dies) and a closed boss arena at the end.
 * Every jump stays well inside the parkour physics (gaps <= 3 tiles, steps <= 2 tiles up), so the level is
 * always beatable without a solver. The same seed gives the same dungeon on every client.
 */
object DungeonGen {
	const val HEIGHT = 12
	const val BASE = 9          // normal floor row (top solid tile)
	const val CEILING = 2       // rows 0..1 are the ceiling
	const val ARENA_W = 28
	const val MINI_W = 22

	/** [hp] base health, [big] size factor (1 = player size), [touch] contact damage in half hearts. */
	enum class Mob(val hp: Int, val big: Float, val touch: Int) {
		ZOMBIE(6, 1f, 2), SKELETON(4, 1f, 0), BRUTE(16, 1.25f, 4), BAT(2, 0.6f, 2), MINIBOSS(45, 1.5f, 3), BOSS(100, 2f, 4)
	}

	enum class Power { STRENGTH, HEAL }

	/** A monster to spawn: tile column, floor row and the columns it may walk (or fly) between. */
	class Spawn(val mob: Mob, val col: Int, val floor: Int, val minCol: Int, val maxCol: Int)

	/** A floating pick-up; [x], [y] are its center in world pixels. Everyone can take each one once. */
	class PowerUp(val id: Int, val kind: Power, val x: Float, val y: Float)

	class Dungeon(
		val level: ParkourLevel,
		val spawns: List<Spawn>,
		val powerUps: List<PowerUp>,
		val miniStart: Int,
		val gateCol: Int,
		val arenaStart: Int,
		val arenaEnd: Int,
	)

	fun generate(seed: Long, sections: Int = 14): Dungeon {
		val rng = ParkourRng(seed)
		val surface = ArrayList<Int>() // per column: top solid row, HEIGHT = pit
		val spikes = mutableListOf<Int>()
		val checkpoints = mutableListOf<Int>()
		val plan = mutableListOf<Pair<Mob, Int>>()
		val powers = mutableListOf<Pair<Power, Int>>()
		fun col() = surface.size
		fun floor(n: Int, row: Int) = repeat(n) { surface += row }
		fun pit(n: Int) = repeat(n) { surface += HEIGHT }

		floor(6, BASE)
		checkpoints += 1
		var miniStart = 0
		var gateCol = 0
		val row = BASE
		for (s in 0 until sections) {
			if (s == sections / 2) {
				// Miniboss room: flat and closed by a gate until the miniboss falls.
				floor(3, row)
				checkpoints += col() - 2
				miniStart = col()
				floor(MINI_W, row)
				gateCol = col()
				floor(3, row)
			}
			checkpoints += col() + 1
			val hard = s >= sections / 2
			if (rng.chance(40)) powers += (if (rng.chance(60)) Power.STRENGTH else Power.HEAL) to col() + 2
			when (rng.range(0, 7)) {
				0 -> { // corridor with ghouls
					floor(4, row)
					val st = col()
					floor(18, row)
					plan += Mob.ZOMBIE to st + 5; plan += Mob.ZOMBIE to st + 11
					if (hard) plan += Mob.ZOMBIE to st + 16
				}
				1 -> { // pit with archers on the far side
					floor(6, row)
					pit(if (hard) 3 else 2)
					val st = col()
					floor(14, row)
					plan += Mob.SKELETON to st + 9
					if (hard || rng.chance(50)) plan += Mob.ZOMBIE to st + 3
				}
				2 -> { // steps up, then down again
					floor(4, row)
					val up = rng.range(1, 2)
					floor(5, row - up)
					val top = row - up - rng.range(0, 1)
					val st = col()
					floor(7, top)
					plan += (if (hard) Mob.SKELETON else Mob.ZOMBIE) to st + 4
					floor(6, row)
					plan += Mob.ZOMBIE to col() - 3
				}
				3 -> { // floating platforms over a pit, bats above
					floor(4, row)
					val bat = col() + 4
					pit(2); floor(2, row - 1); pit(2); floor(2, row - 1); pit(2)
					val st = col()
					floor(9, row)
					plan += Mob.BAT to bat
					plan += (if (rng.chance(50)) Mob.SKELETON else Mob.ZOMBIE) to st + 5
				}
				4 -> { // spike patches you have to hop over
					floor(4, row)
					repeat(3) { floor(3, row); spikes += col(); floor(1, row) }
					val st = col()
					floor(8, row)
					plan += Mob.ZOMBIE to st + 3
					if (hard) plan += Mob.SKELETON to st + 7
				}
				5 -> { // brute hall
					floor(4, row)
					val st = col()
					floor(18, row)
					plan += Mob.ZOMBIE to st + 4; plan += Mob.BRUTE to st + 11
					if (hard) plan += Mob.ZOMBIE to st + 15
				}
				6 -> { // bat cave
					floor(4, row)
					val st = col()
					floor(17, row)
					plan += Mob.BAT to st + 4; plan += Mob.BAT to st + 10
					if (hard) { plan += Mob.BAT to st + 14; plan += Mob.ZOMBIE to st + 8 }
				}
				else -> { // archer ledge
					floor(5, row)
					floor(2, row - 1)
					val st = col()
					floor(6, row - 2)
					plan += Mob.SKELETON to st + 3
					floor(9, row)
					plan += Mob.ZOMBIE to col() - 4
					if (hard) plan += Mob.SKELETON to col() - 1
				}
			}
		}
		// Boss arena: flat, closed on the right.
		floor(3, row)
		checkpoints += col() - 2
		val arenaStart = col()
		floor(ARENA_W, row)
		val arenaEnd = col()
		repeat(3) { surface += 0 } // the right wall

		val width = surface.size
		val level = ParkourLevel(width, HEIGHT)
		for (x in 0 until width) {
			for (y in 0 until CEILING) level.set(x, y, ParkourLevel.SOLID)
			for (y in surface[x] until HEIGHT) level.set(x, y, ParkourLevel.SOLID)
		}
		for (x in spikes) if (surface[x] < HEIGHT) level.set(x, surface[x] - 1, ParkourLevel.SPIKE)
		for (y in CEILING until BASE) level.set(gateCol, y, ParkourLevel.SOLID)
		for (c in checkpoints) level.checkpoints += ParkourLevel.Checkpoint(c, surface[c])
		level.finishX = width + 1000 // the dungeon ends with the boss, not a finish line

		// Ground monsters walk along their flat stretch (stops at pits, steps, walls and spikes); bats fly a bit.
		fun walkable(x: Int, r: Int) = x in 0 until width && surface[x] == r && level.tile(x, r - 1) != ParkourLevel.SPIKE && x != gateCol
		val spawns = plan.map { (mob, c) ->
			val r = surface[c].coerceAtMost(BASE)
			if (mob == Mob.BAT) Spawn(mob, c, r, c - 5, c + 5)
			else {
				var lo = c; while (walkable(lo - 1, r)) lo--
				var hi = c; while (walkable(hi + 1, r)) hi++
				Spawn(mob, c, r, lo, hi)
			}
		} + Spawn(Mob.MINIBOSS, miniStart + MINI_W - 5, BASE, miniStart, gateCol - 1) +
			Spawn(Mob.BOSS, arenaStart + ARENA_W - 6, BASE, arenaStart, arenaEnd - 1)
		val powerUps = powers.mapIndexed { i, (kind, c) -> PowerUp(i, kind, c * TILE + 8f, surface[c] * TILE - 22f) }
		return Dungeon(level, spawns, powerUps, miniStart, gateCol, arenaStart, arenaEnd)
	}
}
