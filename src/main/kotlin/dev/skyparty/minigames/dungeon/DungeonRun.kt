package dev.skyparty.minigames.dungeon

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import dev.skyparty.minigames.GameLength
import dev.skyparty.minigames.LobbyRoom
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.minigames.dungeon.DungeonGen.Mob
import dev.skyparty.minigames.dungeon.DungeonGen.Power
import dev.skyparty.minigames.parkour.ParkourLevel
import dev.skyparty.minigames.parkour.ParkourPhysics
import dev.skyparty.minigames.parkour.ParkourPhysics.FP
import dev.skyparty.minigames.parkour.ParkourPhysics.Input
import dev.skyparty.minigames.parkour.ParkourPhysics.Outcome
import dev.skyparty.minigames.parkour.ParkourPhysics.State
import dev.skyparty.minigames.parkour.ParkourPhysics.TILE
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.net.Net
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dungeon Run: a co-op side-scroller for up to 5. Pick a class, run and jump through a long generated dungeon,
 * fight ghouls, archers, brutes and bats, beat the miniboss to open its gate and the Dungeon Warden at the end.
 * Physics are the parkour game's. Downed players must be revived by a teammate; when everyone is down it's over.
 *
 * Sync: the host runs the monsters, arrows and bosses and sends a world snapshot 4x a second (only monsters near
 * players, plus the ones that died since the last one); every player sends their own state, the monsters they
 * hit and the shots they fired 4x a second. Damage to you is worked out on your own client. No message type ends
 * in ".state", so queued hits are never coalesced away. Payloads stay well under the 4 KB message limit.
 *
 * Protocol (echoed to the sender):
 *  - "dun.cls" {c}                    a player's class pick
 *  - "dun.pick" {}                    (host) back to class selection
 *  - "dun.start.rng" {}               (host) new run; the roll is the dungeon seed
 *  - "dun.p" {x, y, f, a, hp, c, fx, h:[[mob, dmg]], s:[[kind, x, y, vx100, vy100]]}
 *  - "dun.w" {p:[x,y,f,a,hp,c,fx], m:[[id,x,y,hp,f,j]], d:[ids], sm:[[id,type,x,y]], ar:[[id,x,y,vx100,vy100,dmg]],
 *             s:[...], slam, boss, mini, won, lost, k:{uuid:kills}}   (host) world
 *  - "dun.rev" {t}                    revive player t
 *  - "dun.heal" {x, y}                a healer's circle at x, y
 */
object DungeonRun : Minigame {
	override val id = "dungeon"
	override val name = "Dungeon Run"
	override val description = "Co-op side-scroller for up to 5: pick a class, fight through a dungeon, revive your team and beat the boss."
	override val minPlayers = 1
	override val maxPlayers = 5
	override val realtime = true
	override val length = GameLength.LONG

	const val RESPAWN_MS = 3_000L
	const val SOLO_LIVES = 3

	override fun open(room: Net.Room?) = MinigameHub.openScreen(DungeonScreen(LobbyRoom.of(room, id)))

	val ZOMBIE_TEX: Identifier = Identifier.withDefaultNamespace("textures/entity/zombie/zombie.png")
	val HUSK_TEX: Identifier = Identifier.withDefaultNamespace("textures/entity/zombie/husk.png")
	val SKELETON_TEX: Identifier = Identifier.withDefaultNamespace("textures/entity/skeleton/skeleton.png")
	val STRAY_TEX: Identifier = Identifier.withDefaultNamespace("textures/entity/skeleton/stray.png")
	val BOSS_TEX: Identifier = Identifier.withDefaultNamespace("textures/entity/skeleton/wither_skeleton.png")

	/** The class you picked last time, preselected next time. */
	var lastClass: DClass? = null
}

/** Dungeon classes. HP is in half hearts; cooldowns in physics steps (60/s). */
enum class DClass(
	val title: String, val color: Int, val maxHp: Int, val weapon: String,
	val ability: String, val abilityInfo: String, val abilityCd: Int, val attackCd: Int,
) {
	BERSERK("Berserk", 0xFFE0503C.toInt(), 20, "Sword: heavy melee hits", "Ragnarok", "6s of double damage", 20 * 60, 22),
	ARCHER("Archer", 0xFF62D062.toInt(), 16, "Bow: long range arrows", "Arrow Volley", "5 arrows at once", 12 * 60, 30),
	MAGE("Mage", 0xFF62A6FF.toInt(), 16, "Staff: bolts pierce enemies", "Fireball", "big explosion", 14 * 60, 28),
	HEALER("Healer", 0xFFFF80D4.toInt(), 20, "Wand: weak bolts", "Healing Circle", "heals team, fast revives", 16 * 60, 22),
	TANK("Tank", 0xFFB4B8C4.toInt(), 32, "Sword & shield", "Castle of Stone", "5s invulnerable", 20 * 60, 26),
}

class DungeonScreen(val room: LobbyRoom) : GameScreen("Dungeon Run", room) {
	enum class Phase { PICK, WAITING, COUNTDOWN, RUN, WON, LOST }

	override fun inProgress() = phase == Phase.COUNTDOWN || phase == Phase.RUN || phase == Phase.WAITING

	/** A monster. [x], [y] are the top-left of its hitbox in world pixels; ground monsters stand on [floorY]. */
	class MobState(val id: Int, val type: Mob, var x: Float, val floorY: Float, val minX: Float, val maxX: Float, val maxHp: Int) {
		var hp = maxHp
		val w = if (type == Mob.BAT) 16f else 8f * type.big
		val h = if (type == Mob.BAT) 12f else 32f * type.big
		val baseY = floorY - 72f
		var y = if (type == Mob.BAT) baseY else floorY - h
		var facing = -1
		var cooldown = 0
		var alt = 0
		/** Boss: steps left of a jump. Miniboss: steps left of a dash. Bat: steps left of a swoop. */
		var jump = 0
		var stun = 0
		var stage = 0
		// Client-side interpolation between snapshots
		var px = x; var py = y; var tx = x; var ty = y; var snapAt = 0L
		val alive get() = hp > 0
	}

	class Arrow(val id: Int, var x: Float, var y: Float, val vx: Float, val vy: Float, val dmg: Int, var life: Int = 200)

	/** A player's projectile. Only your own ([own]) ones hit; the others are drawn. kind: 0 arrow, 1 bolt, 2 wand, 3 fireball. */
	class Shot(var x: Float, var y: Float, val vx: Float, val vy: Float, val kind: Int, val own: Boolean, var life: Int, val dmg: Int) {
		val hit = HashSet<Int>()
	}

	private class Effect(val x: Float, val y: Float, val r: Float, val color: Int, var life: Int, val max: Int = life)
	private class Ally(var x: Float, var y: Float, var px: Float, var py: Float, var at: Long) {
		var facing = 1; var anim = 0; var hp = 1; var cls = DClass.BERSERK; var fx = 0
	}

	private var phase = Phase.PICK
	private var dungeon: DungeonGen.Dungeon? = null
	private val level get() = dungeon?.level
	private var state = State(0, 0)
	private var prevX = 0
	private var prevY = 0
	private var steps = 0
	private var lastStepNanos = 0L
	private var countdownUntil = 0L
	private var checkpoint = 0

	private val classes = HashMap<UUID, DClass>()
	private val myClass get() = classes[Net.selfId] ?: DClass.BERSERK

	private var hp = 20
	private var downed = false
	private var respawnAt = 0L
	private var loseAt = 0L
	private var lives = DungeonRun.SOLO_LIVES
	private var invuln = 0
	private var deaths = 0
	private var attackCd = 0
	private var abilityCd = 0
	private var swing = 0
	private val swingHit = mutableSetOf<Int>()
	private var rage = 0
	private var castle = 0
	private var strength = 0
	private val taken = HashSet<Int>()
	private var reviveTarget: UUID? = null
	private var reviveProgress = 0
	private val pendingHits = mutableListOf<Pair<Int, Int>>()
	private val shotsOut = JsonArray()

	private val mobs = linkedMapOf<Int, MobState>()
	private val killedOut = mutableListOf<Int>()
	private val arrows = linkedMapOf<Int, Arrow>()
	private val newArrows = mutableListOf<Arrow>()
	private val shots = mutableListOf<Shot>()
	private val effects = mutableListOf<Effect>()
	private var nextArrow = 0
	private var nextSummon = 1000
	private var slamCount = 0
	private var bossActive = false
	private var miniActive = false
	private var gateOpen = false
	private var allDownSteps = 0
	private val kills = HashMap<UUID, Int>()
	private val deathsBy = HashMap<UUID, Int>()
	private val allies = HashMap<UUID, Ally>()
	private var endSteps = 0
	private var rewarded = false

	private val hostLike get() = room.solo || room.isHost
	private val party get() = room.members.size.coerceIn(1, 5)

	init {
		room.onMessage = ::onMessage
		DungeonRun.lastClass?.let { pick(it) }
	}

