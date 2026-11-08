package dev.skyparty.ui.screens

import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.PetDraw
import dev.skyparty.module.Category
import dev.skyparty.module.ModuleManager
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.pet.PetData
import dev.skyparty.pet.scoreboard.PetSprites
import dev.skyparty.pet.scoreboard.ScoreboardPetHud
import dev.skyparty.ui.Theme
import net.minecraft.client.gui.GuiGraphicsExtractor

/** ❀ Pets: your pet and how to play with it on the left, the pet settings on the right. */
class PetsScreen : ModuleSettingsScreen("Pets") {
	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = header(g, "Pets", "your companion")
		val leftW = minOf(170, width / 3)
		val x = 10
		val y = top + 8
		val h = height - y - 8
		panel(g, x, y, leftW, h)

		val name = CustomPets.petName.value.ifBlank { "Your pet" }
		centered(g, "§l$name", x + leftW / 2, y + 8, Theme.TEXT)
		centered(g, "§7${if (PetData.isAfk) "Sleeping (you are AFK)" else "Awake"}", x + leftW / 2, y + 20, Theme.TEXT)

		// Animated preview
		val sprites = PetSprites.of(dev.skyparty.pet.PetSpecies.own)
		val frames = sprites.walk
		val frame = if (PetData.isAfk) sprites.sleep else frames[((System.currentTimeMillis() / 300) % frames.size).toInt()]
		val scale = 5f
		PetDraw.draw(g, frame, x + leftW / 2f - frame.width * scale / 2, y + 40f, scale, true, PetDraw.own())

		var ly = y + 100
		val feedKey = dev.skyparty.Skyparty.feedPetKey.translatedKeyMessage.string
		for (line in listOf(
			"§7Press §f$feedKey §7to feed your scoreboard pet. §8(Change the key in Controls.)",
			"§7Snacks eaten: §f${ScoreboardPetHud.snacks}",
			"",
			"§7With chat open you can drag it, right-click to pet it or click the board to throw a ball.",
			"",
			"§7Other players with Skyparty see your 3D pet in the world.",
		)) {
			if (line.isEmpty()) { ly += 5; continue }
			for (part in font.split(net.minecraft.network.chat.Component.literal(line), leftW - 14)) {
				if (ly + 9 > y + h - 4) break
				g.text(font, part, x + 8, ly, Theme.TEXT, false)
				ly += 10
			}
		}

		val modules = ModuleManager.modules.filter { it.category == Category.PETS }
		val rx = x + leftW + 10
		drawModules(g, modules, rx, y, width - rx - 12, h, mouseX, mouseY)
	}
}
