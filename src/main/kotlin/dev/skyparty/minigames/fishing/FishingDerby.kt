package dev.skyparty.minigames.fishing

import com.google.gson.JsonObject
import dev.skyparty.minigames.GameLength
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.common.RoundGameScreen
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fishing Derby: 90 seconds to catch as much as you can. Cast, wait for the bite, hook it in time, then stop
 * the reel marker in the green zone; the better the timing, the rarer the catch. Everyone fishes on their own
 * screen, catches are shared live. Solo: three bots.
 *
 * Protocol (echoed to the sender):
 *  - "fish.start" {}         (host) the derby starts now (after a short countdown)
 *  - "fish.catch" {n, p}     a player caught n for p points
 */
object FishingDerby : Minigame {
	override val id = "fishing"
	override val name = "Fishing Derby"
	override val description = "90 seconds of fishing: hook the bite, time the reel. Rarer catches, more points."
	override val minPlayers = 1
	override val maxPlayers = 8
	override val realtime = true
	override val length = GameLength.SHORT

	const val COUNTDOWN_MS = 3_000L
	const val DERBY_MS = 90_000L

	class Catch(val name: String, val points: Int, val tier: Int, val icon: Item)

	/** Tier 0 = common fish … 3 = legendary sea creature. */
	val CATCHES = listOf(
		Catch("Raw Cod", 1, 0, Items.COD), Catch("Raw Salmon", 2, 0, Items.SALMON), Catch("Pufferfish", 3, 0, Items.PUFFERFISH),
		Catch("Clownfish", 4, 1, Items.TROPICAL_FISH), Catch("Squid", 5, 1, Items.INK_SAC), Catch("Sea Walker", 7, 1, Items.ROTTEN_FLESH),
		Catch("Night Squid", 10, 2, Items.GLOW_INK_SAC), Catch("Sea Guardian", 12, 2, Items.PRISMARINE_SHARD), Catch("Sea Witch", 14, 2, Items.GLASS_BOTTLE),
		Catch("Water Hydra", 25, 3, Items.HEART_OF_THE_SEA), Catch("Great White Shark", 30, 3, Items.NAUTILUS_SHELL), Catch("Sea Emperor", 40, 3, Items.TRIDENT),
	)

	/** A catch for reel [accuracy] (0 = edge of the zone, 1 = dead center). */
	fun roll(accuracy: Double, rng: Random = Random): Catch {
		val tier = when {
			accuracy > 0.92 && rng.nextInt(100) < 45 -> 3
			accuracy > 0.75 && rng.nextInt(100) < 55 -> 2
			accuracy > 0.4 && rng.nextInt(100) < 60 -> 1
			else -> 0
		}
		return CATCHES.filter { it.tier == tier }.random(rng)
	}

	fun tierColor(t: Int) = when (t) { 0 -> "§f"; 1 -> "§a"; 2 -> "§9"; else -> "§6" }

	override fun open(room: Net.Room?) = MinigameHub.openScreen(FishingScreen(LobbyRoom.of(room, id)))
}

class FishingScreen(room: LobbyRoom) : RoundGameScreen("Fishing Derby", room, listOf("Old Angler", "Rod God", "Fishy Fran")) {
	enum class Phase { WAITING, COUNTDOWN, FISHING, OVER }
	/** Your rod: idle, line in the water, fish biting, reeling, just caught. */
	enum class Rod { IDLE, WAITING, BITE, REEL, CAUGHT, ESCAPED }

	override fun inProgress() = phase != Phase.OVER

	private var phase = Phase.WAITING
	private var startedAt = 0L
	private var rod = Rod.IDLE
	private var rodAt = 0L
	private var biteAt = 0L
	private var zoneCenter = 0.5
	private var zoneHalf = 0.12
	private var markerSpeed = 1.0
	private var last: FishingDerby.Catch? = null
	private val catches = HashMap<UUID, Int>()
	private val feed = ArrayDeque<String>()
	private val botNextAt = HashMap<UUID, Long>()

	init {
		room.onMessage = ::onMessage
	}

