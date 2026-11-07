package dev.skyparty.pet.scoreboard

import dev.skyparty.pet.PetStage

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
	private val EGG = Sprite(
		listOf(
			"..KKKK..",
			".KWWWWK.",
			"KWWBWWWK",
			"KWWWWWBK",
			"KWBWWWWK",
			"KWWWWBWK",
			"KWWWWWWK",
			".KWWWWK.",
			"..KKKK..",
		)
	)

	private val HATCHLING_BODY = listOf(
		"..W.W.W..",
		".KWWWWWK.",
		"KBBBBBBBK",
		"KBBBBBEBK",
		"KBBBBBBAA",
		"KBSSBBBK.",
		"KBSSSBBK.",
		".KBBBBK..",
		"..KKKK...",
	)

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

	private fun juvenile(legs: List<String>) =
		Sprite(JUVENILE_TOP + "KAK.......KBEBK." + JUVENILE_MID + legs)

	/** Rows 1-2 of the adult with the wing folded into the back (up / down flap frames). */
	private val WING_UP = listOf("....AAA...KKKKK.", "AA.AAAAA..KBBBK.")
	private val WING_DOWN = listOf("..........KKKKK.", "AA..AAAA..KBBBK.")

	private fun adult(wing: List<String>, legs: List<String>) =
		Sprite(listOf(JUVENILE_TOP[0]) + wing + "KAK..A.A..KBEBK." + JUVENILE_MID + legs)

	val sprites: Map<PetStage, StageSprites> = mapOf(
		PetStage.EGG to StageSprites(listOf(EGG)),
		PetStage.HATCHLING to StageSprites(
			listOf(
				Sprite(HATCHLING_BODY + "..A..A..."),
				Sprite(HATCHLING_BODY + "...A.A..."),
			)
		),
		PetStage.JUVENILE to StageSprites(listOf(juvenile(LEGS_A), juvenile(LEGS_B))),
		PetStage.ADULT to StageSprites(listOf(adult(WING_UP, LEGS_A), adult(WING_DOWN, LEGS_B))),
	)

	/** Small 4x4 ball for the throw interaction. */
	val BALL = Sprite(listOf(".AA.", "AWAA", "AAAA", ".AA."))
}
