package dev.skyparty.emote

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.skyparty.emote.Emotes.Emote
import net.minecraft.client.model.player.PlayerModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The emote animations, applied by mixins on top of the normal player pose:
 *  - [arms] sets limb and head rotations after PlayerModel.setupAnim (radians; arms: negative xRot = forward/up;
  *    hanging right arm: positive zRot = outwards, raised arm: positive zRot = inwards; left arm mirrored),
 *  - [body] moves or turns the whole model (lean back, lie down, bounce) after the renderer's setupRotations,
 *  - [drop] lowers the whole model (sitting in the low beach chair).
 */
object EmotePoses {
	private const val PI_F = PI.toFloat()

	/** Beach chair: back leaned this far, hips this high (blocks, before the 0.9375 player scale). */
	const val CHAIR_LEAN = 32f
	const val CHAIR_DROP = 0.36

	@JvmStatic
	fun drop(e: Emote): Double = if (e == Emote.CHAIR) CHAIR_DROP else 0.0

	/**
	 * Whole-body transform. [mirror] is for the cosmetics drawn in the world (wings, ears, …): their frame is turned
	 * 180° around Y against the player renderer's, so turns around X go the other way.
	 */
	@JvmStatic
	@JvmOverloads
	fun body(pose: PoseStack, d: Emotes.Drawn, mirror: Boolean = false) {
		val t = d.age
		val x = if (mirror) -1f else 1f
		when (d.emote) {
			Emote.CHAIR -> {
				// Lean back around the hips.
				val hip = 0.70f
				pose.translate(0f, hip, 0f)
				pose.mulPose(Axis.XP.rotationDegrees(CHAIR_LEAN * x))
				pose.translate(0f, -hip, 0f)
			}
			Emote.SLEEP -> {
				// On the back, slowly breathing.
				pose.translate(0f, 0.14f + sin(t * 0.08f) * 0.006f, 0f)
				pose.mulPose(Axis.XP.rotationDegrees(90f * x))
			}
			Emote.DANCE -> {
				val p = t * 0.32f
				pose.translate(0f, -abs(sin(p)) * 0.07f, 0f)
				if (((t / 32f).toInt()) % 2 == 1) pose.mulPose(Axis.YP.rotationDegrees(sin(p * 0.5f) * 14f))
			}
			Emote.CHEER -> pose.translate(0f, abs(sin(t * 0.3f)) * 0.22f, 0f)
			else -> {}
		}
	}

	/**
	 * Head turn relative to the body during an emote (yaw, pitch, roll in radians). The head does not follow
	 * where you look: it faces straight ahead, only the emote's own motion moves it. [yaw] and [pitch] (the
	 * normal look) are not used. Shared by the model and the cosmetics on the head.
	 */
	fun head(d: Emotes.Drawn, yaw: Float, pitch: Float): FloatArray {
		val t = d.age
		return when (d.emote) {
			// The body leans back: tilt the head forward so the face looks ahead, not at the sky.
			Emote.CHAIR -> floatArrayOf(0f, 0.42f, 0f)
			Emote.SLEEP -> floatArrayOf(0f, 0f, 0.12f)
			Emote.FACEPALM -> floatArrayOf(sin(t * 0.25f) * 0.12f, 0.45f, 0f)
			Emote.CHEER -> floatArrayOf(0f, -0.35f, 0f)
			Emote.DANCE -> floatArrayOf(0f, 0.1f + sin(t * 0.64f) * 0.12f, 0f)
			Emote.WAVE -> floatArrayOf(0f, 0f, 0f)
		}
	}

	/**
	 * For cosmetics drawn in the world: call right after turning to the body yaw (from [bodyYaw]) and before
	 * scaling to the player size. Lowers, leans or lays the frame down like the player model.
	 */
	fun applyToCosmetic(pose: PoseStack, d: Emotes.Drawn) {
		val drop = drop(d.emote)
		if (drop != 0.0) pose.translate(0.0, -drop, 0.0)
		body(pose, d, mirror = true)
	}

