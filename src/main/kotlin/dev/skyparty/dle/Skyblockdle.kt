package dev.skyparty.dle

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyparty.Skyparty
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.HubTabs
import dev.skyparty.ui.Theme
import dev.skyparty.util.Chat
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Skyblockdle: one SkyBlock word per day (UTC), the same for everyone, six guesses, no unlimited mode.
 * The word is picked from a fixed list with a fixed shuffle, so no server is needed. Progress is saved
 * so a day can only be played once.
 */
object Skyblockdle {
	const val MAX_GUESSES = 6
	private val FIRST_DAY = LocalDate.of(2026, 11, 1).toEpochDay()

	private val RAW = """
		BAZAAR AUCTION DUNGEON CATACOMBS NECRON GOLDOR STORM MAXOR SADAN LIVID THORN BONZO SCARF PROFESSOR WATCHER
		MIMIC HYPERION VALKYRIE SCYLLA ASTRAEA TERMINATOR JUJU BONEMERANG SPIRIT SHADOW ASSASSIN BERSERKER ARCHER
		MAGE TANK HEALER REVENANT TARANTULA SVEN VOIDGLOOM BLAZE INFERNO VAMPIRE RIFT KUUDRA CRIMSON DOJO BARBARIAN
		MAGMA GHAST ENDERMAN DRAGON ZEALOT SUPERIOR WISE YOUNG STRONG UNSTABLE PROTECTOR MINION ISLAND MAYOR DIANA
		DERPY JERRY PAUL MARINA AATROX COLE FINNEGAN FOXY DIAZ COOKIE REFORGE ENCHANT ESSENCE GEMSTONE MITHRIL
		TITANIUM GLACITE TUNGSTEN UMBER JADE AMBER TOPAZ SAPPHIRE AMETHYST JASPER RUBY OPAL ONYX CITRINE PERIDOT
		AQUAMARINE POWDER COMMISSION FORGE GOBLIN YETI MINESHAFT CORPSE GARDEN VISITOR PEST COMPOSTER WHEAT CARROT
		POTATO PUMPKIN MELON CACTUS COCOA MUSHROOM FISHING TROPHY LAVA SQUID SHARK HYDRA SLAYER BESTIARY MUSEUM
		COLLECTION SKILL FARMING MINING COMBAT FORAGING ALCHEMY TAMING CARPENTRY SOCIAL HOPPITY CHOCOLATE RABBIT
		SPOOKY JACOB CONTEST ANITA MEDAL GRIFFIN ELEPHANT TIGER LION PHOENIX GOLEM WITHER SKELETON ZOMBIE SPIDER
		WOLF BINGO STRANDED IRONMAN COOP PROFILE PURSE BANK COINS BITS GEMS FIRESALE ARTIFACT TALISMAN RELIC
		ACCESSORY MAGICAL POWER SEYMOUR MADDOX ELIZABETH TREVOR KATANA DAEDALUS AXE FROZEN SCYTHE ASPECT LEAPING
		FLOWER GRAPPLING HOOK SPIDER DIVER SHEEP CHICKEN JUNGLE DWARVEN DEEP CAVERNS PARK BARN DESERT GOLD END
		NETHER SPIDERS DEN WINTER GIFT GRINCH FROSTY KAT REZAR BEACON SACK BACKPACK WARDROBE ABIPHONE MOBY CROCHET
		GALATEA FUSION ATTRIBUTE SHARD HUNTING LASSO TORRHUS TERMITE CRITTER BOOSTER GODPOT ARACHNE BROODMOTHER
		DREADLORD FROZENSCYTHE MIDAS GIANT LEGION BOBBIN ENRAGE VENOM STING FURY CUBISM DRAIN CHIMERA OVERLOAD
	""".trimIndent()

