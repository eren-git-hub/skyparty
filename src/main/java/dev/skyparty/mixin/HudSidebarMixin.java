package dev.skyparty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skyparty.pet.scoreboard.SidebarBounds;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Records where vanilla draws the sidebar backgrounds, so the scoreboard pet knows
 * the exact box regardless of how wide Hypixel's lines are.
 */
@Mixin(Hud.class)
public abstract class HudSidebarMixin {
	@WrapOperation(
		method = "displayScoreboardSidebar",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V")
	)
	private void skyparty$recordSidebarBox(
		GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color, Operation<Void> original
	) {
		SidebarBounds.INSTANCE.record(x1, y1, x2, y2);
		original.call(graphics, x1, y1, x2, y2, color);
	}
}
