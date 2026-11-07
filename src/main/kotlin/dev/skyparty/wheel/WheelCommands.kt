package dev.skyparty.wheel

import com.mojang.brigadier.arguments.StringArgumentType
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.party.PartyTracker
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands

/** "/sp wheel …" and "/sp party …" commands. */
object WheelCommands {
	fun init() {
		SkypartyCommands.sub {
			ClientCommands.literal("wheel")
				.executes { WheelSession.openScreenNextTick(); 1 }
				.then(ClientCommands.literal("create").executes {
					val code = WheelSession.createRoom()
					Chat.info("§aWheel room created: §e§l$code §7– friends join with §f/sp wheel join $code")
					WheelSession.openScreenNextTick()
					1
				})
				.then(ClientCommands.literal("join").then(
					ClientCommands.argument("code", StringArgumentType.word()).executes { ctx ->
						WheelSession.joinCode(StringArgumentType.getString(ctx, "code"))
						WheelSession.openScreenNextTick()
						1
					}
				))
				.then(ClientCommands.literal("leave").executes {
					WheelSession.leaveCode()
					Chat.info("§7Left the wheel room.")
					1
				})
		}
		SkypartyCommands.sub {
			ClientCommands.literal("party")
				.executes {
					PartyTracker.describe().forEach { Chat.info(it) }
					1
				}
				.then(ClientCommands.literal("refresh").executes {
					// Only ever sent because the player ran this command (no automation).
					Chat.command("pl")
					1
				})
		}
	}
}
