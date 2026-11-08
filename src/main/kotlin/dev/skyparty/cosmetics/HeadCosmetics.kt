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
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.LightLayer
import kotlin.math.sin

/**
 * Head cosmetics for the players listed in cosmetics.json: devil horns ("horns", Deadpool red with black tips) and long bunny ears
 * ("bunny_ears", one of them floppy). Client-side only, like the cat ears; same conventions (model pixels,
 * Y up, facing +Z, everything applied on the PoseStack so the shared parts carry no per-player state).
 */
object HeadCosmetics {
	/** Extra names for tests only (the dev account is "Player0"). */
	val testHorns = mutableSetOf<String>()
	val testBunny = mutableSetOf<String>()

	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/pet_white.png")
	private val renderType by lazy { RenderTypes.entityTranslucent(TEXTURE) }

	// Deadpool colors (to match Exlusiv's skin): deep suit red, black tips.
	private const val HORN = 0xFF9E0F18.toInt()
	private const val HORN_TIP = 0xFF141414.toInt()
	private const val FUR = 0xFFF4F1EC.toInt()
	private const val INNER = 0xFFF2A7B5.toInt()

	private fun has(key: String, test: Set<String>, player: Player) =
		player.gameProfile.name.lowercase().let { CosmeticOwners.has(key, it) || it in test }

	private class Parts(
		val hornBase: ModelPart, val hornTip: ModelPart,
		val earLow: ModelPart, val earHigh: ModelPart, val earInnerLow: ModelPart, val earInnerHigh: ModelPart,
	)

