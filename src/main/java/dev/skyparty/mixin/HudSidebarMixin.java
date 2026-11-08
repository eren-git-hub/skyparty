package dev.skyparty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skyparty.pet.scoreboard.SidebarBounds;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.scores.Objective;

/**
 * Records where vanilla draws the sidebar backgrounds, so the scoreboard pet knows
 * the exact box regardless of how wide Hypixel's lines are. The corners go through the current pose, so a mod
 * that scales or moves the scoreboard (pushing a transform around it) still gives the box where it really is on screen.
 */
@Mixin(Hud.class)
public abstract class HudSidebarMixin {
	/** Tests only: scale the sidebar around its right edge like a "scoreboard scale" mod would. */
	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"))
	private void skyparty$testScaleStart(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
		float s = SidebarBounds.INSTANCE.getTestScale();
		if (s == 1f) return;
		graphics.pose().pushMatrix();
		graphics.pose().translate(graphics.guiWidth(), graphics.guiHeight() / 2f - 40f);
		graphics.pose().scale(s, s);
		graphics.pose().translate(-graphics.guiWidth(), -graphics.guiHeight() / 2f);
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void skyparty$testScaleEnd(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
		if (SidebarBounds.INSTANCE.getTestScale() != 1f) graphics.pose().popMatrix();
	}

	@WrapOperation(
		method = "displayScoreboardSidebar",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V")
	)
	private void skyparty$recordSidebarBox(
		GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color, Operation<Void> original
	) {
		Vector2f a = graphics.pose().transformPosition(x1, y1, new Vector2f());
		Vector2f b = graphics.pose().transformPosition(x2, y2, new Vector2f());
		SidebarBounds.INSTANCE.record(Math.round(a.x), Math.round(a.y), Math.round(b.x), Math.round(b.y));
		original.call(graphics, x1, y1, x2, y2, color);
	}
}
