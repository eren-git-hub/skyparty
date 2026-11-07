package dev.skyparty.pet

import dev.skyparty.module.impl.pets.CustomPets
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Your 3D pet reacts to good news: a rare drop makes it hop and flap (HAPPY), a very rare drop or a skill
 * level-up makes it leap, spin and burst into sparkles (CELEBRATE). Client-side only, purely visual.
 */
object PetReactions {
	enum class Kind(val ticks: Int) { HAPPY(30), CELEBRATE(46) }

	class Pose(val lift: Double, val spin: Float, val wings: Float, val jaw: Float, val tailWag: Float, val headUp: Float)

	private var kind: Kind? = null
	private var start = 0

	fun trigger(k: Kind) {
		// A small reaction never cuts a big one short.
		if (kind == Kind.CELEBRATE && k == Kind.HAPPY && progress(PetController.age.toFloat()) != null) return
		kind = k
		start = PetController.age
	}

	/** Called for a rare drop; [rank] from StatsPatterns.RARITY_RANK (3+ = very rare or better). */
	fun onDrop(rank: Int) = trigger(if (rank >= 3) Kind.CELEBRATE else Kind.HAPPY)

	fun onLevelUp() = trigger(Kind.CELEBRATE)

	private fun progress(time: Float): Float? {
		val k = kind ?: return null
		val t = (time - start) / k.ticks
		return if (t in 0f..1f) t else null
	}

	/** The extra pose for the current reaction at [time] (pet age + partial tick), or null when idle. */
	fun pose(time: Float): Pose? {
		val t = progress(time) ?: return null
		val ease = sin(PI * t).toFloat() // 0 → 1 → 0, so the reaction blends in and out
		return when (kind!!) {
			// Two quick hops with fluttering wings and a happy open mouth.
			Kind.HAPPY -> Pose(
				lift = abs(sin(t * 2 * PI * 2)) * 0.35,
				spin = 0f, wings = ease, jaw = 0.35f * ease, tailWag = ease, headUp = 0.3f * ease,
			)
			// One big leap with a full turn in the air, wings spread wide.
			Kind.CELEBRATE -> Pose(
				lift = sin(PI * t) * 0.9,
				spin = smooth(((t - 0.15f) / 0.6f).coerceIn(0f, 1f)) * 360f,
				wings = ease, jaw = 0.45f * ease, tailWag = ease, headUp = 0.5f * ease,
			)
		}
	}

	private fun smooth(x: Float) = x * x * (3 - 2 * x)

	/** Sparkles around the pet while a reaction runs. */
	fun tick(mc: Minecraft) {
		if (!CustomPets.enabled) return
		val k = kind ?: return
		val age = PetController.age - start
		if (age > k.ticks) { kind = null; return }
		val level = mc.level ?: return
		val p = PetController.pos
		val y = p.y + 0.5 + (pose(PetController.age.toFloat())?.lift ?: 0.0)
		when (k) {
			Kind.HAPPY -> if (age == 2 || age == 14) {
				level.addParticle(ParticleTypes.HEART, p.x, y + 1.1, p.z, 0.0, 0.05, 0.0)
			}
			Kind.CELEBRATE -> {
				if (age % 3 == 0 && age < 36) {
					val a = age * 0.7
					level.addParticle(ParticleTypes.FIREWORK, p.x + cos(a) * 0.6, y, p.z + sin(a) * 0.6, 0.0, 0.06, 0.0)
				}
				if (age == 20) repeat(14) { i ->
					val a = i / 14.0 * 2 * PI
					level.addParticle(ParticleTypes.FIREWORK, p.x, y + 0.3, p.z, cos(a) * 0.12, 0.08, sin(a) * 0.12)
				}
				if (age == 22) level.addParticle(ParticleTypes.HEART, p.x, y + 1.2, p.z, 0.0, 0.05, 0.0)
			}
		}
	}
}
