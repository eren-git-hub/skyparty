package dev.skyparty.emote

import com.mojang.math.Axis
import dev.skyparty.Skyparty
import dev.skyparty.module.impl.pets.EmotesModule
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LightLayer

/**
 * The folding chair under players doing the chair emote: a metal frame with a fabric seat and back, built
 * from boxes (model units 1/16 block, Y up, facing +Z like the player). The sitting player is drawn
 * [SIT_DROP] blocks lower, so the thighs rest on the seat.
 */
object ChairRenderer {
	/** How far the sitting player model is lowered (blocks): hips at 8 px, thighs on the seat at 6 px. */
	const val SIT_DROP = 0.25

	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/pet_white.png")
	private val renderType by lazy { RenderTypes.entityCutout(TEXTURE) }
	private const val FRAME_COLOR = 0xFF8E959C.toInt()
	private const val FRAME_DARK = 0xFF5E646A.toInt()

	private class Parts(val frame: ModelPart, val feet: ModelPart, val fabric: ModelPart)

	private val parts: Parts by lazy {
		val mesh = MeshDefinition()
		val frame = CubeListBuilder.create()
			// Back uprights (also the back legs) and front legs.
			.addBox(-6f, 0f, -4.6f, 1f, 20f, 1f).addBox(5f, 0f, -4.6f, 1f, 20f, 1f)
			.addBox(-6f, 0f, 5f, 1f, 6f, 1f).addBox(5f, 0f, 5f, 1f, 6f, 1f)
			// Seat rails, top rail of the back, cross bars near the floor.
			.addBox(-6f, 5f, -4.6f, 1f, 1f, 11.6f).addBox(5f, 5f, -4.6f, 1f, 1f, 11.6f)
			.addBox(-5f, 19f, -4.6f, 10f, 1f, 1f)
			.addBox(-5f, 1.5f, -4.4f, 10f, 0.6f, 0.6f).addBox(-5f, 1.5f, 5.2f, 10f, 0.6f, 0.6f)
		val feet = CubeListBuilder.create()
			.addBox(-6.3f, 0f, -4.9f, 1.6f, 0.5f, 1.6f).addBox(4.7f, 0f, -4.9f, 1.6f, 0.5f, 1.6f)
			.addBox(-6.3f, 0f, 4.7f, 1.6f, 0.5f, 1.6f).addBox(4.7f, 0f, 4.7f, 1.6f, 0.5f, 1.6f)
		val fabric = CubeListBuilder.create()
			.addBox(-5f, 5.2f, -3.8f, 10f, 0.8f, 10.4f) // seat
			.addBox(-5f, 11f, -4.3f, 10f, 8f, 0.6f) // back
		mesh.root.addOrReplaceChild("frame", frame, PartPose.ZERO)
		mesh.root.addOrReplaceChild("feet", feet, PartPose.ZERO)
		mesh.root.addOrReplaceChild("fabric", fabric, PartPose.ZERO)
		val root = LayerDefinition.create(mesh, 64, 64).bakeRoot()
		Parts(root.getChild("frame"), root.getChild("feet"), root.getChild("fabric"))
	}

	/** Tests only: chairs drawn so far. */
	var drawn = 0

	fun render(ctx: LevelRenderContext) {
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val cam = ctx.levelState().cameraRenderState.pos
		val fabricColor = EmotesModule.chairColor.value or 0xFF000000.toInt()
		for (player in level.players()) {
			if (!Emotes.isSitting(player) || player.isInvisible) continue
			if (player === mc.player && mc.options.cameraType.isFirstPerson) continue
			val pos = player.getPosition(partial)
			val yaw = Emotes.yawOf(player, player.yBodyRot)
			val bp = BlockPos.containing(pos.x, pos.y + 0.5, pos.z)
			val light = (level.getBrightness(LightLayer.BLOCK, bp) shl 4) or (level.getBrightness(LightLayer.SKY, bp) shl 20)
			val pose = ctx.poseStack()
			pose.pushPose()
			pose.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
			pose.mulPose(Axis.YP.rotationDegrees(-yaw))
			val c = ctx.submitNodeCollector()
			c.submitModelPart(parts.frame, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, FRAME_COLOR, null)
			c.submitModelPart(parts.feet, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, FRAME_DARK, null)
			c.submitModelPart(parts.fabric, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, fabricColor, null)
			drawn++
			pose.popPose()
		}
	}
}