	/** How a cosmetic on [player] is placed: body yaw, head yaw relative to the body, pitch, roll (degrees), and the emote. */
	class Cosmetic(val bodyYaw: Float, val headYaw: Float, val pitch: Float, val roll: Float, val drawn: Emotes.Drawn?) {
		/** Turn to the body and, during an emote, lean / lie / drop like the model. */
		fun body(pose: PoseStack) {
			pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw))
			drawn?.let { applyToCosmetic(pose, it) }
		}

		/** At the neck pivot: turn like the head. */
		fun head(pose: PoseStack) {
			pose.mulPose(Axis.YP.rotationDegrees(-headYaw))
			pose.mulPose(Axis.XP.rotationDegrees(pitch))
			if (roll != 0f) pose.mulPose(Axis.ZP.rotationDegrees(-roll))
		}

		/** Sneaking only counts without an emote (emotes stand the model up). */
		fun crouching(player: net.minecraft.world.entity.player.Player) = player.isCrouching && drawn == null
	}

	fun cosmetic(player: net.minecraft.world.entity.player.Player, bodyYaw: Float, headYaw: Float, pitch: Float): Cosmetic {
		val e = Emotes.emoteOf(player) ?: return Cosmetic(bodyYaw, headYaw - bodyYaw, pitch, 0f, null)
		val d = Emotes.Drawn(e, Emotes.ageOf(player))
		val by = Emotes.yawOf(player, bodyYaw)
		val rel = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(headYaw - by), -80f, 80f)
		val h = head(d, Math.toRadians(rel.toDouble()).toFloat(), Math.toRadians(pitch.toDouble()).toFloat())
		return Cosmetic(by, Math.toDegrees(h[0].toDouble()).toFloat(), Math.toDegrees(h[1].toDouble()).toFloat(), Math.toDegrees(h[2].toDouble()).toFloat(), d)
	}

	@JvmStatic
	fun arms(m: PlayerModel, d: Emotes.Drawn) {
		val t = d.age
		val h = head(d, m.head.yRot, m.head.xRot)
		m.head.yRot = h[0]; m.head.xRot = h[1]; m.head.zRot = h[2]
		when (d.emote) {
			Emote.CHAIR -> {
				// Legs stretched out towards the ground (the body leans back, so the angle is relative),
				// arms relaxed with the hands resting on the lap (clear of the head and the backrest).
				leg(m, -0.80f, 0.12f)
				m.rightArm.xRot = -0.85f; m.rightArm.yRot = 0f; m.rightArm.zRot = -0.32f
				m.leftArm.xRot = -0.85f; m.leftArm.yRot = 0f; m.leftArm.zRot = 0.32f
			}
			Emote.WAVE -> {
				m.rightArm.xRot = -0.2f
				m.rightArm.yRot = 0f
				m.rightArm.zRot = 2.75f + sin(t * 0.55f) * 0.38f
				m.leftArm.zRot = -0.08f
			}
			Emote.DANCE -> dance(m, t)
			Emote.SLEEP -> {
				m.rightArm.xRot = 0f; m.rightArm.yRot = 0f; m.rightArm.zRot = 0.12f
				m.leftArm.xRot = -0.2f; m.leftArm.yRot = 0f; m.leftArm.zRot = -0.5f
				m.rightLeg.xRot = 0f; m.rightLeg.zRot = 0.06f
				m.leftLeg.xRot = 0f; m.leftLeg.zRot = -0.04f
			}
			Emote.FACEPALM -> {
				val shake = sin(t * 0.25f) * 0.12f
				m.rightArm.xRot = -2.0f; m.rightArm.yRot = shake; m.rightArm.zRot = 0.6f
				m.leftArm.xRot = -0.35f; m.leftArm.yRot = 0.4f; m.leftArm.zRot = 0.25f
			}
			Emote.CHEER -> {
				val w = sin(t * 0.6f) * 0.25f
				m.rightArm.xRot = -PI_F + 0.15f; m.rightArm.yRot = 0f; m.rightArm.zRot = -0.35f - w
				m.leftArm.xRot = -PI_F + 0.15f; m.leftArm.yRot = 0f; m.leftArm.zRot = 0.35f + w
			}
		}
	}

	private fun leg(m: PlayerModel, x: Float, spread: Float) {
		m.rightLeg.xRot = x; m.rightLeg.yRot = spread; m.rightLeg.zRot = 0.04f
		m.leftLeg.xRot = x; m.leftLeg.yRot = -spread; m.leftLeg.zRot = -0.04f
	}

	/** A bouncy groove in two parts that take turns: arm pumps across the body, then a side-to-side swing. */
	private fun dance(m: PlayerModel, t: Float) {
		val p = t * 0.32f
		val s = sin(p)
		val c = sin(p + PI_F / 2)
		if (((t / 32f).toInt()) % 2 == 0) {
			m.rightArm.xRot = -1.1f - s * 0.75f; m.rightArm.yRot = 0f; m.rightArm.zRot = -0.25f + c * 0.25f
			m.leftArm.xRot = -1.1f + s * 0.75f; m.leftArm.yRot = 0f; m.leftArm.zRot = 0.25f + c * 0.25f
		} else {
			m.rightArm.xRot = -0.25f; m.rightArm.yRot = 0f; m.rightArm.zRot = 0.55f + s * 0.45f
			m.leftArm.xRot = -0.25f; m.leftArm.yRot = 0f; m.leftArm.zRot = -0.55f + s * 0.45f
		}
		m.rightLeg.xRot = abs(s) * -0.35f; m.rightLeg.yRot = 0f; m.rightLeg.zRot = 0.05f
		m.leftLeg.xRot = abs(c) * -0.35f; m.leftLeg.yRot = 0f; m.leftLeg.zRot = -0.05f
	}
}
