package dev.skyparty.pet

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Moves the local player's pet: it hovers beside and slightly behind the player,
 * eases toward that spot and faces its movement direction (or the player's when idle).
 */
object PetController {
	/** Offset in blocks relative to the player's facing: +x = left, +z = forward. */
	private const val SIDE = 0.9
	private const val BACK = -0.7
	private const val HOVER = 0.45
	private const val TELEPORT_DISTANCE = 12.0

	var pos: Vec3 = Vec3.ZERO
		private set
	var prevPos: Vec3 = Vec3.ZERO
		private set
	var yaw = 0f
		private set
	var prevYaw = 0f
		private set

	/** Horizontal speed in blocks/tick, used for leg animation. */
	var speed = 0.0
		private set

	/** Ticks since the pet started existing; drives idle animations. */
	var age = 0
		private set

	private var initialized = false

	fun reset() {
		initialized = false
	}

	fun tick(mc: Minecraft) {
		val player = mc.player ?: run { initialized = false; return }
		age++

		val rad = Math.toRadians(player.yRot.toDouble())
		// Player forward vector in MC: (-sin(yaw), cos(yaw)); left vector: (cos(yaw), sin(yaw)).
		val fx = -sin(rad)
		val fz = cos(rad)
		val lx = cos(rad)
		val lz = sin(rad)
		val target = Vec3(
			player.x + lx * SIDE + fx * BACK,
			player.y + HOVER,
			player.z + lz * SIDE + fz * BACK,
		)

		if (!initialized || pos.distanceTo(target) > TELEPORT_DISTANCE) {
			pos = target
			prevPos = target
			yaw = player.yRot
			prevYaw = yaw
			initialized = true
			return
		}

		prevPos = pos
		prevYaw = yaw
		pos = pos.add(target.subtract(pos).scale(0.18))

		val dx = pos.x - prevPos.x
		val dz = pos.z - prevPos.z
		speed = sqrt(dx * dx + dz * dz)
		val desiredYaw = if (speed > 0.03) {
			(Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
		} else {
			player.yRot
		}
		yaw += Mth.wrapDegrees(desiredYaw - yaw) * 0.25f
	}

	fun renderPos(partial: Float): Vec3 = Vec3(
		Mth.lerp(partial.toDouble(), prevPos.x, pos.x),
		Mth.lerp(partial.toDouble(), prevPos.y, pos.y),
		Mth.lerp(partial.toDouble(), prevPos.z, pos.z),
	)

	fun renderYaw(partial: Float): Float = prevYaw + Mth.wrapDegrees(yaw - prevYaw) * partial
}
