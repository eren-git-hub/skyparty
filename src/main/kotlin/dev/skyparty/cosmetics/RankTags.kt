package dev.skyparty.cosmetics

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.Optional

/**
 * Replaces a player's Hypixel rank ("[MVP+] Name") with a custom tag ("[LARP] Name") for the players in
 * cosmetics.json "ranks". Purely visual and only on screens of players with the mod: chat lines, the tab list
 * and name tags (the last two through small mixins calling [apply]). Nothing is sent anywhere.
 *
 * Only the rank characters are cut out and the tag is put in their place; every other part keeps its own
 * style (RGB colors like the SkyBlock level color, click and hover actions).
 */
object RankTags {
	private val regexCache = HashMap<String, Regex>()
	private val LEGACY = Regex("§[0-9a-fk-orA-FK-OR]")

	fun init() {
		ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ -> apply(message) }
	}

	/** One styled piece of text (its string may still hold § codes). */
	private class Seg(val style: Style, var text: String)

	/** [text] with tagged players' ranks swapped; the same instance when nothing matches (cheap check first). */
	@JvmStatic
	fun apply(text: Component): Component {
		val tags = CosmeticOwners.rankTags
		if (tags.isEmpty()) return text
		val plainAll = text.string
		if (!plainAll.contains('[')) return text
		val lower = plainAll.lowercase()
		val hits = tags.filterKeys { lower.contains(it) }
		if (hits.isEmpty()) return text

		val segs = mutableListOf<Seg>()
		text.visit(FormattedText.StyledContentConsumer<Unit> { style, s ->
			if (s.isNotEmpty()) segs += Seg(style, s)
			Optional.empty()
		}, Style.EMPTY)

		var changed = false
		for ((name, tag) in hits) {
			val re = regexCache.getOrPut(name) {
				// The rank right before the name: "[VIP]" … "[MVP++]", then spaces and the name as a whole word.
				Regex("\\[(?:VIP|MVP)\\+*](?=\\s+${Regex.escape(name)}(?![A-Za-z0-9_]))", RegexOption.IGNORE_CASE)
			}
			// Replace every occurrence (rebuilding the index map after each, it changes the pieces).
			repeat(4) {
				val (plain, map) = plainMap(segs)
				val m = re.find(plain) ?: return@repeat
				replace(segs, map, m.range.first, m.range.last + 1, tag)
				changed = true
			}
		}
		if (!changed) return text
		val out: MutableComponent = Component.empty()
		for (s in segs) if (s.text.isNotEmpty()) out.append(Component.literal(s.text).withStyle(s.style))
		return out
	}

	/** Visible text of all pieces (§ codes left out) and, per visible char, its piece and index. */
	private fun plainMap(segs: List<Seg>): Pair<String, List<Pair<Int, Int>>> {
		val sb = StringBuilder()
		val map = ArrayList<Pair<Int, Int>>()
		for ((si, s) in segs.withIndex()) {
			var i = 0
			while (i < s.text.length) {
				if (s.text[i] == '§' && i + 1 < s.text.length) { i += 2; continue }
				sb.append(s.text[i])
				map += si to i
				i++
			}
		}
		return sb.toString() to map
	}

	/**
	 * Removes the visible chars [from, to) and puts [tag] where the first one was. § codes inside the rank stay
	 * where they were, so the name keeps its color; the part after the tag gets the codes active before it.
	 */
	private fun replace(segs: MutableList<Seg>, map: List<Pair<Int, Int>>, from: Int, to: Int, tag: String) {
		val (firstSeg, firstIdx) = map[from]
		// Delete from the back so indices of earlier chars stay valid.
		for (k in (from until to).reversed()) {
			val (si, ci) = map[k]
			val t = segs[si].text
			segs[si].text = t.removeRange(ci, ci + 1)
		}
		val seg = segs[firstSeg]
		val before = seg.text.substring(0, firstIdx)
		val after = seg.text.substring(firstIdx)
		val carry = activeCodes(before)
		segs[firstSeg] = Seg(seg.style, before)
		segs.add(firstSeg + 1, Seg(Style.EMPTY, tag))
		segs.add(firstSeg + 2, Seg(seg.style, carry + after))
	}

	/** The § color/format codes in effect at the end of [s] (a color code resets the formats before it). */
	private fun activeCodes(s: String): String {
		var color = ""
		val formats = StringBuilder()
		for (m in LEGACY.findAll(s)) {
			val c = m.value[1].lowercaseChar()
			when {
				c in '0'..'9' || c in 'a'..'f' -> { color = m.value; formats.clear() }
				c == 'r' -> { color = ""; formats.clear() }
				else -> formats.append(m.value)
			}
		}
		return color + formats
	}

	/** The component as one string with § codes (tests and debugging). */
	fun toLegacy(text: Component): String {
		val sb = StringBuilder()
		text.visit(FormattedText.StyledContentConsumer<Unit> { style, s ->
			if (s.isNotEmpty()) {
				sb.append("§r")
				style.color?.let { c -> sb.append(COLOR_CODES[c.serialize()]?.let { "§$it" } ?: "§#${c.serialize().removePrefix("#")}") }
				sb.append(s)
			}
			Optional.empty()
		}, Style.EMPTY)
		return sb.toString()
	}

	private val COLOR_CODES = listOf(
		"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
		"dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
	).withIndex().associate { (i, n) -> n to "0123456789abcdef"[i] }
}
