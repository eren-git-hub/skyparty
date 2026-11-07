package dev.skyparty.stats

import dev.skyparty.pet.PetData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Aggregated numbers for one Wrapped period (rolling window ending today). */
class WrappedSummary(val period: Period, val today: LocalDate = LocalDate.now()) {
	enum class Period(val label: String, val days: Long) {
		WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365);

		val title get() = "Last $days days"

		companion object {
			fun parse(s: String): Period? = entries.firstOrNull { it.name.equals(s, true) || it.label.equals(s, true) }
		}
	}

	val from: LocalDate = today.minusDays(period.days - 1)
	private val days = StatsStore.range(from, today)

	val playtimeMs = days.sumOf { it.playtimeMs }
	val daysPlayed = days.count { it.playtimeMs > 0 }
	val areas = sum(days.map { it.areaMs }).filterKeys { it != "Unknown" }
	val topArea = areas.maxByOrNull { it.value }
	val coinsGained = days.sumOf { it.coinsGained }
	val coinsLost = days.sumOf { it.coinsLost }
	val skills: Map<String, Double> = days.flatMap { it.skillXp.entries }
		.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
	val skillTotal = skills.values.sum()
	val topSkill = skills.maxByOrNull { it.value }
	val drops = days.flatMap { it.drops }
	val biggestDrop = drops.maxWithOrNull(
		compareBy<Drop>({ StatsPatterns.RARITY_RANK[it.rarity] ?: 0 }, { it.time })
	)
	val deaths = days.sumOf { it.deaths }
	val topDeathCause = days.flatMap { it.deathCauses.entries }
		.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }.maxByOrNull { it.value }
	val runs = sumInt(days.map { it.dungeonRuns })
	val runTotal = runs.values.sum()
	val topFloor = runs.maxByOrNull { it.value }
	val slayers = sumInt(days.map { it.slayers })
	val slayerTotal = slayers.values.sum()
	val topSlayer = slayers.maxByOrNull { it.value }
	val hours = LongArray(24).also { arr -> days.forEach { d -> d.hourMs.forEachIndexed { i, v -> if (i < 24) arr[i] += v } } }
	val activeHour = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 }
	val bestDay = days.filter { it.playtimeMs > 0 }.maxByOrNull { it.playtimeMs }
	val averagePerDayMs = if (daysPlayed == 0) 0L else playtimeMs / daysPlayed

	val dateRange: String
		get() {
			val f = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
			val y = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
			return "${from.format(if (from.year == today.year) f else y)} – ${today.format(f)}"
		}

	val bestDayLabel: String?
		get() = bestDay?.let {
			val d = LocalDate.parse(it.date)
			d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", " + d.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
		}

	/** Plain-text summary for the clipboard. */
	fun text(): String = buildString {
		appendLine("SkyBlock Wrapped - ${period.title} ($dateRange)")
		appendLine("Playtime: ${duration(playtimeMs)} on $daysPlayed day(s)" + (topArea?.let { " | Top area: ${it.key} (${duration(it.value)})" } ?: ""))
		appendLine("Coins gained: ${compact(coinsGained.toDouble())} | Skill XP: ${compact(skillTotal)}" + (topSkill?.let { " (top: ${it.key})" } ?: ""))
		appendLine("Rare drops: ${drops.size}" + (biggestDrop?.let { " | Biggest: ${it.item} (${it.rarity.removeSuffix("!")})" } ?: ""))
		appendLine("Deaths: $deaths | Dungeon runs: $runTotal" + (topFloor?.let { " (top: ${it.key})" } ?: "") +
			" | Slayer bosses: $slayerTotal" + (topSlayer?.let { " (top: ${it.key})" } ?: ""))
		appendLine("Most active hour: ${activeHour?.let(::hourRange) ?: "-"} | Best day: ${bestDayLabel ?: "-"}")
		append("- via Skyparty")
	}

	companion object {
		fun duration(ms: Long): String = PetData.formatDuration(ms)

		fun hourRange(h: Int) = "%02d:00–%02d:00".format(h, (h + 1) % 24)

		/** 1234 -> "1.2k", 12_300_000 -> "12.3M". */
		fun compact(v: Double): String {
			val abs = kotlin.math.abs(v)
			val (div, suffix) = when {
				abs >= 1e9 -> 1e9 to "B"
				abs >= 1e6 -> 1e6 to "M"
				abs >= 1e3 -> 1e3 to "k"
				else -> 1.0 to ""
			}
			val text = if (div == 1.0) "%.0f".format(Locale.ENGLISH, v) else "%.1f".format(Locale.ENGLISH, v / div)
			return text + suffix
		}

		private fun sum(maps: List<Map<String, Long>>): Map<String, Long> {
			val out = LinkedHashMap<String, Long>()
			maps.forEach { m -> m.forEach { (k, v) -> out[k] = (out[k] ?: 0L) + v } }
			return out
		}

		private fun sumInt(maps: List<Map<String, Int>>): Map<String, Int> {
			val out = LinkedHashMap<String, Int>()
			maps.forEach { m -> m.forEach { (k, v) -> out[k] = (out[k] ?: 0) + v } }
			return out
		}
	}
}
