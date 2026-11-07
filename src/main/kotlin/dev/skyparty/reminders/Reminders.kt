package dev.skyparty.reminders

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import dev.skyparty.Skyparty
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.HubTabs
import dev.skyparty.ui.Theme
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.nio.file.Files

/**
 * Reminders: "remind me in 30m to check my minions". Real-time based and saved to disk, so they survive
 * restarts (a reminder that came due while the game was closed fires on the next start). Optional repeat.
 *
 * Commands: /sp remind <time> <text>, /sp remind every <time> <text>, /sp reminders
 * Time format: 90s, 15m, 1h30m, 2h, 1d
 */
object Reminders {
	class Reminder(val id: Long, val text: String, var at: Long, val repeatMs: Long)

	private val file = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("reminders.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()
	val list = mutableListOf<Reminder>()
	private var ticks = 0
	private var pendingScreen = false

	fun init() {
		load()
		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			if (pendingScreen) { pendingScreen = false; mc.gui.setScreen(RemindersScreen()) }
			if (++ticks % 20 == 0 && mc.player != null) check()
		}
		// Greedy argument so clock times like 18:30 work (brigadier words can't contain ':').
		fun args(repeat: Boolean) = ClientCommands.argument("args", StringArgumentType.greedyString()).executes { ctx ->
			addFromCommand(StringArgumentType.getString(ctx, "args"), repeat); 1
		}
		SkypartyCommands.sub {
			ClientCommands.literal("remind")
				.then(ClientCommands.literal("every").then(args(repeat = true)))
				.then(ClientCommands.literal("at").then(args(repeat = false)))
				.then(args(repeat = false))
				.executes { Chat.info("§7Usage: §f/sp remind <30m | 18:30> <text> §8· §f/sp remind every <2h | 18:30> <text>"); 1 }
		}
		SkypartyCommands.sub { ClientCommands.literal("reminders").executes { pendingScreen = true; 1 } }
	}

	private fun addFromCommand(args: String, repeat: Boolean) {
		val time = args.trim().substringBefore(' ')
		val text = args.trim().substringAfter(' ', "").trim()
		val w = parseWhen(time)
		if (w == null) { Chat.info("§cCouldn't read \"$time\". Try 15m, 1h30m, 18:30 or 6:30pm."); return }
		if (text.isBlank()) { Chat.info("§cWhat should I remind you about? §7/sp remind $time <text>"); return }
		add(text, w, repeat)
		Chat.info("§a⏰ Reminder set: §f$text §7${describe(w.at, w.clock)}" + if (repeat) " §8(repeats)" else "")
	}

	/** When a reminder fires: [at] (epoch ms); [interval] is used for repeats; [clock] = a time of day (repeats daily). */
	class When(val at: Long, val interval: Long, val clock: Boolean)

	private val CLOCK = Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$")

	/** "15m", "1h30m", "90s" → from now; "18:30", "6pm", "6:30pm" → the next time the clock shows that. */
	fun parseWhen(s: String): When? {
		val t = s.trim().lowercase()
		val m = CLOCK.find(t)
		if (m != null && (t.contains(':') || m.groupValues[3].isNotEmpty())) {
			var h = m.groupValues[1].toInt()
			val min = m.groupValues[2].ifEmpty { "0" }.toInt()
			when (m.groupValues[3]) {
				"am" -> if (h == 12) h = 0
				"pm" -> if (h < 12) h += 12
			}
			if (h > 23 || min > 59) return null
			val now = java.time.ZonedDateTime.now()
			var next = now.withHour(h).withMinute(min).withSecond(0).withNano(0)
			if (!next.isAfter(now)) next = next.plusDays(1)
			return When(next.toInstant().toEpochMilli(), 86_400_000L, true)
		}
		val ms = parseDuration(t) ?: return null
		if (ms < 5_000) return null
		return When(System.currentTimeMillis() + ms, ms, false)
	}

	fun add(text: String, w: When, repeat: Boolean) {
		list += Reminder(System.nanoTime(), text.take(80), w.at, if (repeat) w.interval else 0)
		list.sortBy { it.at }
		save()
	}

	fun add(text: String, inMs: Long, repeat: Boolean) = add(text, When(System.currentTimeMillis() + inMs, inMs, false), repeat)

	private val HHMM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

