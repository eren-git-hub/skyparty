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
 * The low beach chair under players doing the chair emote: a wooden frame with a striped fabric seat and a
 * backrest leaned back like the player ([EmotePoses.CHAIR_LEAN]). Boxes in model units (1/16 block), Y up,
 * facing +Z like the player.
 */
object ChairRenderer {
	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/pet_white.png")
	private val renderType by lazy { RenderTypes.entityCutout(TEXTURE) }
	private const val WOOD = 0xFFC8955A.toInt()
	private const val WOOD_DARK = 0xFF9C6B3A.toInt()
	private const val STRIPE = 0xFFF4F1E8.toInt()

	/** Seat height and where the backrest is hinged (behind the hips). */
	private const val SEAT = 3.4f
	private const val HINGE_Y = 4.2f
	private const val HINGE_Z = -3.2f

	private class Parts(val base: ModelPart, val baseDark: ModelPart, val seatA: ModelPart, val seatB: ModelPart,
		val back: ModelPart, val backA: ModelPart, val backB: ModelPart)

	private val parts: Parts by lazy {
		val mesh = MeshDefinition()
		val root = mesh.root
		// Seat frame: two side rails sloping slightly, short front legs, back legs, a bar in front.
		root.addOrReplaceChild("base", CubeListBuilder.create()
			.addBox(-6f, SEAT - 1f, -4f, 1f, 1f, 11f).addBox(5f, SEAT - 1f, -4f, 1f, 1f, 11f)
			.addBox(-6f, 0f, 5.5f, 1f, SEAT - 1f, 1f).addBox(5f, 0f, 5.5f, 1f, SEAT - 1f, 1f)
			.addBox(-6f, 0f, -4.5f, 1f, SEAT, 1f).addBox(5f, 0f, -4.5f, 1f, SEAT, 1f)
			.addBox(-5f, SEAT - 1f, 6f, 10f, 1f, 1f),
			PartPose.ZERO)
		// Feet pads and a floor bar between the back legs.
		root.addOrReplaceChild("baseDark", CubeListBuilder.create()
			.addBox(-6.2f, 0f, 5.3f, 1.4f, 0.4f, 1.4f).addBox(4.8f, 0f, 5.3f, 1.4f, 0.4f, 1.4f)
			.addBox(-6.2f, 0f, -4.7f, 1.4f, 0.4f, 1.4f).addBox(4.8f, 0f, -4.7f, 1.4f, 0.4f, 1.4f)
			.addBox(-5f, 0.5f, -4.4f, 10f, 0.6f, 0.6f),
			PartPose.ZERO)
		// Striped sling seat: 5 strips across, sagging a little below the rails.
		val seatA = CubeListBuilder.create(); val seatB = CubeListBuilder.create()
		for (i in 0 until 5) (if (i % 2 == 0) seatA else seatB).addBox(-5f + i * 2f, SEAT - 1.2f, -3.8f, 2f, 0.6f, 10.4f)
		root.addOrReplaceChild("seatA", seatA, PartPose.ZERO)
		root.addOrReplaceChild("seatB", seatB, PartPose.ZERO)
		// Backrest, hinged behind the hips and leaned back.
		val lean = -Math.toRadians(EmotePoses.CHAIR_LEAN.toDouble()).toFloat()
		val hinge = PartPose.offsetAndRotation(0f, HINGE_Y, HINGE_Z, lean, 0f, 0f)
		root.addOrReplaceChild("back", CubeListBuilder.create()
			.addBox(-6f, 0f, -1f, 1f, 16f, 1f).addBox(5f, 0f, -1f, 1f, 16f, 1f)
			.addBox(-6f, 15f, -1f, 12f, 1.2f, 1f),
			hinge)
		val backA = CubeListBuilder.create(); val backB = CubeListBuilder.create()
		for (i in 0 until 5) (if (i % 2 == 0) backA else backB).addBox(-5f + i * 2f, 1.5f, -1.1f, 2f, 13f, 0.6f)
		root.addOrReplaceChild("backA", backA, hinge)
		root.addOrReplaceChild("backB", backB, hinge)
		val baked = LayerDefinition.create(mesh, 16, 16).bakeRoot()
		Parts(baked.getChild("base"), baked.getChild("baseDark"), baked.getChild("seatA"), baked.getChild("seatB"),
			baked.getChild("back"), baked.getChild("backA"), baked.getChild("backB"))
	}

	/** Tests only: chairs drawn so far. */
	var drawn = 0

	fun render(ctx: LevelRenderContext) {
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val cam = ctx.levelState().cameraRenderState.pos
		val fabric = EmotesModule.chairColor.value or 0xFF000000.toInt()
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
			fun part(p: ModelPart, color: Int) = c.submitModelPart(p, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, color, null)
			part(parts.base, WOOD); part(parts.back, WOOD); part(parts.baseDark, WOOD_DARK)
			part(parts.seatA, fabric); part(parts.backA, fabric)
			part(parts.seatB, STRIPE); part(parts.backB, STRIPE)
			pose.popPose()
			drawn++
		}
	}
}
