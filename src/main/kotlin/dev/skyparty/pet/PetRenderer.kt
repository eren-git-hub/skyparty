package dev.skyparty.pet

import com.mojang.math.Axis
import dev.skyparty.Skyparty
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.util.ColorUtil
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LightLayer
import kotlin.math.sin

/** Submits the local player's pet model into the world render each frame. */
object PetRenderer {
	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/pet_white.png")
	private val renderType by lazy { RenderTypes.entityTranslucent(TEXTURE) }

	fun render(ctx: LevelRenderContext) {
		if (!CustomPets.enabled) return
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		if (mc.player == null) return

		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val stage = PetData.stage
		val model = PetModels.models[stage] ?: return
		val time = PetController.age + partial

		animate(model, stage, time)

		val pos = PetController.renderPos(partial)
		val cam = ctx.levelState().cameraRenderState.pos
		val bob = sin(time * 0.1) * 0.06
		val light = lightAt(level, BlockPos.containing(pos.x, pos.y + 0.3, pos.z))
		val colors = palette()

		val pose = ctx.poseStack()
		pose.pushPose()
		pose.translate(pos.x - cam.x, pos.y - cam.y + bob, pos.z - cam.z)
		pose.mulPose(Axis.YP.rotationDegrees(-PetController.renderYaw(partial)))
		pose.scale(model.scale, model.scale, model.scale)
		if (stage == PetStage.EGG) applyEggWobble(pose, time)

		val collector = ctx.submitNodeCollector()
		for (part in model.parts) {
			val modelPart = model.part(part.name) ?: continue
			collector.submitModelPart(
				modelPart, pose, renderType, light, OverlayTexture.NO_OVERLAY, null,
				colors.getValue(part.role), null,
			)
		}
		pose.popPose()
	}

	private fun applyEggWobble(pose: com.mojang.blaze3d.vertex.PoseStack, time: Float) {
		// The closer the egg is to hatching, the more it wobbles.
		val progress = PetStage.progress(PetData.playtimeMs).toFloat()
		val amplitude = 2f + progress * 14f
		val wobble = sin(time * 0.35f) * amplitude * (if ((time / 40).toInt() % 3 == 0) 1f else 0.25f)
		pose.mulPose(Axis.ZP.rotationDegrees(wobble))
	}

	private fun animate(model: PetModel, stage: PetStage, time: Float) {
		val walk = (PetController.speed * 6).toFloat().coerceIn(0f, 1f)
		when (stage) {
			PetStage.EGG -> {}
			PetStage.HATCHLING -> {
				val flap = sin(time * 0.5f) * 0.5f
				model.part("wingL")?.zRot = -0.2f - flap
				model.part("wingR")?.zRot = 0.2f + flap
			}
			PetStage.JUVENILE, PetStage.ADULT -> {
				val swing = sin(time * 0.6f) * 0.7f * walk
				model.part("legFL")?.xRot = swing
				model.part("legBR")?.xRot = swing
				model.part("legFR")?.xRot = -swing
				model.part("legBL")?.xRot = -swing
				val wag = sin(time * 0.2f) * 0.4f
				model.part("tail")?.yRot = wag
				model.part("tailTip")?.yRot = wag
				model.part("tail")?.xRot = 0.25f
				model.part("tailTip")?.xRot = 0.25f
				val flap = sin(time * 0.25f) * 0.35f
				model.part("wingL")?.zRot = 0.3f + flap
				model.part("wingR")?.zRot = -0.3f - flap
			}
		}
	}

	private fun palette(): Map<ColorRole, Int> {
		val alpha = (CustomPets.opacity.value / 100.0 * 255).toInt()
		val body = CustomPets.bodyColor.value
		fun a(c: Int) = ColorUtil.withAlpha(c, alpha)
		return mapOf(
			ColorRole.BODY to a(body),
			ColorRole.BODY_SHADE to a(ColorUtil.shade(body, 0.78f)),
			ColorRole.ACCENT to a(CustomPets.accentColor.value),
			ColorRole.SHELL to a(0xFFF3E9D2.toInt()),
			ColorRole.EYE to a(0xFF1A1A22.toInt()),
		)
	}

	/** Packs block and sky light the same way vanilla light coords do. */
	private fun lightAt(level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos): Int {
		val block = level.getBrightness(LightLayer.BLOCK, pos)
		val sky = level.getBrightness(LightLayer.SKY, pos)
		return (block shl 4) or (sky shl 20)
	}
}
