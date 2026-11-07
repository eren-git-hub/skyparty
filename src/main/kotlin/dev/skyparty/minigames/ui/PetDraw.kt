package dev.skyparty.minigames.ui

import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.pet.scoreboard.Sprite
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Draws [Sprite] pixel art in any colors (the scoreboard pet's own drawer only knows your colors). */
object PetDraw {
	class Colors(val body: Int, val accent: Int)

	/** Your colors from the Custom Pets module. */
	fun own() = Colors(
		CustomPets.bodyColor.value or 0xFF000000.toInt(),
		CustomPets.accentColor.value or 0xFF000000.toInt(),
	)

	/** Stable colors for another player, derived from a hash of their name. */
	fun forName(name: String): Colors {
		val h = (name.lowercase().hashCode() and 0x7FFFFFFF) % 360 / 360f
		return Colors(ColorUtil.hsbToArgb(h, 0.55f, 0.95f), ColorUtil.hsbToArgb(h + 0.45f, 0.75f, 1f))
	}

	fun draw(g: GuiGraphicsExtractor, sprite: Sprite, x: Float, y: Float, scale: Float, facingRight: Boolean, colors: Colors) {
		val palette = mapOf(
			'K' to 0xFF1A1A22.toInt(),
			'B' to colors.body,
			'S' to ColorUtil.shade(colors.body, 0.78f),
			'A' to colors.accent,
			'W' to 0xFFF3E9D2.toInt(),
			'E' to 0xFF1A1A22.toInt(),
		)
		val pose = g.pose()
		pose.pushMatrix()
		pose.translate(x, y)
		pose.scale(scale, scale)
		for ((row, line) in sprite.rows.withIndex()) {
			for ((col, ch) in line.withIndex()) {
				val color = palette[ch] ?: continue
				val px = if (facingRight) col else sprite.width - 1 - col
				g.fill(px, row, px + 1, row + 1, color)
			}
		}
		pose.popMatrix()
	}
}
