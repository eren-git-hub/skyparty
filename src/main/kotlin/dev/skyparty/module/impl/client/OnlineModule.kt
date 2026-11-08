package dev.skyparty.module.impl.client

import dev.skyparty.module.Category
import dev.skyparty.module.Module
import dev.skyparty.net.BackendTransport
import dev.skyparty.net.LoopbackTransport
import dev.skyparty.net.Net

/**
 * Connection to the Skyparty server (a free Cloudflare Worker). When on and logged in, party rooms,
 * room codes and multiplayer games go through it; otherwise everything stays offline (solo).
 */
object OnlineModule : Module(
	id = "online",
	name = "Online",
	description = "Multiplayer through the Skyparty server (party rooms, room codes, games).",
	category = Category.CLIENT,
	defaultEnabled = true,
) {
	const val DEFAULT_URL = "https://skyparty-api.riqer123.workers.dev"

	val serverUrl = text("Server URL", DEFAULT_URL, maxLength = 96, description = "Address of the Skyparty server").also { it.visibleIf = { false } }

	override fun onTick() {
		BackendTransport.update(true, serverUrl.value.ifBlank { DEFAULT_URL })
		val wanted: Net.Transport = if (BackendTransport.connected) BackendTransport else LoopbackTransport
		if (Net.transport !== wanted) Net.transport = wanted
	}

	override fun onDisable() {
		BackendTransport.stop()
		Net.transport = LoopbackTransport
	}

	/** Short status for the UI, e.g. "Online as Steve" or "Connecting…". */
	fun status(): String = when (BackendTransport.state) {
		BackendTransport.State.ONLINE -> "§aOnline" + (BackendTransport.session?.let { " as ${it.name}" } ?: "")
		BackendTransport.State.CONNECTING -> "§eConnecting…"
		BackendTransport.State.ERROR -> "§cOffline – ${BackendTransport.lastError ?: "error"}"
		BackendTransport.State.NOT_CONFIGURED -> "§cServer URL not valid"
		BackendTransport.State.OFF -> "§8Offline (Online is turned off)"
	}
}
