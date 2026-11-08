package dev.skyparty.mayor

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.HubTabs
import dev.skyparty.ui.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Current SkyBlock mayor (+ minister) and the running election, from the public Hypixel endpoint
 * GET https://api.hypixel.net/v2/resources/skyblock/election (no API key). Cached for a few minutes.
 *
 * Timing (SkyBlock calendar, 1 year = 124 h): the election for year Y opens on Late Summer 27 of Y and
 * closes on Late Spring 27 of Y+1, when the winner takes office.
 */
object Mayor {
	class Perk(val name: String, val description: String, val minister: Boolean)
	class Candidate(val key: String, val name: String, val perks: List<Perk>, val votes: Long)
	class Data(
		val mayor: Candidate,
		val minister: Candidate?,
		val lastElection: List<Candidate>,
		val lastYear: Int,
		val current: List<Candidate>?,
		val currentYear: Int?,
	)

	private const val URL = "https://api.hypixel.net/v2/resources/skyblock/election"
	private const val CACHE_MS = 5 * 60_000L

	// SkyBlock calendar
	private const val EPOCH = 1_560_275_700_000L // Year 1, Early Spring 1
	private const val DAY_MS = 20 * 60_000L
	private const val MONTH_MS = 31 * DAY_MS
	private const val YEAR_MS = 12 * MONTH_MS

	private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build() }

	@Volatile var data: Data? = null
		private set
	@Volatile var loading = false
		private set
	@Volatile var error: String? = null
		private set
	private var fetchedAt = 0L

	fun ensureLoaded(force: Boolean = false) {
		if (loading || (!force && data != null && System.currentTimeMillis() - fetchedAt < CACHE_MS)) return
		loading = true
		error = null
		val req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Skyparty-Mod").GET().build()
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
			.thenApply { if (it.statusCode() != 200) error("HTTP ${it.statusCode()}"); parse(it.body()) }
			.whenComplete { d, t ->
				Minecraft.getInstance().execute {
					loading = false
					if (t != null || d == null) {
						Skyparty.LOGGER.warn("Election fetch failed: {}", t?.message)
						error = "Couldn't reach the Hypixel API. Are you offline?"
					} else {
						data = d
						fetchedAt = System.currentTimeMillis()
					}
				}
			}
	}

	private fun parse(body: String): Data {
		val root = JsonParser.parseString(body).asJsonObject
		if (root["success"]?.asBoolean != true) error("success=false")
		val m = root.getAsJsonObject("mayor")
		val mayor = candidate(m)
		val minister = m.getAsJsonObject("minister")?.let { mo ->
			val perk = mo.getAsJsonObject("perk")
			Candidate(mo["key"].asString, mo["name"].asString, listOfNotNull(perk?.let { perk(it) }), 0)
		}
		val election = m.getAsJsonObject("election")
		val current = root.getAsJsonObject("current")
		return Data(
			mayor, minister,
			election?.getAsJsonArray("candidates")?.map { candidate(it.asJsonObject) } ?: emptyList(),
			election?.get("year")?.asInt ?: 0,
			current?.getAsJsonArray("candidates")?.map { candidate(it.asJsonObject) },
			current?.get("year")?.asInt,
		)
	}

	private fun candidate(o: JsonObject) = Candidate(
		o["key"]?.asString ?: "", o["name"]?.asString ?: "?",
		o.getAsJsonArray("perks")?.map { perk(it.asJsonObject) } ?: emptyList(),
		o["votes"]?.asLong ?: 0,
	)

	private fun perk(o: JsonObject) = Perk(o["name"]?.asString ?: "?", o["description"]?.asString ?: "", o["minister"]?.asBoolean ?: false)

	// ------------------------------------------------------------------ calendar

	/** SkyBlock year right now (1-based). */
	fun currentYear(now: Long = System.currentTimeMillis()) = ((now - EPOCH) / YEAR_MS + 1).toInt()

	/** Late Spring 27 of [year]: when the election of year-1 closes and the new mayor takes office. */
	fun termStart(year: Int) = EPOCH + (year - 1) * YEAR_MS + 2 * MONTH_MS + 26 * DAY_MS

	/** Late Summer 27 of [year]: the election for that year opens. */
	fun electionOpens(year: Int) = EPOCH + (year - 1) * YEAR_MS + 5 * MONTH_MS + 26 * DAY_MS

	fun electionCloses(year: Int) = termStart(year + 1)

	/** The next election end (= end of the current term) after [now]. */
	fun nextTermChange(now: Long = System.currentTimeMillis()): Long {
		val y = currentYear(now)
		return if (now < termStart(y)) termStart(y) else termStart(y + 1)
	}

	fun formatIn(ms: Long): String {
		val m = (ms / 60_000).coerceAtLeast(0)
		return when {
			m >= 1440 -> "${m / 1440}d ${m / 60 % 24}h"
			m >= 60 -> "${m / 60}h ${m % 60}m"
			else -> "${m}m"
		}
	}

	// ------------------------------------------------------------------ looks

	/** Theme color per mayor type. */
	fun color(key: String): Int = when (key) {
		"mining" -> 0xFF6FD3FF
		"farming" -> 0xFF8BDC4F
		"fishing" -> 0xFF4AB3FF
		"dungeons" -> 0xFFE0524F
		"slayer" -> 0xFFB04FE0
		"pets" -> 0xFFFF9EC8
		"economist" -> 0xFFFFC94A
		"events" -> 0xFFFF9A3C
		"shady", "derpy" -> 0xFFC9D1DB
		else -> 0xFFE8C99A
	}.toInt()

	private val ICONS = mapOf(
		"mining" to "diamond_pickaxe", "farming" to "golden_hoe", "fishing" to "fishing_rod", "dungeons" to "wither_skeleton_skull",
		"slayer" to "golden_sword", "pets" to "bone", "economist" to "gold_ingot", "events" to "cake", "shady" to "ender_eye",
		"derpy" to "pufferfish", "jerry" to "snowball", "scorpius" to "nether_star",
	)

	private val iconCache = HashMap<String, ItemStack>()

	fun icon(key: String): ItemStack = iconCache.getOrPut(key) {
		val id = Identifier.tryParse("minecraft:" + (ICONS[key] ?: "emerald"))
		val item = id?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.EMERALD
		ItemStack(item)
	}
}

