package dev.skyparty.module.impl.party

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object Minigames : Module(
	id = "minigames",
	name = "Lobby Minigames",
	description = "Reaction duel, chess, pet race, price guessing, parkour race. (WIP)",
	category = Category.PARTY,
	defaultEnabled = true,
) {
	val p2pOptIn = boolean(
		"Allow P2P", false,
		"Real-time games directly with your party. Warning: other players can see your IP.",
	)
	val pauseInCombat = boolean("Pause in Combat", true)
	val invites = boolean("Accept Invites", true)
}
