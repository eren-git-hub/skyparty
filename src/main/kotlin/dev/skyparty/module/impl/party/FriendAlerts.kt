package dev.skyparty.module.impl.party

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object FriendAlerts : Module(
	id = "friend_alerts",
	name = "Friends",
	description = "Friend in lobby alerts and the ✦ next to Skyparty players.",
	category = Category.PARTY,
	defaultEnabled = true,
) {
	val alerts = boolean("Lobby Alerts", true, "Chat message when a friend is in your lobby or joins it")
	val sound = boolean("Sound", true)
	val autoSync = boolean("Auto Sync", true, "Read your /f list quietly once a day")
	val badge = boolean("Skyparty Badge", true, "Pink ✦ next to the names of players who use Skyparty")
}
