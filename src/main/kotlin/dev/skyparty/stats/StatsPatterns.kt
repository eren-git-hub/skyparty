package dev.skyparty.stats

/**
 * Every Hypixel text pattern the stats tracker relies on, in one place.
 * Input is always formatting-stripped text (see Chat.strip / Location.sidebarLines).
 *
 * Verification status (keep this up to date when checked on a live server):
 * - VERIFIED (used elsewhere in the mod): DUNGEON_END ("☠ Defeated … in …"), OWN_DEATH ("☠ You …"),
 *   dungeon floor from the sidebar ("The Catacombs (M7)").
 * - NEEDS VERIFICATION on Hypixel: AREA (⏣ / ф prefix and the filler emoji Hypixel appends), PURSE (incl. "Piggy"
 *   and the "(+5)" suffix), SKILL_XP (both "(1,234/5,000)" and "(45.3%)" progress forms), DROP (all rarity
 *   prefixes, items wrapped in parentheses, Magic Find suffix), SLAYER_COMPLETE, SLAYER_BOSS (sidebar boss line
 *   during a quest), DUNGEON_END on a failed run.
 */
object StatsPatterns {
	/** Sidebar area line, e.g. "⏣ Dwarven Mines" (Rift: "ф Wizard Tower"). */
	val AREA = Regex("^\\s*[⏣ф]\\s*(.+)$")

	/** Sidebar purse line, e.g. "Purse: 1,234,567" or "Piggy: 1,234 (+50)". */
	val PURSE = Regex("^\\s*(?:Purse|Piggy): ([\\d,]+(?:\\.\\d+)?)")

	val SKILLS = setOf(
		"Farming", "Mining", "Combat", "Foraging", "Fishing", "Enchanting", "Alchemy",
		"Carpentry", "Runecrafting", "Taming", "Social", "Hunting",
	)

	/** Action bar skill gain, e.g. "+12.5 Mining (1,234/5,000)" or "+3 Farming (45.3%)". Searched with find(). */
	val SKILL_XP = Regex("\\+([\\d,]+(?:\\.\\d+)?) ([A-Z][a-z]+) \\(([^)]*)\\)")

	/** Rare drop chat line; group 1 = rarity, 2 = item, 3 = magic find percent (optional). */
	val DROP = Regex(
		"^\\s*(RARE DROP!|VERY RARE DROP!|CRAZY RARE DROP!|INSANE DROP!|PRAY RNGESUS DROP!|PET DROP!)\\s+" +
			"(.+?)(?:\\s*\\(\\+(\\d+)% ✯ Magic Find\\))?\\s*$"
	)

	/** Higher = rarer. Used to pick the "biggest drop". */
	val RARITY_RANK = mapOf(
		"RARE DROP!" to 1,
		"PET DROP!" to 2,
		"VERY RARE DROP!" to 3,
		"CRAZY RARE DROP!" to 4,
		"INSANE DROP!" to 5,
		"PRAY RNGESUS DROP!" to 6,
	)

	val RARITY_COLOR = mapOf(
		"RARE DROP!" to "§9",
		"PET DROP!" to "§a",
		"VERY RARE DROP!" to "§5",
		"CRAZY RARE DROP!" to "§d",
		"INSANE DROP!" to "§c",
		"PRAY RNGESUS DROP!" to "§6",
	)

	val SLAYER_COMPLETE = Regex("^\\s*SLAYER QUEST COMPLETE!\\s*$")

	/** Sidebar line showing the active slayer boss during a quest, e.g. "Voidgloom Seraph IV". */
	val SLAYER_BOSS = Regex(
		"^\\s*(Revenant Horror|Tarantula Broodfather|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|" +
			"Riftstalker Bloodfiend) ([IVX]+)\\s*$"
	)

	/** End of a dungeon run, e.g. "☠ Defeated Maxor, Storm, Goldor, and Necron in 05m12s". */
	val DUNGEON_END = Regex("^\\s*☠ Defeated .+ in [\\dhms ]+(\\(NEW RECORD!\\))?\\s*$")

	/** Own death, e.g. "☠ You were killed by Zombie Soldier and became a ghost." or "☠ You burned to death.". */
	val OWN_DEATH = Regex("^\\s*☠ You (.+?)\\.?\\s*$")

	/** Removes item wrapping parentheses: "(Judgement Core)" -> "Judgement Core". */
	fun cleanItem(raw: String): String {
		var s = raw.trim()
		if (s.startsWith("(") && s.endsWith(")") && s.count { it == '(' } == 1) s = s.substring(1, s.length - 1)
		return s.trim()
	}

	/**
	 * Hypixel appends invisible/emoji filler characters to sidebar lines so every line is unique.
	 * Keeps letters, digits and a few punctuation characters only.
	 */
	fun cleanSidebar(raw: String): String =
		raw.filter { it.isLetterOrDigit() || it in " '-()&.,:" }.replace(Regex("\\s+"), " ").trim()

	fun parseNumber(raw: String): Double? = raw.replace(",", "").toDoubleOrNull()
}
