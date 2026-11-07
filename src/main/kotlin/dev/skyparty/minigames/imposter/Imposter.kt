package dev.skyparty.minigames.imposter

import com.google.gson.JsonObject
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.random.Random

/**
 * Who's the Imposter?: everyone gets the same SkyBlock word except one player, who only knows the
 * category. Two rounds of one-word hints, then everybody votes. If the imposter is caught they get one
 * guess at the word to steal the win. Solo: you and three bots.
 *
 * Protocol (echoed to the sender; everyone derives the same order, word and imposter from the seed):
 *  - "imp.start.rng" (host)   seed
 *  - "imp.hint" {n, text}     from the player whose turn it is (n = turn counter)
 *  - "imp.skip" {n}           (host) that player ran out of time
 *  - "imp.vote" {target}      one vote per player
 *  - "imp.endvote" {}         (host) voting time is over
 *  - "imp.guess" {text}       the caught imposter's guess ("" = no guess / timed out, host may send for them)
 */
object Imposter : Minigame {
	override val id = "imposter"
	override val name = "Imposter"
	override val description = "Everyone knows the secret word except one. Give hints, then vote out the imposter."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = false

	const val HINT_ROUNDS = 2
	const val TURN_MS = 30_000L
	const val VOTE_MS = 35_000L
	const val GUESS_MS = 25_000L

	class Word(val word: String, val category: String, val hints: List<String>)

	val WORDS = listOf(
		Word("Necron", "Boss", listOf("wither", "terminals", "floor seven")),
		Word("Sadan", "Boss", listOf("giants", "terracotta", "floor six")),
		Word("Livid", "Boss", listOf("clones", "blindness", "floor five")),
		Word("Thorn", "Boss", listOf("spirit", "bow", "floor four")),
		Word("Kuudra", "Boss", listOf("tentacles", "ballista", "crimson")),
		Word("Bonzo", "Boss", listOf("balloons", "mask", "floor one")),
		Word("Professor", "Boss", listOf("guardians", "floor three", "chaos")),
		Word("Hyperion", "Item", listOf("implosion", "teleport", "sword")),
		Word("Terminator", "Item", listOf("arrows", "salvation", "bow")),
		Word("Aspect of the End", "Item", listOf("teleport", "cheap", "sword")),
		Word("Juju Shortbow", "Item", listOf("spider", "shortbow", "arrows")),
		Word("Grappling Hook", "Item", listOf("pull", "movement", "rod")),
		Word("Booster Cookie", "Item", listOf("bits", "buff", "purse")),
		Word("Spirit Sceptre", "Item", listOf("bats", "wand", "mage")),
		Word("Bonemerang", "Item", listOf("bone", "throw", "skeleton")),
		Word("Dwarven Mines", "Place", listOf("mithril", "commissions", "king")),
		Word("Crystal Hollows", "Place", listOf("gemstones", "nucleus", "sludge")),
		Word("Crimson Isle", "Place", listOf("factions", "magma", "nether")),
		Word("The Park", "Place", listOf("trees", "foraging", "howling")),
		Word("Spider's Den", "Place", listOf("tarantula", "string", "cave")),
		Word("The End", "Place", listOf("zealots", "dragon", "eyes")),
		Word("Garden", "Place", listOf("pests", "visitors", "crops")),
		Word("The Rift", "Place", listOf("motes", "vampire", "time")),
		Word("Golden Dragon", "Pet", listOf("bank", "coins", "expensive")),
		Word("Griffin", "Pet", listOf("burrows", "diana", "rarity")),
		Word("Elephant", "Pet", listOf("farming", "trunk", "defense")),
		Word("Yeti", "Mob", listOf("jerry", "winter", "axe")),
		Word("Zealot", "Mob", listOf("special", "eyes", "enderman")),
		Word("Mimic", "Mob", listOf("chest", "dungeon", "fake")),
		Word("Diana", "Mayor", listOf("griffin", "burrows", "mythological")),
		Word("Derpy", "Mayor", listOf("chaos", "double", "auctions")),
		Word("Paul", "Mayor", listOf("dungeons", "score", "ezpz")),
		Word("Marina", "Mayor", listOf("fishing", "sea", "creatures")),
		Word("Aatrox", "Mayor", listOf("slayers", "xp", "discount")),
		Word("Cole", "Mayor", listOf("mining", "fiesta", "prestige")),
	)

	private val VAGUE = listOf("rare", "famous", "strong", "useful", "expensive", "cool", "old", "big", "everyone knows")

