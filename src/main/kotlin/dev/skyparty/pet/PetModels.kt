package dev.skyparty.pet

import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition

/** Which pet color a part is tinted with. The texture is plain white, so color comes only from the tint. */
enum class ColorRole { BODY, BODY_SHADE, ACCENT, SHELL, EYE }

/**
 * A part of the pet. [follow] names another part whose transform this one rides on (a horn on the head,
 * the spade on the tail tip), so parts with different colors can still move together.
 */
class PetPart(val name: String, val role: ColorRole, val follow: List<String> = emptyList())

/**
 * The pet model: one baked root whose direct children are tinted individually.
 * Coordinates are in 1/16 block, Y up, origin at the bottom center, facing +Z.
 * Every pet in the world needs its own instance ([PetModels.create]): submitted parts are drawn later
 * in the frame, so sharing one instance would give every pet the last pet's pose.
 */
class PetModel(val root: ModelPart, val parts: List<PetPart>, val scale: Float) {
	fun part(name: String): ModelPart? = if (root.hasChild(name)) root.getChild(name) else null

	/** Resets every part to its rest pose before animating. */
	fun reset() {
		for (p in parts) part(p.name)?.resetPose()
		part("tail")?.let { t ->
			t.getChild("tail2").resetPose()
			t.getChild("tail2").getChild("tail3").resetPose()
		}
	}
}

/** A small, lifelike dragon: neck and head with jaw and horns, jointed legs, a three-part tail and membrane wings. */
object PetModels {
	const val SCALE = 0.85f

	fun create(): PetModel {
		val mesh = MeshDefinition()
		val b = Builder(mesh.root)
		b.define()
		return PetModel(LayerDefinition.create(mesh, 16, 16).bakeRoot(), b.parts, SCALE)
	}

	private class Builder(val root: PartDefinition) {
		val parts = mutableListOf<PetPart>()

		fun part(
			name: String,
			role: ColorRole,
			pose: PartPose = PartPose.ZERO,
			follow: List<String> = emptyList(),
			boxes: CubeListBuilder.() -> Unit,
		): PartDefinition {
			val cubes = CubeListBuilder.create().texOffs(0, 0)
			cubes.boxes()
			parts += PetPart(name, role, follow)
			return root.addOrReplaceChild(name, cubes, pose)
		}
	}

	private fun CubeListBuilder.box(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float) {
		addBox(x, y, z, w, h, d)
	}

