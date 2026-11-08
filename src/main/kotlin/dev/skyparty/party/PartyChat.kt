package dev.skyparty.party

import dev.skyparty.module.impl.party.PartyChatModule
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

/**
 * Hypixel party chat ("Party > [MVP+] Name: text") collected from chat, for the small party chat box.
 * Shown only inside Skyparty menus and games, bottom right (see GameScreen), never in the world. Writing sends "/pc <text>" — only when the player presses Enter.
 */
object PartyChat {
	class Line(val name: String, val text: String, val at: Long)

	private val PARTY = Regex("^Party > (?:\\[[^\\]]+] )?([A-Za-z0-9_]{1,16})[^:]*: (.*)$")
	const val MAX_LINES = 40
	const val MAX_INPUT = 200

	val lines = ArrayDeque<Line>()
	/** Messages since the chat panel was last opened. */
	var unread = 0
		private set

	private var initialized = false

	fun init() {
		if (initialized) return
		initialized = true
		ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) onChat(Chat.strip(message.string)) }
	}

	/** Feeds one stripped chat line (public for gametests). */
	fun onChat(raw: String) {
		for (line in raw.lines()) {
			val m = PARTY.matchEntire(line.trim()) ?: continue
			lines.addLast(Line(m.groupValues[1], m.groupValues[2].take(256), System.currentTimeMillis()))
			while (lines.size > MAX_LINES) lines.removeFirst()
			unread++
		}
	}

	/** Whether the chat window is unfolded (kept across Skyparty screens). */
	var windowOpen = false

	fun markRead() {
		unread = 0
	}

	/** Sends [text] to party chat. */
	fun send(text: String) {
		val t = text.replace("§", "").trim().take(MAX_INPUT)
		if (t.isNotEmpty()) Chat.command("pc $t")
	}

	val visible: Boolean
		get() = PartyChatModule.enabled && (!PartyChatModule.onlyInParty.value || PartyTracker.inParty || lines.isNotEmpty())

	fun format(l: Line): String = (if (l.name.equals(dev.skyparty.net.Net.selfName, ignoreCase = true)) "§b" else "§e") + l.name + "§7: §f" + l.text

}