	/** "at 18:30 (in 2h 5m)" / "at 18:30 tomorrow". */
	fun describe(at: Long, clock: Boolean = true): String {
		val z = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
		val today = java.time.LocalDate.now()
		val day = when (z.toLocalDate()) {
			today -> ""
			today.plusDays(1) -> " tomorrow"
			else -> " " + z.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH))
		}
		val left = formatDuration(at - System.currentTimeMillis())
		return if (clock) "at ${z.format(HHMM)}$day §8(in $left)" else "in $left §8(${z.format(HHMM)}$day)"
	}

	fun remove(r: Reminder) { list.remove(r); save() }

	private fun check() {
		val now = System.currentTimeMillis()
		val due = list.filter { it.at <= now }
		if (due.isEmpty()) return
		for (r in due) {
			fire(r)
			if (r.repeatMs > 0) {
				while (r.at <= now) r.at += r.repeatMs
			} else list.remove(r)
		}
		list.sortBy { it.at }
		save()
	}

	private fun fire(r: Reminder) {
		Chat.info("§e§l⏰ Reminder: §f${r.text}")
		val mc = Minecraft.getInstance()
		mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.2f))
		mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.6f))
	}

	/** "1h30m", "15m", "90s", "2d", or a bare number of minutes. */
	fun parseDuration(s: String): Long? {
		val t = s.trim().lowercase()
		t.toLongOrNull()?.let { return it * 60_000 }
		val m = Regex("(\\d+)\\s*([dhms])").findAll(t).toList()
		if (m.isEmpty() || m.sumOf { it.value.length } != t.replace(" ", "").length) return null
		return m.sumOf { val n = it.groupValues[1].toLong(); when (it.groupValues[2]) { "d" -> n * 86_400_000; "h" -> n * 3_600_000; "m" -> n * 60_000; else -> n * 1000 } }
	}

	fun formatDuration(ms: Long): String {
		val s = (ms / 1000).coerceAtLeast(0)
		return when {
			s >= 86_400 -> "${s / 86_400}d ${s / 3600 % 24}h"
			s >= 3600 -> "${s / 3600}h ${s / 60 % 60}m"
			s >= 60 -> "${s / 60}m ${s % 60}s"
			else -> "${s}s"
		}
	}

	private fun load() {
		runCatching {
			if (!Files.exists(file)) return
			JsonParser.parseString(Files.readString(file)).asJsonArray.forEach {
				val o = it.asJsonObject
				list += Reminder(o["id"].asLong, o["text"].asString, o["at"].asLong, o["repeat"]?.asLong ?: 0)
			}
			list.sortBy { it.at }
		}.onFailure { Skyparty.LOGGER.warn("Could not read reminders.json", it) }
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			val arr = JsonArray()
			for (r in list) arr.add(JsonObject().apply {
				addProperty("id", r.id); addProperty("text", r.text); addProperty("at", r.at); addProperty("repeat", r.repeatMs)
			})
			Files.writeString(file, gson.toJson(arr))
		}.onFailure { Skyparty.LOGGER.warn("Could not save reminders.json", it) }
	}
}

class RemindersScreen : GameScreen("Reminders") {
	override val backToHub = false
	private var text = ""
	private var time = "30m"
	private var focus = 0 // 0 = text, 1 = time
	private var repeat = false
	private var atMode = false

	/** The next full half hour, e.g. "18:30". */
	private fun defaultClock(): String {
		val t = java.time.LocalTime.now().plusMinutes(30)
		return "%02d:%02d".format(t.hour, if (t.minute < 30) 30 else 0).let { if (t.minute >= 30) "%02d:00".format((t.hour + 1) % 24) else it }
	}