	private fun onMessage(msg: Net.Message) {
		when (msg.type) {
			"fish.start" -> if (msg.from == room.host) begin()
			"fish.catch" -> if (phase == Phase.FISHING || phase == Phase.OVER) {
				val name = msg.payload["n"]?.asString?.take(24) ?: return
				val pts = msg.payload["p"]?.asInt?.coerceIn(0, 50) ?: return
				addCatch(msg.from, name, pts)
			}
		}
	}

	private fun begin() {
		resetMatch()
		catches.clear(); feed.clear(); botNextAt.clear()
		phase = Phase.COUNTDOWN
		startedAt = now()
		rod = Rod.IDLE
		for (b in bots) botNextAt[b] = startedAt + FishingDerby.COUNTDOWN_MS + Random.nextLong(3_000, 7_000)
	}

	private fun addCatch(from: UUID, name: String, pts: Int) {
		if (from !in names) return
		addScore(from, pts)
		catches[from] = (catches[from] ?: 0) + 1
		val c = FishingDerby.CATCHES.firstOrNull { it.name == name }
		if (from != me && c != null && c.tier >= 2) {
			feed.addFirst("${names[from]} §7caught ${FishingDerby.tierColor(c.tier)}$name§7!")
			while (feed.size > 3) feed.removeLast()
		}
	}

	private val timeLeft get() = FishingDerby.COUNTDOWN_MS + FishingDerby.DERBY_MS - (now() - startedAt)

	// ------------------------------------------------------------------ the rod

	private fun setRod(r: Rod) { rod = r; rodAt = now() }

	/** Left click / space: cast, hook or stop the reel, depending on the rod. */
	private fun action() {
		if (phase != Phase.FISHING) return
		when (rod) {
			Rod.IDLE, Rod.CAUGHT, Rod.ESCAPED -> {
				setRod(Rod.WAITING)
				biteAt = now() + Random.nextLong(1_500, 5_000)
				play(SoundEvents.FISHING_BOBBER_THROW, 1f)
			}
			Rod.WAITING -> { setRod(Rod.ESCAPED); play(SoundEvents.VILLAGER_NO, 1.4f) } // pulled too early
			Rod.BITE -> {
				setRod(Rod.REEL)
				zoneCenter = Random.nextDouble(0.25, 0.75)
				zoneHalf = Random.nextDouble(0.07, 0.15)
				markerSpeed = Random.nextDouble(0.8, 1.5)
				play(SoundEvents.FISHING_BOBBER_RETRIEVE, 1.2f)
			}
			Rod.REEL -> {
				val dist = abs(marker() - zoneCenter)
				if (dist > zoneHalf) { setRod(Rod.ESCAPED); play(SoundEvents.VILLAGER_NO, 1.2f); return }
				val c = FishingDerby.roll(1 - dist / zoneHalf)
				last = c
				setRod(Rod.CAUGHT)
				play(if (c.tier >= 2) SoundEvents.PLAYER_LEVELUP else SoundEvents.EXPERIENCE_ORB_PICKUP, if (c.tier >= 2) 1.4f else 1f)
				if (room.solo) addCatch(me, c.name, c.points)
				else room.send("fish.catch", JsonObject().apply { addProperty("n", c.name); addProperty("p", c.points) })
			}
		}
	}

	/** Reel marker position 0..1, bouncing back and forth. */
	private fun marker(): Double {
		val t = (now() - rodAt) / 1000.0 * markerSpeed
		val f = t % 2.0
		return if (f < 1) f else 2 - f
	}