	/** A believable hint for a bot: crew picks from the word's hints, the imposter bluffs from the category. */
	fun botHint(word: Word, imposter: Boolean, used: Set<String>, rng: Random): String {
		val pool = if (!imposter) word.hints
		else WORDS.filter { it.category == word.category && it != word }.flatMap { it.hints } + VAGUE
		return pool.filter { it !in used }.randomOrNull(rng) ?: VAGUE.random(rng)
	}

	override fun open(room: Net.Room?) = MinigameHub.openScreen(ImposterScreen(LobbyRoom.of(room, id)))
}

class ImposterScreen(val room: LobbyRoom) : GameScreen("Imposter") {
	enum class Phase { WAITING, HINTS, VOTE, GUESS, OVER }

	private var phase = Phase.WAITING
	private var phaseStart = 0L
	private var seed = 0L
	private var order = listOf<UUID>()
	private val names = linkedMapOf<UUID, String>()
	private val bots = mutableSetOf<UUID>()
	private var word = Imposter.WORDS.first()
	private var imposter: UUID? = null
	private var turn = 0
	private val hints = linkedMapOf<UUID, MutableList<String>>()
	private val votes = linkedMapOf<UUID, UUID>()
	private var voted: UUID? = null
	private var guess: String? = null
	private var impostorWon = false
	private var input = ""
	private var botActAt = 0L
	private var rewarded = false
	private val botVoteAt = HashMap<UUID, Long>()

	init {
		room.onMessage = ::onMessage
		if (room.isHost) requestStart()
	}

	private fun now() = System.currentTimeMillis()
	private val me get() = Net.selfId
	private val isImposter get() = imposter == me
	private val current get() = order.getOrNull(turn % order.size.coerceAtLeast(1))
	private val turnsTotal get() = order.size * Imposter.HINT_ROUNDS

	// ------------------------------------------------------------------ protocol

	private fun requestStart() = room.send("imp.start.rng", JsonObject())

	private fun onMessage(msg: Net.Message) {
		when (msg.type) {
			"imp.start.rng" -> if (msg.from == room.host) start(((msg.payload["roll"]?.asDouble ?: Math.random()) * Long.MAX_VALUE).toLong())
			"imp.hint" -> if (phase == Phase.HINTS && msg.from == current && msg.payload["n"]?.asInt == turn)
				addHint(msg.from, msg.payload["text"]?.asString ?: "")
			"imp.skip" -> if (phase == Phase.HINTS && msg.from == room.host && msg.payload["n"]?.asInt == turn)
				current?.let { addHint(it, "") }
			"imp.vote" -> if (phase == Phase.VOTE) msg.payload["target"]?.asString?.let { castVote(msg.from, UUID.fromString(it)) }
			"imp.endvote" -> if (phase == Phase.VOTE && msg.from == room.host) tally()
			"imp.guess" -> if (phase == Phase.GUESS && (msg.from == imposter || msg.from == room.host)) resolveGuess(msg.payload["text"]?.asString ?: "")
		}
	}

