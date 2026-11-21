package dev.skyparty.mixin;

import dev.skyparty.emote.ChairRenderer;
import dev.skyparty.emote.Emotes;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Emotes (see Emotes): sitting players are drawn in the riding pose, lower and facing their chair. Visual only. */
@Mixin(AvatarRenderer.class)
public abstract class EmoteMixins {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void skyparty$emotePose(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (!(entity instanceof Player player) || !Emotes.isSitting(player)) {
			Emotes.sittingIds.remove(state.id);
			return;
		}
		Emotes.sittingIds.add(state.id);
		state.isPassenger = true;
		state.isCrouching = false;
		float head = state.bodyRot + state.yRot;
		float yaw = Emotes.INSTANCE.yawOf(player, state.bodyRot);
		state.bodyRot = yaw;
		state.yRot = Mth.clamp(Mth.wrapDegrees(head - yaw), -80f, 80f);
		state.walkAnimationSpeed = 0f;
	}

	@Inject(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
	private void skyparty$emoteOffset(AvatarRenderState state, CallbackInfoReturnable<Vec3> cir) {
		if (Emotes.sittingIds.contains(state.id)) cir.setReturnValue(cir.getReturnValue().add(0, -ChairRenderer.SIT_DROP, 0));
	}
}