	private fun update() {
		val n = now()
		when (phase) {
			Phase.WAITING -> if (hostLike) once("m$match start") { if (room.solo) begin() else room.send("fish.start", JsonObject()) }
			Phase.COUNTDOWN -> if (n - startedAt >= FishingDerby.COUNTDOWN_MS) { phase = Phase.FISHING; play(SoundEvents.NOTE_BLOCK_CHIME.value()) }
			Phase.FISHING -> {
				when (rod) {
					Rod.WAITING -> if (n >= biteAt) { setRod(Rod.BITE); play(SoundEvents.FISHING_BOBBER_SPLASH, 1f) }
					Rod.BITE -> if (n - rodAt > 900) { setRod(Rod.ESCAPED); play(SoundEvents.NOTE_BLOCK_BASS.value()) }
					Rod.REEL -> if (n - rodAt > 6_000) setRod(Rod.ESCAPED)
					else -> {}
				}
				for ((b, at) in botNextAt.toMap()) if (n >= at) {
					val c = FishingDerby.roll(Random.nextDouble(0.2, 1.0))
					addCatch(b, c.name, c.points)
					botNextAt[b] = n + Random.nextLong(3_000, 7_500)
				}
				if (timeLeft <= 0) {
					phase = Phase.OVER
					val won = rewardMatch(FishingDerby.id)
					play(if (won) SoundEvents.PLAYER_LEVELUP else SoundEvents.VILLAGER_NO)
				}
			}
			Phase.OVER -> {}
		}
	}

	override fun onClickAnywhere(mx: Double, my: Double): Boolean {
		if (phase != Phase.FISHING) return false
		action()
		return true
	}

	override fun onKey(event: KeyEvent): Boolean {
		if (phase == Phase.FISHING && event.key() == GLFW.GLFW_KEY_SPACE) { action(); return true }
		return super.onKey(event)
	}

	// ------------------------------------------------------------------ drawing

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		update()
		val top = header(g, "Fishing Derby", if (room.solo) "vs 3 bots" else "${names.size} players")
		val cx = width / 2
		if (phase == Phase.WAITING) {
			centered(g, "§7Waiting for the host…", cx, height / 2, Theme.TEXT)
			return
		}
		val w = minOf(340, width - 130)
		val x0 = cx - w / 2
		drawScores(g, x0 + w)
		val sceneTop = top + 18
		val sceneH = (height - sceneTop - 70).coerceIn(90, 170)
		val secs = (timeLeft.coerceAtLeast(0) / 1000).toInt()
		g.text(font, "§7Time §f§l${secs / 60}:%02d".format(secs % 60), x0, top + 6, Theme.TEXT, true)
		val mine = "§7Your catches §f${catches[me] ?: 0} §8· §6${scores[me] ?: 0} pts"
		g.text(font, mine, x0 + w - font.width(mine), top + 6, Theme.TEXT, true)
		drawScene(g, x0, sceneTop, w, sceneH)

