package dev.skyparty.cosmetics

import com.mojang.math.Axis
import dev.skyparty.Skyparty
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.LightLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exclusive cosmetic: red-black dragon wings on players whose name is in [OWNERS]. Purely client-side
 * (only people with the mod see them), nothing is sent to any server.
 *
 * Model units are 1/16 block, Y up, facing +Z (same convention as the pet models). The right wing is
 * the left one turned 180° around Y, so it shows the mirrored back face of the texture.
 */
object DragonWings {
	private val OWNERS = setOf("exlusiv")

	/** Extra names for tests only (the dev account is "Player0"). */
	val testOwners = mutableSetOf<String>()

	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/dragon_wings.png")
	private val renderType by lazy { RenderTypes.entityTranslucent(TEXTURE) }


	fun hasWings(player: Player) = player.gameProfile.name.lowercase().let { it in OWNERS || it in testOwners }

	/**
	 * Each wing is one flat, pixel-art textured plane (24x20 px, texture 64x32: front at 0,0, mirrored back
	 * at 24,0) in the colors of the owner's skin. The pivot is the shoulder, 7 px below the top edge.
	 */
	private val wings: List<ModelPart> by lazy {
		// Two separate parts: submitted parts are drawn later, so one shared part would end up in one place.
		val mesh = MeshDefinition()
		for (name in listOf("left", "right")) {
			mesh.root.addOrReplaceChild(name, CubeListBuilder.create().texOffs(0, 0).addBox(0f, -13f, 0f, 24f, 20f, 0f), PartPose.ZERO)
		}
		val root = LayerDefinition.create(mesh, 64, 32).bakeRoot()
		listOf(root.getChild("left"), root.getChild("right"))
	}

	fun render(ctx: LevelRenderContext) {
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val cam = ctx.levelState().cameraRenderState.pos
		for (player in level.players()) {
			if (!hasWings(player) || player.isInvisible) continue
			if (player === mc.player && mc.options.cameraType.isFirstPerson) continue
			val pos = player.getPosition(partial)
			val bodyYaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot)
			val time = player.tickCount + partial
			val airborne = !player.onGround()
			val flap = if (airborne) sin(time * 0.55f) * 0.55f else sin(time * 0.12f) * 0.18f
			val spread = if (player.isCrouching) 0.95f else 0.45f
			val light = lightAt(level, BlockPos.containing(pos.x, pos.y + 1.2, pos.z))

			val pose = ctx.poseStack()
			pose.pushPose()
			pose.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
			pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw))
			// Attach to the upper back (lower and tilted forward while sneaking).
			if (player.isCrouching) {
				pose.translate(0.0, 19.0 / 16, -3.5 / 16)
				pose.mulPose(Axis.XP.rotationDegrees(28f))
			} else pose.translate(0.0, 22.0 / 16, -2.6 / 16)
			val collector = ctx.submitNodeCollector()
			val sides = listOf((spread + flap) to 1f, (PI.toFloat() - spread - flap) to -1f)
			for ((i, side) in sides.withIndex()) {
				val (yaw, xOff) = side
				val wing = wings[i]
				wing.x = xOff
				wing.y = 0f
				wing.z = 0f
				wing.yRot = yaw
				wing.zRot = if (airborne) sin(time * 0.55f + 1f) * 0.12f else 0f
				wing.xRot = 0f
				collector.submitModelPart(wing, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, 0xFFFFFFFF.toInt(), null)
			}
			pose.popPose()
		}
	}

	private fun lightAt(level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos): Int {
		val block = level.getBrightness(LightLayer.BLOCK, pos)
		val sky = level.getBrightness(LightLayer.SKY, pos)
		return (block shl 4) or (sky shl 20)
	}
}
