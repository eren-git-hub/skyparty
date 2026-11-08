package dev.skyparty.pet

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import kotlin.math.atan2
import kotlin.math.sin

/*
 * Models and animations of the pets besides the dragon. Same conventions as PetModels: 1/16 block units,
 * Y up from the ground, facing +Z. Parts that move together share one pivot (PartPose), so the animation
 * moves them as a group by name.
 */

private fun CubeListBuilder.cube(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float) {
	addBox(x, y, z, w, h, d)
}

private fun mirror(side: Float, from: Float, w: Float) = if (side > 0) from else -from - w

// ---------------------------------------------------------------------------------------------- Ryu

const val RYU_SEGMENTS = 7
private const val RYU_SPACING = 2.7f
private const val RYU_HEAD_Z = 7f
private const val RYU_Y = 6f

/** Japanese dragon: horned head with whiskers and mane, a long serpent body of segments with a dorsal fin, tiny legs. */
internal fun PetModels.Builder.defineRyu() {
	val head = PartPose.offset(0f, RYU_Y, RYU_HEAD_Z)
	part("head", ColorRole.BODY, head) {
		cube(-1.6f, -1.1f, -1.6f, 3.2f, 2.6f, 3.4f) // skull
		cube(-1.1f, -0.9f, 1.7f, 2.2f, 1.6f, 2.8f) // snout
	}
	part("headJaw", ColorRole.BODY_SHADE, head) { cube(-1f, -1.6f, 0.6f, 2f, 0.6f, 3.6f) }
	part("headEyes", ColorRole.EYE, head) {
		cube(-1.7f, 0.3f, 1f, 0.3f, 0.6f, 0.8f)
		cube(1.4f, 0.3f, 1f, 0.3f, 0.6f, 0.8f)
	}
	part("headHorns", ColorRole.SHELL, head) {
		for (s in listOf(-1f, 1f)) {
			// Antler-like: up and back, with a small tine.
			cube(mirror(s, 0.6f, 0.6f), 1.4f, -1.2f, 0.6f, 1.6f, 0.6f)
			cube(mirror(s, 0.8f, 0.5f), 2.6f, -2.4f, 0.5f, 0.5f, 1.6f)
			cube(mirror(s, 1.2f, 0.4f), 2.7f, -1.6f, 0.4f, 0.9f, 0.4f)
		}
	}
	part("headWhiskers", ColorRole.ACCENT, head) {
		cube(-2.8f, -0.4f, 3.6f, 1.7f, 0.25f, 0.25f)
		cube(1.1f, -0.4f, 3.6f, 1.7f, 0.25f, 0.25f)
		cube(-3.6f, -0.9f, 2.4f, 0.25f, 0.25f, 1.4f)
		cube(3.35f, -0.9f, 2.4f, 0.25f, 0.25f, 1.4f)
	}
	part("headMane", ColorRole.ACCENT, head) {
		cube(-1.8f, -0.8f, -2.6f, 3.6f, 2.8f, 1.2f)
		cube(-1.2f, 1.6f, -3.4f, 2.4f, 1f, 1f)
	}
	for (i in 0 until RYU_SEGMENTS) {
		val r = 1.25f - i * 0.11f
		val pose = PartPose.offset(0f, RYU_Y, RYU_HEAD_Z - RYU_SPACING * (i + 1))
		part("seg$i", ColorRole.BODY, pose) { cube(-r, -r, -RYU_SPACING / 2 - 0.3f, r * 2, r * 2, RYU_SPACING + 0.6f) }
		part("segBelly$i", ColorRole.BODY_SHADE, pose) { cube(-r * 0.75f, -r - 0.15f, -RYU_SPACING / 2, r * 1.5f, 0.3f, RYU_SPACING) }
		part("segFin$i", ColorRole.ACCENT, pose) { cube(-0.15f, r - 0.1f, -0.9f, 0.3f, 0.8f - i * 0.05f, 1.8f) }
		if (i == 1 || i == 4) part("segLegs$i", ColorRole.BODY_SHADE, pose) {
			for (s in listOf(-1f, 1f)) {
				cube(mirror(s, r - 0.2f, 0.7f), -r - 1.4f, -0.3f, 0.7f, 1.6f, 0.7f)
				cube(mirror(s, r - 0.3f, 0.9f), -r - 1.7f, -0.3f, 0.9f, 0.4f, 1.2f)
			}
		}
	}
	part("tailTuft", ColorRole.ACCENT, PartPose.offset(0f, RYU_Y, RYU_HEAD_Z - RYU_SPACING * (RYU_SEGMENTS + 0.6f))) {
		cube(-0.9f, -0.9f, -1.6f, 1.8f, 1.8f, 1.8f)
		cube(-0.5f, -0.2f, -2.6f, 1f, 1.4f, 1.2f)
	}
}

