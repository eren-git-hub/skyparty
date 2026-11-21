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

	@JvmStatic
	fun body(pose: PoseStack, d: Emotes.Drawn) {
		val t = d.age
		when (d.emote) {
			Emote.CHAIR -> {
				// Lean back around the hips.
				val hip = 0.70f
				pose.translate(0f, hip, 0f)
				pose.mulPose(Axis.XP.rotationDegrees(CHAIR_LEAN))
				pose.translate(0f, -hip, 0f)
			}
			Emote.SLEEP -> {
				// On the back, slowly breathing.
				pose.translate(0f, 0.14f + sin(t * 0.08f) * 0.006f, 0f)
				pose.mulPose(Axis.XP.rotationDegrees(90f))
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

	@JvmStatic
	fun arms(m: PlayerModel, d: Emotes.Drawn) {
		val t = d.age
		when (d.emote) {
			Emote.CHAIR -> {
				// Legs stretched out towards the ground (the body leans back, so the angle is relative), hands behind the head.
				leg(m, -0.80f, 0.12f)
				m.rightArm.xRot = -2.95f; m.rightArm.yRot = 0f; m.rightArm.zRot = 0.62f
				m.leftArm.xRot = -2.95f; m.leftArm.yRot = 0f; m.leftArm.zRot = -0.62f
				m.head.xRot = (m.head.xRot - 0.25f).coerceIn(-1.2f, 0.6f)
			}
			Emote.WAVE -> {
				m.rightArm.xRot = -0.2f
				m.rightArm.yRot = 0f
				m.rightArm.zRot = 2.75f + sin(t * 0.55f) * 0.38f
				m.leftArm.zRot = -0.08f
			}
			Emote.DANCE -> dance(m, t)
			Emote.SLEEP -> {
				m.head.xRot = 0f; m.head.yRot = 0f; m.head.zRot = 0.12f
				m.rightArm.xRot = 0f; m.rightArm.yRot = 0f; m.rightArm.zRot = 0.12f
				m.leftArm.xRot = -0.2f; m.leftArm.yRot = 0f; m.leftArm.zRot = -0.5f
				m.rightLeg.xRot = 0f; m.rightLeg.zRot = 0.06f
				m.leftLeg.xRot = 0f; m.leftLeg.zRot = -0.04f
			}
			Emote.FACEPALM -> {
				val shake = sin(t * 0.25f) * 0.12f
				m.head.xRot = 0.45f; m.head.yRot = shake; m.head.zRot = 0f
				m.rightArm.xRot = -2.0f; m.rightArm.yRot = shake; m.rightArm.zRot = 0.6f
				m.leftArm.xRot = -0.35f; m.leftArm.yRot = 0.4f; m.leftArm.zRot = 0.25f
			}
			Emote.CHEER -> {
				val w = sin(t * 0.6f) * 0.25f
				m.rightArm.xRot = -PI_F + 0.15f; m.rightArm.yRot = 0f; m.rightArm.zRot = -0.35f - w
				m.leftArm.xRot = -PI_F + 0.15f; m.leftArm.yRot = 0f; m.leftArm.zRot = 0.35f + w
				m.head.xRot = -0.35f
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
		m.head.xRot = 0.1f + sin(p * 2) * 0.12f
		m.head.yRot = 0f
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
