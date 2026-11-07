package dev.skyparty.stats

import dev.skyparty.module.impl.stats.Wrapped
import dev.skyparty.pet.PetData
import dev.skyparty.pet.scoreboard.ScoreboardPetHud
import dev.skyparty.util.Chat
import dev.skyparty.util.Location
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.IsoFields

/**
 * Turns sidebar, action bar and chat into [StatsStore] records. Only counts while in SkyBlock
 * (or singleplayer in dev) and while the Wrapped module is enabled.
 */
object StatsTracker {
	/** Seconds a finished source stays the drop context (slayer drops can arrive just after "QUEST COMPLETE"). */
	private const val CONTEXT_GRACE_MS = 30_000L
	private const val SIDEBAR_SCAN_TICKS = 10

	private var lastTickAt = 0L
	private var ticks = 0
	private var wasInSkyBlock = false

	/** Cached from the last sidebar scan. */
	var area: String = "Unknown"
		private set
	private var purse: Long? = null
	private var purseLevel: ClientLevel? = null
	private var slayerBoss: String? = null
	private var slayerSeenAt = 0L

	private var lastSource: String? = null
	private var lastSourceAt = 0L

	/** Last progress text per skill, to ignore the repeated action bar packets of one gain. */
	private val lastSkillProgress = HashMap<String, String>()

	val active: Boolean get() = Wrapped.enabled

	fun tick(mc: Minecraft) {
		val now = System.currentTimeMillis()
		val elapsed = if (lastTickAt == 0L) 0L else (now - lastTickAt).coerceIn(0L, 1_000L)
		lastTickAt = now
		StatsStore.tick()

		val inSkyBlock = mc.player != null && Location.inSkyBlockOrDev
		if (inSkyBlock && !wasInSkyBlock) onSkyBlockJoin()
		wasInSkyBlock = inSkyBlock
		if (!inSkyBlock || !active) {
			purse = null
			return
		}

		if (ticks++ % SIDEBAR_SCAN_TICKS == 0) scanSidebar(mc)

		if (!PetData.isAfk && elapsed > 0) {
			val day = StatsStore.today()
			day.playtimeMs += elapsed
			day.areaMs[area] = (day.areaMs[area] ?: 0L) + elapsed
			day.addHour(LocalDateTime.now().hour, elapsed)
			StatsStore.markDirty()
		}
	}

	private fun scanSidebar(mc: Minecraft) {
		var newArea: String? = null
		var newPurse: Long? = null
		for (raw in Location.sidebarLines) {
			StatsPatterns.AREA.find(raw)?.let { newArea = StatsPatterns.cleanSidebar(it.groupValues[1]) }
			val clean = StatsPatterns.cleanSidebar(raw)
			StatsPatterns.PURSE.find(clean)?.let { m ->
				newPurse = StatsPatterns.parseNumber(m.groupValues[1])?.toLong()
			}
			StatsPatterns.SLAYER_BOSS.find(clean)?.let { m ->
				slayerBoss = "${m.groupValues[1]} ${m.groupValues[2]}"
				slayerSeenAt = System.currentTimeMillis()
			}
		}
		area = newArea?.takeIf { it.isNotBlank() } ?: Location.dungeonFloor?.let { "Catacombs ($it)" } ?: "Unknown"
		onPurse(newPurse, mc.level)
	}

	/** Purse deltas between two scans in the same world; switching worlds resets the baseline. */
	private fun onPurse(value: Long?, level: ClientLevel?) {
		val previous = purse
		if (value == null) {
			purse = null
			return
		}
		if (previous != null && level === purseLevel && value != previous) {
			val day = StatsStore.today()
			if (value > previous) day.coinsGained += value - previous else day.coinsLost += previous - value
			StatsStore.markDirty()
		}
		purse = value
		purseLevel = level
	}

