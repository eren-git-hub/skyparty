package dev.skyparty.pet.scoreboard


/**
 * Hand-made pixel art, facing right. Characters map to colors at draw time so the sprite
 * follows the pet colors from the Custom Pets module:
 *  . transparent  K outline  B body  S body shade  A accent  W shell  E eye
 */
class Sprite(rows: List<String>) {
	val rows: List<String> = rows
	val width = rows.first().length
	val height = rows.size

	init {
		require(rows.all { it.length == width }) { "Sprite rows differ in length: ${rows.map { it.length }}" }
	}

	/** Same sprite with eyes closed, for sleeping. */
	fun sleeping() = Sprite(rows.map { it.replace('E', 'S') })
}

class StageSprites(val walk: List<Sprite>) {
	val idle get() = walk.first()
	val sleep by lazy { idle.sleeping() }
}

object PetSprites {
	private val JUVENILE_TOP = listOf(
		"...........A.A..",
		"..........KKKKK.",
		"AA........KBBBK.",
	)
	private val JUVENILE_MID = listOf(
		".KBKKKKKKKBBBBAA",
		"..KBBBBBBBBBBKK.",
		"...KBBBBBBBBBK..",
		"...KSSSSSSSSSK..",
	)
	private val LEGS_A = listOf("....KSK...KSK...", "....KK....KK....")
	private val LEGS_B = listOf(".....KSK.KSK....", ".....KK..KK.....")


	/** Rows 1-2 of the adult with the wing folded into the back (up / down flap frames). */
	private val WING_UP = listOf("....AAA...KKKKK.", "AA.AAAAA..KBBBK.")
	private val WING_DOWN = listOf("..........KKKKK.", "AA..AAAA..KBBBK.")

	private fun adult(wing: List<String>, legs: List<String>) =
		Sprite(listOf(JUVENILE_TOP[0]) + wing + "KAK..A.A..KBEBK." + JUVENILE_MID + legs)

	val adult = StageSprites(listOf(adult(WING_UP, LEGS_A), adult(WING_DOWN, LEGS_B)))

	// ---- other species (same color letters)

	private val AXO_TOP = listOf(
		"...........A.A..",
		"..........AKKKKK",
		"KK.......AKBBBBK",
		"KBK.......KBBEBK",
		".KBKKKKKKBBBBBBK",
		"..KBBBBBBBBSSSK.",
		"...KSSSSSSSSSK..",
	)
	val axolotl = StageSprites(listOf(
		Sprite(AXO_TOP + listOf("...KSK...KSK....", "...KK....KK.....")),
		Sprite(AXO_TOP + listOf("....KSK.KSK.....", "....KK..KK......")),
	))

	private val GHOST_TOP = listOf(
		"....KKKK....",
		"..KKBBBBKK..",
		".KBBBBBBBBK.",
		".KBBBBBBBBK.",
		"KBBEEBBEEBBK",
		"KBBEEBBEEBBK",
		"KBBBBBBBBBBK",
		"KBBBBKKBBBBK",
		"KSBBBBBBBBSK",
		"KSBBBBBBBBSK",
	)
	// Wavy hem: the three bumps shift between the frames.
	val ghost = StageSprites(listOf(
		Sprite(GHOST_TOP + listOf("KBBBKBBBKBBK", ".KKK.KKK.KK.")),
		Sprite(GHOST_TOP + listOf("KBBKBBBKBBBK", ".KK.KKK.KKK.")),
	))

	private val PENGUIN_TOP = listOf(
		"...KKKK...",
		"..KBBBBK..",
		"..KBWWEK..",
		"..KBWWWKAA",
		".KBBWWWK..",
		"KSBWWWWBK.",
		"KSBWWWWBK.",
		".KBWWWWBK.",
		".KBBWWBBK.",
		"..KKKKKK..",
	)
	val penguin = StageSprites(listOf(
		Sprite(PENGUIN_TOP + "..AA..AA.."),
		Sprite(PENGUIN_TOP + ".AA....AA."),
	))

	val ryu = StageSprites(listOf(
		Sprite(listOf(
			"...............W.W..",
			"..............KWKWK.",
			".......A.A...KBBBBK.",
			"......KKKKK.KBBBEBBK",
			"..A..KBBBBBKBBBBBBBA",
			".KAKKBSSSSBBBBSKKKK.",
			"KBBBBSK..KSSSSK.....",
			".KSSK.....KKKK......",
			"..KK................",
		)),
		Sprite(listOf(
			"...............W.W..",
			"..............KWKWK.",
			"........A.A..KBBBBK.",
			".......KKKKKKBBBEBBK",
			"..A...KBBBBBBBBBBBBA",
			".KAK.KBSSSSSBBSKKKK.",
			"KBBBKBSK..KSSSK.....",
			".KSSSK....KKK.......",
			"..KKK...............",
		)),
	))

	/** Sprites for [species]. */
	fun of(species: dev.skyparty.pet.PetSpecies): StageSprites = when (species) {
		dev.skyparty.pet.PetSpecies.DRAGON -> adult
		dev.skyparty.pet.PetSpecies.RYU -> ryu
		dev.skyparty.pet.PetSpecies.AXOLOTL -> axolotl
		dev.skyparty.pet.PetSpecies.GHOST -> ghost
		dev.skyparty.pet.PetSpecies.PENGUIN -> penguin
	}

	/** Food for the feed key: a little drumstick (5x5). */
	val FOOD = Sprite(listOf("...WW", "..MWW", ".MMM.", "MMMm.", ".mm.."))

	/** Small 4x4 ball for the throw interaction. */
	val BALL = Sprite(listOf(".AA.", "AWAA", "AAAA", ".AA."))
}