/** The body waves like a swimming serpent; every segment turns along the wave, the head looks around. */
internal fun animateRyu(m: PetModel, time: Float, speed: Double, sleeping: Boolean, react: PetReactions.Pose?) {
	// Speed and the wave are eased / accumulated so nothing jumps when the pet speeds up or stops.
	val move = m.ease(0, if (sleeping) 0f else (speed * 6).toFloat().coerceIn(0f, 1f), 0.08f)
	val amp = m.ease(1, if (sleeping) 0.25f else 0.8f + 0.4f * move, 0.05f)
	val p0 = m.osc(0, if (sleeping) 0.04f else 0.1f + 0.08f * move)
	val p1 = m.osc(1, if (sleeping) 0.03f else 0.07f + 0.05f * move)
	m.hover = (if (sleeping) 0.15 else 0.45) + sin(time * 0.08f) * 0.06
	// Turning: the body lags behind like a real serpent (more the further back), instead of swinging rigidly.
	val turnLag = m.turn * 3f
	fun wave(i: Float) = sin(p0 - i * 0.75f) * amp * (0.35f + i / RYU_SEGMENTS) - (turnLag * RYU_SPACING * i).coerceIn(-4f, 4f)
	fun lift(i: Float) = sin(p1 - i * 0.6f) * 0.5f * amp
	var prevX = 0f
	var prevY = RYU_Y
	var prevZ = RYU_HEAD_Z
	for (i in 0..RYU_SEGMENTS) {
		val f = i + 1f
		val x = wave(f)
		val y = RYU_Y + lift(f)
		val z = RYU_HEAD_Z - RYU_SPACING * (if (i == RYU_SEGMENTS) RYU_SEGMENTS + 0.6f else f)
		val yaw = atan2(prevX - x, prevZ - z)
		val pitch = -atan2(prevY - y, RYU_SPACING)
		val names = if (i == RYU_SEGMENTS) listOf("tailTuft") else listOf("seg$i", "segBelly$i", "segFin$i", "segLegs$i")
		for (n in names) m.part(n)?.let { it.x = x; it.y = y; it.z = z; it.yRot = yaw; it.xRot = pitch }
		prevX = x; prevY = y; prevZ = z
	}
	val look = sin(time * 0.03f) * 0.3f * (1f - move)
	val headUp = react?.headUp ?: 0f
	for (n in listOf("head", "headJaw", "headEyes", "headHorns", "headWhiskers", "headMane")) m.part(n)?.let {
		it.y = RYU_Y + sin(p1) * 0.3f * amp - if (sleeping) 1.2f else 0f
		it.yRot = look + sin(p0) * amp * 0.05f
		it.xRot = -headUp + if (sleeping) 0.35f else 0f
	}
	m.part("headWhiskers")?.let { it.zRot = sin(time * 0.2f) * 0.15f }
	m.part("headJaw")?.let { it.xRot += (react?.jaw ?: 0f) + ((sin(time * 0.05f) - 0.75f) * 0.5f).coerceAtLeast(0f) }
	m.part("headEyes")?.visible = !sleeping
}

