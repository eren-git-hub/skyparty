package dev.skyparty.net

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Who is using Skyparty right now. Every online client sits in one shared backend room ("presence:all") and
 * never sends anything there: the server already reports the member list with names whenever someone comes
 * or goes, so this costs no messages. Shown in the Settings tab.
 */
object ActiveUsers {
	private const val ROOM = "presence:all"

	private var room: Net.Room? = null
	private var joinedWith: Net.Transport? = null

	/** Names of everyone online with the mod (you included), sorted; empty while offline. */
	val names: List<String>
		get() {
			val r = room ?: return emptyList()
			if (!Net.transport.connected) return emptyList()
			return (r.memberNames.values + Net.selfName).filter { it.isNotBlank() }
				.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
		}

	val online: Boolean get() = room != null && Net.transport.connected

	/** Tests only: players that count as Skyparty users. */
	val testUsers = mutableSetOf<java.util.UUID>()
	val testNames = mutableSetOf<String>()

	/** Same as [isUser], by name (lists that only have names, like your friends). */
	fun isUserName(name: String) = testNames.any { it.equals(name, true) } || names.any { it.equals(name, true) }

	/** Whether this player is online with Skyparty right now (by UUID, so nicks and renames do not matter). */
	fun isUser(uuid: java.util.UUID): Boolean {
		if (uuid in testUsers) return true
		val r = room ?: return false
		return Net.transport.connected && (uuid == Net.selfId || uuid in r.members)
	}

	fun init() {
		ClientTickEvents.END_CLIENT_TICK.register { sync() }
	}

	/** Joins the presence room while the backend is connected (and again after a reconnect / server change). */
	private fun sync() {
		val transport = Net.transport
		val wanted = transport.connected
		if (wanted && room != null && joinedWith === transport) return
		if (!wanted && room == null) return
		room?.leave()
		room = null
		if (!wanted) return
		room = transport.join(ROOM)
		joinedWith = transport
	}
}
