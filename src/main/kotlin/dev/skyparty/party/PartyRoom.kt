package dev.skyparty.party

import com.google.gson.JsonObject
import dev.skyparty.net.LoopbackTransport
import dev.skyparty.net.Net
import org.slf4j.LoggerFactory

/**
 * One shared [Net.Room] for the current party, used by the Decision Wheel and the Ping System.
 *
 * A Net room has a single onMessage callback, so features register handlers by type prefix here
 * instead of joining the same room twice. Not in a party → a private loopback room ("solo"), so
 * features behave identically offline and never open a network connection for nothing.
 *
 * The room is only held while at least one [demand] says it is needed (feature enabled).
 */
object PartyRoom {
	private val log = LoggerFactory.getLogger("Skyparty/PartyRoom")

	private val handlers = mutableListOf<Pair<String, (Net.Message) -> Unit>>()
	private val demands = mutableListOf<() -> Boolean>()

	/** Called after the room was (re)joined, e.g. so the wheel host can re-broadcast its state. */
	val roomChanged = mutableListOf<(Net.Room) -> Unit>()

	private var joinedWith: Net.Transport? = null

	var room: Net.Room? = null
		private set

	val isSolo get() = room?.id?.startsWith("solo") ?: true

	/** Handle every message whose type starts with [prefix] ("wheel.", "ping"). */
	fun on(prefix: String, handler: (Net.Message) -> Unit) {
		handlers += prefix to handler
	}

	/** Registers a condition under which the room should be held. */
	fun demand(needed: () -> Boolean) {
		demands += needed
	}

	/** Joins/leaves/switches so the held room matches the current party. Cheap; called every tick. */
	fun sync() {
		val needed = demands.any { it() }
		val wanted = if (!needed) null else PartyTracker.roomId() ?: "solo"
		val current = room
		if (current?.id == wanted && (wanted == "solo" || joinedWith === Net.transport)) return
		current?.leave()
		room = null
		if (wanted == null) return
		val r = if (wanted == "solo") LoopbackTransport.join("solo") else Net.join(wanted)
		joinedWith = Net.transport
		r.onMessage = ::dispatch
		room = r
		log.info("Party room is now {}", wanted)
		roomChanged.toList().forEach { runCatching { it(r) } }
	}

	/** Sends into the party room (joins first if needed). Returns false when no room is wanted. */
	fun send(type: String, payload: JsonObject): Boolean {
		sync()
		val r = room ?: return false
		r.send(type, payload)
		return true
	}

	private fun dispatch(msg: Net.Message) {
		for ((prefix, handler) in handlers) {
			if (msg.type.startsWith(prefix)) {
				runCatching { handler(msg) }.onFailure { log.warn("Handler for {} failed", msg.type, it) }
			}
		}
	}
}
