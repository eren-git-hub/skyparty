package dev.skyparty.module.impl.party

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object DecisionWheel : Module(
	id = "decision_wheel",
	name = "Decision Wheel",
	description = "Live spin wheel with party detection and room codes. Open with /sp wheel.",
	category = Category.PARTY,
) {
	val autoParty = boolean("Party Detection", true, "Automatically join your party's room")
	val onlyHostEdits = boolean("Host Edits Only", true, "When you host: only you can change the entries")
	val onlyHostSpins = boolean("Host Spins Only", false, "When you host: only you can spin")
	val sendToParty = boolean("Send Result to Party", true, "Show a button that posts the result in party chat")
	val sound = boolean("Sound", true)
}
