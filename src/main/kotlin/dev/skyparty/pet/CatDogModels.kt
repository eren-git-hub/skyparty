package dev.skyparty.pet

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import kotlin.math.abs
import kotlin.math.sin

/* Cat and dog pets. Same conventions as PetSpeciesModels: 1/16 block units, Y up from the ground, facing +Z. */

private fun CubeListBuilder.cube(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float) {
	addBox(x, y, z, w, h, d)
}

private fun mirror(side: Float, from: Float, w: Float) = if (side > 0) from else -from - w

// ---------------------------------------------------------------------------------------------- Cat

private val CAT_HEAD = listOf("head", "headSnout", "headEars", "headEarInner", "headEyes", "headNose", "headWhiskers")

/** Cat: slim body on thin legs, round head with pointy ears (accent inside), whiskers and a long curling tail. */
internal fun PetModels.Builder.defineCat() {
	part("body", ColorRole.BODY) { cube(-1.8f, 3.6f, -4f, 3.6f, 3.2f, 8f) }
	part("belly", ColorRole.BODY_SHADE) { cube(-1.4f, 3.4f, -3.5f, 2.8f, 0.3f, 7f) }
	val head = PartPose.offset(0f, 7.3f, 4.2f)
	part("head", ColorRole.BODY, head) { cube(-2f, -1.6f, -0.6f, 4f, 3.4f, 3.4f) }
	part("headSnout", ColorRole.BODY_SHADE, head) { cube(-1f, -1.5f, 2.7f, 2f, 1.2f, 0.7f) }
	part("headEars", ColorRole.BODY, head) {
		for (s in listOf(-1f, 1f)) {
			cube(mirror(s, 0.5f, 1.4f), 1.7f, 0.1f, 1.4f, 1f, 1f)
			cube(mirror(s, 0.75f, 0.9f), 2.7f, 0.2f, 0.9f, 0.7f, 0.8f)
			cube(mirror(s, 0.95f, 0.5f), 3.4f, 0.3f, 0.5f, 0.5f, 0.6f)
		}
	}
	part("headEarInner", ColorRole.ACCENT, head) {
		for (s in listOf(-1f, 1f)) cube(mirror(s, 0.8f, 0.8f), 1.9f, 1.1f, 0.8f, 1.3f, 0.1f)
	}
	part("headEyes", ColorRole.EYE, head) {
		cube(-1.5f, 0.1f, 2.75f, 0.7f, 0.8f, 0.15f)
		cube(0.8f, 0.1f, 2.75f, 0.7f, 0.8f, 0.15f)
	}
	part("headNose", ColorRole.ACCENT, head) { cube(-0.3f, -0.5f, 3.4f, 0.6f, 0.4f, 0.1f) }
	part("headWhiskers", ColorRole.SHELL, head) {
		for (wy in listOf(-0.9f, -0.5f)) {
			cube(-3f, wy, 3.1f, 1.9f, 0.12f, 0.12f)
			cube(1.1f, wy, 3.1f, 1.9f, 0.12f, 0.12f)
		}
	}
	for ((name, x, z) in listOf(Triple("legFL", -1.1f, 2.9f), Triple("legFR", 1.1f, 2.9f), Triple("legBL", -1.1f, -3f), Triple("legBR", 1.1f, -3f))) {
		part(name, ColorRole.BODY, PartPose.offset(x, 3.9f, z)) { cube(-0.6f, -3.9f, -0.6f, 1.2f, 4f, 1.2f) }
	}
	// Tail: three segments pointing up and curling forward at the tip.
	val tail = part("tail", ColorRole.BODY, PartPose.offsetAndRotation(0f, 6.2f, -3.9f, 0.9f, 0f, 0f)) {
		cube(-0.45f, -0.45f, -3f, 0.9f, 0.9f, 3.2f)
	}
	val tail2 = tail.addOrReplaceChild("tail2", CubeListBuilder.create().texOffs(0, 0).addBox(-0.42f, -0.42f, -2.8f, 0.84f, 0.84f, 3f),
		PartPose.offsetAndRotation(0f, 0f, -3f, 0.3f, 0f, 0f))
	tail2.addOrReplaceChild("tail3", CubeListBuilder.create().texOffs(0, 0).addBox(-0.5f, -0.5f, -2.2f, 1f, 1f, 2.4f),
		PartPose.offsetAndRotation(0f, 0f, -2.8f, 0.45f, 0f, 0f))
}

