package dev.skyparty.minigames.ui

import net.minecraft.client.gui.screens.Screen

/** The tabs of the main Skyparty menu (/skyparty). */
object HubTabs {
	const val GAMES = 0
	const val FRIENDS = 1
	const val WHEEL = 2
	const val DLE = 3
	const val NEWS = 4
	const val MAYOR = 5
	const val REMINDERS = 6
	const val WRAPPED = 7

	val names = listOf("★ Games", "☺ Friends", "◎ Wheel", "✎ Skyblockdle", "✉ News", "♔ Mayor", "⏰ Reminders", "✦ Wrapped")

	fun open(index: Int): Screen = when (index) {
		FRIENDS -> dev.skyparty.party.FriendsScreen()
		WHEEL -> dev.skyparty.wheel.WheelScreen()
		DLE -> dev.skyparty.dle.DleScreen()
		NEWS -> dev.skyparty.news.NewsScreen()
		MAYOR -> dev.skyparty.mayor.MayorScreen()
		REMINDERS -> dev.skyparty.reminders.RemindersScreen()
		WRAPPED -> dev.skyparty.stats.WrappedScreen()
		else -> HubScreen()
	}
}
