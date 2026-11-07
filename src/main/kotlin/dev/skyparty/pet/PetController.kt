package dev.skyparty.pet

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

/** Moves the local player's pet; the movement itself lives in [PetFollower] (shared with remote pets). */
object PetController {
	val follower = PetFollower()

	val pos: Vec3 get() = follower.pos
	val prevPos: Vec3 get() = follower.prevPos
	val yaw get() = follower.yaw
	val prevYaw get() = follower.prevYaw

	/** Horizontal speed in blocks/tick, used for leg animation. */
	val speed get() = follower.speed

	/** Ticks since the pet started existing; drives idle animations. */
	val age get() = follower.age
	val airborne get() = follower.airborne

	fun reset() = follower.reset()

	/** Tests only: stop moving so the pet can be photographed up close. */
	var frozenForTest = false

	fun tick(mc: Minecraft) {
		if (frozenForTest) return
		val player = mc.player ?: run { follower.reset(); return }
		follower.tick(player)
	}

	fun renderPos(partial: Float): Vec3 = follower.renderPos(partial)

	fun renderYaw(partial: Float): Float = follower.renderYaw(partial)
}
