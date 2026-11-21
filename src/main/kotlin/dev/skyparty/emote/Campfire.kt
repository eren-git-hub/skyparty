package dev.skyparty.emote

import com.google.gson.JsonObject
import com.mojang.math.Axis
import dev.skyparty.Skyparty
import dev.skyparty.net.Net
import dev.skyparty.util.Chat
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
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * A little campfire you put down next to you (emote wheel or /sp emote campfire), purely visual: logs, embers,
 * flickering flames and smoke, seen by everyone with Skyparty in your lobby. Grab a beach chair and sit
 * around it. It goes away when you toggle it again, walk far away or change lobby.
 */
object Campfire {
	private const val MAX_DISTANCE = 32.0

	/** Your campfire (null = none). */
	var own: Vec3? = null
		private set
	private val remote = HashMap<UUID, Vec3>()

	/** Tests only: extra campfires. */
	val testFires = mutableListOf<Vec3>()

	fun all(): List<Vec3> = listOfNotNull(own) + remote.values + testFires

	fun toggle() {
		if (own != null) { own = null; send(); return }
		val p = Minecraft.getInstance().player ?: return
		if (!p.onGround()) { Chat.info("§7Stand on the ground to put down a campfire."); return }
		val yaw = Math.toRadians(p.yRot.toDouble())
		own = Vec3(p.x - sin(yaw) * 1.8, p.y, p.z + cos(yaw) * 1.8)
		send()
	}