/** Prowls with a diagonal gait, the tail sways slowly, ears twitch now and then; lies down low to sleep. */
internal fun animateCat(m: PetModel, time: Float, speed: Double, sleeping: Boolean, react: PetReactions.Pose?) {
	val walk = (speed * 7).toFloat().coerceIn(0f, 1f)
	val stroke = sin(time * 0.6f) * 0.75f * walk
	m.part("legFL")?.xRot = stroke
	m.part("legBR")?.xRot = stroke
	m.part("legFR")?.xRot = -stroke
	m.part("legBL")?.xRot = -stroke
	val w = m.ease(0, walk, 0.1f)
	val sway = sin(m.osc(0, 0.05f + 0.08f * w)) * 0.35f + sin(time * 1.2f) * 0.6f * (react?.tailWag ?: 0f) - (m.turn * 2f).coerceIn(-0.6f, 0.6f)
	m.part("tail")?.let {
		it.yRot = sway
		it.xRot -= 0.35f * w // lower while running
		it.getChild("tail2").zRot = sin(m.osc(1, 0.07f)) * 0.3f
	}
	val look = sin(time * 0.03f) * 0.4f * (1f - walk)
	val twitch = if ((time / 70).toInt() % 3 == 0) sin(time * 1.3f) * 0.15f else 0f
	for (n in CAT_HEAD) m.part(n)?.let { it.yRot = look; it.xRot = -(react?.headUp ?: 0f) }
	m.part("headEars")?.zRot = twitch
	m.part("headEarInner")?.zRot = twitch
	if (sleeping) {
		// Lying down: legs folded under, head resting, tail wrapped around the side.
		m.hover = -0.2
		for (n in listOf("legFL", "legFR")) m.part(n)?.xRot = -1.45f
		for (n in listOf("legBL", "legBR")) m.part(n)?.xRot = 1.45f
		for (n in CAT_HEAD) m.part(n)?.let { it.y -= 0.9f; it.xRot = 0.25f; it.yRot = 0.35f }
		m.part("tail")?.let { it.xRot = 0.1f; it.yRot = 1.1f + sin(time * 0.04f) * 0.08f }
	}
	m.part("headEyes")?.visible = !sleeping
}

// ---------------------------------------------------------------------------------------------- Dog

private val DOG_HEAD = listOf("head", "headSnout", "headNose", "headEyes", "headTongue")

