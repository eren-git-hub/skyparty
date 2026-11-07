package dev.skyparty.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.skyparty.Skyparty
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
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.LightLayer
import kotlin.math.sin

/**
 * Cosmetic: cat ears on the head and a swaying tail for the players in [OWNERS]. Client-side only
 * (just people with the mod see them), nothing is sent anywhere.
 *
 * Offsets are in model pixels (1/16 block), Y up, facing +Z. Every transform is applied on the PoseStack before a part
 * is submitted, so the shared parts never carry per-player state (submitted parts are drawn later).
 */
object CatEars {
	/** Classic catgirl look: black ears (pink inside) and a black tail. Names are lowercase. */
	private val OWNERS = setOf("evocative", "mutsiri", "jijli", "mongers", "limnophobia")
	private const val FUR = 0xFF1B1A1F.toInt()

	/** Extra names for tests only (the dev account is "Player0"). */
	val testOwners = mutableSetOf<String>()

	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/pet_white.png")
	private val renderType by lazy { RenderTypes.entityTranslucent(TEXTURE) }
	private const val INNER = 0xFFF2A7B5.toInt()
	private const val TAIL_SEGMENTS = 4

	fun hasCatEars(player: Player) = player.gameProfile.name.lowercase().let { it in OWNERS || it in testOwners }

	private class Parts(val ear: ModelPart, val earInner: ModelPart, val tail: ModelPart, val tailTip: ModelPart)

	private val parts by lazy {
		val mesh = MeshDefinition()
		fun add(name: String, cubes: CubeListBuilder) = mesh.root.addOrReplaceChild(name, cubes, PartPose.ZERO)
		// A pointy ear from stacked, narrowing boxes (origin = ear base center).
		add("ear", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-1.5f, 0f, -0.5f, 3f, 1.6f, 1f)
			.addBox(-1.25f, 1.6f, -0.5f, 2.5f, 1f, 1f)
			.addBox(-0.9f, 2.6f, -0.5f, 1.8f, 0.8f, 1f)
			.addBox(-0.5f, 3.4f, -0.5f, 1f, 0.7f, 1f))
		add("earInner", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-0.9f, 0.8f, 0.45f, 1.8f, 1.2f, 0.15f)
			.addBox(-0.55f, 2f, 0.45f, 1.1f, 0.9f, 0.15f))
		// One tail segment, 3 px long towards -Z (origin = its joint).
		add("tail", CubeListBuilder.create().texOffs(0, 0).addBox(-0.6f, -0.6f, -3.2f, 1.2f, 1.2f, 3.4f))
		add("tailTip", CubeListBuilder.create().texOffs(0, 0).addBox(-0.7f, -0.7f, -2.4f, 1.4f, 1.4f, 2.6f))
		val root = LayerDefinition.create(mesh, 16, 16).bakeRoot()
		Parts(root.getChild("ear"), root.getChild("earInner"), root.getChild("tail"), root.getChild("tailTip"))
	}

	fun render(ctx: LevelRenderContext) {
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val cam = ctx.levelState().cameraRenderState.pos
		for (player in level.players()) {
			if (!hasCatEars(player)) continue
			val fur = FUR
			if (player.isInvisible) continue
			if (player === mc.player && mc.options.cameraType.isFirstPerson) continue
			val pos = player.getPosition(partial)
			val bodyYaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot)
			val headYaw = Mth.rotLerp(partial, player.yHeadRotO, player.yHeadRot)
			val pitch = Mth.lerp(partial, player.xRotO, player.xRot)
			val time = player.tickCount + partial
			val light = lightAt(level, BlockPos.containing(pos.x, pos.y + 1.6, pos.z))
			val pose = ctx.poseStack()
			val crouch = player.isCrouching

			pose.pushPose()
			pose.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
			pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw))
			// Players are drawn at 15/16 size.
			pose.scale(0.9375f, 0.9375f, 0.9375f)

			// ---- ears: follow the head (neck pivot at 24 px; sneaking lowers the head 4.2 px and the whole player 2 px)
			pose.pushPose()
			pose.px(0f, if (crouch) 17.7f else 24f, 0f)
			pose.mulPose(Axis.YP.rotationDegrees(-(headYaw - bodyYaw)))
			pose.mulPose(Axis.XP.rotationDegrees(pitch))
			val twitch = if ((time / 50).toInt() % 4 == 0) sin(time * 1.4f) * 8f else 0f
			for (side in listOf(-1f, 1f)) {
				pose.pushPose()
				// Base sunk 0.6 px into the head so no gap shows, even with the hat layer or when looking down.
				pose.px(2.5f * side, 7.4f, -0.6f)
				pose.mulPose(Axis.ZP.rotationDegrees(-7f * side + (if (side > 0) twitch else 0f)))
				pose.mulPose(Axis.XP.rotationDegrees(-8f))
				submit(ctx, parts.ear, pose, light, fur)
				submit(ctx, parts.earInner, pose, light, INNER)
				pose.popPose()
			}
			pose.popPose()

			// ---- tail: from the lower back, curling up and swaying
			pose.pushPose()
			if (crouch) {
				pose.px(0f, 8.4f, -4.6f)
			} else pose.px(0f, 12.3f, -2.1f)
			val walk = (player.walkAnimation.speed(partial)).coerceIn(0f, 1f)
			val sway = sin(time * 0.09f)
			pose.mulPose(Axis.YP.rotationDegrees(sway * 18f))
			pose.mulPose(Axis.XP.rotationDegrees(-25f + walk * 10f))
			for (i in 0 until TAIL_SEGMENTS) {
				val last = i == TAIL_SEGMENTS - 1
				submit(ctx, if (last) parts.tailTip else parts.tail, pose, light, fur)
				pose.px(0f, 0f, -3f)
				// Each joint bends further up and lags behind the sway, so the tail curls into an S.
				pose.mulPose(Axis.XP.rotationDegrees(26f))
				pose.mulPose(Axis.YP.rotationDegrees(sin(time * 0.09f - (i + 1) * 0.7f) * 14f))
			}
			pose.popPose()

			pose.popPose()
		}
	}

	/** Translate in model pixels (1/16 block); the parts themselves are already scaled by Minecraft. */
	private fun PoseStack.px(x: Float, y: Float, z: Float) = translate(x / 16f, y / 16f, z / 16f)

	private fun submit(ctx: LevelRenderContext, part: ModelPart, pose: PoseStack, light: Int, color: Int) {
		ctx.submitNodeCollector().submitModelPart(part, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, color, null)
	}

	private fun lightAt(level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos): Int {
		val block = level.getBrightness(LightLayer.BLOCK, pos)
		val sky = level.getBrightness(LightLayer.SKY, pos)
		return (block shl 4) or (sky shl 20)
	}
}
