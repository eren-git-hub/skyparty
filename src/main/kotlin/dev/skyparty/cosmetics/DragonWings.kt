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
 * Exclusive cosmetic: red-black dragon wings on the players listed in cosmetics.json (see [CosmeticOwners]). Purely client-side
 * (only people with the mod see them), nothing is sent to any server.
 *
 * Model units are 1/16 block, Y up, facing +Z (same convention as the pet models). The right wing is
 * the left one turned 180° around Y, so it shows the mirrored back face of the texture.
 */
object DragonWings {
	/** Extra names for tests only (the dev account is "Player0"). */
	val testOwners = mutableSetOf<String>()

	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/dragon_wings.png")
	private val renderType by lazy { RenderTypes.entityTranslucent(TEXTURE) }


	fun hasWings(player: Player) = player.gameProfile.name.lowercase().let { CosmeticOwners.has(CosmeticOwners.DRAGON_WINGS, it) || it in testOwners }

	/**
	 * The wing design as pixels (image space: x = outwards from the spine, y = down). Letters index the
	 * palette rows of dragon_wings.png (4 px tall each): a outline, b/c bone, d..h membrane shades taken
	 * from the owner's skin. Generated from the 2D design, so the 3D wing looks exactly like it.
	 */
	private val PIXELS = listOf(
		"...................abccc",
		"..............accccbccca",
		"..........accbccccbddda.",
		"......aaccccbccdddeeeea.",
		"...aacbccccdcccddffffa..",
		"aacccbcddddcccdbddffa...",
		"ccccdddgggdccdbdcddga...",
		"cdddeeeehdcdcdcddcdda...",
		"afffffhffdcdbdcdfdccda..",
		"afffhfffdcddcddcdfdcca..",
		"aahgggggdcddcddbdggabca.",
		"..aaeeedcdedcddcdea..cc.",
		"....affdbdfdcdfdcda.....",
		"....afdbdffdbdfacda.....",
		".....adcaagdcda.bca.....",
		".....aca..adca..ac......",
		"......c...adca...c......",
		"...........ac...........",
		"...........ab...........",
		"........................",
	)
	private const val BONES = "bc"
	/** Image row of the shoulder (the wing's pivot). */
	private const val SHOULDER_ROW = 7f
	private const val BONE_DEPTH = 1.0f
	private const val SKIN_DEPTH = 0.5f
	const val SCALE = 0.8f

	/**
	 * Each wing is the pixel design extruded into blocks: every horizontal run of one color is one box,
	 * bones thicker than the membrane so they stand out. Two separate parts, because submitted parts are
	 * drawn later and one shared part would end up in one place.
	 */
	private val wings: List<ModelPart> by lazy {
		val mesh = MeshDefinition()
		for (name in listOf("left", "right")) {
			val cubes = CubeListBuilder.create()
			for ((y, row) in PIXELS.withIndex()) {
				var x = 0
				while (x < row.length) {
					val c = row[x]
					if (c == '.') { x++; continue }
					var end = x
					while (end + 1 < row.length && row[end + 1] == c) end++
					val depth = if (c in BONES) BONE_DEPTH else SKIN_DEPTH
					cubes.texOffs(0, (c - 'a') * 4)
						.addBox(x.toFloat(), SHOULDER_ROW - y - 1, -depth / 2, (end - x + 1).toFloat(), 1f, depth)
					x = end + 1
				}
			}
			mesh.root.addOrReplaceChild(name, cubes, PartPose.ZERO)
		}
		val root = LayerDefinition.create(mesh, 64, 64).bakeRoot()
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
			// Always the same calm idle sway: no flapping while falling or flying, no folding while sneaking.
			val flap = sin(time * 0.12f) * 0.18f
			val spread = 0.45f
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
			pose.scale(SCALE, SCALE, SCALE)
			val collector = ctx.submitNodeCollector()
			val sides = listOf((spread + flap) to 1f, (PI.toFloat() - spread - flap) to -1f)
			for ((i, side) in sides.withIndex()) {
				val (yaw, xOff) = side
				val wing = wings[i]
				wing.x = xOff
				wing.y = 0f
				wing.z = 0f
				wing.yRot = yaw
				wing.zRot = 0f
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