	fun tick(mc: Minecraft) {
		val p = mc.player ?: return
		own?.let { if (it.distanceToSqr(p.position()) > MAX_DISTANCE * MAX_DISTANCE) { own = null; send() } }
		val level = mc.level ?: return
		val r = level.random
		for (f in all()) {
			if (f.distanceToSqr(p.position()) > 40.0 * 40.0) continue
			if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SMALL_FLAME, f.x + (r.nextDouble() - 0.5) * 0.3, f.y + 0.25, f.z + (r.nextDouble() - 0.5) * 0.3, 0.0, 0.02, 0.0)
			if (r.nextInt(6) == 0) level.addParticle(ParticleTypes.SMOKE, f.x + (r.nextDouble() - 0.5) * 0.2, f.y + 0.7, f.z + (r.nextDouble() - 0.5) * 0.2, 0.0, 0.04, 0.0)
			if (level.gameTime % 40 == 0L) level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, f.x, f.y + 0.6, f.z, 0.0, 0.05, 0.0)
		}
	}

	// ------------------------------------------------------------------ network

	private fun send() {
		val room = dev.skyparty.pet.RemotePets.lobbyRoom ?: return
		room.send("camp", JsonObject().apply { own?.let { addProperty("x", it.x); addProperty("y", it.y); addProperty("z", it.z) } })
	}

	fun onMembersChanged(members: Set<UUID>) {
		remote.keys.retainAll(members)
		if (own != null) send()
	}

	fun onMessage(msg: Net.Message) {
		val p = msg.payload
		val x = p["x"]?.asDouble
		if (x == null) remote.remove(msg.from)
		else remote[msg.from] = Vec3(x, p["y"]?.asDouble ?: return, p["z"]?.asDouble ?: return)
	}

	fun onLobbyChanged() {
		remote.clear()
		// A new server: your campfire stayed behind in the old one.
		own = null
	}

	// ------------------------------------------------------------------ drawing

	private val TEXTURE = Identifier.fromNamespaceAndPath(Skyparty.MOD_ID, "textures/entity/pet_white.png")
	private val renderType by lazy { RenderTypes.entityCutout(TEXTURE) }

	private class Parts(val logs: ModelPart, val bark: ModelPart, val stones: ModelPart, val embers: ModelPart, val flames: List<ModelPart>)

	private val parts: Parts by lazy {
		val mesh = MeshDefinition()
		val root = mesh.root
		// Two logs below, two crossing on top (like a log cabin), cut ends lighter.
		root.addOrReplaceChild("bark", CubeListBuilder.create()
			.addBox(-6f, 0f, -4f, 12f, 2.4f, 2.4f).addBox(-6f, 0f, 1.6f, 12f, 2.4f, 2.4f)
			.addBox(-4f, 2.2f, -6f, 2.4f, 2.4f, 12f).addBox(1.6f, 2.2f, -6f, 2.4f, 2.4f, 12f),
			PartPose.ZERO)
		root.addOrReplaceChild("logs", CubeListBuilder.create()
			.addBox(-6.05f, 0.3f, -3.7f, 0.1f, 1.8f, 1.8f).addBox(5.95f, 0.3f, -3.7f, 0.1f, 1.8f, 1.8f)
			.addBox(-6.05f, 0.3f, 1.9f, 0.1f, 1.8f, 1.8f).addBox(5.95f, 0.3f, 1.9f, 0.1f, 1.8f, 1.8f),
			PartPose.ZERO)
		val stones = CubeListBuilder.create()
		for (i in 0 until 10) {
			val a = i / 10.0 * Math.PI * 2
			stones.addBox((cos(a) * 8.5).toFloat() - 1.2f, 0f, (sin(a) * 8.5).toFloat() - 1.2f, 2.4f, 1.4f + (i % 3) * 0.3f, 2.4f)
		}
		root.addOrReplaceChild("stones", stones, PartPose.ZERO)
		root.addOrReplaceChild("embers", CubeListBuilder.create().addBox(-3f, 0.1f, -3f, 6f, 1.2f, 6f), PartPose.ZERO)
		val flames = (0 until 4).map { i ->
			val w = if (i == 0) 3.2f else 2f
			root.addOrReplaceChild("flame$i", CubeListBuilder.create().addBox(-w / 2, 0f, -w / 2, w, 1f, w), PartPose.ZERO)
		}
		val baked = LayerDefinition.create(mesh, 16, 16).bakeRoot()
		Parts(baked.getChild("logs"), baked.getChild("bark"), baked.getChild("stones"), baked.getChild("embers"),
			flames.indices.map { baked.getChild("flame$it") })
	}

	private val FLAME_COLORS = intArrayOf(0xFFFFB02E.toInt(), 0xFFFF7A1A.toInt(), 0xFFFFD84A.toInt(), 0xFFFF5A12.toInt())
	private val FLAME_POS = listOf(0f to 0f, 1.4f to 0.8f, -1.3f to 0.6f, 0.2f to -1.4f)

	fun render(ctx: LevelRenderContext) {
		val fires = all()
		if (fires.isEmpty()) return
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val cam = ctx.levelState().cameraRenderState.pos
		val time = level.gameTime + mc.deltaTracker.getGameTimeDeltaPartialTick(true)
		val c = ctx.submitNodeCollector()
		for (f in fires) {
			if (f.distanceToSqr(cam) > 64.0 * 64.0) continue
			val bp = BlockPos.containing(f.x, f.y + 0.3, f.z)
			val light = (maxOf(level.getBrightness(LightLayer.BLOCK, bp), 12) shl 4) or (level.getBrightness(LightLayer.SKY, bp) shl 20)
			val pose = ctx.poseStack()
			pose.pushPose()
			pose.translate(f.x - cam.x, f.y - cam.y, f.z - cam.z)
			pose.mulPose(Axis.YP.rotationDegrees(25f))
			pose.scale(1.3f, 1.3f, 1.3f)
			c.submitModelPart(parts.stones, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, 0xFF7E7E86.toInt(), null)
			c.submitModelPart(parts.bark, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, 0xFF5A3D24.toInt(), null)
			c.submitModelPart(parts.logs, pose, renderType, light, OverlayTexture.NO_OVERLAY, null, 0xFFB98A55.toInt(), null)
			val glow = 0xF000F0
			val pulse = (0.75f + sin(time * 0.3f) * 0.25f)
			c.submitModelPart(parts.embers, pose, renderType, glow, OverlayTexture.NO_OVERLAY, null, colorScale(0xFFFF5A1F.toInt(), pulse), null)
			// Flames: boxes that grow and shrink at their own pace.
			for ((i, part) in parts.flames.withIndex()) {
				val (fx, fz) = FLAME_POS[i]
				val h = (if (i == 0) 9f else 5.5f) * (0.7f + 0.3f * sin(time * (0.45f + i * 0.13f) + i * 1.7f))
				pose.pushPose()
				pose.translate(fx / 16.0, 1.0 / 16, fz / 16.0)
				pose.mulPose(Axis.YP.rotationDegrees(time * (3f + i) % 360f))
				pose.scale(1f, h, 1f)
				c.submitModelPart(part, pose, renderType, glow, OverlayTexture.NO_OVERLAY, null, FLAME_COLORS[i], null)
				pose.popPose()
			}
			pose.popPose()
		}
	}

	private fun colorScale(argb: Int, f: Float): Int {
		val r = ((argb shr 16 and 0xFF) * f).toInt().coerceIn(0, 255)
		val g = ((argb shr 8 and 0xFF) * f).toInt().coerceIn(0, 255)
		val b = ((argb and 0xFF) * f).toInt().coerceIn(0, 255)
		return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
	}
}