class MayorScreen : GameScreen("Mayor") {
	override val backToHub = false
	private var scroll = 0
	private var maxScroll = 0

	init { Mayor.ensureLoaded() }

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = hubChrome(g, HubTabs.MAYOR)
		val d = Mayor.data
		if (d == null) {
			centered(g, if (Mayor.loading) "§7Loading the election from Hypixel…" else "§c${Mayor.error ?: "No data"}", width / 2, height / 2, Theme.TEXT)
			if (!Mayor.loading) button(g, width / 2 - 30, height / 2 + 14, 60, 14, "Retry", mouseX, mouseY) { Mayor.ensureLoaded(force = true) }
			return
		}
		val y = top + 6
		val h = height - y - 8
		val lw = ((width - 24) * 0.46f).toInt()
		drawMayor(g, d, 8, y, lw, h)
		drawElection(g, d, 8 + lw + 8, y, width - lw - 24, h, mouseX, mouseY)
	}

	/** Left: the mayor in office, the minister and all their perks. */
	private fun drawMayor(g: GuiGraphicsExtractor, d: Mayor.Data, x: Int, y: Int, w: Int, h: Int) {
		panel(g, x, y, w, h)
		val m = d.mayor
		val color = Mayor.color(m.key)
		g.fill(x, y, x + w, y + 2, color)
		item(g, Mayor.icon(m.key), x + 8, y + 8, 2f)
		g.text(font, "§7Mayor", x + 46, y + 8, Theme.TEXT, false)
		bigText(g, m.name, x + 46 + font.width(m.name), y + 19, 2f, color)
		g.text(font, "§8Term ends in §7${Mayor.formatIn(Mayor.nextTermChange() - System.currentTimeMillis())}", x + 8, y + 42, Theme.TEXT, false)
		var ty = y + 56
		for (p in m.perks) ty = perk(g, p, x + 8, ty, w - 16, color, y + h - 4)
		val min = d.minister
		if (min != null && ty + 30 < y + h) {
			ty += 2
			g.fill(x + 8, ty, x + w - 8, ty + 1, GameColors.LINE)
			ty += 6
			val mc = Mayor.color(min.key)
			item(g, Mayor.icon(min.key), x + 8, ty - 2, 1f)
			g.text(font, "§7Minister §f§l${min.name}", x + 28, ty + 2, Theme.TEXT, false)
			ty += 16
			for (p in min.perks) ty = perk(g, p, x + 8, ty, w - 16, mc, y + h - 4)
		}
	}

	/** Drops symbols the Minecraft font cannot draw (Hypixel uses private glyphs; they show as boxes). */
	private fun clean(s: String) = s.filter { c ->
		val b = Character.UnicodeBlock.of(c)
		b != Character.UnicodeBlock.PRIVATE_USE_AREA && b != Character.UnicodeBlock.SUPPLEMENTAL_PUNCTUATION
	}

	/** One perk: name in the mayor's color, description wrapped below. Returns the next y. */
	private fun perk(g: GuiGraphicsExtractor, p: Mayor.Perk, x: Int, y: Int, w: Int, color: Int, bottom: Int): Int {
		if (y + 10 > bottom) return y
		g.text(font, "§l" + p.name, x, y, color, false)
		var ty = y + 10
		for (line in font.split(Component.literal("§7" + clean(p.description)), w - 6)) {
			if (ty + 9 > bottom) return ty
			g.text(font, line, x + 4, ty, Theme.TEXT, false)
			ty += 9
		}
		return ty + 4
	}

	/** Right: the running election (votes so far) or, between elections, the last result and when the next opens. */
	private fun drawElection(g: GuiGraphicsExtractor, d: Mayor.Data, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
		val now = System.currentTimeMillis()
		val running = d.current != null && d.currentYear != null
		val list = (if (running) d.current!! else d.lastElection).sortedByDescending { it.votes }
		val title = if (running) "Election · Year ${d.currentYear}" else "Last election · Year ${d.lastYear}"
		panel(g, x, y, w, h, title)
		val sub = if (running) "§aOpen §7· closes in §f${Mayor.formatIn(Mayor.electionCloses(d.currentYear!!) - now)}"
		else "§7Next election opens in §f${Mayor.formatIn(Mayor.electionOpens(Mayor.currentYear(now)).let { if (it < now) Mayor.electionOpens(Mayor.currentYear(now) + 1) else it } - now)}"
		g.text(font, sub, x + 6, y + 20, Theme.TEXT, false)
		button(g, x + w - 20, y + 3, 14, 11, "↻", mx, my, hint = "Refresh") { Mayor.ensureLoaded(force = true) }

		val total = list.sumOf { it.votes }.coerceAtLeast(1)
		val top = y + 32
		val bottom = y + h - 15 // room for the legend below the list
		g.enableScissor(x, top, x + w, bottom)
		var cy = top - scroll
		for ((i, c) in list.withIndex()) {
			val color = Mayor.color(c.key)
			val rowStart = cy
			item(g, Mayor.icon(c.key), x + 6, cy, 1f)
			val pct = c.votes * 100.0 / total
			val lead = if (i == 0) " §6♛" else ""
			g.text(font, "§f§l${c.name}$lead", x + 26, cy + 1, Theme.TEXT, false)
			val pctText = "%.1f%%".format(java.util.Locale.ROOT, pct)
			g.text(font, "§7$pctText", x + w - 8 - font.width(pctText), cy + 1, Theme.TEXT, false)
			// Vote bar
			val bx = x + 26
			val bw = w - 34
			g.fill(bx, cy + 11, bx + bw, cy + 14, 0xFF2A2A36.toInt())
			g.fill(bx, cy + 11, bx + (bw * pct / 100).toInt().coerceAtLeast(1), cy + 14, color)
			cy += 17
			for (p in c.perks) {
				val star = if (p.minister) " §e★" else ""
				g.text(font, font.plainSubstrByWidth("§7• ${p.name}", w - 40) + star, x + 28, cy, Theme.TEXT, false)
				if (my in top until bottom && hovered(x + 26, cy - 1, w - 32, 10, mx, my)) tooltip("${p.name}: ${p.description.replace(Regex("§."), "")}")
				cy += 10
			}
			cy += 5
			if (i < list.size - 1) g.fill(x + 6, cy - 3, x + w - 6, cy - 2, GameColors.LINE)
			if (rowStart > bottom) break
		}
		g.disableScissor()
		maxScroll = (cy + scroll - bottom).coerceAtLeast(0)
		scroll = scroll.coerceIn(0, maxScroll)
		g.fill(x + 6, bottom + 1, x + w - 6, bottom + 2, GameColors.LINE)
		g.text(font, "§e★ §8= minister perk §7· §8scroll for more", x + 6, bottom + 4, Theme.TEXT, false)
	}

	private fun item(g: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int, scale: Float) {
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(x.toFloat(), y.toFloat())
		pose.scale(scale, scale)
		g.item(stack, 0, 0)
		pose.popMatrix()
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		scroll = (scroll - (scrollY * 20).toInt()).coerceIn(0, maxScroll)
		return true
	}
}
