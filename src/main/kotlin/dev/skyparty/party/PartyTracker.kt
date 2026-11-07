package dev.skyparty.party

import dev.skyparty.party.PartyPatterns as P
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * Knows who is in your Hypixel party (parsed from chat). Used to auto-join party rooms.
 *
 * CONTRACT: [members] includes yourself when in a party; empty when not in a party.
 * [roomId] is stable for the same set of members (order-independent), null when not in a party.
 *
 * Purely passive: it only reads chat. It never sends /pl by itself; `/sp party refresh` does that
 * when the player runs it (see [PartySuite]). Patterns live in [PartyPatterns].
 */
object PartyTracker {
	private val log = LoggerFactory.getLogger("Skyparty/Party")

	@Volatile
	var members: List<String> = emptyList()
		internal set

	@Volatile
	var leader: String? = null
		internal set

	val inParty get() = members.size > 1

	/** Room id shared by everyone in the same party, e.g. "party:3f2a9c". */
	fun roomId(): String? {
		if (!inParty) return null
		val key = members.map { it.lowercase() }.sorted().joinToString(",")
		return "party:" + Integer.toHexString(key.hashCode())
	}

	/** Listeners notified (client thread) when the member list changes. */
	val listeners = mutableListOf<(List<String>) -> Unit>()

	// ------------------------------------------------------------------------------------------

	/** /pl block being collected (between "Party Members (N)" and the closing separator). */
	private class PendingList(val expected: Int, val startedAt: Long) {
		val names = LinkedHashSet<String>()
		var leader: String? = null
	}

	private var pending: PendingList? = null
	private var initialized = false

	private val self: String get() = Minecraft.getInstance().user.name

	fun init() {
		if (initialized) return
		initialized = true
		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			if (!overlay) onChat(Chat.strip(message.string))
		}
		ClientTickEvents.END_CLIENT_TICK.register {
			val p = pending
			if (p != null && System.currentTimeMillis() - p.startedAt > 1500) commitPending()
		}
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> set(emptyList(), null, "disconnect") }
	}

	/** Feeds one stripped chat line. Public so gametests and other features can replay lines. */
	fun onChat(raw: String) {
		for (line in raw.split('\n')) handleLine(line.trim())
	}

	private fun handleLine(line: String) {
		if (line.isEmpty()) return
		val p = pending
		if (p != null) {
			P.LIST_LEADER.matchEntire(line)?.let {
				val name = P.cleanName(it.groupValues[1])
				if (name != null) {
					p.leader = name
					p.names += name
				}
				return
			}
			P.LIST_MODS.matchEntire(line)?.let { p.names += P.names(it.groupValues[1]); return }
			P.LIST_MEMBERS.matchEntire(line)?.let {
				p.names += P.names(it.groupValues[1])
				if (p.names.size >= p.expected) commitPending()
				return
			}
			// Separator, or any unrelated line, closes the block.
			commitPending()
			if (P.SEPARATOR.matches(line)) return
		}

		P.LIST_HEADER.matchEntire(line)?.let {
			pending = PendingList(it.groupValues[1].toInt(), System.currentTimeMillis())
			return
		}
		if (P.SEPARATOR.matches(line)) return

		P.SELF_JOINED.matchEntire(line)?.let {
			val host = it.groupValues[1]
			set(listOf(self, host), host, "joined $host's party")
			return
		}
		P.PARTYING_WITH.matchEntire(line)?.let {
			set(withSelf(members + P.names(it.groupValues[1])), leader, "partying with")
			return
		}
		P.JOINED.matchEntire(line)?.let { onJoin(it.groupValues[1]); return }
		P.FINDER_JOINED.matchEntire(line)?.let { onJoin(it.groupValues[1]); return }
		P.LEFT.matchEntire(line)?.let { onLeave(it.groupValues[1]); return }
		P.REMOVED.matchEntire(line)?.let { onLeave(it.groupValues[1]); return }
		P.KICKED_OFFLINE.matchEntire(line)?.let { m ->
			P.names(m.groupValues[1]).forEach { onLeave(it) }
			return
		}
		P.TRANSFER.matchEntire(line)?.let {
			val newLeader = it.groupValues[1]
			set(withSelf(members + newLeader), newLeader, "transfer")
			return
		}
		P.PROMOTED_LEADER.matchEntire(line)?.let {
			val newLeader = it.groupValues[2]
			set(withSelf(members + newLeader), newLeader, "promoted")
			return
		}
		if (P.SELF_LEFT.matches(line) || P.DISBANDED.matches(line) || P.DISBANDED_BY.matches(line) ||
			P.SELF_KICKED.matches(line) || P.NOT_IN_PARTY.matches(line)
		) {
			set(emptyList(), null, "left/disbanded")
		}
	}

	private fun onJoin(name: String) {
		if (name.equals(self, ignoreCase = true)) return
		// First join while we had no party: we are the one who invited, so we lead.
		val newLeader = leader ?: if (members.isEmpty()) self else null
		set(withSelf(members + name), newLeader, "$name joined")
	}

	private fun onLeave(name: String) {
		if (name.equals(self, ignoreCase = true)) {
			set(emptyList(), null, "self removed")
			return
		}
		val rest = members.filterNot { it.equals(name, ignoreCase = true) }
		if (rest.size <= 1) set(emptyList(), null, "$name left, party empty")
		else set(rest, leader?.takeUnless { it.equals(name, ignoreCase = true) }, "$name left")
	}

	private fun commitPending() {
		val p = pending ?: return
		pending = null
		if (p.names.isEmpty()) return
		set(withSelf(p.names.toList()), p.leader, "/pl list")
	}

	private fun withSelf(names: List<String>): List<String> {
		val out = LinkedHashMap<String, String>()
		for (n in listOf(self) + names) out.putIfAbsent(n.lowercase(), n)
		return out.values.toList()
	}

	private fun set(newMembers: List<String>, newLeader: String?, reason: String) {
		val normalized = if (newMembers.size <= 1) emptyList() else newMembers
		val lead = if (normalized.isEmpty()) null else newLeader
		val changed = normalized != members
		members = normalized
		leader = lead
		if (changed) {
			log.info("Party changed ({}): members={} leader={}", reason, normalized, lead)
			for (l in listeners.toList()) runCatching { l(normalized) }.onFailure { log.warn("Party listener failed", it) }
		}
	}

	/** Human-readable state for `/sp party`. */
	fun describe(): List<String> {
		if (!inParty) return listOf("§7You are not in a party §8(as far as chat told us). §7Run §f/sp party refresh§7 to check.")
		val lines = mutableListOf("§fParty §7(${members.size})§8 – room §7${roomId()}")
		for (m in members) {
			val tag = when {
				m.equals(leader, ignoreCase = true) -> " §6★ Leader"
				m.equals(self, ignoreCase = true) -> " §8(you)"
				else -> ""
			}
			lines += " §8• §f$m$tag"
		}
		return lines
	}

	/** Test hook: forget everything. */
	fun reset() {
		pending = null
		set(emptyList(), null, "reset")
	}
}