// ---------------------------------------------------------------------------------------------- Axolotl

/** Axolotl: wide flat head with frilly gills, round body, four stubby legs and a finned tail. */
internal fun PetModels.Builder.defineAxolotl() {
	part("body", ColorRole.BODY) { cube(-2.4f, 1.4f, -4f, 4.8f, 3.4f, 8f) }
	part("belly", ColorRole.BODY_SHADE) { cube(-2.1f, 1.2f, -3.6f, 4.2f, 0.4f, 7.2f) }
	part("back", ColorRole.ACCENT) { cube(-0.3f, 4.8f, -3.5f, 0.6f, 0.7f, 6.5f) }
	val head = PartPose.offset(0f, 3.1f, 4f)
	part("head", ColorRole.BODY, head) { cube(-3f, -1.7f, -0.2f, 6f, 3.4f, 4.2f) }
	part("headMouth", ColorRole.BODY_SHADE, head) { cube(-2f, -1.8f, 3.8f, 4f, 0.5f, 0.3f) }
	part("headEyes", ColorRole.EYE, head) {
		cube(-2.4f, 0.4f, 3.75f, 0.7f, 0.7f, 0.3f)
		cube(1.7f, 0.4f, 3.75f, 0.7f, 0.7f, 0.3f)
	}
	for (s in listOf(-1f, 1f)) {
		val suffix = if (s > 0) "L" else "R"
		// Three gill stalks per side, each its own part so they can wiggle.
		for ((k, gy) in listOf(1.4f, 0.2f, -1f).withIndex()) {
			part("gill$suffix$k", ColorRole.ACCENT, PartPose.offset(3f * s, 3.1f + gy, 6.9f)) {
				cube(mirror(s, 0f, 1.8f), -0.3f, -0.3f, 1.8f, 0.6f, 0.6f)
				cube(mirror(s, 1.5f, 0.5f), -0.3f + (1 - k) * 0.3f, -0.3f, 0.5f, 0.9f, 0.5f)
			}
		}
	}
	for ((name, x, z) in listOf(Triple("legFL", -2.3f, 2.4f), Triple("legFR", 2.3f, 2.4f), Triple("legBL", -2.3f, -2.6f), Triple("legBR", 2.3f, -2.6f))) {
		part(name, ColorRole.BODY_SHADE, PartPose.offset(x, 2f, z)) {
			cube(if (x < 0) -1.3f else 0.1f, -2f, -0.6f, 1.2f, 2.2f, 1.2f)
		}
	}
	val tail = PartPose.offset(0f, 3f, -4f)
	part("tail", ColorRole.BODY, tail) { cube(-0.8f, -1.1f, -5.5f, 1.6f, 2.2f, 5.6f) }
	part("tailFin", ColorRole.ACCENT, tail) { cube(-0.2f, -1.9f, -6.2f, 0.4f, 3.8f, 5f) }
}

