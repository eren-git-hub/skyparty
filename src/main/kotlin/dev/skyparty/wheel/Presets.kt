package dev.skyparty.wheel

/** Ready-made wheels for common SkyBlock party decisions. */
object Presets {
	class Preset(val button: String, val title: String, val entries: List<String>)

	val all = listOf(
		Preset(
			"Floor", "Which floor?",
			(1..7).map { "F$it" } + (1..7).map { "M$it" },
		),
		Preset(
			"Slayer", "Which slayer?",
			listOf("Revenant", "Tarantula", "Sven", "Voidgloom", "Inferno", "Riftstalker"),
		),
		Preset(
			"Grind", "What to grind?",
			listOf("Dungeons", "Slayers", "Kuudra", "Mining", "Farming", "Fishing", "Foraging", "Diana", "Dragons", "Crimson Isle"),
		),
	)
}
