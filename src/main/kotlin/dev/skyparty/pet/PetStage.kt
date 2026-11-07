package dev.skyparty.pet

/** Growth stages; [hours] is the active SkyBlock playtime needed to reach the stage. */
enum class PetStage(val displayName: String, val hours: Double) {
	EGG("Egg", 0.0),
	HATCHLING("Hatchling", 5.0),
	JUVENILE("Juvenile", 25.0),
	ADULT("Adult", 100.0);

	val millis: Long get() = (hours * 3_600_000).toLong()

	val next: PetStage? get() = entries.getOrNull(ordinal + 1)

	companion object {
		fun forPlaytime(ms: Long): PetStage = entries.last { ms >= it.millis }

		/** 0..1 progress from this stage to the next (1 for the final stage). */
		fun progress(ms: Long): Double {
			val stage = forPlaytime(ms)
			val next = stage.next ?: return 1.0
			return (ms - stage.millis).toDouble() / (next.millis - stage.millis)
		}
	}
}