	val words: List<String> by lazy {
		RAW.split(Regex("\\s+")).map { it.trim().uppercase() }
			.filter { it.length in 5..10 && it.all { c -> c in 'A'..'Z' } }
			.distinct()
			.let { all ->
				// Round-robin over the lengths so consecutive days always have a different word length.
				val rng = Random(20261107)
				val buckets = listOf(8, 6, 10, 5, 9, 7).map { len -> all.filter { it.length == len }.shuffled(rng).toMutableList() }
				val out = ArrayList<String>()
				while (buckets.any { it.isNotEmpty() }) for (b in buckets) if (b.isNotEmpty()) out += b.removeAt(0)
				out
			}
	}

	fun today(): Long = LocalDate.now(ZoneOffset.UTC).toEpochDay()
	fun number(day: Long = today()) = (day - FIRST_DAY + 1).toInt()
	fun wordFor(day: Long): String = words[Math.floorMod(day - FIRST_DAY, words.size.toLong()).toInt()]

	fun msUntilNext(): Long {
		val now = ZonedDateTime.now(ZoneOffset.UTC)
		val next = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC)
		return java.time.Duration.between(now, next).toMillis()
	}

	enum class Mark { MISS, PRESENT, HIT }

	/** Wordle scoring with correct handling of repeated letters. */
	fun score(guess: String, answer: String): List<Mark> {
		val marks = MutableList(guess.length) { Mark.MISS }
		val left = HashMap<Char, Int>()
		for (i in guess.indices) {
			if (guess[i] == answer[i]) marks[i] = Mark.HIT else left.merge(answer[i], 1, Int::plus)
		}
		for (i in guess.indices) {
			if (marks[i] == Mark.HIT) continue
			val n = left[guess[i]] ?: 0
			if (n > 0) { marks[i] = Mark.PRESENT; left[guess[i]] = n - 1 }
		}
		return marks
	}

	// ------------------------------------------------------------------ saved state

	private val file = FabricLoader.getInstance().configDir.resolve("skyparty").resolve("skyblockdle.json")
	private val gson = GsonBuilder().setPrettyPrinting().create()

	var day = -1L; private set
	val guesses = mutableListOf<String>()
	var played = 0; private set
	var wins = 0; private set
	var streak = 0; private set
	var bestStreak = 0; private set
	private var lastWinDay = -10L
	private var loaded = false

	val answer get() = wordFor(today())
	val won get() = guesses.lastOrNull() == answer
	val finished get() = won || guesses.size >= MAX_GUESSES

	fun ensureLoaded() {
		if (!loaded) {
			loaded = true
			runCatching {
				if (Files.exists(file)) {
					val o = JsonParser.parseString(Files.readString(file)).asJsonObject
					day = o["day"]?.asLong ?: -1
					o["guesses"]?.asJsonArray?.forEach { guesses += it.asString }
					played = o["played"]?.asInt ?: 0
					wins = o["wins"]?.asInt ?: 0
					streak = o["streak"]?.asInt ?: 0
					bestStreak = o["bestStreak"]?.asInt ?: 0
					lastWinDay = o["lastWinDay"]?.asLong ?: -10
				}
			}.onFailure { Skyparty.LOGGER.warn("Could not read skyblockdle.json", it) }
		}
		val t = today()
		if (day != t) {
			day = t
			guesses.clear()
			if (lastWinDay < t - 1) streak = 0
			save()
		}
		// Guesses saved for a different word (word list changed) don't count.
		if (guesses.any { it.length != answer.length }) { guesses.clear(); save() }
	}

	/** Submits a guess; returns an error message or null. */
	fun submit(guess: String): String? {
		ensureLoaded()
		if (finished) return "Come back tomorrow!"
		if (guess.length != answer.length) return "The word has ${answer.length} letters"
		if (guess in guesses) return "Already guessed"
		guesses += guess
		if (finished) {
			played++
			if (won) {
				wins++
				streak = if (lastWinDay == day - 1) streak + 1 else 1
				bestStreak = maxOf(bestStreak, streak)
				lastWinDay = day
				MinigameStore.addTokens(MAX_GUESSES + 1 - guesses.size)
			} else streak = 0
		}
		save()
		return null
	}

	/** Shareable result line, e.g. "Skyblockdle #7 3/6 🟩 streak 4". */
	fun shareText(): String {
		val result = if (won) "${guesses.size}/$MAX_GUESSES" else "X/$MAX_GUESSES"
		return "Skyblockdle #${number()} $result" + (if (won && streak > 1) " (streak $streak)" else "")
	}

	private fun save() {
		runCatching {
			Files.createDirectories(file.parent)
			val o = JsonObject().apply {
				addProperty("day", day)
				add("guesses", JsonArray().apply { guesses.forEach { add(it) } })
				addProperty("played", played); addProperty("wins", wins)
				addProperty("streak", streak); addProperty("bestStreak", bestStreak)
				addProperty("lastWinDay", lastWinDay)
			}
			Files.writeString(file, gson.toJson(o))
		}.onFailure { Skyparty.LOGGER.warn("Could not save skyblockdle.json", it) }
	}
}