	private fun play(s: SoundEvent, pitch: Float = 1f) = Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(s, pitch))

	/** Solo: run it right here. Online: send it; everyone (us included, by echo) handles it. */
	private fun broadcast(type: String, payload: JsonObject) {
		if (room.solo) onMessage(Net.Message(type, payload, Net.selfId, Net.selfName)) else room.send(type, payload)
	}

	// ------------------------------------------------------------------ protocol

	private fun onMessage(msg: Net.Message) {
		val p = msg.payload
		when (msg.type) {
			"dun.cls" -> runCatching { DClass.valueOf(p["c"].asString) }.getOrNull()?.let { classes[msg.from] = it }
			"dun.pick" -> if (msg.from == room.host) { phase = Phase.PICK; dungeon = null }
			"dun.start.rng" -> if (msg.from == room.host && !room.solo) begin(((p["roll"]?.asDouble ?: Math.random()) * 1_000_000_000).toLong() + 1)
			"dun.p" -> if (msg.from != Net.selfId) {
				readAlly(msg.from, p["x"]?.asFloat ?: return, p["y"]?.asFloat ?: return, p["f"]?.asInt ?: 1, p["a"]?.asInt ?: 0,
					p["hp"]?.asInt ?: 1, p["c"]?.asInt ?: 0, p["fx"]?.asInt ?: 0)
				p["s"]?.asJsonArray?.let(::readShots)
				if (hostLike) p["h"]?.asJsonArray?.forEach { el -> el.asJsonArray.let { a -> applyHit(a[0].asInt, a[1].asInt.coerceIn(0, 20), msg.from) } }
			}
			"dun.w" -> if (msg.from == room.host && !hostLike) readWorld(msg.from, p)
			"dun.rev" -> {
				val t = runCatching { UUID.fromString(p["t"].asString) }.getOrNull() ?: return
				if (t == Net.selfId && downed && phase == Phase.RUN) revive()
				allies[t]?.let { it.hp = 1 } // alive again until their next update
			}
			"dun.heal" -> {
				val x = p["x"]?.asFloat ?: return
				val y = p["y"]?.asFloat ?: return
				effects += Effect(x + 4, y + 16, 90f, 0xFFFF80D4.toInt(), 30)
				if (!downed && phase == Phase.RUN && abs(myX - x) < 90 && abs(myY - y) < 80) { hp = (hp + 8).coerceAtMost(myClass.maxHp); play(SoundEvents.PLAYER_LEVELUP, 1.8f) }
			}
		}
	}

	private fun readAlly(from: UUID, x: Float, y: Float, f: Int, a: Int, ahp: Int, c: Int, fx: Int) {
		val al = allies.getOrPut(from) { Ally(x, y, x, y, 0) }
		al.px = al.x; al.py = al.y; al.x = x; al.y = y
		al.facing = f; al.anim = a; al.hp = ahp; al.fx = fx
		al.cls = DClass.entries.getOrElse(c) { DClass.BERSERK }
		al.at = room.now()
	}

	private fun readShots(arr: JsonArray) = arr.forEach { el ->
		val a = el.asJsonArray
		val kind = a[0].asInt
		shots += Shot(a[1].asFloat, a[2].asFloat, a[3].asFloat / 100f, a[4].asFloat / 100f, kind, false, shotLife(kind), 0)
	}

	private fun readWorld(from: UUID, p: JsonObject) {
		p["p"]?.asJsonArray?.let { a -> readAlly(from, a[0].asFloat, a[1].asFloat, a[2].asInt, a[3].asInt, a[4].asInt, a[5].asInt, a[6].asInt) }
		val now = room.now()
		p["sm"]?.asJsonArray?.forEach { el ->
			val a = el.asJsonArray
			if (a[0].asInt !in mobs) addMob(a[0].asInt, Mob.entries[a[1].asInt], a[2].asFloat)
		}
		p["m"]?.asJsonArray?.forEach { el ->
			val a = el.asJsonArray
			val m = mobs[a[0].asInt] ?: return@forEach
			m.px = mobX(m); m.py = mobY(m)
			m.tx = a[1].asFloat; m.ty = a[2].asFloat; m.snapAt = now
			m.x = m.tx; m.y = m.ty
			m.hp = a[3].asInt; m.facing = a[4].asInt; m.jump = a[5].asInt
		}
		p["d"]?.asJsonArray?.forEach { el -> mobs[el.asInt]?.let { if (it.alive) { it.hp = 0; onMobDied(it) } } }
		p["ar"]?.asJsonArray?.forEach { el ->
			val a = el.asJsonArray
			val id = a[0].asInt
			if (id !in arrows) arrows[id] = Arrow(id, a[1].asFloat, a[2].asFloat, a[3].asFloat / 100f, a[4].asFloat / 100f, a[5].asInt)
		}
		p["s"]?.asJsonArray?.let(::readShots)
		val slam = p["slam"]?.asInt ?: 0
		if (slam != slamCount) { slamCount = slam; onSlam() }
		if (p["boss"]?.asBoolean == true && !bossActive) { bossActive = true; play(SoundEvents.WITHER_SPAWN, 1.4f) }
		miniActive = p["mini"]?.asBoolean == true
		p["k"]?.asJsonObject?.entrySet()?.forEach { (k, v) -> runCatching { kills[UUID.fromString(k)] = v.asInt } }
		if (p["won"]?.asBoolean == true && phase == Phase.RUN) win()
		if (p["lost"]?.asBoolean == true && phase == Phase.RUN) lose()
	}

	// ------------------------------------------------------------------ setup

	private fun pick(c: DClass) {
		DungeonRun.lastClass = c
		if (room.solo) classes[Net.selfId] = c
		else room.send("dun.cls", JsonObject().apply { addProperty("c", c.name) })
	}

	private fun startRun() {
		if (room.solo) begin(kotlin.random.Random.nextLong(1, 1_000_000_000)) else room.send("dun.start.rng", JsonObject())
	}

	private fun begin(s: Long) {
		phase = Phase.WAITING
		dungeon = null
		Thread({
			val d = DungeonGen.generate(s)
			Minecraft.getInstance().execute {
				dungeon = d
				reset()
				countdownUntil = room.now() + 3000
				phase = Phase.COUNTDOWN
			}
		}, "Skyparty-DungeonGen").apply { isDaemon = true }.start()
	}

	private fun mobHp(type: Mob) = (type.hp * when (type) {
		Mob.BOSS, Mob.MINIBOSS -> 0.5f + 0.5f * party
		else -> 0.75f + 0.25f * party
	}).toInt().coerceAtLeast(1)

	private fun addMob(id: Int, type: Mob, x: Float) {
		val d = dungeon ?: return
		val boss = d.spawns.last()
		mobs[id] = MobState(id, type, x, (DungeonGen.BASE * TILE).toFloat(), boss.minCol * TILE.toFloat(), (boss.maxCol + 1) * TILE - 8f, mobHp(type))
	}

	private fun reset() {
		val d = dungeon ?: return
		classes.putIfAbsent(Net.selfId, DungeonRun.lastClass ?: DClass.BERSERK)
		state = d.level.checkpoints.first().spawnState()
		prevX = state.x; prevY = state.y
		steps = 0; checkpoint = 0; hp = myClass.maxHp; deaths = 0; invuln = 0; downed = false; respawnAt = 0; loseAt = 0
		lives = DungeonRun.SOLO_LIVES; attackCd = 0; abilityCd = 0; swing = 0; rage = 0; castle = 0; strength = 0
		taken.clear(); pendingHits.clear(); shots.clear(); effects.clear(); reviveTarget = null; reviveProgress = 0
		mobs.clear(); arrows.clear(); kills.clear(); deathsBy.clear(); killedOut.clear(); newArrows.clear()
		bossActive = false; miniActive = false; gateOpen = false; slamCount = 0; rewarded = false; allDownSteps = 0
		nextArrow = 0; nextSummon = 1000
		for ((i, sp) in d.spawns.withIndex()) {
			val w = if (sp.mob == Mob.BAT) 16f else 8f * sp.mob.big
			mobs[i] = MobState(i, sp.mob, sp.col * TILE + (TILE - w) / 2, (sp.floor * TILE).toFloat(),
				sp.minCol * TILE.toFloat(), (sp.maxCol + 1) * TILE - w, mobHp(sp.mob))
		}
	}

	// ------------------------------------------------------------------ simulation

	private fun keyDown(vararg keys: Int): Boolean {
		val w = Minecraft.getInstance().window
		return keys.any { InputConstants.isKeyDown(w, it) }
	}

	private fun readInput(): Input {
		val dir = (if (keyDown(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT)) 1 else 0) - (if (keyDown(GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT)) 1 else 0)
		return Input(dir, keyDown(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP))
	}

	/** Test hook: inputs to use instead of the keyboard. */
	var autoInput: Input? = null

	private fun simulate() {
		val l = level ?: return
		val now = room.nanos()
		if (phase == Phase.COUNTDOWN && room.now() >= countdownUntil) { phase = Phase.RUN; lastStepNanos = now }
		if (phase != Phase.RUN) return
		val stepNanos = 1_000_000_000L / ParkourPhysics.STEPS_PER_SECOND
		var budget = 8
		while (now - lastStepNanos >= stepNanos && budget-- > 0) {
			lastStepNanos += stepNanos
			step(l)
			if (phase != Phase.RUN) return
		}
		if (now - lastStepNanos > stepNanos * 8) lastStepNanos = now
	}

	private val myX get() = state.x / FP.toFloat()
	private val myY get() = state.y / FP.toFloat()

	private fun step(l: ParkourLevel) {
		steps++
		if (invuln > 0) invuln--
		if (attackCd > 0) attackCd--
		if (abilityCd > 0) abilityCd--
		if (rage > 0) rage--
		if (castle > 0) castle--
		if (strength > 0) strength--
		val now = room.now()
		if (downed) {
			if (respawnAt != 0L && now >= respawnAt) { respawnAt = 0; revive(atCheckpoint = true) }
			if (loseAt != 0L && now >= loseAt) { lose(); return }
		} else {
			prevX = state.x; prevY = state.y
			val outcome = ParkourPhysics.step(l, state, autoInput ?: readInput())
			while (checkpoint + 1 < l.checkpoints.size && state.x >= l.checkpoints[checkpoint + 1].col * ParkourPhysics.TILE_FP) checkpoint++
			if (outcome == Outcome.DIED) damage(6, fell = true)
			pickUps()
			if (autoInput == null && keyDown(GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_J)) attack()
			reviveStep()
		}
		if (swing > 0) { swing--; checkSwing() }
		moveShots(l)
		if (hostLike) hostStep(l)
		moveArrows(l)
		if (!downed) checkContact()
		effects.removeAll { --it.life <= 0 }
		if (!gateOpen && mobs.values.any { it.type == Mob.MINIBOSS && !it.alive }) openGate()
		if (room.isHost && !room.solo) checkAllDown()
		if (steps % 15 == 0) sendUpdate()
	}

	/** Everyone alive, for the monsters: top-left positions. */
	private fun players(): List<Pair<Float, Float>> {
		val list = mutableListOf<Pair<Float, Float>>()
		if (!downed) list += myX to myY
		val now = room.now()
		for (a in allies.values) if (a.hp > 0 && now - a.at < 3_000) list += a.x to a.y
		return list
	}

	private fun hostStep(l: ParkourLevel) {
		val d = dungeon ?: return
		val ps = players()
		for (m in mobs.values.toList()) {
			if (!m.alive) continue
			if (m.cooldown > 0) m.cooldown--
			if (m.alt > 0) m.alt--
			if (m.stun > 0) { m.stun--; continue }
			val cx = m.x + m.w / 2
			if (m.type != Mob.BOSS && m.type != Mob.MINIBOSS && ps.none { abs(it.first - cx) < 420 }) continue
			val band = ps.filter { abs(it.second + 32 - m.floorY) < 72 }
			val target = band.minByOrNull { abs(it.first + 4 - cx) }
			val dx = target?.let { it.first + 4 - cx } ?: 0f
			when (m.type) {
				Mob.ZOMBIE, Mob.BRUTE -> {
					val chase = target != null && abs(dx) < 170
					val speed = if (m.type == Mob.BRUTE) (if (chase) 0.55f else 0.25f) else (if (chase) 0.85f else 0.35f)
					if (chase && abs(dx) > 2) m.facing = sign(dx).toInt()
					walk(m, speed, chase)
				}
				Mob.SKELETON -> {
					val t = ps.minByOrNull { abs(it.first - cx) }
					if (t != null && abs(t.first - cx) < 230 && abs(t.second - m.y) < 120) {
						m.facing = if (t.first + 4 >= cx) 1 else -1
						if (m.cooldown == 0) { m.cooldown = 100; shootAt(m, t, 2.6f, 3) }
					}
				}
				Mob.BAT -> {
					val t = ps.minByOrNull { abs(it.first - cx) + abs(it.second - m.y) }
					if (m.jump > 0 && t != null) {
						// Swoop at the player.
						m.jump--
						val tx = t.first + 4 - cx
						val ty = t.second + 12 - m.y
						val len = sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
						m.x += tx / len * 1.4f; m.y += ty / len * 1.4f
						m.facing = if (tx >= 0) 1 else -1
						if (m.jump == 0) m.cooldown = 110
					} else {
						m.x += m.facing * 0.5f
						if (m.x <= m.minX) m.facing = 1
						if (m.x >= m.maxX) m.facing = -1
						m.y += (m.baseY + sin(steps * 0.08f + m.id) * 10f - m.y) * 0.05f
						if (m.cooldown == 0 && t != null && abs(t.first - cx) < 140 && abs(t.second - m.y) < 110) m.jump = 80
					}
					if (l.solid(Math.floorDiv(m.x.toInt(), TILE), Math.floorDiv(m.y.toInt(), TILE))) m.y += 1.5f
				}
				Mob.MINIBOSS -> {
					if (!miniActive) {
						if (ps.any { it.first >= m.minX && it.first <= m.maxX + 24 }) { miniActive = true; play(SoundEvents.WITHER_SKELETON_AMBIENT, 0.6f) } else continue
					}
					if (m.jump > 0) {
						m.jump--
						m.x = (m.x + m.facing * 3.2f).coerceIn(m.minX, m.maxX)
					} else {
						if (target != null && abs(dx) > 2) m.facing = sign(dx).toInt()
						m.x = (m.x + m.facing * 0.8f).coerceIn(m.minX, m.maxX)
						if (m.cooldown == 0 && target != null) { m.cooldown = 170; m.jump = 26 }
						val t = ps.minByOrNull { abs(it.first - cx) }
						if (m.alt == 0 && t != null) { m.alt = 120; shootAt(m, t, 3f, 4) }
					}
				}
				Mob.BOSS -> {
					if (!bossActive) {
						if (ps.any { it.first >= d.arenaStart * TILE }) { bossActive = true; play(SoundEvents.WITHER_SPAWN, 1.4f) } else continue
					}
					if (m.jump > 0) {
						m.jump--
						if (m.jump == 0) { slamCount++; onSlam() }
					} else {
						if (target != null && abs(dx) > 2) m.facing = sign(dx).toInt()
						m.x = (m.x + m.facing * 0.6f).coerceIn(m.minX, m.maxX)
						if (m.cooldown == 0) { m.cooldown = 240; m.jump = 40 }
						if (m.alt == 0) { val t = ps.minByOrNull { abs(it.first - cx) }; if (t != null) { m.alt = 150; shootAt(m, t, 2.8f, 4) } }
					}
					m.y = m.floorY - m.h - (if (m.jump > 0) jumpLift(m.jump) else 0f)
					// At 2/3 and 1/3 health it calls help.
					val stage = when { m.hp <= m.maxHp / 3 -> 2; m.hp <= m.maxHp * 2 / 3 -> 1; else -> 0 }
					while (m.stage < stage) {
						m.stage++
						addMob(nextSummon++, Mob.ZOMBIE, m.minX + 24f)
						addMob(nextSummon++, if (m.stage == 2) Mob.BRUTE else Mob.ZOMBIE, m.maxX - 24f)
						play(SoundEvents.ZOMBIE_AMBIENT, 0.7f)
					}
				}
			}
		}
	}

	private fun walk(m: MobState, speed: Float, chase: Boolean) {
		m.x += m.facing * speed
		if (m.x <= m.minX) { m.x = m.minX; if (!chase) m.facing = 1 }
		if (m.x >= m.maxX) { m.x = m.maxX; if (!chase) m.facing = -1 }
		m.y = m.floorY - m.h
	}

	private fun shootAt(m: MobState, t: Pair<Float, Float>, speed: Float, dmg: Int) {
		val sx = m.x + m.w / 2 + m.facing * 4
		val sy = m.y + m.h * 0.3f
		val dx = t.first + 4 - sx
		val dy = t.second + 14 - sy
		val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
		val a = Arrow(nextArrow++, sx, sy, dx / len * speed, dy / len * speed, dmg)
		arrows[a.id] = a
		newArrows += a
	}

	private fun moveArrows(l: ParkourLevel) {
		val it = arrows.values.iterator()
		while (it.hasNext()) {
			val a = it.next()
			a.x += a.vx; a.y += a.vy
			if (--a.life <= 0 || l.solid(Math.floorDiv(a.x.toInt(), TILE), Math.floorDiv(a.y.toInt(), TILE))) { it.remove(); continue }
			// Arrows only hurt you (each client checks itself). The tank's castle blocks them.
			if (!downed && a.x in myX - 1..myX + 9 && a.y in myY..myY + 32) { it.remove(); damage(a.dmg) }
		}
	}

	/** Bumping into a monster hurts. */
	private fun checkContact() {
		if (invuln > 0 || castle > 0) return
		for (m in mobs.values) {
			if (!m.alive || m.type.touch == 0) continue
			val mx = mobX(m)
			val my = mobY(m)
			if (myX < mx + m.w && myX + 8 > mx && myY < my + m.h && myY + 32 > my) {
				damage(m.type.touch)
				state.vx = if (myX + 4 < mx + m.w / 2) -700 else 700
				state.vy = -500
				return
			}
		}
	}

	private fun onSlam() {
		play(SoundEvents.GENERIC_EXPLODE.value(), 1.3f)
		val boss = mobs.values.firstOrNull { it.type == Mob.BOSS } ?: return
		effects += Effect(boss.x + boss.w / 2, boss.floorY, 80f, 0xFFB040E0.toInt(), 20)
		if (!downed && state.grounded && abs(myX + 4 - (mobX(boss) + boss.w / 2)) < 80) damage(4)
	}

	private fun jumpLift(left: Int): Float {
		val t = (40 - left) / 40f
		return 56f * 4 * t * (1 - t)
	}

	private fun damage(n: Int, fell: Boolean = false) {
		if (downed || phase != Phase.RUN) return
		if (!fell && (invuln > 0 || castle > 0)) return
		hp -= n
		invuln = 45
		play(SoundEvents.PLAYER_HURT, 1f)
		if (fell) {
			// Into a pit or onto spikes: back to the last torch.
			state = level!!.checkpoints[checkpoint].spawnState()
			prevX = state.x; prevY = state.y
		}
		if (hp <= 0) down()
	}

	private fun down() {
		hp = 0
		downed = true
		deaths++
		swing = 0; rage = 0; castle = 0
		state.vx = 0
		play(SoundEvents.PLAYER_DEATH, 1f)
		if (room.solo) {
			lives--
			if (lives > 0) respawnAt = room.now() + DungeonRun.RESPAWN_MS else loseAt = room.now() + 1_500
		}
	}

	private fun revive(atCheckpoint: Boolean = false) {
		downed = false
		hp = if (atCheckpoint) myClass.maxHp else myClass.maxHp / 2
		invuln = 120
		if (atCheckpoint) {
			state = level!!.checkpoints[checkpoint].spawnState()
			prevX = state.x; prevY = state.y
		}
		play(SoundEvents.TOTEM_USE, 1.5f)
	}

	/** Hold E next to a downed teammate to bring them back. */
	private fun reviveStep() {
		val target = if (keyDown(GLFW.GLFW_KEY_E)) allies.entries.filter { (_, a) -> a.hp <= 0 && room.now() - a.at < 5_000 }
			.firstOrNull { (_, a) -> abs(a.x - myX) < 30 && abs(a.y - myY) < 40 }?.key else null
		if (target == null) { reviveTarget = null; reviveProgress = 0; return }
		if (target != reviveTarget) { reviveTarget = target; reviveProgress = 0 }
		reviveProgress++
		if (reviveProgress >= reviveNeed) {
			reviveProgress = 0; reviveTarget = null
			broadcast("dun.rev", JsonObject().apply { addProperty("t", target.toString()) })
		}
	}

	private val reviveNeed get() = if (myClass == DClass.HEALER) 60 else 150

	/** Host: when everyone is down for a second, the run is lost. */
	private fun checkAllDown() {
		val now = room.now()
		val others = allies.values.filter { now - it.at < 5_000 }
		allDownSteps = if (downed && others.all { it.hp <= 0 }) allDownSteps + 1 else 0
		if (allDownSteps > 60) lose()
	}

	private fun pickUps() {
		val d = dungeon ?: return
		for (p in d.powerUps) {
			if (p.id in taken) continue
			if (abs(myX + 4 - p.x) < 10 && abs(myY + 16 - p.y) < 22) {
				taken += p.id
				when (p.kind) {
					Power.STRENGTH -> strength = 15 * 60
					Power.HEAL -> hp = (hp + 8).coerceAtMost(myClass.maxHp)
				}
				effects += Effect(p.x, p.y, 20f, if (p.kind == Power.STRENGTH) 0xFFFF5030.toInt() else 0xFFFF80C0.toInt(), 14)
				play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f)
			}
		}
	}

	private fun openGate() {
		val d = dungeon ?: return
		gateOpen = true
		for (y in DungeonGen.CEILING until DungeonGen.BASE) d.level.set(d.gateCol, y, ParkourLevel.EMPTY)
		play(SoundEvents.IRON_DOOR_OPEN, 0.8f)
	}

	// ------------------------------------------------------------------ attacks and abilities

	private fun power(base: Int) = ceil(base * (if (strength > 0) 1.5f else 1f) * (if (rage > 0) 2f else 1f)).toInt()

	private fun attack() {
		if (phase != Phase.RUN || downed || attackCd > 0) return
		val c = myClass
		attackCd = if (rage > 0) c.attackCd * 2 / 3 else c.attackCd
		when (c) {
			DClass.BERSERK, DClass.TANK -> {
				swing = 8
				swingHit.clear()
				play(SoundEvents.PLAYER_ATTACK_SWEEP, if (c == DClass.TANK) 0.9f else 1.2f)
			}
			DClass.ARCHER -> { fire(0, 5f, 0f, power(2)); play(SoundEvents.ARROW_SHOOT, 1.2f) }
			DClass.MAGE -> { fire(1, 4f, 0f, power(2)); play(SoundEvents.BLAZE_SHOOT, 1.6f) }
			DClass.HEALER -> { fire(2, 4f, 0f, power(1)); play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.4f) }
		}
	}

	private fun ability() {
		if (phase != Phase.RUN || downed || abilityCd > 0) return
		val c = myClass
		abilityCd = c.abilityCd
		when (c) {
			DClass.BERSERK -> { rage = 6 * 60; play(SoundEvents.RAVAGER_ROAR, 1.3f) }
			DClass.ARCHER -> { for (k in -2..2) fire(0, 5f, k * 0.45f, power(2)); play(SoundEvents.ARROW_SHOOT, 0.9f) }
			DClass.MAGE -> { fire(3, 2.6f, 0f, power(8)); play(SoundEvents.BLAZE_SHOOT, 0.8f) }
			DClass.HEALER -> broadcast("dun.heal", JsonObject().apply { addProperty("x", myX); addProperty("y", myY) })
			DClass.TANK -> { castle = 5 * 60; play(SoundEvents.ANVIL_LAND, 0.8f) }
		}
	}

	private fun shotLife(kind: Int) = when (kind) { 0 -> 70; 1 -> 45; 2 -> 32; else -> 90 }

	private fun fire(kind: Int, speed: Float, vy: Float, dmg: Int) {
		val f = state.facing
		val s = Shot(myX + 4 + f * 6, myY + 13, f * speed, vy, kind, true, shotLife(kind), dmg)
		shots += s
		shotsOut.add(JsonArray().apply { add(kind); add(s.x.toInt()); add(s.y.toInt()); add((s.vx * 100).toInt()); add((s.vy * 100).toInt()) })
	}

	private fun moveShots(l: ParkourLevel) {
		val it = shots.iterator()
		while (it.hasNext()) {
			val s = it.next()
			s.x += s.vx; s.y += s.vy
			val wall = l.solid(Math.floorDiv(s.x.toInt(), TILE), Math.floorDiv(s.y.toInt(), TILE))
			var done = --s.life <= 0 || wall
			if (!done) for (m in mobs.values) {
				if (!m.alive || m.id in s.hit || (s.own && !canHit(m))) continue
				val mx = mobX(m); val my = mobY(m)
				if (s.x in mx - 2..mx + m.w + 2 && s.y in my - 2..my + m.h + 2) {
					s.hit += m.id
					if (s.kind == 3) { done = true; break }
					if (s.own) hit(m, s.dmg)
					if (s.kind != 1) { done = true; break } // mage bolts pierce
				}
			}
			if (done) {
				if (s.kind == 3) explode(s)
				it.remove()
			}
		}
	}


	/** Bosses can only be hurt from inside their own room (no shooting in from outside). */
	private fun canHit(m: MobState): Boolean {
		val d = dungeon ?: return true
		return when (m.type) {
			Mob.BOSS -> bossActive && myX >= d.arenaStart * TILE
			Mob.MINIBOSS -> miniActive && myX >= d.miniStart * TILE && myX < d.gateCol * TILE
			else -> true
		}
	}
	private fun explode(s: Shot) {
		effects += Effect(s.x, s.y, 44f, 0xFFFF8030.toInt(), 18)
		play(SoundEvents.GENERIC_EXPLODE.value(), 1.5f)
		if (!s.own) return
		for (m in mobs.values) if (m.alive && canHit(m) && abs(mobX(m) + m.w / 2 - s.x) < 44 + m.w / 2 && abs(mobY(m) + m.h / 2 - s.y) < 44 + m.h / 2) hit(m, s.dmg)
	}

	/** While swinging: hit each monster in front of you once. */
	private fun checkSwing() {
		val f = state.facing
		val reach = if (myClass == DClass.BERSERK) 24 else 20
		val hx0 = if (f > 0) myX + 4 else myX + 4 - reach
		val hx1 = hx0 + reach
		for (m in mobs.values) {
			if (!m.alive || m.id in swingHit || !canHit(m)) continue
			val mx = mobX(m); val my = mobY(m)
			if (mx < hx1 && mx + m.w > hx0 && my < myY + 32 && my + m.h > myY - 6) {
				swingHit += m.id
				// A falling strike hits harder.
				val base = (if (myClass == DClass.BERSERK) 3 else 2) + (if (!state.grounded && state.vy > 0) 1 else 0)
				hit(m, power(base))
				play(SoundEvents.PLAYER_ATTACK_STRONG, 1f)
			}
		}
	}

	private fun hit(m: MobState, dmg: Int) {
		effects += Effect(mobX(m) + m.w / 2, mobY(m) + m.h / 3, 6f, 0xFFFFFFFF.toInt(), 6)
		if (hostLike) applyHit(m.id, dmg, Net.selfId) else pendingHits += m.id to dmg
	}

	/** Host: apply a player's hit (knockback, kills, gate and victory). */
	private fun applyHit(id: Int, dmg: Int, by: UUID) {
		val m = mobs[id] ?: return
		if (!m.alive || phase != Phase.RUN) return
		if (m.type == Mob.BOSS && !bossActive || m.type == Mob.MINIBOSS && !miniActive) return
		m.hp -= dmg
		if (m.type != Mob.BOSS && m.type != Mob.MINIBOSS) {
			m.x = (m.x - m.facing * 3f).coerceIn(m.minX, m.maxX)
			m.stun = 6
		}
		if (!m.alive) {
			kills[by] = (kills[by] ?: 0) + 1
			killedOut += m.id
			onMobDied(m)
			if (m.type == Mob.BOSS) win()
		}
	}

	private fun onMobDied(m: MobState) {
		effects += Effect(mobX(m) + m.w / 2, mobY(m) + m.h / 2, 14f + m.w, 0xFFAAAAAA.toInt(), 16)
		play(if (m.type == Mob.SKELETON || m.type == Mob.MINIBOSS) SoundEvents.SKELETON_DEATH else SoundEvents.ZOMBIE_DEATH, 1.1f)
	}

	private fun fxBits() = (if (rage > 0) 1 else 0) or (if (castle > 0) 2 else 0) or (if (strength > 0) 4 else 0)
	private fun animState() = when { swing > 0 -> 2; state.grounded && state.vx != 0 -> 1; else -> 0 }

	private fun sendUpdate() {
		if (room.solo) { shotsOut.forEach { }; shotsOut.removeAll { true }; return }
		val shotsCopy = shotsOut.deepCopy()
		shotsOut.removeAll { true }
		if (hostLike) {
			val ps = players()
			val near = mobs.values.filter { m -> m.alive && (m.type == Mob.BOSS && bossActive || ps.any { abs(it.first - m.x) < 480 }) }.take(40)
			room.send("dun.w", JsonObject().apply {
				add("p", JsonArray().apply { add(myX.toInt()); add(myY.toInt()); add(state.facing); add(animState()); add(hp); add(myClass.ordinal); add(fxBits()) })
				add("m", JsonArray().apply { near.forEach { m -> add(JsonArray().apply { add(m.id); add(m.x.toInt()); add(m.y.toInt()); add(m.hp); add(m.facing); add(m.jump) }) } })
				add("d", JsonArray().apply { killedOut.forEach { add(it) } })
				add("sm", JsonArray().apply { mobs.values.filter { it.id >= 1000 && it.alive }.forEach { m -> add(JsonArray().apply { add(m.id); add(m.type.ordinal); add(m.x.toInt()); add(m.y.toInt()) }) } })
				add("ar", JsonArray().apply { newArrows.forEach { a -> add(JsonArray().apply { add(a.id); add(a.x.toInt()); add(a.y.toInt()); add((a.vx * 100).toInt()); add((a.vy * 100).toInt()); add(a.dmg) }) } })
				add("s", shotsCopy)
				addProperty("slam", slamCount)
				addProperty("boss", bossActive)
				addProperty("mini", miniActive)
				addProperty("won", phase == Phase.WON)
				addProperty("lost", phase == Phase.LOST)
				add("k", JsonObject().apply { kills.forEach { (k, v) -> addProperty(k.toString(), v) } })
			})
			newArrows.clear()
			killedOut.clear()
		} else {
			room.send("dun.p", JsonObject().apply {
				addProperty("x", myX.toInt()); addProperty("y", myY.toInt()); addProperty("f", state.facing)
				addProperty("a", animState()); addProperty("hp", hp); addProperty("c", myClass.ordinal); addProperty("fx", fxBits())
				add("h", JsonArray().apply { pendingHits.forEach { (m, d) -> add(JsonArray().apply { add(m); add(d) }) } })
				add("s", shotsCopy)
			})
			pendingHits.clear()
		}
	}

	private fun win() {
		if (phase == Phase.WON) return
		phase = Phase.WON
		endSteps = steps
		if (hostLike) sendUpdate()
		if (!rewarded) {
			rewarded = true
			MinigameStore.addTokens(15)
			MinigameStore.submit(DungeonRun.id, steps / 60.0, true, formatTime(steps))
		}
		play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f)
	}

	private fun lose() {
		if (phase != Phase.RUN) return
		phase = Phase.LOST
		endSteps = steps
		if (room.isHost && !room.solo) sendUpdate()
		play(SoundEvents.WITHER_DEATH, 1.2f)
	}

	// ------------------------------------------------------------------ input

	override fun onClickAnywhere(mx: Double, my: Double): Boolean {
		if (phase != Phase.RUN) return false
		attack()
		return true
	}

	override fun onKey(event: KeyEvent): Boolean {
		if (phase == Phase.RUN) when (event.key()) {
			GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_J -> { attack(); return true }
			GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_K -> { ability(); return true }
			GLFW.GLFW_KEY_E -> return true
		}
		return super.onKey(event)
	}

	// ------------------------------------------------------------------ drawing

	private class View(val camX: Float, val camY: Float, val scale: Float, val top: Float)
	private var view = View(0f, 0f, 1f, 0f)

	/** Monster position as drawn (and hit): on clients it glides between snapshots. */
	private fun mobX(m: MobState): Float {
		if (hostLike || m.snapAt == 0L) return m.x
		val t = ((room.now() - m.snapAt) / 250f).coerceIn(0f, 1f)
		return m.px + (m.tx - m.px) * t
	}

	private fun mobY(m: MobState): Float {
		if (hostLike || m.snapAt == 0L) return m.y
		val t = ((room.now() - m.snapAt) / 250f).coerceIn(0f, 1f)
		return m.py + (m.ty - m.py) * t
	}

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		simulate()
		val top = header(g, "Dungeon Run", if (room.solo) "solo" else "${room.members.size} players")
		if (phase == Phase.PICK) { drawPick(g, top, mouseX, mouseY); return }
		val l = level
		if (l == null) {
			centered(g, "§7Building the dungeon…", width / 2, height / 2, Theme.TEXT)
			return
		}
		val areaTop = top
		val areaH = height - top - 22
		val scale = areaH / (11f * TILE)
		val frac = if (phase == Phase.RUN) ((room.nanos() - lastStepNanos).toFloat() * ParkourPhysics.STEPS_PER_SECOND / 1e9f).coerceIn(0f, 1f) else 1f
		val px = if (downed) myX else (prevX + (state.x - prevX) * frac) / FP
		val py = if (downed) myY else (prevY + (state.y - prevY) * frac) / FP
		val viewW = width / scale
		val viewH = areaH / scale
		val camX = (px - viewW * 0.38f).coerceIn(0f, (l.width * TILE - viewW).coerceAtLeast(0f))
		val camY = (l.height * TILE - viewH).coerceAtLeast(0f) // fixed: the whole dungeon height stays in view
		view = View(camX, camY, scale, areaTop.toFloat())

		g.fillGradient(0, areaTop, width, areaTop + areaH, 0xFF15121C.toInt(), 0xFF241C2E.toInt())
		drawBackWall(g, areaTop, areaH, camX)
		g.enableScissor(0, areaTop, width, areaTop + areaH)
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(0f, areaTop.toFloat()); pose.scale(scale, scale); pose.translate(-camX, -camY)
		drawTiles(g, l, camX, viewW)
		drawPowerUps(g, camX, viewW)
		for (m in mobs.values) if (m.alive && m.type == Mob.BAT) drawBat(g, m)
		for (a in arrows.values) drawArrow(g, a.x, a.y, a.vx, a.vy, if (a.dmg >= 4) 0xFF9AD8FF.toInt() else 0xFFC8B89A.toInt())
		for (s in shots) drawShot(g, s)
		for (e in effects) drawEffect(g, e)
		pose.popMatrix()

		val now = room.now()
		for (m in mobs.values) {
			if (!m.alive || m.type == Mob.BAT) continue
			val x = mobX(m)
			if (x + m.w < camX - 20 || x > camX + viewW + 20) continue
			val (tex, texH, mirror) = when (m.type) {
				Mob.ZOMBIE -> Triple(DungeonRun.ZOMBIE_TEX, 64, true)
				Mob.BRUTE -> Triple(DungeonRun.HUSK_TEX, 64, true)
				Mob.SKELETON -> Triple(DungeonRun.SKELETON_TEX, 32, true)
				Mob.MINIBOSS -> Triple(DungeonRun.STRAY_TEX, 32, true)
				else -> Triple(DungeonRun.BOSS_TEX, 32, true)
			}
			val walking = m.type == Mob.ZOMBIE || m.type == Mob.BRUTE || (m.type == Mob.MINIBOSS && miniActive)
			val stunned = m.stun > 0
			drawFigure(g, tex, texH, mirror, x, mobY(m), m.facing, if (walking) (now / 16).toInt() + m.id * 7 else 0,
				if (stunned) 0xFFFF9090.toInt() else 0xFFFFFFFF.toInt(), m.type.big, m.jump > 0 && m.type == Mob.BOSS,
				armsForward = m.type == Mob.ZOMBIE || m.type == Mob.BRUTE)
			if (m.hp < m.maxHp && m.type != Mob.BOSS && m.type != Mob.MINIBOSS) bar(g, x, mobY(m) - 5f, m.w.coerceAtLeast(8f), m.hp.toFloat() / m.maxHp, GameColors.RED)
		}
		// Teammates and you
		for ((id, a) in allies) {
			if (now - a.at > 5_000) continue
			val t = ((now - a.at) / 250f).coerceIn(0f, 1f)
			val ax = a.px + (a.x - a.px) * t
			val ay = a.py + (a.y - a.py) * t
			val isDown = a.hp <= 0
			drawFigure(g, skinOf(id), 64, false, ax, ay, a.facing, if (a.anim == 1) (now / 16).toInt() else 0,
				if (isDown) 0xFF8890A0.toInt() else 0xEEFFFFFF.toInt(), 1f, false, lying = isDown, cls = a.cls, fx = a.fx,
				label = (if (isDown) "§c✚ " else "") + room.nameOf(id), swinging = a.anim == 2)
			if (isDown && !downed && abs(a.x - myX) < 30 && abs(a.y - myY) < 40) {
				val prog = if (reviveTarget == id) reviveProgress.toFloat() / reviveNeed else 0f
				bar(g, ax - 6, ay + 6, 20f, prog, GameColors.GREEN)
				val sx = ((ax + 4 - camX) * scale).toInt()
				val sy = (areaTop + (ay - 4 - camY) * scale).toInt()
				centered(g, "§aHold E to revive", sx, sy - 22, Theme.TEXT)
			}
		}
		val blink = invuln > 0 && !downed && (steps / 4) % 2 == 0
		drawFigure(g, selfSkin(), 64, false, px, py, state.facing, if (state.grounded && state.vx != 0) steps else 0,
			if (downed) 0xFF8890A0.toInt() else if (blink) 0x66FFFFFF else 0xFFFFFFFF.toInt(), 1f, !state.grounded && !downed,
			lying = downed, cls = myClass, fx = fxBits(), swinging = false)
		if (swing > 0) drawSwing(g, px + 4f, py + 12f, state.facing, 1f - swing / 8f)
		for ((_, a) in allies) if (a.anim == 2 && a.hp > 0 && now - a.at < 600) drawSwing(g, a.x + 4f, a.y + 12f, a.facing, (now % 300) / 300f)
		g.disableScissor()
		drawHud(g, l, areaTop, areaH, mouseX, mouseY)
	}

	private fun bar(g: GuiGraphicsExtractor, wx: Float, wy: Float, ww: Float, frac: Float, color: Int) {
		val v = view
		val sx = (wx - v.camX) * v.scale
		val sy = v.top + (wy - v.camY) * v.scale
		val bw = ww * v.scale
		g.fill(sx.toInt(), sy.toInt(), (sx + bw).toInt(), sy.toInt() + 2, 0xFF1A1A1A.toInt())
		g.fill(sx.toInt(), sy.toInt(), (sx + bw * frac.coerceIn(0f, 1f)).toInt(), sy.toInt() + 2, color)
	}

	private fun drawBackWall(g: GuiGraphicsExtractor, top: Int, h: Int, camX: Float) {
		val off = (camX * 0.3f).toInt()
		for (row in 0 until h / 10 + 1) for (cx in -1 until width / 24 + 2) {
			val x = cx * 24 - off % 24 + (if (row % 2 == 0) 0 else 12)
			val y = top + row * 10
			g.fill(x, y, x + 23, y + 9, 0x18FFFFFF)
		}
	}

	private fun drawTiles(g: GuiGraphicsExtractor, l: ParkourLevel, camX: Float, viewW: Float) {
		val d = dungeon ?: return
		val x0 = (camX / TILE).toInt() - 1
		val x1 = ((camX + viewW) / TILE).toInt() + 1
		for (tx in x0..x1) for (ty in 0 until l.height) {
			val x = tx * TILE
			val y = ty * TILE
			when (l.tile(tx, ty)) {
				ParkourLevel.SOLID -> {
					if (tx == d.gateCol && ty in DungeonGen.CEILING until DungeonGen.BASE) {
						// The miniboss gate: iron bars.
						g.fill(x, y, x + TILE, y + TILE, 0xFF2A2A30.toInt())
						for (b in 0 until 4) g.fill(x + 1 + b * 4, y, x + 3 + b * 4, y + TILE, 0xFF8A8C96.toInt())
						g.fill(x, y + 6, x + TILE, y + 8, 0xFF6A6C76.toInt())
						continue
					}
					val base = if ((tx * 7 + ty * 3) % 5 == 0) 0xFF4E4A56.toInt() else 0xFF5A5662.toInt()
					g.fill(x, y, x + TILE, y + TILE, base)
					g.fill(x, y + 7, x + TILE, y + 8, 0xFF3A3742.toInt())
					g.fill(x + (if (ty % 2 == 0) 7 else 3), y, x + (if (ty % 2 == 0) 8 else 4), y + 7, 0xFF3A3742.toInt())
					g.fill(x + (if (ty % 2 == 0) 3 else 11), y + 8, x + (if (ty % 2 == 0) 4 else 12), y + TILE, 0xFF3A3742.toInt())
					if (!l.solid(tx, ty - 1)) g.fill(x, y, x + TILE, y + 2, 0xFF7A7686.toInt())
				}
				ParkourLevel.SPIKE -> for (i in 0 until 4) {
					val sx = x + i * 4
					for (row in 0 until 8) g.fill(sx + row / 4, y + TILE - row - 1, sx + 4 - row / 4, y + TILE - row, 0xFFB8BEC8.toInt())
				}
			}
		}
		for ((i, cp) in l.checkpoints.withIndex()) {
			val fx = cp.col * TILE + 6
			val fy = cp.floorRow * TILE
			if (fx < camX - 20 || fx > camX + viewW + 20) continue
			g.fill(fx, fy - 14, fx + 2, fy, 0xFF6B4A2E.toInt())
			if (i <= checkpoint) {
				val flick = (sin(Theme.time * 12 + i) * 1.5f).toInt()
				g.fill(fx - 1, fy - 19 + flick, fx + 3, fy - 14, 0xFFFFA030.toInt())
				g.fill(fx, fy - 21 + flick, fx + 2, fy - 17, 0xFFFFE070.toInt())
			} else g.fill(fx - 1, fy - 16, fx + 3, fy - 14, 0xFF3A3A3A.toInt())
		}
		val gx = d.arenaStart * TILE
		for (ty in DungeonGen.CEILING until DungeonGen.BASE) g.fill(gx, ty * TILE, gx + 2, ty * TILE + TILE, if (bossActive) 0x30FF4040 else 0x20FFFFFF)
	}

	private fun drawPowerUps(g: GuiGraphicsExtractor, camX: Float, viewW: Float) {
		val d = dungeon ?: return
		for (p in d.powerUps) {
			if (p.id in taken || p.x < camX - 20 || p.x > camX + viewW + 20) continue
			val bob = (sin(Theme.time * 3 + p.id) * 2f).toInt()
			val x = p.x.toInt()
			val y = p.y.toInt() + bob
			when (p.kind) {
				Power.STRENGTH -> { // a red potion
					g.fill(x - 1, y - 6, x + 1, y - 3, 0xFFD8D8E0.toInt())
					g.fill(x - 3, y - 3, x + 3, y + 5, 0xFFE03020.toInt())
					g.fill(x - 2, y - 2, x - 1, y + 1, 0xFFFF9080.toInt())
				}
				Power.HEAL -> { // a heart
					g.fill(x - 4, y - 3, x - 1, y, 0xFFFF5070.toInt()); g.fill(x + 1, y - 3, x + 4, y, 0xFFFF5070.toInt())
					g.fill(x - 4, y - 1, x + 4, y + 1, 0xFFFF5070.toInt()); g.fill(x - 3, y + 1, x + 3, y + 3, 0xFFFF5070.toInt())
					g.fill(x - 1, y + 3, x + 1, y + 4, 0xFFFF5070.toInt())
				}
			}
		}
	}

	private fun drawBat(g: GuiGraphicsExtractor, m: MobState) {
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(mobX(m), mobY(m))
		pose.scale(1.6f, 1.6f)
		val x = 0
		val y = 0
		val flap = (steps / 6 + m.id) % 2 == 0
		val dark = if (m.stun > 0) 0xFF7A3030.toInt() else 0xFF2A2026.toInt()
		g.fill(x + 3, y + 2, x + 7, y + 7, dark)
		g.fill(x + 3, y + 1, x + 4, y + 2, dark); g.fill(x + 6, y + 1, x + 7, y + 2, dark)
		g.fill(x + (if (m.facing > 0) 5 else 4), y + 3, x + (if (m.facing > 0) 6 else 5), y + 4, 0xFFFF3030.toInt())
		if (flap) { g.fill(x - 1, y + 1, x + 3, y + 3, dark); g.fill(x + 7, y + 1, x + 11, y + 3, dark) }
		else { g.fill(x - 1, y + 4, x + 3, y + 6, dark); g.fill(x + 7, y + 4, x + 11, y + 6, dark) }
		pose.popMatrix()
	}

	private fun drawArrow(g: GuiGraphicsExtractor, x: Float, y: Float, vx: Float, vy: Float, color: Int) {
		val len = sqrt(vx * vx + vy * vy).coerceAtLeast(0.01f)
		for (k in 0 until 6) {
			val px = (x - vx / len * k * 1.6f).toInt()
			val py = (y - vy / len * k * 1.6f).toInt()
			g.fill(px, py, px + 1, py + 1, if (k == 0) 0xFFE8E8E8.toInt() else color)
		}
	}

	private fun drawShot(g: GuiGraphicsExtractor, s: Shot) {
		val x = s.x.toInt()
		val y = s.y.toInt()
		when (s.kind) {
			0 -> drawArrow(g, s.x, s.y, s.vx, s.vy, 0xFFB08A5A.toInt())
			1 -> { g.fill(x - 3, y - 1, x + 3, y + 1, 0xFF62A6FF.toInt()); g.fill(x - 1, y - 2, x + 1, y + 2, 0xFFD8ECFF.toInt()) }
			2 -> { g.fill(x - 2, y - 1, x + 2, y + 1, 0xFFFF80D4.toInt()); g.fill(x - 1, y - 1, x + 1, y + 1, 0xFFFFFFFF.toInt()) }
			else -> {
				g.fill(x - 4, y - 4, x + 4, y + 4, 0xFFFF6020.toInt())
				g.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFFE070.toInt())
				g.fill(x - (s.vx.sign * 7).toInt() - 1, y - 1, x - (s.vx.sign * 5).toInt() + 1, y + 1, 0xA0FF8030.toInt())
			}
		}
	}

	private fun drawEffect(g: GuiGraphicsExtractor, e: Effect) {
		val t = 1f - e.life.toFloat() / e.max
		val r = e.r * (0.3f + 0.7f * t)
		val alpha = ((1f - t) * 220).toInt().coerceIn(0, 255)
		val c = ColorUtil.withAlpha(e.color, alpha)
		val n = 16
		for (k in 0 until n) {
			val a = k * Math.PI * 2 / n
			val x = (e.x + cos(a) * r).toInt()
			val y = (e.y + sin(a) * r * 0.6f).toInt()
			g.fill(x - 1, y - 1, x + 1, y + 1, c)
		}
	}

	private fun drawSwing(g: GuiGraphicsExtractor, wx: Float, wy: Float, f: Int, prog: Float) {
		val v = view
		val sx = (wx + f * 6f - v.camX) * v.scale
		val sy = v.top + (wy - v.camY) * v.scale
		for (k in 0 until 6) {
			val a = ((-70 + 140 * prog * k / 5f) * Math.PI / 180)
			val ax = sx + (cos(a) * 15 * f * v.scale).toFloat()
			val ay = sy + (sin(a) * 15 * v.scale).toFloat()
			g.fill(ax.toInt(), ay.toInt(), ax.toInt() + 2, ay.toInt() + 2, ColorUtil.withAlpha(0xFFFFFFFF.toInt(), 0x50 + k * 0x20))
		}
	}

	private fun selfSkin(): Identifier = Minecraft.getInstance().player?.skin?.body()?.texturePath() ?: DefaultPlayerSkin.getDefaultTexture()
	private fun skinOf(id: UUID): Identifier = Minecraft.getInstance().connection?.getPlayerInfo(id)?.skin?.body()?.texturePath() ?: DefaultPlayerSkin.get(id).body().texturePath()

	/**
	 * Chibi figure from a player-layout texture, 32 world pixels tall times [big]. [mirror]: the texture has no own
	 * left limbs (zombies, skeletons), so the right ones are reused. [armsForward]: zombie pose. [lying]: downed.
	 */
	private fun drawFigure(
		g: GuiGraphicsExtractor, tex: Identifier, texH: Int, mirror: Boolean, x: Float, y: Float, facing: Int, anim: Int,
		color: Int, big: Float, air: Boolean, armsForward: Boolean = false, lying: Boolean = false, cls: DClass? = null,
		fx: Int = 0, label: String? = null, swinging: Boolean = false,
	) {
		val v = view
		val sx = (x + 4f * big - v.camX) * v.scale
		val sy = v.top + (y - v.camY) * v.scale
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(sx, sy)
		pose.scale(v.scale * big, v.scale * big)
		val look = if (facing >= 0) 1 else -1
		if (lying) {
			pose.translate(0f, 23f)
			pose.rotate((look * Math.PI / 2).toFloat())
			pose.translate(0f, -23f)
		}
		val hop = abs(sin(anim * 0.3f))
		val running = anim != 0 && !air && !lying
		val lift = if (running) (hop * 4f).toInt() else 0
		fun part(dx: Int, dy: Int, w: Int, h: Int, u: Int, vv: Int, rw: Int, rh: Int) =
			if (facing >= 0) g.blit(RenderPipelines.GUI_TEXTURED, tex, dx, dy, u.toFloat(), vv.toFloat(), w, h, rw, rh, 64, texH, color)
			else g.blit(RenderPipelines.GUI_TEXTURED, tex, -dx - w, dy, (u + rw).toFloat(), vv.toFloat(), w, h, -rw, rh, 64, texH, color)
		fun rect(dx: Int, dy: Int, w: Int, h: Int, c: Int) = if (look > 0) g.fill(dx, dy, dx + w, dy + h, c) else g.fill(-dx - w, dy, -dx, dy + h, c)
		if (fx and 1 != 0) g.fill(-9, -2, 9, 33, 0x40FF2020)
		if (fx and 2 != 0) { g.fill(-11, -3, 11, 34, 0x305A8CFF); g.fill(-11, -3, 11, -2, 0xA08AB0FF.toInt()); g.fill(-11, 33, 11, 34, 0xA08AB0FF.toInt()) }
		if (fx and 4 != 0 && (steps / 8) % 2 == 0) { g.fill(-7, -4, -6, -3, 0xFFFF5030.toInt()); g.fill(6, 2, 7, 3, 0xFFFF5030.toInt()) }
		if (!air && !lying) g.fill(-7 + lift, 32, 7 - lift, 33, ColorUtil.withAlpha(0xFF000000.toInt(), (color ushr 24) / 3))
		val y0 = -lift
		// Legs: step alternately while running.
		val stepA = if (running) (sin(anim * 0.3f) * 1.5f).toInt() else 0
		part(-5, 25 + y0 + stepA.coerceAtLeast(0), 4, 7, 4, 20, 4, 12)
		if (mirror) part(1, 25 + y0 + (-stepA).coerceAtLeast(0), 4, 7, 4, 20, 4, 12)
		else part(1, 25 + y0 + (-stepA).coerceAtLeast(0), 4, 7, 20, 52, 4, 12)
		part(-5, 15 + y0, 10, 10, 20, 20, 8, 12)
		val armY = if (air) 11 + y0 else 15 + y0
		if (armsForward) {
			// Both arms stretched out in front, like a zombie.
			for (k in 0..1) {
				pose.pushMatrix()
				pose.translate(if (k == 0) -1f * look else 2f * look, (armY + 1 + k * 3).toFloat())
				pose.rotate((-look * Math.PI / 2).toFloat())
				g.blit(RenderPipelines.GUI_TEXTURED, tex, -1, 0, 44f, 20f, 3, 9, 4, 12, 64, texH,
					if (k == 0) ColorUtil.withAlpha(0xFFB0B0B0.toInt(), color ushr 24) else color)
				pose.popMatrix()
			}
		} else {
			part(-8, armY, 3, 8, 44, 20, 4, 12)
			if (mirror) part(5, armY, 3, 8, 44, 20, 4, 12) else part(5, armY, 3, 8, 36, 52, 4, 12)
		}
		part(-8 + look, y0, 16, 15, 8, 8, 8, 8)
		part(-9 + look, y0 - 1, 18, 17, 40, 8, 8, 8)
		if (cls != null && !lying) drawWeapon(::rect, cls, armY + if (swinging) -4 else 0)
		pose.popMatrix()
		if (label != null) {
			val w = font.width(label)
			g.text(font, label, (sx - w / 2f).toInt(), (sy - 10 + if (lying) 10 * v.scale else 0f).toInt(), 0xFFFFFFFF.toInt(), true)
		}
	}

	/** The class weapon in the front hand ([rect] draws mirrored for facing left). */
	private fun drawWeapon(rect: (Int, Int, Int, Int, Int) -> Unit, c: DClass, armY: Int) {
		val hy = armY + 7
		when (c) {
			DClass.BERSERK -> { rect(7, hy - 13, 2, 12, 0xFFDCDCE6.toInt()); rect(5, hy - 1, 6, 1, 0xFFC8A040.toInt()); rect(7, hy, 2, 3, 0xFF6B4A2E.toInt()) }
			DClass.TANK -> {
				rect(9, hy - 10, 1, 9, 0xFFDCDCE6.toInt()); rect(8, hy - 1, 3, 1, 0xFF6B4A2E.toInt())
				rect(5, hy - 6, 4, 10, 0xFF6A6C76.toInt()); rect(6, hy - 5, 2, 8, 0xFF9A9CA8.toInt())
			}
			DClass.ARCHER -> {
				rect(9, hy - 8, 1, 2, 0xFF8A5A34.toInt()); rect(10, hy - 6, 1, 10, 0xFF8A5A34.toInt()); rect(9, hy + 4, 1, 2, 0xFF8A5A34.toInt())
				rect(8, hy - 7, 1, 12, 0xFFE0E0E0.toInt())
			}
			DClass.MAGE -> { rect(7, hy - 12, 1, 18, 0xFF6B4A2E.toInt()); rect(6, hy - 15, 3, 3, 0xFF62A6FF.toInt()) }
			DClass.HEALER -> { rect(7, hy - 7, 1, 10, 0xFFE8D8A0.toInt()); rect(6, hy - 10, 3, 3, 0xFFFF80D4.toInt()) }
		}
	}

	private fun drawHud(g: GuiGraphicsExtractor, l: ParkourLevel, areaTop: Int, areaH: Int, mx: Int, my: Int) {
		val cx = width / 2
		val barY = areaTop + areaH
		g.fill(0, barY, width, height, Theme.HEADER)
		// Hearts (half hearts)
		val c = myClass
		val hearts = c.maxHp / 2
		val hs = if (hearts > 10) 7 else 9
		for (i in 0 until hearts) {
			val hx = 6 + i * hs
			val fill = (hp - i * 2).coerceIn(0, 2)
			val col = when (fill) { 2 -> 0xFFE0303A.toInt(); 1 -> 0xFFE07078.toInt(); else -> 0xFF3A2228.toInt() }
			g.fill(hx + 1, barY + 7, hx + hs - 2, barY + 12, col)
			g.fill(hx, barY + 8, hx + hs - 1, barY + 11, col)
		}
		var x = 10 + hearts * hs
		// Class and ability
		val ready = abilityCd == 0
		val ab = "§fQ §7${c.ability}"
		val abW = font.width(ab) + 8
		g.fill(x, barY + 3, x + abW, barY + 17, 0xFF1A1A22.toInt())
		val cdFrac = abilityCd.toFloat() / c.abilityCd
		g.fill(x, barY + 3, x + (abW * (1 - cdFrac)).toInt(), barY + 17, if (ready) ColorUtil.withAlpha(c.color, 0x90) else 0x50FFFFFF)
		g.text(font, ab, x + 4, barY + 6, Theme.TEXT, true)
		x += abW + 6
		if (strength > 0) { val s = "§c⚔ ${strength / 60 + 1}s"; g.text(font, s, x, barY + 6, Theme.TEXT, true); x += font.width(s) + 6 }
		if (room.solo) { val s = "§d♥×$lives"; g.text(font, s, x, barY + 6, Theme.TEXT, true); x += font.width(s) + 6 }
		val info = "⏱ ${formatTime(if (phase == Phase.WON || phase == Phase.LOST) endSteps else steps)}  ⚔ ${kills[Net.selfId] ?: 0}  ☠ $deaths"
		val infoX = width - 8 - font.width(info)
		g.text(font, info, infoX, barY + 6, Theme.TEXT, true)
		val d = dungeon
		if (infoX - 10 - x > 30 && d != null) {
			val progress = (myX / TILE / d.arenaStart.toFloat()).coerceIn(0f, 1f)
			g.fill(x, barY + 9, infoX - 10, barY + 11, Theme.TRACK)
			g.fill(x, barY + 9, x + ((infoX - 10 - x) * progress).toInt(), barY + 11, GameColors.GOLD)
			val gx = x + ((infoX - 10 - x) * d.miniStart / d.arenaStart.toFloat()).toInt()
			g.fill(gx, barY + 7, gx + 1, barY + 13, if (gateOpen) 0xFF60D060.toInt() else 0xFF9AD8FF.toInt())
		}

		// Boss / miniboss health bar
		val boss = mobs.values.firstOrNull { it.type == Mob.BOSS }
		val mini = mobs.values.firstOrNull { it.type == Mob.MINIBOSS }
		val (bm, bname, bcol) = when {
			bossActive && boss != null && boss.alive -> Triple(boss, "§5§lDungeon Warden", 0xFFB040E0.toInt())
			miniActive && mini != null && mini.alive -> Triple(mini, "§b§lFrozen Adventurer", 0xFF62C8FF.toInt())
			else -> Triple(null, "", 0)
		}
		if (bm != null) {
			val bw = minOf(240, width - 40)
			val bx = cx - bw / 2
			val by = areaTop + 6
			centered(g, bname, cx, by, Theme.TEXT)
			g.fill(bx, by + 11, bx + bw, by + 16, 0xFF1A1020.toInt())
			g.fill(bx, by + 11, bx + bw * bm.hp / bm.maxHp, by + 16, bcol)
		}

		when (phase) {
			Phase.COUNTDOWN -> {
				val left = ((countdownUntil - room.now()) / 1000 + 1).coerceAtLeast(1)
				bigText(g, "$left", cx, areaTop + areaH / 2 - 26, 4f, GameColors.GOLD)
				centered(g, "§7A/D run · Space jump · Click/F attack · Q ${c.ability} · hold E revive", cx, areaTop + areaH / 2 + 18, Theme.TEXT)
			}
			Phase.RUN -> if (downed) {
				bigText(g, "You're down!", cx, areaTop + areaH / 2 - 22, 2f, GameColors.RED)
				val msg = when {
					respawnAt != 0L -> "§7Back at the last torch in ${((respawnAt - room.now()) / 1000 + 1).coerceAtLeast(1)}s"
					room.solo -> "§7No lives left…"
					else -> "§7A teammate has to revive you (hold E next to you)"
				}
				centered(g, msg, cx, areaTop + areaH / 2, Theme.TEXT)
			} else if (!gateOpen && d != null && myX > (d.gateCol - 3) * TILE && myX < d.gateCol * TILE) {
				centered(g, "§bDefeat the Frozen Adventurer to open the gate", cx, areaTop + 26, Theme.TEXT)
			}
			Phase.WON, Phase.LOST -> drawEnd(g, cx, areaTop + 24, mx, my)
			else -> {}
		}
	}

	private fun drawPick(g: GuiGraphicsExtractor, top: Int, mx: Int, my: Int) {
		val cx = width / 2
		g.fillGradient(0, top, width, height, 0xFF15121C.toInt(), 0xFF241C2E.toInt())
		bigText(g, "Choose your class", cx, top + 8, 1.5f, GameColors.GOLD)
		val n = DClass.entries.size
		val gap = 6
		val cw = ((width - 24 - gap * (n - 1)) / n).coerceAtMost(118)
		val ch = 132
		val x0 = cx - (cw * n + gap * (n - 1)) / 2
		val y0 = top + 30
		for ((i, c) in DClass.entries.withIndex()) {
			val x = x0 + i * (cw + gap)
			val mine = classes[Net.selfId] == c
			val hover = mx in x until x + cw && my in y0 until y0 + ch
			g.fill(x, y0, x + cw, y0 + ch, if (hover) 0xFF2C2638.toInt() else 0xFF201C2A.toInt())
			g.fill(x, y0, x + cw, y0 + 3, c.color)
			if (mine) {
				g.fill(x - 1, y0 - 1, x + cw + 1, y0, c.color); g.fill(x - 1, y0 + ch, x + cw + 1, y0 + ch + 1, c.color)
				g.fill(x - 1, y0, x, y0 + ch, c.color); g.fill(x + cw, y0, x + cw + 1, y0 + ch, c.color)
			}
			centered(g, "§l" + c.title, x + cw / 2, y0 + 8, c.color)
			// A little figure holding the weapon.
			view = View(0f, 0f, 1.2f, 0f)
			drawFigure(g, selfSkin(), 64, false, (x + cw / 2) / 1.2f - 4f, (y0 + 20) / 1.2f, 1, 0, 0xFFFFFFFF.toInt(), 1f, false, cls = c)
			centered(g, "§c❤ §f${c.maxHp / 2}", x + cw / 2, y0 + 62, Theme.TEXT)
			val ny = drawWrapped(g, "§7" + c.weapon, x + 4, y0 + 74, cw - 8)
			val ny2 = drawWrapped(g, "§e" + c.ability, x + 4, ny + 3, cw - 8)
			drawWrapped(g, "§7" + c.abilityInfo, x + 4, ny2, cw - 8)
			val who = classes.filter { it.value == c && it.key != Net.selfId && it.key in room.members }.keys.map { room.nameOf(it) }
			if (who.isNotEmpty()) centered(g, fit("§7" + who.joinToString(", "), cw - 4), x + cw / 2, y0 + ch + 4, Theme.TEXT)
			if (hover && mouseClickedThisFrame) pick(c)
		}
		mouseClickedThisFrame = false
		val by = y0 + ch + 20
		val picked = room.members.count { it in classes }
		when {
			room.solo -> button(g, cx - 60, by, 120, 16, "Enter the dungeon", mx, my, color = GameColors.GREEN) { classes.putIfAbsent(Net.selfId, DClass.BERSERK); startRun() }
			room.isHost -> button(g, cx - 70, by, 140, 16, "Start ($picked/${room.members.size} picked)", mx, my, color = GameColors.GREEN) { startRun() }
			else -> centered(g, "§7Waiting for the host to start… ($picked/${room.members.size} picked)", cx, by + 4, Theme.TEXT)
		}
	}

	private var mouseClickedThisFrame = false

	override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (phase == Phase.PICK && event.button() == 0) mouseClickedThisFrame = true
		return super.mouseClicked(event, doubleClick)
	}

	/** Draws word-wrapped text; returns the y below it. */
	private fun drawWrapped(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, w: Int): Int {
		var line = ""
		var yy = y
		val color = text.take(2)
		for (word in text.drop(2).split(' ')) {
			val next = if (line.isEmpty()) word else "$line $word"
			if (font.width(next) > w && line.isNotEmpty()) { g.text(font, color + line, x, yy, Theme.TEXT, false); yy += 9; line = word } else line = next
		}
		if (line.isNotEmpty()) { g.text(font, color + line, x, yy, Theme.TEXT, false); yy += 9 }
		return yy
	}

	private fun drawEnd(g: GuiGraphicsExtractor, cx: Int, y: Int, mx: Int, my: Int) {
		val won = phase == Phase.WON
		val players = room.names.keys.toList()
		val h = 56 + players.size * 11
		panel(g, cx - 110, y, 220, h, if (won) "Dungeon cleared!" else "Game over")
		centered(g, if (won) "§6§l✦ The Warden is defeated! ✦" else if (room.solo) "§c§lOut of lives" else "§c§lYour whole team is down", cx, y + 20, Theme.TEXT)
		players.forEachIndexed { i, id ->
			val c = if (id == Net.selfId) myClass else allies[id]?.cls ?: classes[id] ?: DClass.BERSERK
			val name = (if (id == Net.selfId) "You" else room.nameOf(id))
			g.text(font, name, cx - 102, y + 34 + i * 11, if (id == Net.selfId) GameColors.GOLD else Theme.TEXT, false)
			g.text(font, c.title, cx - 30, y + 34 + i * 11, c.color, false)
			val k = "§7⚔ ${kills[id] ?: 0}"
			g.text(font, k, cx + 102 - font.width(k), y + 34 + i * 11, Theme.TEXT, false)
		}
		val by = y + h - 18
		if (room.solo || room.isHost) button(g, cx - 94, by, 90, 14, if (won) "New dungeon" else "Try again", mx, my, color = GameColors.GREEN) {
			if (room.solo) phase = Phase.PICK else room.send("dun.pick", JsonObject())
		} else centered(g, "§7Waiting for the host…", cx - 50, by + 3, Theme.TEXT)
		button(g, cx + 4, by, 90, 14, "Leave", mx, my, color = GameColors.RED) { room.quit(); onClose() }
	}

	override fun removed() {
		room.leave()
		super.removed()
	}

	/** Test hooks. */
	val debugPhase get() = phase
	val debugX get() = myX
	val debugHp get() = hp
	val debugDowned get() = downed
	val debugMobs get() = mobs.values.toList()
	val debugBossActive get() = bossActive
	val debugGateOpen get() = gateOpen
	val debugDungeon get() = dungeon
	val debugShots get() = shots.size
	fun debugPick(c: DClass) = pick(c)
	fun debugStart() = startRun()
	fun debugAttack() = attack()
	fun debugAbility() { abilityCd = 0; ability() }
	fun debugHit(id: Int, dmg: Int) = applyHit(id, dmg, Net.selfId)
	fun debugDamage(n: Int) { invuln = 0; damage(n) }
	fun debugFace(f: Int) { state.facing = f }
	fun debugTeleport(col: Int) {
		val l = level ?: return
		val row = (DungeonGen.CEILING until l.height).firstOrNull { l.solid(col, it) } ?: return
		state = State(col * ParkourPhysics.TILE_FP, row * ParkourPhysics.TILE_FP - ParkourPhysics.H_FP, grounded = true)
		prevX = state.x; prevY = state.y
		while (checkpoint + 1 < l.checkpoints.size && state.x >= l.checkpoints[checkpoint + 1].col * ParkourPhysics.TILE_FP) checkpoint++
	}

	companion object {
		fun formatTime(steps: Int): String {
			val ms = steps * 1000L / ParkourPhysics.STEPS_PER_SECOND
			return "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
		}
	}
}
