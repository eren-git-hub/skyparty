package dev.skyparty.module.impl.stats

import dev.skyparty.module.Category
import dev.skyparty.module.Module

object Wrapped : Module(
	id = "wrapped",
	name = "SkyBlock Wrapped",
	description = "Weekly, monthly and yearly recap. (WIP)",
	category = Category.STATS,
	defaultEnabled = true,
) {
	val defaultPeriod = selector("Default Period", "Week", listOf("Week", "Month", "Year"))
	val weeklyReminder = boolean("Weekly Reminder", true, "Notify when your weekly recap is ready")
}