class DleScreen : GameScreen("Skyblockdle") {
	override val backToHub = false
	private var input = ""
	private var error: String? = null
	private var errorAt = 0L
	private var shakeAt = 0L

	init {
		Skyblockdle.ensureLoaded()
	}

	private fun type(c: Char) {
		if (Skyblockdle.finished) return
		if (input.length < Skyblockdle.answer.length) input += c
	}

	private fun backspace() { if (input.isNotEmpty()) input = input.dropLast(1) }

	private fun enter() {
		if (Skyblockdle.finished) return
		val err = Skyblockdle.submit(input)
		if (err != null) {
			error = err; errorAt = System.currentTimeMillis(); shakeAt = errorAt
			return
		}
		input = ""
		val sound = when {
			Skyblockdle.won -> SoundEvents.PLAYER_LEVELUP
			Skyblockdle.finished -> SoundEvents.VILLAGER_NO
			else -> SoundEvents.NOTE_BLOCK_PLING.value()
		}
		Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, 1f))
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		when (event.key()) {
			GLFW.GLFW_KEY_BACKSPACE -> { backspace(); return true }
			GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { enter(); return true }
		}
		return super.keyPressed(event)
	}

	override fun charTyped(event: CharacterEvent): Boolean {
		val s = event.codepointAsString().uppercase()
		if (s.length == 1 && s[0] in 'A'..'Z') { type(s[0]); return true }
		return false
	}

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		Skyblockdle.ensureLoaded()
		val top = hubChrome(g, HubTabs.DLE)
		val cx = width / 2
		val answer = Skyblockdle.answer
		val len = answer.length
		centered(g, "§l✎ Skyblockdle #${Skyblockdle.number()} §7· ${len} letters · same word for everyone", cx, top + 6, Theme.TEXT)

		// Board
		val box = minOf(18, (height - top - 120) / Skyblockdle.MAX_GUESSES - 2).coerceAtLeast(11)
		val gap = 2
		val boardW = len * (box + gap) - gap
		val bx0 = cx - boardW / 2
		var y = top + 20
		val guesses = Skyblockdle.guesses
		for (row in 0 until Skyblockdle.MAX_GUESSES) {
			val word = guesses.getOrNull(row)
			val marks = word?.let { Skyblockdle.score(it, answer) }
			val current = row == guesses.size && !Skyblockdle.finished
			val shake = if (current && System.currentTimeMillis() - shakeAt < 300) (kotlin.math.sin(System.currentTimeMillis() / 20.0) * 3).toInt() else 0
			for (i in 0 until len) {
				val x = bx0 + i * (box + gap) + shake
				val ch = word?.get(i) ?: if (current) input.getOrNull(i) else null
				val fill = when (marks?.get(i)) {
					Skyblockdle.Mark.HIT -> GameColors.GREEN
					Skyblockdle.Mark.PRESENT -> 0xFFD9A62E.toInt()
					Skyblockdle.Mark.MISS -> 0xFF3A3A46.toInt()
					null -> 0xFF1A1030.toInt()
				}
				g.fill(x, y, x + box, y + box, fill)
				g.outline(x, y, box, box, if (current && i == input.length) Theme.rainbow(0f, 0.5f) else if (ch != null && marks == null) 0xFF8A80A0.toInt() else 0xFF2A2A36.toInt())
				if (ch != null) centered(g, "§l$ch", x + box / 2, y + (box - 8) / 2 + 1, Theme.TEXT)
			}
			y += box + gap
		}

		y += 6
		val err = error
		if (err != null && System.currentTimeMillis() - errorAt < 1800) centered(g, "§c$err", cx, y, Theme.TEXT)
		else if (Skyblockdle.finished) drawResult(g, cx, y, mouseX, mouseY)
		y += 12
		if (!Skyblockdle.finished) drawKeyboard(g, cx, y, mouseX, mouseY)
		else drawStats(g, cx, y + 14)
	}

	private fun drawKeyboard(g: GuiGraphicsExtractor, cx: Int, y0: Int, mx: Int, my: Int) {
		val state = HashMap<Char, Skyblockdle.Mark>()
		for (w in Skyblockdle.guesses) for ((i, m) in Skyblockdle.score(w, Skyblockdle.answer).withIndex()) {
			val c = w[i]
			if ((state[c]?.ordinal ?: -1) < m.ordinal) state[c] = m
		}
		val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
		val kw = 15
		val kh = 15
		for ((r, row) in rows.withIndex()) {
			val extra = if (r == 2) 2 * (kw * 2 + 2) else 0
			val rowW = row.length * (kw + 2) - 2 + extra
			var x = cx - rowW / 2
			val y = y0 + r * (kh + 2)
			if (r == 2) { button(g, x, y, kw * 2, kh, "OK", mx, my, color = GameColors.GREEN, hint = "Enter") { enter() }; x += kw * 2 + 2 }
			for (c in row) {
				val color = when (state[c]) {
					Skyblockdle.Mark.HIT -> GameColors.GREEN
					Skyblockdle.Mark.PRESENT -> 0xFFD9A62E.toInt()
					Skyblockdle.Mark.MISS -> 0xFF2A2A30.toInt()
					null -> 0xFF5A4A7A.toInt()
				}
				button(g, x, y, kw, kh, c.toString(), mx, my, color = color) { type(c) }
				x += kw + 2
			}
			if (r == 2) button(g, x, y, kw * 2, kh, "DEL", mx, my, color = GameColors.RED, hint = "Backspace") { backspace() }
		}
	}

	private fun drawResult(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		if (Skyblockdle.won) centered(g, "§a§lYou got it in ${Skyblockdle.guesses.size}! §6+${Skyblockdle.MAX_GUESSES + 1 - Skyblockdle.guesses.size} Tokens", cx, y, Theme.TEXT)
		else centered(g, "§cThe word was §f§l${Skyblockdle.answer}", cx, y, Theme.TEXT)
		val left = Skyblockdle.msUntilNext() / 1000
		centered(g, "§7Next word in §f%02d:%02d:%02d".format(left / 3600, left / 60 % 60, left % 60), cx, y + 12, Theme.TEXT)
		button(g, cx - 60, y + 24, 120, 16, "Share in party chat", mx, my, hint = "Sends: ${Skyblockdle.shareText()}") {
			Chat.command("pc ${Skyblockdle.shareText()}")
		}
	}

	private fun drawStats(g: GuiGraphicsExtractor, cx: Int, y: Int) {
		val d = Skyblockdle
		val pct = if (d.played == 0) 0 else d.wins * 100 / d.played
		centered(g, "§7Played §f${d.played}   §7Win % §f$pct   §7Streak §f${d.streak}   §7Best §f${d.bestStreak}", cx, y + 20, Theme.TEXT)
	}
}