/** Waddles with diagonal leg strokes, tail sways, gills wiggle; flat on its belly when asleep. */
internal fun animateAxolotl(m: PetModel, time: Float, speed: Double, sleeping: Boolean, react: PetReactions.Pose?) {
	val walk = (speed * 7).toFloat().coerceIn(0f, 1f)
	val stroke = sin(time * 0.6f) * 0.7f * walk
	m.part("legFL")?.xRot = stroke
	m.part("legBR")?.xRot = stroke
	m.part("legFR")?.xRot = -stroke
	m.part("legBL")?.xRot = -stroke
	// Accumulated phase + eased walk: the tail never jumps when the axolotl starts or stops.
	val w = m.ease(0, walk, 0.1f)
	val sway = sin(m.osc(0, 0.1f + 0.2f * w)) * (0.25f + 0.2f * w) + sin(time * 1.1f) * 0.5f * (react?.tailWag ?: 0f) - (m.turn * 2f).coerceIn(-0.6f, 0.6f)
	for (n in listOf("tail", "tailFin")) m.part(n)?.yRot = sway
	val look = sin(time * 0.035f) * 0.3f * (1f - walk)
	for (n in listOf("head", "headMouth", "headEyes")) m.part(n)?.let { it.yRot = look; it.xRot = -(react?.headUp ?: 0f) * 0.6f }
	for (s in listOf("L", "R")) for (k in 0..2) m.part("gill$s$k")?.let {
		val sign = if (s == "L") 1f else -1f
		it.zRot = sign * (sin(time * 0.15f + k) * 0.18f + (react?.wings ?: 0f) * sin(time * 1.2f + k) * 0.4f) + sign * (k - 1) * 0.35f
		it.yRot = look
	}
	if (sleeping) {
		for (n in listOf("legFL", "legFR", "legBL", "legBR")) m.part(n)?.let { it.xRot = 0f; it.zRot = if (n.endsWith("L")) -1.2f else 1.2f }
		for (n in listOf("head", "headMouth", "headEyes")) m.part(n)?.let { it.y -= 0.6f; it.xRot = 0.15f }
	}
	m.part("headEyes")?.visible = !sleeping
}

// ---------------------------------------------------------------------------------------------- Ghost

/** Ghost: a rounded sheet with a fluttering hem, little arms, big eyes and blush. */
internal fun PetModels.Builder.defineGhost() {
	part("body", ColorRole.BODY) {
		cube(-3f, 2f, -2.8f, 6f, 6.5f, 5.6f)
		cube(-2.5f, 8.5f, -2.3f, 5f, 1.3f, 4.6f) // round top
		cube(-1.6f, 9.8f, -1.4f, 3.2f, 0.6f, 2.8f)
	}
	part("face", ColorRole.EYE) {
		cube(-1.9f, 5.6f, 2.8f, 1f, 1.6f, 0.2f)
		cube(0.9f, 5.6f, 2.8f, 1f, 1.6f, 0.2f)
		cube(-0.5f, 4.3f, 2.8f, 1f, 0.7f, 0.2f) // little "o" mouth
	}
	part("blush", ColorRole.ACCENT) {
		cube(-2.7f, 4.6f, 2.8f, 0.9f, 0.5f, 0.15f)
		cube(1.8f, 4.6f, 2.8f, 0.9f, 0.5f, 0.15f)
	}
	// Hem: six flaps around the bottom edge, pivoting at their top so they flutter.
	for ((k, pos) in listOf(-2f to 2.3f, 0f to 2.3f, 2f to 2.3f, -2f to -2.3f, 0f to -2.3f, 2f to -2.3f).withIndex()) {
		part("hem$k", ColorRole.BODY_SHADE, PartPose.offset(pos.first, 2.2f, pos.second)) { cube(-1f, -2f, -0.5f, 2f, 2.2f, 1f) }
	}
	for (s in listOf(-1f, 1f)) {
		part(if (s > 0) "armL" else "armR", ColorRole.BODY, PartPose.offset(3f * s, 5.5f, 0.5f)) {
			cube(mirror(s, -0.2f, 1.6f), -0.7f, -0.7f, 1.6f, 1.4f, 1.4f)
		}
	}
}

/** Floats and bobs, the hem flutters, arms wave; when asleep it sinks low and closes its eyes. */
internal fun animateGhost(m: PetModel, time: Float, speed: Double, sleeping: Boolean, react: PetReactions.Pose?) {
	val move = (speed * 6).toFloat().coerceIn(0f, 1f)
	m.hover = (if (sleeping) 0.05 else 0.4) + sin(time * 0.09f) * (if (sleeping) 0.02 else 0.08)
	for (k in 0..5) m.part("hem$k")?.let {
		val front = k < 3
		it.xRot = (if (front) -1f else 1f) * (0.12f + sin(time * 0.3f + k * 1.3f) * 0.25f) - move * 0.35f * (if (front) 1f else -1f)
	}
	val wave = sin(time * 0.12f) * 0.25f + (react?.wings ?: 0f) * sin(time * 1.2f) * 1.1f
	m.part("armL")?.zRot = -0.3f - wave
	m.part("armR")?.zRot = 0.3f + wave
	m.part("face")?.visible = !sleeping
}

