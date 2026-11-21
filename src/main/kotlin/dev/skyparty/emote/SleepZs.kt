package dev.skyparty.emote

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import kotlin.math.cos
import kotlin.math.sin

/** Little "z"s drifting up from the heads of sleeping players (everyone draws them locally). */
object SleepZs {
	private val Z = Component.literal("z").visualOrderText
	private val BIG_Z = Component.literal("Z").visualOrderText

	fun render(ctx: LevelRenderContext) {
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val camState = ctx.levelState().cameraRenderState
		val cam = camState.pos
		val c = ctx.submitNodeCollector()
		for (player in level.players()) {
			if (Emotes.emoteOf(player) != Emotes.Emote.SLEEP || player.isInvisible) continue
			if (player === mc.player && mc.options.cameraType.isFirstPerson) continue
			val pos = player.getPosition(partial)
			// Lying on the back: the head is behind where the player stands.
			val yaw = Math.toRadians(Emotes.yawOf(player, player.yBodyRot).toDouble())
			val hx = pos.x + sin(yaw) * 1.45
			val hz = pos.z - cos(yaw) * 1.45
			val age = Emotes.ageOf(player)
			for (i in 0 until 3) {
				val t = ((age / 40f + i / 3f) % 1f)
				val pose = ctx.poseStack()
				pose.pushPose()
				pose.translate(hx - cam.x + sin(t * 6f + i) * 0.15, pos.y + 0.5 + t * 1.1 - cam.y, hz - cam.z)
				pose.mulPose(camState.orientation)
				val s = 0.018f + t * 0.016f
				pose.scale(s, -s, s)
				val alpha = ((1f - t) * 255).toInt().coerceIn(30, 255)
				c.submitText(pose, -2.5f, -4f, if (i == 1) BIG_Z else Z, true, Font.DisplayMode.NORMAL, 0xF000F0,
					(alpha shl 24) or 0xDDE6FF, 0, 0)
				pose.popPose()
			}
		}
	}
}
