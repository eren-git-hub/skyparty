package dev.skyparty.minigames.ui

import net.minecraft.client.gui.screens.Screen

/** The tabs of the main Skyparty menu (/skyparty). */
object HubTabs {
	const val GAMES = 0
	const val WHEEL = 1
	const val DLE = 2
	const val NEWS = 3
	const val MAYOR = 4
	const val REMINDERS = 5
	const val WRAPPED = 6

	val names = listOf("★ Games", "◎ Wheel", "✎ Skyblockdle", "✉ News", "♔ Mayor", "⏰ Reminders", "✦ Wrapped")

	fun open(index: Int): Screen = when (index) {
		WHEEL -> dev.skyparty.wheel.WheelScreen()
		DLE -> dev.skyparty.dle.DleScreen()
		NEWS -> dev.skyparty.news.NewsScreen()
		MAYOR -> dev.skyparty.mayor.MayorScreen()
		REMINDERS -> dev.skyparty.reminders.RemindersScreen()
		WRAPPED -> dev.skyparty.stats.WrappedScreen()
		else -> HubScreen()
	}
}