// ---------------------------------------------------------------------------------------------- Penguin

/** Penguin: upright body with a white belly and face, beak, flippers and orange feet. */
internal fun PetModels.Builder.definePenguin() {
	part("body", ColorRole.BODY) {
		cube(-2.6f, 1f, -2.2f, 5.2f, 6.8f, 4.6f)
		cube(-2.2f, 7.8f, -1.9f, 4.4f, 0.5f, 3.9f)
		cube(-0.9f, 1.2f, -3f, 1.8f, 1.2f, 0.9f) // tail
	}
	part("belly", ColorRole.SHELL) { cube(-2f, 1.3f, 2.35f, 4f, 5.8f, 0.3f) }
	val head = PartPose.offset(0f, 8.2f, 0f)
	part("head", ColorRole.BODY, head) { cube(-2.1f, 0f, -1.9f, 4.2f, 3.6f, 4f) }
	part("headFace", ColorRole.SHELL, head) { cube(-1.6f, 0.4f, 2.05f, 3.2f, 2.6f, 0.2f) }
	part("headEyes", ColorRole.EYE, head) {
		cube(-1.2f, 1.9f, 2.2f, 0.6f, 0.6f, 0.15f)
		cube(0.6f, 1.9f, 2.2f, 0.6f, 0.6f, 0.15f)
	}
	part("headBeak", ColorRole.ACCENT, head) { cube(-0.55f, 1f, 2.1f, 1.1f, 0.7f, 1.3f) }
	for (s in listOf(-1f, 1f)) {
		part(if (s > 0) "flipperL" else "flipperR", ColorRole.BODY_SHADE, PartPose.offset(2.6f * s, 7f, 0.2f)) {
			cube(mirror(s, 0f, 0.6f), -4.5f, -1f, 0.6f, 4.6f, 2f)
		}
		part(if (s > 0) "footL" else "footR", ColorRole.ACCENT, PartPose.offset(1.1f * s, 0f, 0.8f)) {
			cube(-0.8f, 0f, -0.6f, 1.6f, 0.6f, 2.2f)
		}
	}
}

/** Waddles side to side with alternating feet, flaps when happy, tucks its head down to sleep. */
internal fun animatePenguin(m: PetModel, time: Float, speed: Double, sleeping: Boolean, react: PetReactions.Pose?) {
	val walk = (speed * 7).toFloat().coerceIn(0f, 1f)
	val step = sin(time * 0.6f)
	m.roll = step * 0.18f * walk + sin(time * 0.05f) * 0.03f * (1f - walk)
	m.part("footL")?.z = 0.8f + step * 1.2f * walk
	m.part("footR")?.z = 0.8f - step * 1.2f * walk
	val flap = 0.15f + walk * 0.25f + (react?.wings ?: 0f) * (0.6f + sin(time * 1.4f) * 0.5f)
	m.part("flipperL")?.zRot = -flap
	m.part("flipperR")?.zRot = flap
	val look = sin(time * 0.03f) * 0.4f * (1f - walk)
	for (n in listOf("head", "headFace", "headEyes", "headBeak")) m.part(n)?.let {
		it.yRot = look
		it.xRot = -(react?.headUp ?: 0f)
		if (sleeping) { it.xRot = 0.5f; it.y -= 0.8f; it.z += 0.6f }
	}
	m.part("headEyes")?.visible = !sleeping
}
