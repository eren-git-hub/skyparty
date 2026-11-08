package dev.skyparty.module.impl.party

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object Minigames : Module(
	id = "minigames",
	name = "Lobby Minigames",
	description = "Reaction duel, chess, pet race, price guessing, parkour race.",
	category = Category.PARTY,
	defaultEnabled = true,
) {
	val pauseInCombat = boolean("Pause in Combat", true)
	val invites = boolean("Accept Invites", true)
}
