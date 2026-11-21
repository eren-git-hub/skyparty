package dev.skyparty.emote

import com.google.gson.JsonObject
import dev.skyparty.module.impl.pets.EmotesModule
import dev.skyparty.net.Net
import dev.skyparty.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Emotes like in other clients, purely visual: your player model changes pose (and gets props) on the
 * screens of players with Skyparty. Nothing changes on Hypixel – your real position, hitbox and movement
 * stay the same, and moving ends the emote. Poses live in [EmotePoses], the beach chair in [ChairRenderer],
 * the campfire in [Campfire].
 *
 * Others learn about it through the lobby room of the pets: "emote" {e: id or "", yaw} when it starts or
 * stops, "camp" {x, y, z} or {} for the campfire, again when someone new joins the lobby.
 */
object Emotes {
	enum class Emote(val id: String, val label: String, val icon: String) {
		CHAIR("chair", "Beach Chair", "⛱"),
		WAVE("wave", "Wave", "✋"),
		DANCE("dance", "Dance", "♫"),
		SLEEP("sleep", "Sleep", "☾"),
		FACEPALM("facepalm", "Facepalm", "☹"),
		CHEER("cheer", "Cheer", "★"),
	}

	/** Your own emote (null = none). */
	var active: Emote? = null
		private set
	private var startPos: Vec3? = null
	private var startedAt = 0L
	/** Body yaw when the emote started, so the body (and chair) do not turn while you look around. */
	var startYaw = 0f
		private set

	private class Remote(val emote: Emote, val yaw: Float, val since: Long)
	private val remote = HashMap<UUID, Remote>()

	/** Entity id → emote and its age in ticks, filled by the render mixin for the model mixins of the same frame. */
	class Drawn(@JvmField val emote: Emote, @JvmField val age: Float)
	@JvmField val drawn = HashMap<Int, Drawn>()

	/** Tests only: these players count as doing this emote. */
	val testEmotes = HashMap<UUID, Emote>()

	fun emoteOf(player: Player): Emote? {
		if (player.isPassenger || player.isFallFlying || player.isSwimming) return null
		if (player === Minecraft.getInstance().player) return active
		testEmotes[player.uuid]?.let { return it }
		if (!EmotesModule.showOthers.value) return null
		return remote[player.uuid]?.emote
	}

	/** Ticks since the emote started (with the partial tick), for the animations. */
	fun ageOf(player: Player): Float {
		val since = if (player === Minecraft.getInstance().player) startedAt else remote[player.uuid]?.since ?: 0L
		return (System.currentTimeMillis() - since) / 50f
	}

	/** Body yaw the body (and the chair) faces during the emote. */
	fun yawOf(player: Player, fallback: Float): Float {
		if (player === Minecraft.getInstance().player) return if (active != null) startYaw else fallback
		if (player.uuid in testEmotes) return fallback
		return remote[player.uuid]?.yaw ?: fallback
	}

	@JvmStatic
	fun isSitting(player: Player) = emoteOf(player) == Emote.CHAIR

	fun init() {
		dev.skyparty.core.SkypartyCommands.sub { ClientCommands.literal("sit").executes { later { toggle(Emote.CHAIR) }; 1 } }
		dev.skyparty.core.SkypartyCommands.sub {
			val root = ClientCommands.literal("emote").executes { later { Minecraft.getInstance().gui.setScreen(EmoteWheelScreen(null)) }; 1 }
			for (e in Emote.entries) root.then(ClientCommands.literal(e.id).executes { later { toggle(e) }; 1 })
			root.then(ClientCommands.literal("campfire").executes { later { Campfire.toggle() }; 1 })
			root.then(ClientCommands.literal("stop").executes { stop(); 1 })
		}
	}

	/** After the chat screen (which ran the command) has closed, so the key check does not end it at once. */
	private fun later(action: () -> Unit) = dev.skyparty.minigames.MinigameHub.later(action)

	fun toggle(emote: Emote) {
		if (active == emote) stop() else start(emote)
	}

	fun start(emote: Emote) {
		val p = Minecraft.getInstance().player ?: return
		if (!p.onGround() || p.isPassenger || p.isSwimming || p.isFallFlying) {
			Chat.info("§7Stand still on the ground to use an emote.")
			return
		}
		active = emote
		startPos = p.position()
		startedAt = System.currentTimeMillis()
		startYaw = p.yBodyRot
		broadcast()
	}

	fun stop() {
		if (active == null) return
		active = null
		startPos = null
		broadcast()
	}

	fun tick(mc: Minecraft) {
		val p = mc.player
		if (p == null) { active = null; remote.clear(); return }
		Campfire.tick(mc)
		particles(mc)
		if (active == null) return
		val start = startPos ?: return
		// Any real movement, jumping, sneaking or getting hit ends the emote.
		val opts = mc.options
		val moving = opts.keyUp.isDown || opts.keyDown.isDown || opts.keyLeft.isDown || opts.keyRight.isDown || opts.keyJump.isDown || opts.keyShift.isDown
		val grace = System.currentTimeMillis() - startedAt < 300
		if ((moving && !grace) || p.position().distanceToSqr(start) > 0.04 || !p.onGround() || p.hurtTime > 0 || p.isPassenger) stop()
	}

	private val CONFETTI = intArrayOf(0xFF5A5A, 0xFFD24A, 0x6CE06C, 0x58B7FF, 0xD27BFF, 0xFF8AD8)

	/** Confetti for cheering players (everyone draws it locally, nothing is sent). */
	private fun particles(mc: Minecraft) {
		val level = mc.level ?: return
		if (level.gameTime % 3 != 0L) return
		val r = level.random
		for (pl in level.players()) {
			if (emoteOf(pl) != Emote.CHEER || pl.isInvisible) continue
			if (pl.distanceToSqr(mc.player!!) > 48.0 * 48.0) continue
			repeat(3) {
				val a = r.nextDouble() * Math.PI * 2
				val d = 0.3 + r.nextDouble() * 0.5
				val color = CONFETTI[r.nextInt(CONFETTI.size)]
				level.addParticle(DustParticleOptions(color, 1.0f), pl.x + cos(a) * d, pl.y + 2.3 + r.nextDouble() * 0.4, pl.z + sin(a) * d,
					0.0, 0.0, 0.0)
			}
			if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.END_ROD, pl.x, pl.y + 2.4, pl.z, (r.nextDouble() - 0.5) * 0.1, 0.08, (r.nextDouble() - 0.5) * 0.1)
		}
	}

	// ------------------------------------------------------------------ network (lobby room of the pets)

	private fun payload() = JsonObject().apply {
		addProperty("e", active?.id ?: "")
		addProperty("yaw", startYaw)
	}

	private fun broadcast() {
		dev.skyparty.pet.RemotePets.lobbyRoom?.send("emote", payload())
	}

	/** Someone new is in the lobby: tell them what you are doing (only while something is running). */
	fun onMembersChanged(members: Set<UUID>) {
		remote.keys.retainAll(members)
		Campfire.onMembersChanged(members)
		if (active != null) broadcast()
	}

	fun onMessage(msg: Net.Message) {
		if (msg.from == Net.selfId) return
		if (msg.type == "camp") { Campfire.onMessage(msg); return }
		val id = msg.payload["e"]?.asString ?: return
		val e = Emote.entries.firstOrNull { it.id == id }
		if (e == null) remote.remove(msg.from)
		else remote[msg.from] = Remote(e, msg.payload["yaw"]?.asFloat ?: 0f, System.currentTimeMillis())
	}

	fun onLobbyChanged() {
		remote.clear()
		Campfire.onLobbyChanged()
	}
}