	private fun start(s: Long) {
		seed = s
		val rng = Random(seed)
		names.clear(); bots.clear()
		names.putAll(room.names)
		if (room.solo) for (n in listOf("Sus Steve", "Crewmate Carl", "Detective Dana")) {
			val id = UUID(rng.nextLong(), rng.nextLong()); names[id] = n; bots += id
		}
		order = names.keys.toList()
		word = Imposter.WORDS[rng.nextInt(Imposter.WORDS.size)]
		imposter = order[rng.nextInt(order.size)]
		turn = rng.nextInt(order.size) // random first player; hints go around from there
		val startTurn = turn
		hints.clear(); votes.clear(); botVoteAt.clear()
		order.forEach { hints[it] = mutableListOf() }
		voted = null; guess = null; impostorWon = false; rewarded = false; input = ""
		turn = startTurn
		firstTurn = startTurn
		enter(Phase.HINTS)
		scheduleBot()
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1f)
	}

	private var firstTurn = 0

	private fun enter(p: Phase) { phase = p; phaseStart = now() }

	private fun scheduleBot() {
		if (current in bots) botActAt = now() + Random.nextLong(1_500, 3_200)
	}

	private fun addHint(player: UUID, text: String) {
		hints.getOrPut(player) { mutableListOf() } += text.trim().take(24).ifBlank { "…" }
		turn++
		phaseStart = now()
		play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.3f)
		if (turn - firstTurn >= turnsTotal) {
			enter(Phase.VOTE)
			for (b in bots) botVoteAt[b] = now() + Random.nextLong(2_000, 5_000)
		} else scheduleBot()
	}

	private fun sendHint() {
		val t = input.trim()
		if (t.isEmpty() || phase != Phase.HINTS || current != me) return
		if (t.equals(word.word, ignoreCase = true) && !isImposter) { input = ""; return } // no saying the word
		input = ""
		if (room.solo) addHint(me, t) else room.send("imp.hint", JsonObject().apply { addProperty("n", turn); addProperty("text", t) })
	}

	private fun vote(target: UUID) {
		if (phase != Phase.VOTE || voted != null || target == me) return
		voted = target
		if (room.solo) castVote(me, target) else room.send("imp.vote", JsonObject().apply { addProperty("target", target.toString()) })
	}

	private fun castVote(from: UUID, target: UUID) {
		if (from in votes || target !in names) return
		votes[from] = target
		if (votes.size >= order.size) tally()
	}

	private fun tally() {
		if (phase != Phase.VOTE) return
		val counts = votes.values.groupingBy { it }.eachCount()
		val top = counts.maxOfOrNull { it.value } ?: 0
		val leaders = counts.filter { it.value == top }.keys
		val out = leaders.singleOrNull()
		votedOut = out
		if (out != null && out == imposter) {
			enter(Phase.GUESS)
			if (imposter in bots) botActAt = now() + 2_500
		} else {
			impostorWon = true
			finish()
		}
	}

	private var votedOut: UUID? = null

	private fun resolveGuess(text: String) {
		if (phase != Phase.GUESS) return
		guess = text
		impostorWon = normalize(text) == normalize(word.word) && text.isNotBlank()
		finish()
	}

	private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z]"), "").removePrefix("the")

	private fun finish() {
		enter(Phase.OVER)
		val won = (isImposter && impostorWon) || (!isImposter && !impostorWon)
		if (won && !rewarded) {
			rewarded = true
			MinigameStore.addTokens(if (isImposter) 10 else 5)
		}
		play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.VILLAGER_NO, 1f)
	}

	private fun play(sound: SoundEvent, pitch: Float) = Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, pitch))

	// ------------------------------------------------------------------ loop (timeouts, bots)

	private fun update() {
		val t = now() - phaseStart
		when (phase) {
			Phase.HINTS -> {
				val c = current
				if (c in bots && now() >= botActAt) {
					val used = hints.values.flatten().toSet()
					addHint(c!!, Imposter.botHint(word, c == imposter, used, Random(seed + turn)))
				} else if (room.isHost && t >= Imposter.TURN_MS) {
					if (room.solo) c?.let { addHint(it, "") } else room.send("imp.skip", JsonObject().apply { addProperty("n", turn) })
				}
			}
			Phase.VOTE -> {
				for ((b, at) in botVoteAt.toMap()) if (now() >= at && b !in votes) {
					botVoteAt.remove(b)
					castVote(b, botSuspect(b))
				}
				if (phase == Phase.VOTE && room.isHost && t >= Imposter.VOTE_MS) {
					if (room.solo) tally() else room.send("imp.endvote", JsonObject())
				}
			}
			Phase.GUESS -> {
				if (imposter in bots && now() >= botActAt) resolveGuess(if (Random.nextInt(100) < 40) word.word else Imposter.botHint(word, true, emptySet(), Random))
				else if (room.isHost && t >= Imposter.GUESS_MS) {
					if (room.solo) resolveGuess("") else room.send("imp.guess", JsonObject().apply { addProperty("text", "") })
				}
			}
			else -> {}
		}
	}

	/** Bots suspect whoever gave the hints that fit the word the least (the imposter bot points elsewhere). */
	private fun botSuspect(bot: UUID): UUID {
		val others = order.filter { it != bot }
		if (bot == imposter) return others.random()
		val fit = others.associateWith { p -> hints[p].orEmpty().count { h -> word.hints.any { it.equals(h, true) } || word.word.contains(h, true) } }
		val min = fit.values.minOrNull() ?: 0
		val suspects = fit.filter { it.value == min }.keys.toList()
		return if (Random.nextInt(100) < 75) suspects.random() else others.random()
	}

	// ------------------------------------------------------------------ input

	override fun keyPressed(event: KeyEvent): Boolean {
		if (typing()) when (event.key()) {
			GLFW.GLFW_KEY_BACKSPACE -> { input = input.dropLast(1); return true }
			GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { if (phase == Phase.GUESS) submitGuess() else sendHint(); return true }
		}
		return super.keyPressed(event)
	}

	override fun charTyped(event: CharacterEvent): Boolean {
		if (!typing() || !event.isAllowedChatCharacter()) return false
		if (input.length < 24) input += event.codepointAsString()
		return true
	}

	private fun typing() = (phase == Phase.HINTS && current == me) || (phase == Phase.GUESS && isImposter)

	private fun submitGuess() {
		if (phase != Phase.GUESS || !isImposter) return
		val t = input.trim()
		input = ""
		if (room.solo) resolveGuess(t) else room.send("imp.guess", JsonObject().apply { addProperty("text", t) })
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val top = header(g, "Imposter", if (room.solo) "vs 3 bots" else "${room.members.size} players")
		val cx = width / 2
		if (phase == Phase.WAITING) { centered(g, "§7Waiting for the host…", cx, height / 2, Theme.TEXT); return }
		if (!room.solo && order.size < 3) centered(g, "§eTip: Imposter is best with 3+ players", cx, height - 12, Theme.TEXT)

		// Your secret card
		val cw = 220
		panel(g, cx - cw / 2, top + 6, cw, 30)
		if (isImposter) {
			centered(g, "§c§lYou are the IMPOSTER!", cx, top + 10, Theme.TEXT)
			centered(g, "§7Category: §f${word.category} §8· blend in!", cx, top + 22, Theme.TEXT)
		} else {
			centered(g, "§7Secret word: §a§l${word.word}", cx, top + 10, Theme.TEXT)
			centered(g, "§8${word.category} · don't say it, describe it", cx, top + 22, Theme.TEXT)
		}

		// Players with their hints
		val listW = minOf(360, width - 20)
		val lx = cx - listW / 2
		var y = top + 44
		val rowH = 18
		for (id in order) {
			val isTurn = phase == Phase.HINTS && id == current
			val revealImp = phase == Phase.OVER && id == imposter
			g.fill(lx, y, lx + listW, y + rowH - 2, if (isTurn) GameColors.CARD_HOVER else GameColors.CARD)
			if (isTurn) g.fill(lx, y, lx + 2, y + rowH - 2, Theme.rainbow(0f, 0.5f))
			face(g, id, lx + 5, y + 2, 12)
			val nameColor = when { revealImp -> "§c"; id == me -> "§e"; else -> "§f" }
			g.text(font, nameColor + font.plainSubstrByWidth(names[id] ?: "?", if (revealImp) 62 else 90) + if (revealImp) " §c§lIMP" else "", lx + 21, y + 4, Theme.TEXT, false)
			val hs = hints[id].orEmpty()
			var hx = lx + 120
			for (h in hs) {
				val label = "\"$h\""
				val w = font.width(label) + 8
				g.fill(hx, y + 2, hx + w, y + rowH - 4, 0xFF2A1F44.toInt())
				g.text(font, label, hx + 4, y + 4, Theme.TEXT, false)
				hx += w + 4
			}
			if (isTurn) g.text(font, "§7typing" + ".".repeat(((now() / 300) % 4).toInt()), hx + 2, y + 4, Theme.TEXT, false)
			if (phase == Phase.VOTE && id != me) {
				val count = votes.values.count { it == id }
				val label = if (voted == id) "✔ Voted" else "Vote"
				button(g, lx + listW - 52, y + 1, 48, rowH - 4, label, mouseX, mouseY, enabled = voted == null || voted == id,
					color = if (voted == id) GameColors.RED else Theme.accent) { vote(id) }
				if (voted != null && count > 0) g.text(font, "§c$count", lx + listW - 62, y + 4, Theme.TEXT, false)
			}
			if ((phase == Phase.OVER || phase == Phase.GUESS) && id in votes.values) {
				val count = votes.values.count { it == id }
				g.text(font, "§c$count vote${if (count == 1) "" else "s"}", lx + listW - 50, y + 4, Theme.TEXT, false)
			}
			y += rowH
		}

		// Bottom: input / status
		y += 6
		val t = now() - phaseStart
		when (phase) {
			Phase.HINTS -> {
				val round = (turn - firstTurn) / order.size.coerceAtLeast(1) + 1
				timer(g, cx, y, 1f - t.toFloat() / Imposter.TURN_MS)
				if (current == me) {
					inputBox(g, cx, y + 8, "Your hint (one word)…", mouseX, mouseY, "Send") { sendHint() }
					centered(g, "§7Round $round/${Imposter.HINT_ROUNDS} · your turn!", cx, y + 30, Theme.TEXT)
				} else centered(g, "§7Round $round/${Imposter.HINT_ROUNDS} · §f${names[current] ?: "?"}§7 is giving a hint", cx, y + 12, Theme.TEXT)
			}
			Phase.VOTE -> {
				timer(g, cx, y, 1f - t.toFloat() / Imposter.VOTE_MS)
				centered(g, if (voted == null) "§e§lWho is the imposter? §7Click Vote" else "§7Waiting for votes… §8(${votes.size}/${order.size})", cx, y + 12, Theme.TEXT)
			}
			Phase.GUESS -> {
				timer(g, cx, y, 1f - t.toFloat() / Imposter.GUESS_MS)
				if (isImposter) {
					centered(g, "§cYou were caught! §7Guess the word to still win:", cx, y + 8, Theme.TEXT)
					inputBox(g, cx, y + 20, "The secret word…", mouseX, mouseY, "Guess") { submitGuess() }
				} else centered(g, "§a${names[imposter] ?: "?"} was the imposter! §7They get one guess at the word…", cx, y + 12, Theme.TEXT)
			}
			Phase.OVER -> drawOver(g, cx, y, mouseX, mouseY)
			else -> {}
		}
	}

	private fun drawOver(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		val impName = names[imposter] ?: "?"
		val headline = when {
			votedOut == null -> "§cTie vote – $impName (imposter) escapes!"
			votedOut != imposter -> "§c${names[votedOut] ?: "?"} was innocent! $impName wins."
			impostorWon -> "§c$impName guessed \"${guess}\" – the imposter wins!"
			else -> "§aCaught! $impName guessed \"${guess?.ifBlank { "nothing" }}\" – crew wins!"
		}
		centered(g, headline, cx, y, Theme.TEXT)
		centered(g, "§7The word was §f§l${word.word}", cx, y + 11, Theme.TEXT)
		val won = (isImposter && impostorWon) || (!isImposter && !impostorWon)
		if (won) centered(g, "§6+${if (isImposter) 10 else 5} Tokens", cx, y + 22, Theme.TEXT)
		if (room.solo || room.isHost) {
			button(g, cx - 100, y + 34, 92, 16, "Play again", mx, my, color = GameColors.GREEN) {
				if (room.solo) start(Random.nextLong()) else requestStart()
			}
		} else centered(g, "§7Waiting for the host…", cx, y + 38, Theme.TEXT)
		button(g, cx + 8, y + 34, 92, 16, "Leave", mx, my, color = GameColors.RED) { onClose() }
	}

	private fun timer(g: GuiGraphicsExtractor, cx: Int, y: Int, frac: Float) {
		val f = frac.coerceIn(0f, 1f)
		g.fill(cx - 90, y, cx + 90, y + 3, 0xFF2A1A44.toInt())
		g.fill(cx - 90, y, cx - 90 + (180 * f).toInt(), y + 3, if (f > 0.3f) GameColors.GREEN else GameColors.RED)
	}

	private fun inputBox(g: GuiGraphicsExtractor, cx: Int, y: Int, placeholder: String, mx: Int, my: Int, action: String, onSend: () -> Unit) {
		val w = 200
		val x = cx - w / 2 - 26
		g.fill(x, y, x + w, y + 16, 0xFF100A1C.toInt())
		g.outline(x, y, w, 16, Theme.rainbow(0f, 0.5f))
		val caret = if ((now() / 500) % 2 == 0L) "_" else ""
		g.text(font, if (input.isEmpty()) "§8$placeholder" else font.plainSubstrByWidth(input, w - 10) + caret, x + 4, y + 4, Theme.TEXT, false)
		button(g, x + w + 4, y, 48, 16, action, mx, my, color = GameColors.GREEN) { onSend() }
	}

	private fun face(g: GuiGraphicsExtractor, id: UUID, x: Int, y: Int, size: Int) {
		val mc = Minecraft.getInstance()
		val skin = mc.connection?.getPlayerInfo(id)?.skin?.body()?.texturePath()
			?: (if (id == me) mc.player?.skin?.body()?.texturePath() else null)
			?: DefaultPlayerSkin.get(id).body().texturePath()
		g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8f, 8f, size, size, 8, 8, 64, 64, 0xFFFFFFFF.toInt())
		g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40f, 8f, size, size, 8, 8, 64, 64, 0xFFFFFFFF.toInt())
	}

	/** Test hooks. */
	val debugPhase get() = phase
	val debugMyTurn get() = phase == Phase.HINTS && current == me
	fun debugType(text: String) { input = text; sendHint() }
	fun debugVoteSomeone() { order.firstOrNull { it != me }?.let { vote(it) } }

	override fun removed() {
		room.leave()
		super.removed()
	}
}
