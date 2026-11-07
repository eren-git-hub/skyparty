package dev.skyparty.ui.screens

import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.PetDraw
import dev.skyparty.module.Category
import dev.skyparty.module.ModuleManager
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.pet.PetData
import dev.skyparty.pet.PetStage
import dev.skyparty.pet.scoreboard.PetSprites
import dev.skyparty.ui.Theme
import dev.skyparty.util.Location
import net.minecraft.client.gui.GuiGraphicsExtractor

/** ❀ Pets: your pet's stage and growth on the left, the pet settings on the right. */
class PetsScreen : ModuleSettingsScreen("Pets") {
	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = header(g, "Pets", "your pet grows while you play")
		val leftW = minOf(170, width / 3)
		val x = 10
		val y = top + 8
		val h = height - y - 8
		panel(g, x, y, leftW, h)

		val stage = PetData.stage
		val name = CustomPets.petName.value.ifBlank { "Your pet" }
		centered(g, "§l$name", x + leftW / 2, y + 8, Theme.TEXT)
		centered(g, "§e${stage.displayName}", x + leftW / 2, y + 20, Theme.TEXT)

		// Animated preview of the current stage
		val frames = PetSprites.sprites.getValue(stage).walk
		val frame = frames[((System.currentTimeMillis() / 300) % frames.size).toInt()]
		val scale = 5f
		val bob = if (stage == PetStage.EGG) (kotlin.math.sin(Theme.time * 3) * 2).toFloat() else 0f
		PetDraw.draw(g, frame, x + leftW / 2f - 8 * scale / 2, y + 40 + bob, scale, true, PetDraw.own())

		var ly = y + 100
		g.text(font, "§7Playtime: §f${PetData.formatDuration(PetData.playtimeMs)}", x + 8, ly, Theme.TEXT, false)
		ly += 12
		val next = stage.next
		if (next != null) {
			val pct = PetStage.progress(PetData.playtimeMs)
			g.text(font, "§7Next: §f${next.displayName} §7in §f${PetData.formatDuration(next.millis - PetData.playtimeMs)}", x + 8, ly, Theme.TEXT, false)
			ly += 12
			val bw = leftW - 16
			g.fill(x + 8, ly, x + 8 + bw, ly + 5, 0xFF2A1A44.toInt())
			g.fill(x + 8, ly, x + 8 + (bw * pct).toInt(), ly + 5, Theme.rainbow(0f, 0.5f))
			ly += 10
		} else {
			g.text(font, "§6Final stage reached!", x + 8, ly, Theme.TEXT, false)
			ly += 12
		}
		val counting = when {
			PetData.isAfk -> "§cpaused (AFK)"
			Location.inSkyBlockOrDev -> "§agrowing"
			else -> "§cpaused (not in SkyBlock)"
		}
		g.text(font, "§7Status: $counting", x + 8, ly, Theme.TEXT, false)
		ly += 16
		for (s in PetStage.entries) {
			val reached = PetData.playtimeMs >= s.millis
			g.text(font, (if (reached) "§a✔ " else "§8○ ") + "§f${s.displayName} §8${s.hours.toInt()}h", x + 8, ly, Theme.TEXT, false)
			ly += 11
		}

		val modules = ModuleManager.modules.filter { it.category == Category.PETS }
		val rx = x + leftW + 10
		drawModules(g, modules, rx, y, width - rx - 12, h, mouseX, mouseY)
	}
}