/** Dog: sturdy body, a snout with a dark nose, floppy ears, a collar in the accent color and a wagging tail. */
internal fun PetModels.Builder.defineDog() {
	part("body", ColorRole.BODY) { cube(-2.2f, 4.4f, -4.5f, 4.4f, 4f, 9f) }
	part("belly", ColorRole.BODY_SHADE) { cube(-1.8f, 4.2f, -4f, 3.6f, 0.3f, 8f) }
	part("collar", ColorRole.ACCENT) {
		cube(-1.9f, 7.6f, 3.9f, 3.8f, 0.8f, 1.6f)
		cube(-0.35f, 7f, 5.45f, 0.7f, 0.7f, 0.2f) // tag
	}
	val head = PartPose.offset(0f, 9.3f, 4.8f)
	part("head", ColorRole.BODY, head) { cube(-2.1f, -1.6f, -0.8f, 4.2f, 3.8f, 3.8f) }
	part("headSnout", ColorRole.BODY_SHADE, head) { cube(-1.2f, -1.6f, 3f, 2.4f, 1.8f, 2.2f) }
	part("headNose", ColorRole.EYE, head) { cube(-0.55f, -0.15f, 5.1f, 1.1f, 0.7f, 0.2f) }
	part("headEyes", ColorRole.EYE, head) {
		cube(-1.6f, 0.7f, 3f, 0.6f, 0.7f, 0.15f)
		cube(1f, 0.7f, 3f, 0.6f, 0.7f, 0.15f)
	}
	part("headTongue", ColorRole.ACCENT, head) { cube(-0.4f, -2.3f, 4.3f, 0.8f, 0.8f, 0.6f) }
	for (s in listOf(-1f, 1f)) {
		// Floppy ears hang from the top corners of the head (riding on it) and swing on their own.
		part(if (s > 0) "earL" else "earR", ColorRole.BODY_SHADE, PartPose.offset(2.1f * s, 1.9f, 0.6f), follow = listOf("head")) {
			cube(mirror(s, -0.2f, 0.6f), -3f, -0.9f, 0.6f, 3.2f, 1.8f)
		}
	}
	for ((name, x, z) in listOf(Triple("legFL", -1.3f, 3.2f), Triple("legFR", 1.3f, 3.2f), Triple("legBL", -1.3f, -3.4f), Triple("legBR", 1.3f, -3.4f))) {
		part(name, ColorRole.BODY, PartPose.offset(x, 4.7f, z)) {
			cube(-0.75f, -4.7f, -0.75f, 1.5f, 4.8f, 1.5f)
			cube(-0.85f, -4.7f, -0.6f, 1.7f, 0.7f, 1.8f) // paw
		}
	}
	part("tail", ColorRole.BODY, PartPose.offsetAndRotation(0f, 7.6f, -4.4f, 0.8f, 0f, 0f)) {
		cube(-0.45f, -0.45f, -3.6f, 0.9f, 0.9f, 3.8f)
	}
}

/** Trots with bouncy steps and a happy tail, tongue out while running; lies down with its head low to sleep. */
internal fun animateDog(m: PetModel, time: Float, speed: Double, sleeping: Boolean, react: PetReactions.Pose?) {
	val walk = (speed * 7).toFloat().coerceIn(0f, 1f)
	val stroke = sin(time * 0.65f) * 0.7f * walk
	m.part("legFL")?.xRot = stroke
	m.part("legBR")?.xRot = stroke
	m.part("legFR")?.xRot = -stroke
	m.part("legBL")?.xRot = -stroke
	m.hover = abs(sin(time * 0.65f)) * 0.03 * walk
	val w = m.ease(0, walk, 0.1f)
	// Always a little wag, faster while walking and when happy.
	val wag = sin(m.osc(0, 0.25f + 0.35f * w)) * (0.35f + 0.15f * w) + sin(time * 1.6f) * 0.7f * (react?.tailWag ?: 0f)
	m.part("tail")?.yRot = wag - (m.turn * 2f).coerceIn(-0.5f, 0.5f)
	val look = sin(time * 0.03f) * 0.4f * (1f - walk)
	// Now and then a curious head tilt.
	val tilt = sin(time * 0.017f).let { if (it > 0.85f) (it - 0.85f) * 2.5f else 0f }
	for (n in DOG_HEAD) m.part(n)?.let { it.yRot = look; it.zRot = tilt; it.xRot = -(react?.headUp ?: 0f) + sin(time * 1.3f) * 0.04f * walk }
	val flop = 0.1f + walk * 0.25f * abs(sin(time * 0.65f))
	m.part("earL")?.zRot = flop
	m.part("earR")?.zRot = -flop
	m.part("headTongue")?.visible = walk > 0.3f || react != null
	if (sleeping) {
		m.hover = -0.25
		for (n in listOf("legFL", "legFR")) m.part(n)?.xRot = -1.5f
		for (n in listOf("legBL", "legBR")) m.part(n)?.xRot = 1.45f
		for (n in DOG_HEAD) m.part(n)?.let { it.y -= 2.2f; it.z += 0.3f; it.xRot = 0.15f; it.zRot = 0f }
		m.part("tail")?.let { it.xRot = 0.05f; it.yRot = 0.9f }
		m.part("earL")?.zRot = 0f
		m.part("earR")?.zRot = 0f
	}
	m.part("headEyes")?.visible = !sleeping
}
