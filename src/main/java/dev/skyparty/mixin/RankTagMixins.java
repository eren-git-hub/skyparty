package dev.skyparty.mixin;

import dev.skyparty.cosmetics.RankTags;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom rank tags (see RankTags) in the tab list and in name tags above players. Visual only. */
public final class RankTagMixins {
	private RankTagMixins() {}

	@Mixin(PlayerTabOverlay.class)
	public abstract static class Tab {
		@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
		private void skyparty$rankTag(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
			Component out = RankTags.apply(cir.getReturnValue());
			if (out != cir.getReturnValue()) cir.setReturnValue(out);
		}
	}

	@Mixin(Player.class)
	public abstract static class NameTag {
		@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
		private void skyparty$rankTag(CallbackInfoReturnable<Component> cir) {
			Component out = RankTags.apply(cir.getReturnValue());
			if (out != cir.getReturnValue()) cir.setReturnValue(out);
		}
	}
}