		val by = sceneTop + sceneH + 8
		when (phase) {
			Phase.COUNTDOWN -> bigText(g, "${((FishingDerby.COUNTDOWN_MS - (now() - startedAt)) / 1000 + 1).coerceAtLeast(1)}", cx, sceneTop + sceneH / 2 - 10, 3f, GameColors.GOLD)
			Phase.FISHING -> drawRodStatus(g, cx, x0, w, by)
			Phase.OVER -> drawOver(g, cx, by, mouseX, mouseY) { phase = Phase.WAITING }
			Phase.WAITING -> {}
		}
		for ((i, line) in feed.withIndex()) g.text(font, fit(line, w - 8), x0 + 4, sceneTop + 4 + i * 10, Theme.TEXT, true)
	}

	private fun drawScene(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
		val waterY = y + h * 2 / 5
		g.fillGradient(x, y, x + w, waterY, 0xFF6FA8E8.toInt(), 0xFFB7D8F5.toInt())
		g.fillGradient(x, waterY, x + w, y + h, 0xFF2C6FB0.toInt(), 0xFF123A66.toInt())
		// Gentle waves
		val t = now() / 400.0
		for (row in 0 until 4) {
			val wy = waterY + 6 + row * (h - (waterY - y)) / 5
			var wx = x + ((t * 6 + row * 17) % 24).toInt() - 24
			while (wx < x + w) {
				if (wx >= x) g.fill(wx, wy, (wx + 10).coerceAtMost(x + w), wy + 1, 0x50FFFFFF)
				wx += 24
			}
		}
		// Dock on the left
		g.fill(x, waterY - 6, x + w / 5, waterY + 4, 0xFF7A5230.toInt())
		g.fill(x, waterY - 6, x + w / 5, waterY - 4, 0xFF9B6B3F.toInt())
		// Line and bobber
		val bobX = x + w * 3 / 5
		val bobBase = waterY + 10
		val bob = when (rod) {
			Rod.WAITING -> (sin(now() / 300.0) * 1.5).toInt()
			Rod.BITE -> 4 + (sin(now() / 60.0) * 2).toInt()
			else -> 0
		}
		if (rod == Rod.WAITING || rod == Rod.BITE || rod == Rod.REEL) {
			val rodTipX = x + w / 5 - 2
			val rodTipY = waterY - 30
			val steps = 24
			for (s in 0..steps) {
				val f = s / steps.toFloat()
				val lx = rodTipX + ((bobX - rodTipX) * f).toInt()
				val ly = rodTipY + ((bobBase + bob - rodTipY) * f * f).toInt()
				g.fill(lx, ly, lx + 1, ly + 1, 0xC0EEEEEE.toInt())
			}
			g.fill(bobX - 2, bobBase + bob - 3, bobX + 3, bobBase + bob, 0xFFE03030.toInt())
			g.fill(bobX - 2, bobBase + bob, bobX + 3, bobBase + bob + 2, 0xFFF0F0F0.toInt())
			if (rod == Rod.BITE) bigText(g, "!", bobX, bobBase - 22, 2f, GameColors.GOLD)
		}
		g.fill(x + w / 5 - 14, waterY - 30, x + w / 5 - 2, waterY - 29, 0xFF5A3A1E.toInt())
		// The fresh catch pops up
		val c = last
		if (rod == Rod.CAUGHT && c != null && now() - rodAt < 1_600) {
			val rise = ((now() - rodAt) / 1600f * 20).toInt()
			val pose = g.pose()
			pose.pushMatrix(); pose.translate((bobX - 16).toFloat(), (waterY - 30 - rise).toFloat()); pose.scale(2f, 2f)
			g.item(ItemStack(c.icon), 0, 0)
			pose.popMatrix()
		}
		g.outline(x, y, w, h, ColorUtil.withAlpha(0xFFFFFFFF.toInt(), 0x40))
	}

	private fun drawRodStatus(g: GuiGraphicsExtractor, cx: Int, x0: Int, w: Int, y: Int) {
		when (rod) {
			Rod.IDLE -> centered(g, "§fClick or press §lSpace§r§f to cast", cx, y + 4, Theme.TEXT)
			Rod.WAITING -> centered(g, "§7Wait for the bite…", cx, y + 4, Theme.TEXT)
			Rod.BITE -> centered(g, "§6§lBITE! §fClick now!", cx, y + 4, Theme.TEXT)
			Rod.REEL -> {
				// Stop the marker in the green zone; the center gives the rarest catches.
				val bw = w - 40
				val bx = cx - bw / 2
				g.fill(bx, y, bx + bw, y + 12, 0xFF1A1A24.toInt())
				val zx0 = bx + ((zoneCenter - zoneHalf) * bw).toInt()
				val zx1 = bx + ((zoneCenter + zoneHalf) * bw).toInt()
				g.fill(zx0, y, zx1, y + 12, ColorUtil.withAlpha(GameColors.GREEN, 0xB0))
				val center = bx + (zoneCenter * bw).toInt()
				g.fill(center - 1, y, center + 1, y + 12, GameColors.GOLD)
				val mx = bx + (marker() * bw).toInt()
				g.fill(mx - 1, y - 2, mx + 2, y + 14, 0xFFFFFFFF.toInt())
				centered(g, "§fClick to reel in §8(center = rarer)", cx, y + 18, Theme.TEXT)
			}
			Rod.CAUGHT -> last?.let { centered(g, "${FishingDerby.tierColor(it.tier)}§l${it.name} §6+${it.points} §8· click to cast again", cx, y + 4, Theme.TEXT) }
			Rod.ESCAPED -> centered(g, "§cIt got away. §7Click to cast again", cx, y + 4, Theme.TEXT)
		}
	}

	/** Test hooks. */
	val debugRod get() = rod
	val debugPhase get() = phase
	fun debugAction() = action()
}
