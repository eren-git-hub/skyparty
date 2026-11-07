package dev.skyparty.stats

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.TreeMap

/** One rare drop seen in chat. [source] is the activity context, e.g. "slayer:Voidgloom Seraph IV". */
class Drop(
	var time: Long = 0,
	var rarity: String = "",
	var item: String = "",
	var magicFind: Int = 0,
	var source: String = "",
)

/** Everything recorded for one calendar day (local time). All fields default so Gson keeps them on old files. */
class DayStats(
	var date: String = "",
	var playtimeMs: Long = 0,
	var areaMs: MutableMap<String, Long> = LinkedHashMap(),
	/** Active milliseconds per hour of day (index 0..23). */
	var hourMs: LongArray = LongArray(24),
	var coinsGained: Long = 0,
	var coinsLost: Long = 0,
	var skillXp: MutableMap<String, Double> = LinkedHashMap(),
	var drops: MutableList<Drop> = ArrayList(),
	var slayers: MutableMap<String, Int> = LinkedHashMap(),
	var dungeonRuns: MutableMap<String, Int> = LinkedHashMap(),
	var deaths: Int = 0,
	var deathCauses: MutableMap<String, Int> = LinkedHashMap(),
) {
	fun addHour(hour: Int, ms: Long) {
		if (hourMs.size != 24) hourMs = hourMs.copyOf(24)
		hourMs[hour] += ms
	}
}

/**
 * Persists per-day stats as config/skyparty/stats/YYYY-MM-DD.json (one small file per day, written via a temp
 * file + atomic move, so a crash can lose at most the last unsaved minute but never corrupts a file).
 * All days are kept in memory; a year of play is only a few hundred KB.
 */
object StatsStore {
	private const val SAVE_EVERY_MS = 60_000L

	private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
	val dir: Path = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("stats")
	private val metaFile = dir.resolve("meta.json")
	private val dayFile = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.json$")

	private val days = TreeMap<LocalDate, DayStats>()
	private val dirty = HashSet<LocalDate>()
	private var lastSaveAt = System.currentTimeMillis()

	/** ISO week ("2026-W45") of the last weekly Wrapped reminder. */
	var lastReminderWeek: String = ""
		private set

	fun setReminderWeek(week: String) {
		lastReminderWeek = week
		saveMeta()
	}

	fun load() {
		days.clear()
		try {
			if (!Files.isDirectory(dir)) return
			Files.list(dir).use { stream ->
				for (path in stream) {
					val match = dayFile.matchEntire(path.fileName.toString()) ?: continue
					try {
						val date = LocalDate.parse(match.groupValues[1])
						val day = gson.fromJson(Files.readString(path), DayStats::class.java) ?: continue
						day.date = date.toString()
						days[date] = day
					} catch (e: Exception) {
						Skyparty.LOGGER.warn("Skipping unreadable stats file {}", path, e)
					}
				}
			}
			if (Files.exists(metaFile)) {
				val meta = JsonParser.parseString(Files.readString(metaFile)).asJsonObject
				lastReminderWeek = meta["lastReminderWeek"]?.asString ?: ""
			}
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not load stats", e)
		}
	}

	fun day(date: LocalDate): DayStats = days.getOrPut(date) { DayStats(date = date.toString()) }

	fun today(): DayStats = day(LocalDate.now())

	/** Marks a day as changed; [urgent] writes immediately (e.g. rare drops). */
	fun markDirty(date: LocalDate = LocalDate.now(), urgent: Boolean = false) {
		dirty += date
		if (urgent) save()
	}

	/** Days in [from, to] (inclusive) that have any data. */
	fun range(from: LocalDate, to: LocalDate): List<DayStats> = days.subMap(from, true, to, true).values.toList()

	fun tick() {
		if (dirty.isNotEmpty() && System.currentTimeMillis() - lastSaveAt > SAVE_EVERY_MS) save()
	}

	fun save() {
		lastSaveAt = System.currentTimeMillis()
		if (dirty.isEmpty()) return
		try {
			Files.createDirectories(dir)
			for (date in dirty.toList()) {
				val day = days[date] ?: continue
				writeAtomic(dir.resolve("$date.json"), gson.toJson(day))
			}
			dirty.clear()
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not save stats", e)
		}
	}

	private fun saveMeta() {
		try {
			Files.createDirectories(dir)
			writeAtomic(metaFile, gson.toJson(JsonObject().apply {
				addProperty("version", 1)
				addProperty("lastReminderWeek", lastReminderWeek)
			}))
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not save stats meta", e)
		}
	}

	/** Testing helper: forgets every recorded day (memory and disk). */
	fun wipe() {
		days.clear()
		dirty.clear()
		try {
			if (Files.isDirectory(dir)) Files.list(dir).use { s ->
				s.filter { dayFile.matches(it.fileName.toString()) }.forEach { Files.deleteIfExists(it) }
			}
		} catch (e: Exception) {
			Skyparty.LOGGER.error("Could not wipe stats", e)
		}
	}

	/** Temp file + atomic move; falls back to a plain replace on file systems without atomic moves. */
	fun writeAtomic(target: Path, text: String) {
		val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
		Files.writeString(tmp, text)
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
		}
	}
}
