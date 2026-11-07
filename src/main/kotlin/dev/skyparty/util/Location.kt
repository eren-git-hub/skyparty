package dev.skyparty.util

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/** Where the player currently is, derived from the server address and the sidebar title. */
object Location {
	private val mc get() = Minecraft.getInstance()
	private val formatting = Regex("§.")
	private val FLOOR = Regex("The Catacombs \\((\\w+)\\)")

	val isDev: Boolean = FabricLoader.getInstance().isDevelopmentEnvironment

	val onHypixel: Boolean
		get() = mc.currentServer?.ip?.lowercase()?.contains("hypixel") == true

	/** Plain text of the sidebar (scoreboard) title, or "" if none. */
	val sidebarTitle: String
		get() {
			val objective = mc.level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ""
			return objective.displayName.string.replace(formatting, "")
		}

	/** Plain text of every sidebar line, top to bottom (team prefix/suffix included, as Hypixel uses them). */
	val sidebarLines: List<String>
		get() {
			val scoreboard = mc.level?.scoreboard ?: return emptyList()
			val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
			return scoreboard.listPlayerScores(objective)
				.filter { !it.isHidden }
				.sortedByDescending { it.value() }
				.map { entry ->
					val team = scoreboard.getPlayersTeam(entry.owner())
					PlayerTeam.formatNameForTeam(team, entry.ownerName()).string.replace(formatting, "")
				}
		}

	/** Dungeon floor from the sidebar, e.g. "M7" or "F7"; null outside dungeons. */
	val dungeonFloor: String?
		get() = sidebarLines.firstNotNullOfOrNull { FLOOR.find(it)?.groupValues?.get(1) }

	val inSkyBlock: Boolean
		get() = onHypixel && sidebarTitle.uppercase().contains("SKYBLOCK")

	/** Singleplayer counts too in the dev environment so features can be tested without Hypixel. */
	val inSkyBlockOrDev: Boolean
		get() = inSkyBlock || (isDev && mc.hasSingleplayerServer())
}
