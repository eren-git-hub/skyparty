package dev.skyparty.minigames.ui

import net.minecraft.client.gui.screens.Screen

/** The tabs of the main Skyparty menu (/skyparty). */
object HubTabs {
	const val GAMES = 0
	const val DLE = 1
	const val NEWS = 2
	const val REMINDERS = 3
	const val WRAPPED = 4

	val names = listOf("★ Games", "✎ Skyblockdle", "✉ News", "⏰ Reminders", "✦ Wrapped")

	fun open(index: Int): Screen = when (index) {
		DLE -> dev.skyparty.dle.DleScreen()
		NEWS -> dev.skyparty.news.NewsScreen()
		REMINDERS -> dev.skyparty.reminders.RemindersScreen()
		WRAPPED -> dev.skyparty.stats.WrappedScreen()
		else -> HubScreen()
	}
}
