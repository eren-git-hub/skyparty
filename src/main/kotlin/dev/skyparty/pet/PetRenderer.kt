package dev.skyparty.pet

import com.mojang.math.Axis
import dev.skyparty.Skyparty
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.util.ColorUtil
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LightLayer
import kotlin.math.sin

/** Submits your pet and the synced pets of other players into the world render each frame. */
object PetRenderer {
	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/pet_white.png")
	private val renderType by lazy { RenderTypes.entityTranslucent(TEXTURE) }

	private var ownModelCache: PetModel? = null

	/** Your pet's model, rebuilt when you pick another species. */
	private val ownModel: PetModel
		get() = ownModelCache?.takeIf { it.species == PetSpecies.own } ?: PetModels.create(PetSpecies.own).also { ownModelCache = it }

	/** Ghosts are a little see-through. */
	private fun opacityFor(species: PetSpecies) = CustomPets.opacity.value / 100.0 * if (species == PetSpecies.GHOST) 0.75 else 1.0

	/** Tests only: hide your own pet (e.g. to look at a remote pet attached to the dev player). */
	var hideOwnForTest = false

	fun render(ctx: LevelRenderContext) {
		if (!CustomPets.enabled) return
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		mc.player ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)

		if (!hideOwnForTest) renderPet(ctx, level, ownModel, PetController.follower, partial, palette(
			CustomPets.bodyColor.value, CustomPets.accentColor.value, opacityFor(PetSpecies.own),
		), PetData.isAfk, PetReactions.pose(PetController.age + partial))

