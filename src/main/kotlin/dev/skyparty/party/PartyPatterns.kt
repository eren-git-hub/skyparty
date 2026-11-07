package dev.skyparty.party

/**
 * Every Hypixel party chat line PartyTracker understands, in one place.
 * Lines are matched after [dev.skyparty.util.Chat.strip] and trim. All patterns are anchored, so
 * player chat ("Name: … joined the party.", "Party > Name: …") can never match.
 *
 * VERIFIED = format confirmed from Hypixel/open-source mods; UNVERIFIED = best guess, needs a real log.
 */
object PartyPatterns {
	/** Optional rank prefix + Minecraft username. Group 1 = name. */
	private const val NAME = "(?:\\[[^\\]]{1,20}] )?([A-Za-z0-9_]{1,16})"

	// --- /pl output block -----------------------------------------------------------------------
	/** VERIFIED: "Party Members (3)" starts the /pl block. */
	val LIST_HEADER = Regex("^Party Members \\((\\d+)\\)$")
	/** VERIFIED: "Party Leader: [MVP+] Name ●" */
	val LIST_LEADER = Regex("^Party Leader: (.+)$")
	/** VERIFIED: "Party Moderators: [VIP] A ● B ●" */
	val LIST_MODS = Regex("^Party Moderators: (.+)$")
	/** VERIFIED: "Party Members: [MVP++] A ● B ●" */
	val LIST_MEMBERS = Regex("^Party Members: (.+)$")
	/** VERIFIED: the block is framed by a long line of dashes (strikethrough formatting stripped). */
	val SEPARATOR = Regex("^[-▬]{10,}$")

	// --- membership changes ---------------------------------------------------------------------
	/** VERIFIED: "[MVP+] Name joined the party." */
	val JOINED = Regex("^$NAME joined the party\\.$")
	/** VERIFIED: "[VIP] Name has left the party." */
	val LEFT = Regex("^$NAME has left the party\\.$")
	/** VERIFIED: "[VIP] Name has been removed from the party." */
	val REMOVED = Regex("^$NAME has been removed from the party\\.$")
	/** VERIFIED: "You left the party." */
	val SELF_LEFT = Regex("^You left the party\\.$")
	/** VERIFIED: "The party was disbanded because all invites expired and the party was empty." (and variants) */
	val DISBANDED = Regex("^The party was disbanded.*$")
	/** VERIFIED: "[MVP+] Name has disbanded the party!" */
	val DISBANDED_BY = Regex("^$NAME has disbanded the party!$")
	/** VERIFIED: "You have joined [MVP+] Name's party!" */
	val SELF_JOINED = Regex("^You have joined $NAME's party!$")
	/** UNVERIFIED: "You'll be partying with: [VIP] A, [MVP+] B" (sent after joining). */
	val PARTYING_WITH = Regex("^You'll be partying with: (.+)$")
	/** VERIFIED: "You have been kicked from the party by [MVP+] Name" */
	val SELF_KICKED = Regex("^You have been kicked from the party by .+$")
	/** VERIFIED: "You are not currently in a party." (reply to /pl) */
	val NOT_IN_PARTY = Regex("^You are not (?:currently )?in a party\\.?$")
	/** UNVERIFIED: "Kicked [VIP] A, [VIP] B because they were offline." */
	val KICKED_OFFLINE = Regex("^Kicked (.+) because they were offline\\.$")
	/** UNVERIFIED (Dungeon Party Finder): "Party Finder > Name joined the dungeon group! (Mage Level 30)" */
	val FINDER_JOINED = Regex("^Party Finder > $NAME joined the (?:dungeon )?group!.*$")

	// --- leadership -----------------------------------------------------------------------------
	/** VERIFIED: "The party was transferred to [MVP+] B by [VIP] A" / "… because [VIP] A left" */
	val TRANSFER = Regex("^The party was transferred to $NAME (?:by|because) .+$")
	/** UNVERIFIED: "[VIP] A has promoted [MVP+] B to Party Leader" */
	val PROMOTED_LEADER = Regex("^$NAME has promoted $NAME to Party Leader$")

	private val RANK = Regex("\\[[^\\]]*]")
	private val USERNAME = Regex("[A-Za-z0-9_]{1,16}")

	/** "[MVP+] Name ●" → "Name"; null when no valid username is left. */
	fun cleanName(raw: String): String? {
		val s = raw.replace(RANK, " ").replace("●", " ").replace("◎", " ").trim()
		val token = s.split(' ').lastOrNull { it.isNotBlank() } ?: return null
		return token.takeIf { USERNAME.matches(it) }
	}

	/** Splits "A ● [VIP] B ●" or "A, [VIP] B" into clean names. */
	fun names(list: String): List<String> =
		list.split('●', ',', '◎').mapNotNull { cleanName(it) }
}
