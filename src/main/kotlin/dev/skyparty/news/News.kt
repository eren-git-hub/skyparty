package dev.skyparty.news

import dev.skyparty.Skyparty
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.HubTabs
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SkyBlock news from the public Hypixel forum RSS feeds (no account or key needed):
 *  - SkyBlock Patch Notes forum (every live patch)
 *  - SkyBlock Alpha Network forum, only the official posts by "Hypixel Team" (alpha patch notes)
 *  - News and Announcements, filtered to SkyBlock posts (major updates)
 *
 * The feeds' pubDate is the thread's LAST activity, not its release, so patch dates come from the title
 * ("[October 29] …") whenever there is one.
 */
object News {
	enum class Kind(val label: String, val color: Int) {
		PATCH("Patch Notes", 0xFF7CE38B.toInt()),
		ALPHA("Alpha", 0xFFFF7EB6.toInt()),
		UPDATE("Update", 0xFFFFC94D.toInt()),
	}

	data class Item(val title: String, val link: String, val date: ZonedDateTime?, val text: String, val kind: Kind)

	private val FEEDS = listOf(
		Kind.PATCH to "https://hypixel.net/forums/skyblock-patch-notes.158/index.rss",
		Kind.ALPHA to "https://hypixel.net/skyblock-alpha/index.rss",
		Kind.UPDATE to "https://hypixel.net/forums/news-and-announcements.4/index.rss",
	)
	private const val CACHE_MS = 15 * 60_000L
	private val TITLE_DATE = Regex("^\\[(January|February|March|April|May|June|July|August|September|October|November|December) (\\d{1,2})(?:st|nd|rd|th)?]", RegexOption.IGNORE_CASE)

