package dev.skyparty.minigames

import dev.skyparty.module.impl.party.Minigames
import dev.skyparty.util.Chat
import dev.skyparty.util.Location
import net.minecraft.client.Minecraft

/**
 * "Pause in Combat": no invite popups and no game starts while in a dungeon boss fight
 * or shortly after taking damage.
 */
object CombatGuard {
	private const val DAMAGE_COOLDOWN_MS = 10_000L

	private var lastDamage = 0L
	private var bossFight = false
	private var lastHealth = -1f

	val inCombat: Boolean
		get() = bossFight || System.currentTimeMillis() - lastDamage < DAMAGE_COOLDOWN_MS

	/** True when the setting is on and the player is fighting. */
	val blocked: Boolean get() = Minigames.pauseInCombat.value && inCombat

	fun tick(mc: Minecraft) {
		val player = mc.player ?: run {
			bossFight = false
			lastHealth = -1f
			return
		}
		if (player.hurtTime > 0 || (lastHealth >= 0f && player.health < lastHealth - 0.01f)) {
			lastDamage = System.currentTimeMillis()
		}
		lastHealth = player.health
		// Leaving the dungeon ends the boss fight; checked once a second (sidebar parsing isn't free).
		if (bossFight && player.tickCount % 20 == 0 && Location.dungeonFloor == null) bossFight = false
	}

	/** Hypixel boss dialogue starts with "[BOSS] Name:"; inside a dungeon that means the boss room. */
	fun onChat(text: String) {
		val line = Chat.strip(text)
		// The Watcher talks during the clear phase, not the boss fight.
		if (line.startsWith("[BOSS]") && !line.startsWith("[BOSS] The Watcher") && Location.dungeonFloor != null) {
			bossFight = true
		}
	}
}
