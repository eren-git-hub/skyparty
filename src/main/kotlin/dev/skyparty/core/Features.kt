package dev.skyparty.core

/**
 * Feature entry points, called once from Skyparty.onInitializeClient after config load.
 * Each feature registers its own events, HUD elements and /sp subcommands in its init.
 * Add ONE line per feature here (keeps merge conflicts trivial).
 */
object Features {
	val inits: List<() -> Unit> = listOf(
		{ dev.skyparty.party.PartyTracker.init() },
		{ dev.skyparty.wheel.WheelSession.init() },
		{ dev.skyparty.minigames.MinigameHub.init() },
		{ dev.skyparty.minigames.chess.ChessGame.init() },
		{ dev.skyparty.minigames.parkour.ParkourGame.init() },
		{ dev.skyparty.reminders.Reminders.init() },
		{
			for ((cmd, tab) in listOf("dle" to dev.skyparty.minigames.ui.HubTabs.DLE, "news" to dev.skyparty.minigames.ui.HubTabs.NEWS)) {
				SkypartyCommands.sub { net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(cmd).executes { dev.skyparty.Skyparty.openNextTick(dev.skyparty.minigames.ui.HubTabs.open(tab)); 1 } }
			}
		},
	)
}