	private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build() }

	@Volatile var items: List<Item> = emptyList(); private set
	@Volatile var loading = false; private set
	@Volatile var error: String? = null; private set
	private var fetchedAt = 0L

	fun ensureLoaded(force: Boolean = false) {
		if (loading) return
		if (!force && items.isNotEmpty() && System.currentTimeMillis() - fetchedAt < CACHE_MS) return
		loading = true
		error = null
		// Each feed may fail on its own; show whatever arrived.
		val futures = FEEDS.map { (kind, url) ->
			fetch(url).thenApply { parse(it, kind) }.exceptionally { t ->
				Skyparty.LOGGER.warn("News feed {} failed: {}", kind, t.message); null
			}
		}
		CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
			val parts = futures.map { it.getNow(null) }
			val list = parts.filterNotNull().flatten().sortedWith(compareByDescending<Item> { it.date })
			Minecraft.getInstance().execute {
				loading = false
				if (parts.all { it == null }) error = "Couldn't load the Hypixel forums. Are you offline?"
				else {
					items = list
					fetchedAt = System.currentTimeMillis()
				}
			}
		}
	}

	private fun fetch(url: String): CompletableFuture<String> {
		val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
			.header("User-Agent", "Skyparty-Mod").GET().build()
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply {
			if (it.statusCode() != 200) error("HTTP ${it.statusCode()}")
			it.body()
		}
	}

	private fun parse(xml: String, kind: Kind): List<Item> {
		val factory = DocumentBuilderFactory.newInstance().apply {
			setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			isNamespaceAware = false
		}
		val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
		val nodes = doc.getElementsByTagName("item")
		val out = ArrayList<Item>()
		for (i in 0 until nodes.length) {
			val e = nodes.item(i) as Element
			fun tag(name: String) = e.getElementsByTagName(name).item(0)?.textContent?.trim() ?: ""
			val title = tag("title")
			val creator = tag("dc:creator")
			when (kind) {
				Kind.ALPHA -> if (!creator.equals("Hypixel Team", ignoreCase = true) && !TITLE_DATE.containsMatchIn(title)) continue
				Kind.UPDATE -> if (!title.contains("skyblock", ignoreCase = true)) continue
				Kind.PATCH -> {}
			}
			val pub = runCatching { ZonedDateTime.parse(tag("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
			val body = tag("content:encoded").ifBlank { tag("description") }
			out += Item(title, tag("link"), titleDate(title, pub) ?: pub, htmlToText(body), kind)
		}
		return out
	}

	/** "[October 29] …" → that day, in the year that keeps it at or before the feed's activity date. */
	fun titleDate(title: String, pub: ZonedDateTime?): ZonedDateTime? {
		val m = TITLE_DATE.find(title) ?: return null
		val month = java.time.Month.valueOf(m.groupValues[1].uppercase())
		val day = m.groupValues[2].toInt()
		val ref = pub ?: ZonedDateTime.now(java.time.ZoneOffset.UTC)
		return runCatching {
			var d = ZonedDateTime.of(ref.year, month.value, day, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
			if (d.isAfter(ref.plusDays(2))) d = d.minusYears(1)
			d
		}.getOrNull()
	}
	/** Very small HTML → text: line breaks for block tags and list items, entities decoded. */
	fun htmlToText(html: String): String = html
		.replace(Regex("(?i)<br\\s*/?>"), "\n")
		.replace(Regex("(?i)</(p|div|h\\d|ul|ol)>"), "\n")
		.replace(Regex("(?i)<li[^>]*>"), "\n• ")
		.replace(Regex("<[^>]+>"), "")
		.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
		.replace("&quot;", "\"")
		.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { if (it == 8203) "" else String(Character.toChars(it)) } ?: "" }
		.replace(Regex("[ \\t]+"), " ")
		.replace(Regex("\\n\\s*\\n+"), "\n")
		.trim()
}

class NewsScreen : GameScreen("News") {
	override val backToHub = false
	private var filter: News.Kind? = null
	private var selected = 0
	private var listScroll = 0
	private var textScroll = 0
	private var listBox = intArrayOf(0, 0, 0, 0)
	private var maxText = 0

	init { News.ensureLoaded() }

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = hubChrome(g, HubTabs.NEWS)
		// Filter chips
		var fx = 8
		val fy = top + 5
		for (k in listOf<News.Kind?>(null) + News.Kind.entries) {
			val label = k?.label ?: "All"
			val w = font.width(label) + 12
			val on = filter == k
			button(g, fx, fy, w, 13, label, mouseX, mouseY, color = if (on) (k?.color ?: Theme.accent) else 0xFF3A3050.toInt()) {
				filter = k; selected = 0; listScroll = 0; textScroll = 0
			}
			fx += w + 3
		}
		button(g, width - 70, fy, 62, 13, "↻ Refresh", mouseX, mouseY) { News.ensureLoaded(force = true) }

		val y = fy + 18
		val h = height - y - 8
		val items = News.items.filter { filter == null || it.kind == filter }
		if (items.isEmpty()) {
			val msg = when {
				News.loading -> "§7Loading news from the Hypixel forums…"
				News.error != null -> "§c${News.error}"
				else -> "§7Nothing here yet."
			}
			centered(g, msg, width / 2, y + h / 2, Theme.TEXT)
			return
		}
		selected = selected.coerceIn(0, items.size - 1)

		// List
		val lw = (width * 0.38f).toInt().coerceIn(150, 230)
		listBox = intArrayOf(8, y, lw, h)
		val rowH = 26
		listScroll = listScroll.coerceIn(0, (items.size * rowH - h).coerceAtLeast(0))
		g.enableScissor(8, y, 8 + lw, y + h)
		for ((i, it) in items.withIndex()) {
			val ry = y + i * rowH - listScroll
			if (ry + rowH < y || ry > y + h) continue
			val on = i == selected
			val hover = mouseY in y until y + h && hovered(8, ry, lw, rowH - 2, mouseX, mouseY)
			g.fill(8, ry, 8 + lw, ry + rowH - 2, if (on) GameColors.CARD_HOVER else if (hover) 0xF0201A2C.toInt() else GameColors.CARD)
			g.fill(8, ry, 10, ry + rowH - 2, it.kind.color)
			g.text(font, font.plainSubstrByWidth(it.title, lw - 12), 14, ry + 3, Theme.TEXT, false)
			val date = it.date?.format(DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)) ?: ""
			g.text(font, "§8$date · ", 14, ry + 13, Theme.TEXT, false)
			g.text(font, it.kind.label, 14 + font.width("§8$date · "), ry + 13, it.kind.color, false)
			if (mouseY in y until y + h) clickArea(8, ry, lw, rowH - 2) { selected = i; textScroll = 0 }
		}
		g.disableScissor()

		// Detail
		val item = items[selected]
		val dx = 8 + lw + 8
		val dw = width - dx - 8
		panel(g, dx, y, dw, h)
		var ty = y + 6
		for (line in font.split(Component.literal(item.title), dw - 12)) {
			g.text(font, line, dx + 6, ty, item.kind.color, true); ty += 10
		}
		button(g, dx + dw - 104, ty + 1, 98, 13, "Open in browser", mouseX, mouseY, hint = item.link) {
			runCatching { Util.getPlatform().openUri(item.link) }
		}
		g.text(font, "§8" + (item.date?.format(DateTimeFormatter.ofPattern("EEEE, MMM d yyyy", java.util.Locale.ENGLISH)) ?: ""), dx + 6, ty + 4, Theme.TEXT, false)
		ty += 18
		g.fill(dx + 6, ty - 3, dx + dw - 6, ty - 2, GameColors.LINE)
		val lines = item.text.split('\n').flatMap { font.split(Component.literal(it), dw - 14) }
		val textH = y + h - ty - 4
		maxText = (lines.size * 10 - textH).coerceAtLeast(0)
		textScroll = textScroll.coerceIn(0, maxText)
		g.enableScissor(dx, ty, dx + dw, y + h - 4)
		for ((i, l) in lines.withIndex()) {
			val ly = ty + i * 10 - textScroll
			if (ly < ty - 10 || ly > y + h) continue
			g.text(font, l, dx + 7, ly, ColorUtil.withAlpha(Theme.TEXT, 0xE0), false)
		}
		g.disableScissor()
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		val (x, y, w, h) = listBox
		if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) listScroll = (listScroll - (scrollY * 26).toInt()).coerceAtLeast(0)
		else textScroll = (textScroll - (scrollY * 20).toInt()).coerceIn(0, maxText)
		return true
	}
}