		if (CustomPets.showOthers.value) {
			val maxDist = CustomPets.renderDistance.value
			for (pet in RemotePets.visible()) {
				if (pet.follower.pos.distanceTo(mc.player!!.position()) > maxDist) continue
				renderPet(ctx, level, pet.model, pet.follower, partial, palette(pet.body, pet.accent, opacityFor(pet.info.species)), false)
			}
		}
	}

	fun renderPet(
		ctx: LevelRenderContext, level: ClientLevel, model: PetModel, follower: PetFollower,
		partial: Float, colors: Map<ColorRole, Int>, sleeping: Boolean, react: PetReactions.Pose? = null,
	) {
		val time = follower.age + partial
		val air = follower.renderAirborne(partial)
		// A reaction wakes a sleeping pet.
		model.beginFrame(time, follower.renderYaw(partial))
		val asleep = sleeping && react == null
		when (model.species) {
			PetSpecies.DRAGON -> animate(model, time, follower.speed, air, asleep, react)
			PetSpecies.RYU -> { model.reset(); animateRyu(model, time, follower.speed, asleep, react) }
			PetSpecies.AXOLOTL -> { model.reset(); animateAxolotl(model, time, follower.speed, asleep, react) }
			PetSpecies.GHOST -> { model.reset(); animateGhost(model, time, follower.speed, asleep, react) }
			PetSpecies.PENGUIN -> { model.reset(); animatePenguin(model, time, follower.speed, asleep, react) }
		}

		val pos = follower.renderPos(partial)
		val cam = ctx.levelState().cameraRenderState.pos
		val bob = sin(time * 0.25) * 0.05 * air + (react?.lift ?: 0.0) + model.hover
		val light = lightAt(level, BlockPos.containing(pos.x, pos.y + 0.5, pos.z))

		val pose = ctx.poseStack()
		pose.pushPose()
		pose.translate(pos.x - cam.x, pos.y - cam.y + bob, pos.z - cam.z)
		pose.mulPose(Axis.YP.rotationDegrees(-follower.renderYaw(partial) - (react?.spin ?: 0f)))
		if (model.roll != 0f) pose.mulPose(Axis.ZP.rotation(model.roll))
		pose.scale(model.scale, model.scale, model.scale)

		val collector = ctx.submitNodeCollector()
		for (part in model.parts) {
			val modelPart = model.part(part.name) ?: continue
			pose.pushPose()
			// Ride on the parent chain's transform (e.g. claws on a leg, spade on the tail tip).
			var parent: ModelPart = model.root
			for (name in part.follow) {
				parent = parent.getChild(name)
				parent.translateAndRotate(pose)
			}
			collector.submitModelPart(
				modelPart, pose, renderType, light, OverlayTexture.NO_OVERLAY, null,
				colors.getValue(part.role), null,
			)
			pose.popPose()
		}
		pose.popPose()
	}

	/**
	 * Poses the dragon. Walking: diagonal leg gait, tail swaying, wings folded on the flanks.
	 * Flying ([air] = 1): legs tucked, wings spread and beating. Sleeping: head down, eyes shut, slow breath.
	 */
	private fun animate(model: PetModel, time: Float, speed: Double, air: Float, sleeping: Boolean, react: PetReactions.Pose?) {
		model.reset()
		val walk = ((speed * 7).toFloat().coerceIn(0f, 1f)) * (1f - air)
		val breath = sin(time * 0.08f)

		// Legs: diagonal pairs move together; tucked back while flying.
		val swing = sin(time * 0.55f) * 0.6f * walk
		val tuck = 0.9f * air
		model.part("legFL")?.xRot = swing - tuck * 0.6f
		model.part("legBR")?.xRot = swing + tuck
		model.part("legFR")?.xRot = -swing - tuck * 0.6f
		model.part("legBL")?.xRot = -swing + tuck

		// Neck and head: a little nod while walking, looking around while idle.
		val look = sin(time * 0.03f) * 0.35f * (1f - walk)
		val nod = sin(time * 0.55f * 2) * 0.05f * walk
		model.part("neck")?.let { it.xRot += breath * 0.02f + air * 0.25f }
		for (name in listOf("head", "headJaw", "headEyes", "headHorns")) {
			val p = model.part(name) ?: continue
			p.xRot = -nod * 1.5f - air * 0.2f
			p.yRot = look
			// Follow the neck end, which moves forward and down when the neck tilts in flight.
			p.y -= air * 0.8f
			p.z += air * 0.6f
		}
		// Jaw opens a little now and then (panting/breathing).
		val open = ((sin(time * 0.05f) - 0.7f) * 0.6f).coerceAtLeast(0f)
		model.part("headJaw")?.xRot = (model.part("headJaw")?.xRot ?: 0f) + open

		// Tail: lazy side-to-side sway, stretched out behind while flying.
		val sway = sin(time * 0.12f) * (0.25f + 0.15f * walk)
		model.part("tail")?.let { t ->
			t.yRot = sway
			t.xRot += air * 0.25f
			val t2 = t.getChild("tail2")
			t2.yRot = sin(time * 0.12f - 0.8f) * 0.3f
			t2.xRot -= air * 0.1f
			val t3 = t2.getChild("tail3")
			t3.yRot = sin(time * 0.12f - 1.6f) * 0.35f
		}

		// Wings, folded like a bat's on the ground: the arm runs back along the top of the flank and the
		// membrane hangs folded beneath it. In the air they open and beat.
		val beat = sin(time * 0.45f) * 0.75f
		for ((suffix, sign) in listOf("L" to 1f, "R" to -1f)) {
			for (name in listOf("wing$suffix", "wingBone$suffix")) {
				model.part(name)?.let {
					it.x = sign * (2.3f + 0.35f * (1f - air))
					it.xRot = Mth.lerp(air, -1.4f + breath * 0.03f, 0f)
					it.yRot = sign * Mth.lerp(air, 1.45f, 0.1f)
					it.zRot = sign * Mth.lerp(air, 0.15f, beat)
					it.zScale = Mth.lerp(air, 0.7f, 1f)
				}
			}
		}

		if (react != null) {
			// Happy: wings open and flutter fast, head up, mouth open, tail wagging quickly.
			val w = react.wings
			val flutter = sin(time * 1.3f) * 0.9f
			for ((suffix, sign) in listOf("L" to 1f, "R" to -1f)) {
				for (name in listOf("wing$suffix", "wingBone$suffix")) {
					model.part(name)?.let {
						it.x = Mth.lerp(w, it.x, sign * 2.3f)
						it.xRot = Mth.lerp(w, it.xRot, 0f)
						it.yRot = Mth.lerp(w, it.yRot, sign * 0.1f)
						it.zRot = Mth.lerp(w, it.zRot, sign * flutter)
						it.zScale = Mth.lerp(w, it.zScale, 1f)
					}
				}
			}
			for (name in listOf("head", "headJaw", "headEyes", "headHorns")) model.part(name)?.let { it.xRot -= react.headUp }
			model.part("headJaw")?.let { it.xRot += react.jaw }
			model.part("tail")?.let { it.yRot += sin(time * 1.1f) * 0.5f * react.tailWag }
		}

		if (sleeping) {
			model.part("neck")?.xRot = 1.25f
			for (name in listOf("head", "headJaw", "headEyes", "headHorns")) {
				model.part(name)?.let { it.y = 10.3f; it.z = 8.2f; it.xRot = 0.5f; it.yRot = 0f }
			}
			model.part("headEyes")?.visible = false
		} else {
			model.part("headEyes")?.visible = true
		}
	}

	private object Mth {
		fun lerp(t: Float, a: Float, b: Float) = a + (b - a) * t
	}

	/** Tint per color role from a body and accent color. */
	fun palette(body: Int, accent: Int, opacity: Double): Map<ColorRole, Int> {
		val alpha = (opacity * 255).toInt().coerceIn(0, 255)
		fun a(c: Int) = ColorUtil.withAlpha(c, alpha)
		return mapOf(
			ColorRole.BODY to a(body),
			ColorRole.BODY_SHADE to a(ColorUtil.shade(body, 0.72f)),
			ColorRole.ACCENT to a(accent),
			ColorRole.SHELL to a(0xFFEDE3CC.toInt()),
			ColorRole.EYE to a(0xFF15121A.toInt()),
		)
	}

	/** Packs block and sky light the same way vanilla light coords do. */
	private fun lightAt(level: ClientLevel, pos: BlockPos): Int {
		// The brighter of this block and the one above: a pet whose feet dip into a slab or the ground must not turn black.
		val up = pos.above()
		val block = maxOf(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.BLOCK, up))
		val sky = maxOf(level.getBrightness(LightLayer.SKY, pos), level.getBrightness(LightLayer.SKY, up))
		return (block shl 4) or (sky shl 20)
	}
}
