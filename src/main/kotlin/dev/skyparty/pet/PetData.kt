package dev.skyparty.pet

import net.minecraft.client.Minecraft

/** Shared pet state: whether the player is AFK (the pets fall asleep, Wrapped stops counting). */
object PetData {
	/** No movement or camera rotation for this long counts as AFK. */
	private const val AFK_AFTER_MS = 120_000L

	var isAfk = false
		private set

	private var lastActiveAt = System.currentTimeMillis()
	private var lastPos = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)

	fun tick(mc: Minecraft) {
		val now = System.currentTimeMillis()
		val player = mc.player ?: return
		val current = doubleArrayOf(player.x, player.y, player.z, player.yRot.toDouble(), player.xRot.toDouble())
		if (!current.contentEquals(lastPos)) {
			lastActiveAt = now
			lastPos = current
		}
		isAfk = now - lastActiveAt > AFK_AFTER_MS
	}

	fun formatDuration(ms: Long): String {
		val totalMinutes = ms / 60_000
		val h = totalMinutes / 60
		val m = totalMinutes % 60
		return if (h > 0) "${h}h ${m}min" else "${m}min"
	}
}
