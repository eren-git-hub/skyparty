package dev.skyparty.pet

import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Movement state of one pet following one owner: it hovers beside and slightly behind the owner,
 * eases toward that spot and faces its movement direction (or the owner's when idle).
 * Used for your own pet ([PetController]) and for every remote pet ([RemotePets]).
 */
class PetFollower {
	companion object {
		/** Offset in blocks relative to the owner's facing: +x = left, +z = forward. */
		private const val SIDE = 0.9
		private const val BACK = -0.7
		/** Height above the owner's feet while flying (walking pets stay on the ground). */
		private const val FLY_HEIGHT = 0.9
		private const val TELEPORT_DISTANCE = 12.0
	}

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

	/** 0 = walking on the ground, 1 = flying (owner in the air); eased so take-off and landing are smooth. */
	var airborne = 0f
		private set
	var prevAirborne = 0f
		private set

	private var airTicks = 0
	private var initialized = false

	fun reset() {
		initialized = false
	}

	fun tick(owner: Entity) {
		age++
		prevAirborne = airborne
		airTicks = if (!owner.onGround() && !owner.isInWater && !owner.isPassenger) airTicks + 1 else 0
		// A normal jump lasts ~12 ticks; only longer air time (flying, falling far) makes the pet fly.
		val inAir = airTicks > 14
		airborne = (airborne + if (inAir) 0.08f else -0.06f).coerceIn(0f, 1f)

		val rad = Math.toRadians(owner.yRot.toDouble())
		// Forward vector in MC: (-sin(yaw), cos(yaw)); left vector: (cos(yaw), sin(yaw)).
		val fx = -sin(rad)
		val fz = cos(rad)
		val lx = cos(rad)
		val lz = sin(rad)
		val target = Vec3(
			owner.x + lx * SIDE + fx * BACK,
			owner.y + FLY_HEIGHT * airborne,
			owner.z + lz * SIDE + fz * BACK,
		)

		if (!initialized || pos.distanceTo(target) > TELEPORT_DISTANCE) {
			pos = target
			prevPos = target
			yaw = owner.yRot
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
			owner.yRot
		}
		yaw += Mth.wrapDegrees(desiredYaw - yaw) * 0.25f
	}

	fun renderPos(partial: Float): Vec3 = Vec3(
		Mth.lerp(partial.toDouble(), prevPos.x, pos.x),
		Mth.lerp(partial.toDouble(), prevPos.y, pos.y),
		Mth.lerp(partial.toDouble(), prevPos.z, pos.z),
	)

	fun renderAirborne(partial: Float): Float = Mth.lerp(partial, prevAirborne, airborne)

	fun renderYaw(partial: Float): Float = prevYaw + Mth.wrapDegrees(yaw - prevYaw) * partial
}
