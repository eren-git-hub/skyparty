package dev.skyparty.minigames.ui

import net.minecraft.client.gui.screens.Screen

/** The tabs of the main Skyparty menu (/skyparty). */
object HubTabs {
	const val GAMES = 0
	const val RANKS = 1
	const val FRIENDS = 2
	const val WHEEL = 3
	const val DLE = 4
	const val NEWS = 5
	const val MAYOR = 6
	const val REMINDERS = 7
	const val WRAPPED = 8

	val names = listOf("★ Games", "♛ Ranks", "☺ Friends", "◎ Wheel", "✎ Skyblockdle", "✉ News", "♔ Mayor", "⏰ Reminders", "✦ Wrapped")

	fun open(index: Int): Screen = when (index) {
		RANKS -> RanksScreen()
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
