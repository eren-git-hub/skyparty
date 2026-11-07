package dev.skyparty.core

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

/**
 * Registry for "/sl <sub>" (and "/skyparty <sub>") subcommands. Features call [sub] during
 * their init; Skyparty attaches everything when client commands are registered.
 * The builder factory is invoked once per root alias, so it must create a fresh builder.
 */
object SkypartyCommands {
	private val factories = mutableListOf<() -> LiteralArgumentBuilder<FabricClientCommandSource>>()

	fun sub(factory: () -> LiteralArgumentBuilder<FabricClientCommandSource>) {
		factories += factory
	}

	fun attachAll(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
		for (factory in factories) root.then(factory())
	}
}