	private val parts by lazy {
		val mesh = MeshDefinition()
		fun add(name: String, cubes: CubeListBuilder) = mesh.root.addOrReplaceChild(name, cubes, PartPose.ZERO)
		// Horn: a thick base and a thinner, pointed tip (origin = base center); the tip is bent separately.
		add("hornBase", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-0.9f, 0f, -0.9f, 1.8f, 1.4f, 1.8f)
			.addBox(-0.75f, 1.4f, -0.75f, 1.5f, 1.2f, 1.5f))
		add("hornTip", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-0.55f, 0f, -0.55f, 1.1f, 1.1f, 1.1f)
			.addBox(-0.35f, 1.1f, -0.35f, 0.7f, 0.9f, 0.7f)
			.addBox(-0.2f, 2f, -0.2f, 0.4f, 0.6f, 0.4f))
		// Bunny ear in two halves (lower, upper) so the upper half can flop over.
		add("earLow", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-1f, 0f, -0.5f, 2f, 4.2f, 1f))
		add("earHigh", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-1f, 0f, -0.5f, 2f, 3.2f, 1f)
			.addBox(-0.7f, 3.2f, -0.5f, 1.4f, 0.8f, 1f))
		add("earInnerLow", CubeListBuilder.create().texOffs(0, 0).addBox(-0.6f, 0.8f, 0.45f, 1.2f, 3.4f, 0.15f))
		add("earInnerHigh", CubeListBuilder.create().texOffs(0, 0).addBox(-0.6f, 0f, 0.45f, 1.2f, 3f, 0.15f))
		val root = LayerDefinition.create(mesh, 16, 16).bakeRoot()
		Parts(
			root.getChild("hornBase"), root.getChild("hornTip"),
			root.getChild("earLow"), root.getChild("earHigh"), root.getChild("earInnerLow"), root.getChild("earInnerHigh"),
		)
	}

	fun render(ctx: LevelRenderContext) {
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val cam = ctx.levelState().cameraRenderState.pos
		for (player in level.players()) {
			val horns = has(CosmeticOwners.HORNS, testHorns, player)
			val bunny = has(CosmeticOwners.BUNNY_EARS, testBunny, player)
			if (!horns && !bunny) continue
			if (player.isInvisible) continue
			if (player === mc.player && mc.options.cameraType.isFirstPerson) continue
			val pos = player.getPosition(partial)
			val bodyYaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot)
			val headYaw = Mth.rotLerp(partial, player.yHeadRotO, player.yHeadRot)
			val pitch = Mth.lerp(partial, player.xRotO, player.xRot)
			val time = player.tickCount + partial
			val walk = player.walkAnimation.speed(partial).coerceIn(0f, 1f)
			val light = lightAt(level, BlockPos.containing(pos.x, pos.y + 1.6, pos.z))
			val pose = ctx.poseStack()
			val colors = dev.skyparty.pet.RemotePets.colorsOf(player)
			val fur = colors?.first ?: FUR
			val inner = colors?.second ?: INNER

			pose.pushPose()
			pose.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z)
			pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw))
			pose.scale(0.9375f, 0.9375f, 0.9375f) // players are drawn at 15/16 size
			// Follow the head: neck pivot at 24 px (sneaking lowers the head).
			pose.px(0f, if (player.isCrouching) 17.7f else 24f, 0f)
			pose.mulPose(Axis.YP.rotationDegrees(-(headYaw - bodyYaw)))
			pose.mulPose(Axis.XP.rotationDegrees(pitch))

			if (horns) for (side in listOf(-1f, 1f)) {
				pose.pushPose()
				// On the front half of the head, sunk a little so no gap shows; leaning out and back.
				pose.px(2.4f * side, 7.5f, 1.2f)
				pose.mulPose(Axis.ZP.rotationDegrees(-22f * side))
				pose.mulPose(Axis.XP.rotationDegrees(-12f))
				submit(ctx, parts.hornBase, pose, light, HORN)
				pose.px(0f, 2.5f, 0f)
				// The tip curls inwards and back, like a classic devil horn.
				pose.mulPose(Axis.ZP.rotationDegrees(28f * side))
				pose.mulPose(Axis.XP.rotationDegrees(-18f))
				submit(ctx, parts.hornTip, pose, light, HORN_TIP)
				pose.popPose()
			}

			if (bunny) for (side in listOf(-1f, 1f)) {
				pose.pushPose()
				pose.px(1.7f * side, 7.4f, -0.8f)
				// Ears bounce a bit while walking and twitch now and then.
				val bounce = sin(time * 0.6f) * 6f * walk
				val twitch = if ((time / 60).toInt() % 3 == 0 && side > 0) sin(time * 1.3f) * 6f else 0f
				pose.mulPose(Axis.ZP.rotationDegrees(-10f * side + twitch))
				pose.mulPose(Axis.XP.rotationDegrees(-10f + bounce))
				submit(ctx, parts.earLow, pose, light, fur)
				submit(ctx, parts.earInnerLow, pose, light, inner)
				pose.px(0f, 4.2f, 0f)
				// One ear stands up, the other flops over at the middle.
				if (side > 0) pose.mulPose(Axis.XP.rotationDegrees(-8f - bounce * 0.5f))
				else pose.mulPose(Axis.XP.rotationDegrees(-95f + sin(time * 0.08f) * 5f))
				submit(ctx, parts.earHigh, pose, light, fur)
				submit(ctx, parts.earInnerHigh, pose, light, inner)
				pose.popPose()
			}
			pose.popPose()
		}
	}

	private fun PoseStack.px(x: Float, y: Float, z: Float) = translate(x / 16f, y / 16f, z / 16f)

	private fun submit(ctx: LevelRenderContext, part: ModelPart, pose: PoseStack, light: Int, color: Int) {
		ctx.submitNodeCollector().submitModelPart(part, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, color, null)
	}

	/** The brighter of the head block and the one above (a head under a slab must not go black). */
	private fun lightAt(level: ClientLevel, pos: BlockPos): Int {
		val up = pos.above()
		val block = maxOf(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.BLOCK, up))
		val sky = maxOf(level.getBrightness(LightLayer.SKY, pos), level.getBrightness(LightLayer.SKY, up))
		return (block shl 4) or (sky shl 20)
	}
}
