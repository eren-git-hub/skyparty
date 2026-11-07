package dev.skyparty.util

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Client-side chat helpers. All Skyparty messages use the same colorful prefix. */
object Chat {
	/** Rainbow "Skyparty" with a little confetti star. */
	const val PREFIX = "§d✦ §cS§6k§ey§ap§ba§9r§dt§cy §8» §r"
	private val formatting = Regex("§.")

	/** Local message with the Skyparty prefix (only this player sees it). */
	fun info(text: String) = raw(PREFIX + text)

	/** Local message without prefix, e.g. follow-up lines. */
	fun raw(text: String) {
		Minecraft.getInstance().player?.sendSystemMessage(Component.literal(text))
	}

	/** Sends a real chat message to the server (visible to others). */
	fun sendToServer(text: String) {
		Minecraft.getInstance().player?.connection?.sendChat(text)
	}

	/** Sends a server command without the leading slash, e.g. "pc hello". */
	fun command(command: String) {
		Minecraft.getInstance().player?.connection?.sendCommand(command)
	}

	fun strip(text: String) = text.replace(formatting, "")
}
