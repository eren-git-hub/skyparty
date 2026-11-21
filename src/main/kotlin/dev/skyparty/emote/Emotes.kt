package dev.skyparty.emote

import com.google.gson.JsonObject
import dev.skyparty.module.impl.pets.EmotesModule
import dev.skyparty.net.Net
import dev.skyparty.util.Chat
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Emotes like in other clients, purely visual: your player model changes pose (and gets a prop) on the
 * screens of players with Skyparty. Nothing changes on Hypixel – your real position, hitbox and movement
 * stay the same, and moving ends the emote.
 *
 * Others learn about it through the lobby room of the pets ("emote" {e: id or ""}), once when it starts or
 * stops and again when someone new joins the lobby.
 */
object Emotes {
	enum class Emote(val id: String, val label: String) { CHAIR("chair", "Folding Chair") }

	/** Your own emote (null = none). */
	var active: Emote? = null
		private set
	private var startPos: Vec3? = null
	private var startedAt = 0L
	/** Body yaw when you sat down, so the chair does not turn while you look around. */
	var startYaw = 0f
		private set

	private class Remote(val emote: Emote, val yaw: Float)
	private val remote = HashMap<UUID, Remote>()

	/** Entity ids of players currently drawn sitting (filled by the render mixin, read for the render offset). */
	@JvmField val sittingIds = HashSet<Int>()

	/** Tests only: these players count as sitting. */
	val testSitting = HashMap<UUID, Float>()

	fun emoteOf(player: Player): Emote? {
		val mc = Minecraft.getInstance()
		if (player === mc.player) return active
		testSitting[player.uuid]?.let { return Emote.CHAIR }
		if (!EmotesModule.showOthers.value) return null
		return remote[player.uuid]?.emote
	}

	/** Body yaw the chair (and the sitting body) faces. */
	fun yawOf(player: Player, fallback: Float): Float {
		if (player === Minecraft.getInstance().player) return if (active != null) startYaw else fallback
		testSitting[player.uuid]?.let { return it }
		return remote[player.uuid]?.yaw ?: fallback
	}

	@JvmStatic
	fun isSitting(player: Player) = emoteOf(player) == Emote.CHAIR && !player.isPassenger && !player.isFallFlying

	fun init() {
		dev.skyparty.core.SkypartyCommands.sub {
			net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("sit").executes { later { toggle(Emote.CHAIR) }; 1 }
		}
		dev.skyparty.core.SkypartyCommands.sub {
			val root = net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("emote")
			for (e in Emote.entries) root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(e.id).executes { later { toggle(e) }; 1 })
			root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("stop").executes { stop(); 1 })
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
		val a = active ?: return
		val start = startPos ?: return
		// Any real movement, jumping, sneaking or getting hit ends the emote.
		val opts = mc.options
		val moving = opts.keyUp.isDown || opts.keyDown.isDown || opts.keyLeft.isDown || opts.keyRight.isDown || opts.keyJump.isDown || opts.keyShift.isDown
		val grace = System.currentTimeMillis() - startedAt < 300
		if ((moving && !grace) || p.position().distanceToSqr(start) > 0.04 || !p.onGround() || p.hurtTime > 0 || p.isPassenger) {
			stop()
			return
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

	/** Someone new is in the lobby: tell them what you are doing (only while an emote is running). */
	fun onMembersChanged(members: Set<UUID>) {
		remote.keys.retainAll(members)
		if (active != null) broadcast()
	}

	fun onMessage(msg: Net.Message) {
		if (msg.from == Net.selfId) return
		val id = msg.payload["e"]?.asString ?: return
		val e = Emote.entries.firstOrNull { it.id == id }
		if (e == null) remote.remove(msg.from)
		else remote[msg.from] = Remote(e, msg.payload["yaw"]?.asFloat ?: 0f)
	}

	fun onLobbyChanged() = remote.clear()
}
