package dev.skyparty.pet

import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition

/** Which pet color a part is tinted with. The texture is plain white, so color comes only from the tint. */
enum class ColorRole { BODY, BODY_SHADE, ACCENT, SHELL, EYE }

class PetPart(val name: String, val role: ColorRole)

/**
 * A stage model: one baked root whose direct children are tinted individually.
 * Coordinates are in 1/16 block, Y up, origin at the bottom center, facing +Z.
 */
class PetModel(val root: ModelPart, val parts: List<PetPart>, val scale: Float) {
	fun part(name: String): ModelPart? = if (root.hasChild(name)) root.getChild(name) else null
}

object PetModels {
	val models: Map<PetStage, PetModel> by lazy {
		mapOf(
			PetStage.EGG to egg(),
			PetStage.HATCHLING to hatchling(),
			PetStage.JUVENILE to creature(adult = false),
			PetStage.ADULT to creature(adult = true),
		)
	}

	private fun build(scale: Float, define: Builder.() -> Unit): PetModel {
		val mesh = MeshDefinition()
		val builder = Builder(mesh.root)
		builder.define()
		return PetModel(LayerDefinition.create(mesh, 16, 16).bakeRoot(), builder.parts, scale)
	}

	private class Builder(val root: PartDefinition) {
		val parts = mutableListOf<PetPart>()

		/** Adds a part pivoting at (px,py,pz); boxes are given relative to that pivot. */
		fun part(
			name: String,
			role: ColorRole,
			px: Float = 0f,
			py: Float = 0f,
			pz: Float = 0f,
			boxes: CubeListBuilder.() -> Unit,
		) {
			val cubes = CubeListBuilder.create().texOffs(0, 0)
			cubes.boxes()
			root.addOrReplaceChild(name, cubes, PartPose.offset(px, py, pz))
			parts += PetPart(name, role)
		}
	}

	private fun CubeListBuilder.box(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float) {
		addBox(x, y, z, w, h, d)
	}

	private fun egg() = build(1f) {
		// Pivot at the bottom so the egg wobbles around its base.
		part("shell", ColorRole.SHELL) {
			box(-2.5f, 0f, -2.5f, 5f, 1f, 5f)
			box(-3f, 1f, -3f, 6f, 4f, 6f)
			box(-2.5f, 5f, -2.5f, 5f, 2f, 5f)
			box(-1.5f, 7f, -1.5f, 3f, 1f, 3f)
		}
		part("spots", ColorRole.BODY) {
			box(-1.5f, 2.5f, 2.9f, 1.6f, 1.6f, 0.3f)
			box(1f, 1.5f, 2.9f, 1f, 1f, 0.3f)
			box(-3.2f, 3f, 0f, 0.3f, 1.5f, 1.5f)
			box(2.9f, 1.5f, -1f, 0.3f, 1.2f, 1.2f)
			box(0.5f, 3.5f, -3.2f, 1.5f, 1.2f, 0.3f)
			box(-0.5f, 5.5f, 2.4f, 1f, 1f, 0.3f)
		}
	}

	private fun hatchling() = build(1f) {
		part("body", ColorRole.BODY) {
			box(-3f, 1f, -3f, 6f, 5f, 6f)
			box(-3f, 6f, -2.5f, 6f, 5f, 5f) // big head
		}
		part("belly", ColorRole.BODY_SHADE) { box(-2f, 1.5f, 2.9f, 4f, 3.5f, 0.3f) }
		part("eyes", ColorRole.EYE) {
			box(-2f, 8f, 2.4f, 1f, 1.5f, 0.3f)
			box(1f, 8f, 2.4f, 1f, 1.5f, 0.3f)
		}
		part("beak", ColorRole.ACCENT) { box(-1f, 7f, 2.4f, 2f, 1f, 1f) }
		part("feet", ColorRole.ACCENT) {
			box(-2.5f, 0f, -0.5f, 2f, 1f, 2.5f)
			box(0.5f, 0f, -0.5f, 2f, 1f, 2.5f)
		}
		part("shellHat", ColorRole.SHELL) {
			box(-2.5f, 11f, -2f, 5f, 1.5f, 4f)
			box(-2f, 12.5f, -1.5f, 1f, 1f, 3f)
			box(1f, 12.5f, -1.5f, 1f, 1f, 3f)
		}
		part("wingL", ColorRole.BODY_SHADE, px = 3f, py = 5f) { box(0f, -2.5f, -1.5f, 0.8f, 2.5f, 3f) }
		part("wingR", ColorRole.BODY_SHADE, px = -3f, py = 5f) { box(-0.8f, -2.5f, -1.5f, 0.8f, 2.5f, 3f) }
	}

	private fun creature(adult: Boolean) = build(if (adult) 1.45f else 1f) {
		part("body", ColorRole.BODY) {
			box(-3f, 3f, -4f, 6f, 5f, 8f)
			box(-3f, 6f, 3f, 6f, 5f, 5f) // head
		}
		part("belly", ColorRole.BODY_SHADE) { box(-2f, 2.8f, -3f, 4f, 0.3f, 6f) }
		part("snout", ColorRole.ACCENT) { box(-1.5f, 6.5f, 8f, 3f, 2f, 1.5f) }
		part("eyes", ColorRole.EYE) {
			box(-2.5f, 9f, 7.9f, 1f, 1.5f, 0.3f)
			box(1.5f, 9f, 7.9f, 1f, 1.5f, 0.3f)
		}
		part("ears", ColorRole.ACCENT) {
			box(-2.5f, 11f, 4f, 1.5f, 2f, 1.5f)
			box(1f, 11f, 4f, 1.5f, 2f, 1.5f)
		}
		part("legFL", ColorRole.BODY_SHADE, px = -1.5f, py = 3f, pz = 2.5f) { box(-1f, -3f, -1f, 2f, 3f, 2f) }
		part("legFR", ColorRole.BODY_SHADE, px = 1.5f, py = 3f, pz = 2.5f) { box(-1f, -3f, -1f, 2f, 3f, 2f) }
		part("legBL", ColorRole.BODY_SHADE, px = -1.5f, py = 3f, pz = -2.5f) { box(-1f, -3f, -1f, 2f, 3f, 2f) }
		part("legBR", ColorRole.BODY_SHADE, px = 1.5f, py = 3f, pz = -2.5f) { box(-1f, -3f, -1f, 2f, 3f, 2f) }
		part("tail", ColorRole.BODY, py = 6f, pz = -4f) { box(-1f, -1f, -6f, 2f, 2f, 6f) }
		part("tailTip", ColorRole.ACCENT, py = 6f, pz = -4f) { box(-1.3f, -1.3f, -8f, 2.6f, 2.6f, 2f) }
		if (adult) {
			part("spikes", ColorRole.ACCENT) {
				box(-0.5f, 8f, -3f, 1f, 1.5f, 1.5f)
				box(-0.5f, 8f, 0f, 1f, 1.5f, 1.5f)
				box(-0.5f, 11f, 4.5f, 1f, 1.5f, 1.5f)
			}
			part("wingL", ColorRole.ACCENT, px = 3f, py = 8f) { box(0f, 0f, -3f, 8f, 0.5f, 6f) }
			part("wingR", ColorRole.ACCENT, px = -3f, py = 8f) { box(-8f, 0f, -3f, 8f, 0.5f, 6f) }
		}
	}
}
