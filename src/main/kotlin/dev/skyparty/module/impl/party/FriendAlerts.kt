package dev.skyparty.module.impl.party

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object FriendAlerts : Module(
	id = "friend_alerts",
	name = "Friend in Lobby",
	description = "Tells you when a Hypixel friend is in your lobby. Friends come from /f list (see /sp friends).",
	category = Category.PARTY,
	defaultEnabled = true,
) {
	val alerts = boolean("Lobby Alerts", true, "Chat message when a friend is in your lobby or joins it")
	val sound = boolean("Sound", true)
	val autoSync = boolean("Auto Sync", true, "Read your /f list quietly once a day")
}
