package dev.skyparty.module.impl.party

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object PartyChatModule : Module(
	id = "party_chat",
	name = "Party Chat Window",
	description = "Small party chat box in the bottom-right corner of Skyparty menus and games.",
	category = Category.PARTY,
	defaultEnabled = true,
) {
	val onlyInParty = boolean("Only in a Party", true, "Hide the box when you are not in a party")
}