	private fun Builder.define() {
		// ---- torso: deep chest, narrower hips, lighter belly scales
		part("body", ColorRole.BODY) {
			box(-2.5f, 5f, -1f, 5f, 5f, 6f) // chest
			box(-2.2f, 5.3f, -5f, 4.4f, 4.4f, 4.2f) // hips
			box(-2f, 9.6f, -0.5f, 4f, 0.8f, 5f) // shoulder hump
		}
		part("belly", ColorRole.BODY_SHADE) {
			box(-2f, 4.7f, -4.5f, 4f, 0.4f, 9f)
			box(-1.8f, 5.5f, 4.8f, 3.6f, 3.6f, 0.4f) // chest plate
		}
		part("spikes", ColorRole.ACCENT) {
			for ((i, z) in listOf(3.5f, 1.6f, -0.4f, -2.4f, -4.3f).withIndex()) {
				val h = 1.3f - i * 0.15f
				val top = if (z > -0.8f) 10.4f else 9.7f
				box(-0.3f, top - 0.2f, z, 0.6f, h, 1f)
			}
		}

		// ---- neck and head (head parts share the head's pivot so they move as one)
		part("neck", ColorRole.BODY, PartPose.offsetAndRotation(0f, 9f, 4.2f, 0.75f, 0f, 0f)) {
			box(-1.4f, 0f, -1.4f, 2.8f, 4.6f, 2.8f)
		}
		part("neckBelly", ColorRole.BODY_SHADE, follow = listOf("neck")) {
			box(-1f, 0.2f, 1.35f, 2f, 4f, 0.3f)
		}
		val head = PartPose.offset(0f, 12.1f, 7.1f)
		part("head", ColorRole.BODY, head) {
			box(-1.75f, -0.8f, -1.6f, 3.5f, 3f, 3.6f) // skull
			box(-1.2f, -0.6f, 2f, 2.4f, 1.5f, 3f) // snout
			box(-1.9f, 1.4f, 0.2f, 3.8f, 0.6f, 1.6f) // brow ridge
		}
		part("headJaw", ColorRole.BODY_SHADE, head) { box(-1.1f, -1.35f, 0.8f, 2.2f, 0.7f, 4f) }
		part("headEyes", ColorRole.EYE, head) {
			box(-1.85f, 0.7f, 1.1f, 0.3f, 0.7f, 0.9f)
			box(1.55f, 0.7f, 1.1f, 0.3f, 0.7f, 0.9f)
			box(-0.8f, 0.5f, 4.8f, 0.4f, 0.3f, 0.3f) // nostrils
			box(0.4f, 0.5f, 4.8f, 0.4f, 0.3f, 0.3f)
		}
		part("headHorns", ColorRole.SHELL, head) {
			for (s in listOf(-1f, 1f)) {
				// Stepped boxes read as a horn curving back and up.
				box(if (s < 0) -1.5f else 0.7f, 1.9f, -1.3f, 0.8f, 0.8f, 1.3f)
				box(if (s < 0) -1.65f else 0.95f, 2.3f, -2.4f, 0.7f, 0.7f, 1.2f)
				box(if (s < 0) -1.75f else 1.25f, 2.65f, -3.3f, 0.5f, 0.5f, 1f)
			}
			box(-1.05f, -0.75f, 4.3f, 0.3f, 0.4f, 0.3f) // fangs
			box(0.75f, -0.75f, 4.3f, 0.3f, 0.4f, 0.3f)
		}

		// ---- legs: thigh, shin and a clawed foot; hind legs heavier
		for ((name, x, z, hind) in listOf(
			Leg("legFL", -2f, 3.2f, false), Leg("legFR", 2f, 3.2f, false),
			Leg("legBL", -2f, -3.6f, true), Leg("legBR", 2f, -3.6f, true),
		)) {
			part(name, ColorRole.BODY_SHADE, PartPose.offset(x, 6.5f, z)) {
				if (hind) box(-1.2f, -3.5f, -1.6f, 2.4f, 4.5f, 3.2f) else box(-1f, -3.2f, -1.1f, 2f, 4f, 2.2f)
				box(-0.75f, -6f, -0.7f, 1.5f, 3f, 1.4f)
				box(-1f, -6.5f, -0.7f, 2f, 0.8f, 2.6f)
			}
			part("${name}Claws", ColorRole.SHELL, follow = listOf(name)) {
				for (cx in listOf(-0.9f, -0.15f, 0.6f)) box(cx, -6.5f, 1.9f, 0.3f, 0.5f, 0.5f)
			}
		}

		// ---- tail: three segments that curl, with a spade on the tip
		val tail = part("tail", ColorRole.BODY, PartPose.offsetAndRotation(0f, 7.6f, -4.8f, -0.3f, 0f, 0f)) {
			box(-1.3f, -1.3f, -5f, 2.6f, 2.6f, 5.2f)
		}
		val tail2 = tail.addOrReplaceChild(
			"tail2", CubeListBuilder.create().texOffs(0, 0).addBox(-0.9f, -0.9f, -5f, 1.8f, 1.8f, 5.2f),
			PartPose.offsetAndRotation(0f, 0f, -4.8f, 0.15f, 0f, 0f),
		)
		tail2.addOrReplaceChild(
			"tail3", CubeListBuilder.create().texOffs(0, 0).addBox(-0.55f, -0.55f, -4.5f, 1.1f, 1.1f, 4.7f),
			PartPose.offsetAndRotation(0f, 0f, -4.8f, 0.2f, 0f, 0f),
		)
		part("tailSpade", ColorRole.ACCENT, follow = listOf("tail", "tail2", "tail3")) {
			box(-1.3f, -0.25f, -6f, 2.6f, 0.5f, 1.8f)
			box(-0.7f, -0.25f, -6.9f, 1.4f, 0.5f, 0.9f)
		}

		// ---- wings: bone frame + membrane, pivoting at the shoulder (they fold along the flanks)
		for (side in listOf(1f, -1f)) {
			val suffix = if (side > 0) "L" else "R"
			val pose = PartPose.offset(2.3f * side, 9.6f, 2.6f)
			fun x(from: Float, w: Float) = if (side > 0) from else -from - w
			part("wing$suffix", ColorRole.ACCENT, pose) {
				box(x(0.3f, 6f), -0.12f, -5.2f, 6f, 0.24f, 5f)
				box(x(1f, 2.2f), -0.12f, -6.1f, 2.2f, 0.24f, 1f)
				box(x(4f, 2f), -0.12f, -6.8f, 2f, 0.24f, 1.7f)
			}
			part("wingBone$suffix", ColorRole.BODY_SHADE, pose) {
				box(x(0f, 6.6f), -0.4f, -0.4f, 6.6f, 0.8f, 0.8f) // arm
				box(x(5.9f, 0.6f), -0.3f, -6.8f, 0.6f, 0.6f, 6.6f) // outer finger
				box(x(3.3f, 0.5f), -0.25f, -5.8f, 0.5f, 0.5f, 5.6f) // inner finger
			}
			part("wingClaw$suffix", ColorRole.SHELL, follow = listOf("wingBone$suffix")) {
				box(x(6.4f, 0.6f), -0.3f, 0.1f, 0.6f, 0.6f, 0.7f)
			}
		}
	}

	private data class Leg(val name: String, val x: Float, val z: Float, val hind: Boolean)
}