	private fun clockParts(): Pair<Int, Int> {
		val m = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim()) ?: return 18 to 0
		return m.groupValues[1].toInt().coerceIn(0, 23) to m.groupValues[2].toInt().coerceIn(0, 59)
	}

	private fun setClock(h: Int, m: Int) {
		var hh = h
		var mm = m
		if (mm < 0) { mm += 60; hh-- }
		if (mm >= 60) { mm -= 60; hh++ }
		time = "%02d:%02d".format(Math.floorMod(hh, 24), mm)
	}
	private var error: String? = null

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = hubChrome(g, HubTabs.REMINDERS)
		val w = minOf(360, width - 24)
		val x = width / 2 - w / 2
		var y = top + 8

		// New reminder form: "In" (a duration) or "At" (a time of day)
		panel(g, x, y, w, 84, "New reminder")
		val caret = if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else " "
		field(g, x + 6, y + 21, w - 12, "What should I remind you about?", text + if (focus == 0) caret else "", focus == 0) { focus = 0 }
		val my2 = y + 41
		button(g, x + 6, my2, 24, 14, "In", mouseX, mouseY, color = if (!atMode) Theme.accent else 0xFF3A3050.toInt(), hint = "After a duration") {
			atMode = false; time = "30m"
		}
		button(g, x + 32, my2, 24, 14, "At", mouseX, mouseY, color = if (atMode) Theme.accent else 0xFF3A3050.toInt(), hint = "At a time of day") {
			atMode = true; time = defaultClock()
		}
		var px = x + 62
		if (!atMode) {
			for (p in listOf("5m", "15m", "30m", "1h", "2h", "1d")) {
				button(g, px, my2, 26, 14, p, mouseX, mouseY, color = if (time == p) Theme.accent else 0xFF3A3050.toInt()) { time = p }
				px += 28
			}
			field(g, px + 2, my2, 52, "1h30m", time + if (focus == 1) caret else "", focus == 1) { focus = 1 }
		} else {
			val (h, m) = clockParts()
			button(g, px, my2, 14, 14, "-", mouseX, mouseY) { setClock(h - 1, m) }
			centered(g, "§l%02d".format(h), px + 25, my2 + 3, Theme.TEXT)
			button(g, px + 36, my2, 14, 14, "+", mouseX, mouseY) { setClock(h + 1, m) }
			g.text(font, "§l:", px + 54, my2 + 3, Theme.TEXT, false)
			button(g, px + 60, my2, 14, 14, "-", mouseX, mouseY) { setClock(h, m - 5) }
			centered(g, "§l%02d".format(m), px + 85, my2 + 3, Theme.TEXT)
			button(g, px + 96, my2, 14, 14, "+", mouseX, mouseY) { setClock(h, m + 5) }
			field(g, px + 116, my2, 46, "18:30", time + if (focus == 1) caret else "", focus == 1) { focus = 1 }
		}
		val ry = y + 62
		button(g, x + 6, ry, 92, 14, if (repeat) (if (atMode) "↻ Daily ✔" else "↻ Repeat ✔") else (if (atMode) "↻ Daily" else "↻ Repeat"), mouseX, mouseY,
			color = if (repeat) GameColors.GREEN else 0xFF3A3050.toInt(), hint = if (atMode) "Every day at this time" else "Again after every interval") { repeat = !repeat }
		val preview = Reminders.parseWhen(time)?.let { "§7" + Reminders.describe(it.at, it.clock) } ?: "§cTime like 15m, 1h30m or 18:30"
		error?.let { g.text(font, "§c$it", x + 104, ry + 3, Theme.TEXT, false) } ?: g.text(font, preview, x + 104, ry + 3, Theme.TEXT, false)
		button(g, x + w - 66, ry, 60, 14, "+ Add", mouseX, mouseY, color = GameColors.GREEN) { submit() }
		y += 92

		// List
		val now = System.currentTimeMillis()
		val list = Reminders.list.toList()
		if (list.isEmpty()) {
			centered(g, "§7No reminders yet. Add one above!", width / 2, y + 16, Theme.TEXT)
			return
		}
		for (r in list) {
			if (y + 22 > height - 4) break
			g.fill(x, y, x + w, y + 20, GameColors.CARD)
			g.fill(x, y, x + 2, y + 20, if (r.repeatMs > 0) 0xFF8EC5FF.toInt() else 0xFFFFC94D.toInt())
			g.text(font, "⏰ " + font.plainSubstrByWidth(r.text, (w - 50 - font.width(Reminders.describe(r.at) + " ↻ daily")).coerceAtLeast(40)), x + 8, y + 6, Theme.TEXT, false)
			val left = "§f" + Reminders.describe(r.at) + if (r.repeatMs == 86_400_000L) " §8↻ daily" else if (r.repeatMs > 0) " §8↻" else ""
			g.text(font, left, x + w - 26 - font.width(left), y + 6, Theme.TEXT, false)
			button(g, x + w - 20, y + 3, 14, 14, "✖", mouseX, mouseY, color = GameColors.RED, hint = "Delete") { Reminders.remove(r) }
			y += 23
		}
	}

	private fun field(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, placeholder: String, value: String, active: Boolean, onClick: () -> Unit) {
		g.fill(x, y, x + w, y + 15, 0xFF100A1C.toInt())
		g.outline(x, y, w, 15, if (active) Theme.rainbow(0f, 0.5f) else GameColors.LINE)
		val shown = if (value.isBlank()) "§8$placeholder" else font.plainSubstrByWidth(value, w - 8)
		g.text(font, shown, x + 4, y + 4, Theme.TEXT, false)
		clickArea(x, y, w, 15, onClick)
	}

	private fun submit() {
		val w = Reminders.parseWhen(time)
		error = when {
			text.isBlank() -> "Type what to remind you about"
			w == null -> "Time like 15m, 1h30m or 18:30"
			else -> null
		}
		if (error != null) return
		Reminders.add(text.trim(), w!!, repeat)
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f))
		text = ""
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		when (event.key()) {
			GLFW.GLFW_KEY_BACKSPACE -> { if (focus == 0) text = text.dropLast(1) else time = time.dropLast(1); return true }
			GLFW.GLFW_KEY_TAB -> { focus = 1 - focus; return true }
			GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { submit(); return true }
		}
		return super.keyPressed(event)
	}

	override fun charTyped(event: CharacterEvent): Boolean {
		if (!event.isAllowedChatCharacter()) return false
		val s = event.codepointAsString()
		if (focus == 0 && text.length < 80) text += s
		if (focus == 1 && time.length < 10) time += s
		error = null
		return true
	}
}
