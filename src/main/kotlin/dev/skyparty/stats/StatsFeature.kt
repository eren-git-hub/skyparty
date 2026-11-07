package dev.skyparty.stats

import dev.skyparty.Skyparty
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

/** Wires the stats store and SkyBlock Wrapped (chat/action bar events, ticking, /sp wrapped [week|month|year]). */
object StatsFeature {
	fun init() {
		StatsStore.load()

		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			val text = Chat.strip(message.string)
			if (overlay) StatsTracker.onActionBar(text) else StatsTracker.onChat(text)
		}
		ClientTickEvents.END_CLIENT_TICK.register(StatsTracker::tick)
		ClientLifecycleEvents.CLIENT_STOPPING.register { StatsStore.save() }

		SkypartyCommands.sub {
			val root = ClientCommands.literal("wrapped").executes { Skyparty.openNextTick(WrappedScreen()); 1 }
			for (p in WrappedSummary.Period.entries) {
				root.then(ClientCommands.literal(p.name.lowercase()).executes { Skyparty.openNextTick(WrappedScreen(p)); 1 })
			}
			root
		}
	}
}