	fun onActionBar(text: String) {
		if (!active || !Location.inSkyBlockOrDev) return
		for (m in StatsPatterns.SKILL_XP.findAll(text)) {
			val skill = m.groupValues[2]
			if (skill !in StatsPatterns.SKILLS) continue
			val progress = m.groupValues[3]
			if (lastSkillProgress[skill] == progress) continue
			lastSkillProgress[skill] = progress
			val xp = StatsPatterns.parseNumber(m.groupValues[1]) ?: continue
			val day = StatsStore.today()
			day.skillXp[skill] = (day.skillXp[skill] ?: 0.0) + xp
			StatsStore.markDirty()
		}
	}

	fun onChat(text: String) {
		if (!active || !Location.inSkyBlockOrDev) return
		val now = System.currentTimeMillis()

		StatsPatterns.DROP.matchEntire(text)?.let { m ->
			val rarity = m.groupValues[1]
			val item = StatsPatterns.cleanItem(m.groupValues[2])
			val context = context()
			StatsStore.today().drops += Drop(now, rarity, item, m.groupValues[3].toIntOrNull() ?: 0, context ?: area)
			StatsStore.markDirty(urgent = true)
			ScoreboardPetHud.react(ScoreboardPetHud.Reaction.HAPPY)
			dev.skyparty.pet.PetReactions.onDrop(StatsPatterns.RARITY_RANK[rarity] ?: 1)
			return
		}
		if (StatsPatterns.SKILL_LEVEL_UP.matches(text)) {
			dev.skyparty.pet.PetReactions.onLevelUp()
			return
		}
		if (StatsPatterns.SLAYER_COMPLETE.matches(text)) {
			val boss = slayerBoss?.takeIf { now - slayerSeenAt < 120_000 } ?: "Unknown Slayer"
			val day = StatsStore.today()
			day.slayers[boss] = (day.slayers[boss] ?: 0) + 1
			StatsStore.markDirty(urgent = true)
			finishSource("slayer:$boss")
			return
		}
		if (StatsPatterns.DUNGEON_END.matches(text)) {
			val floor = Location.dungeonFloor ?: "Unknown"
			val day = StatsStore.today()
			day.dungeonRuns[floor] = (day.dungeonRuns[floor] ?: 0) + 1
			StatsStore.markDirty(urgent = true)
			finishSource("dungeon:$floor")
			return
		}
		StatsPatterns.OWN_DEATH.matchEntire(text)?.let { m ->
			val day = StatsStore.today()
			day.deaths++
			val cause = m.groupValues[1].removePrefix("were killed by ").removeSuffix(" and became a ghost")
			day.deathCauses[cause] = (day.deathCauses[cause] ?: 0) + 1
			StatsStore.markDirty()
		}
	}

	private fun finishSource(id: String) {
		lastSource = id
		lastSourceAt = System.currentTimeMillis()
	}

	/** Activity a drop belongs to: a source finished moments ago, the running slayer quest, or the dungeon floor. */
	fun context(): String? {
		val now = System.currentTimeMillis()
		lastSource?.let { if (now - lastSourceAt < CONTEXT_GRACE_MS) return it }
		slayerBoss?.let { if (now - slayerSeenAt < CONTEXT_GRACE_MS) return "slayer:$it" }
		Location.dungeonFloor?.let { return "dungeon:$it" }
		return null
	}

	fun isoWeek(date: LocalDate): String =
		"%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

	/** First SkyBlock join of a new ISO week: point to last week's recap (if there was any play). */
	private fun onSkyBlockJoin() {
		val today = LocalDate.now()
		val week = isoWeek(today)
		if (StatsStore.lastReminderWeek == week) return
		val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
		val lastWeek = StatsStore.range(monday.minusDays(7), monday.minusDays(1))
		val played = lastWeek.sumOf { it.playtimeMs } > 0
		StatsStore.setReminderWeek(week)
		if (played && Wrapped.enabled && Wrapped.weeklyReminder.value) {
			Chat.info("§fYour weekly §bSkyBlock Wrapped §fis ready! §7Type §b/sp wrapped §7to see it.")
		}
	}

	/** Testing helper: forget transient state (cached sidebar, contexts). */
	fun resetTransient() {
		purse = null
		slayerBoss = null
		lastSource = null
		lastSkillProgress.clear()
	}
}
