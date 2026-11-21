package dev.skyparty.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.skyparty.emote.EmotePoses;
import dev.skyparty.emote.Emotes;
import net.minecraft.client.model.player.PlayerModel;
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

/** Emotes (see Emotes and EmotePoses): pose, body transform and height of players doing an emote. Visual only. */
public final class EmoteMixins {
	private EmoteMixins() {}

	@Mixin(AvatarRenderer.class)
	public abstract static class Renderer {
		@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
		private void skyparty$emoteState(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
			Emotes.Emote emote = entity instanceof Player player ? Emotes.INSTANCE.emoteOf(player) : null;
			if (emote == null) {
				Emotes.drawn.remove(state.id);
				return;
			}
			Player player = (Player) entity;
			Emotes.drawn.put(state.id, new Emotes.Drawn(emote, Emotes.INSTANCE.ageOf(player)));
			state.isCrouching = false;
			state.isPassenger = false;
			state.walkAnimationSpeed = 0f;
			// Effects drawn for the standing body would float in the wrong place: no fire, freeze shake,
			// stuck arrows / stingers, item use or riptide spin while emoting.
			state.displayFireAnimation = false;
			state.isFullyFrozen = false;
			state.arrowCount = 0;
			state.stingerCount = 0;
			state.isUsingItem = false;
			state.isAutoSpinAttack = false;
			// The cape would go through the chair or the ground.
			if (emote == Emotes.Emote.CHAIR || emote == Emotes.Emote.SLEEP) state.showCape = false;
			// Name tag closer to the body when sitting low or lying.
			if (state.nameTagAttachment != null && (emote == Emotes.Emote.CHAIR || emote == Emotes.Emote.SLEEP)) {
				state.nameTagAttachment = state.nameTagAttachment.add(0, emote == Emotes.Emote.SLEEP ? -1.3 : -0.55, 0);
			}
			float head = state.bodyRot + state.yRot;
			float yaw = Emotes.INSTANCE.yawOf(player, state.bodyRot);
			state.bodyRot = yaw;
			state.yRot = Mth.clamp(Mth.wrapDegrees(head - yaw), -80f, 80f);
		}

		@Inject(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
		private void skyparty$emoteOffset(AvatarRenderState state, CallbackInfoReturnable<Vec3> cir) {
			Emotes.Drawn d = Emotes.drawn.get(state.id);
			if (d != null) {
				double drop = EmotePoses.drop(d.emote);
				if (drop != 0) cir.setReturnValue(cir.getReturnValue().add(0, -drop, 0));
			}
		}

		@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("TAIL"))
		private void skyparty$emoteBody(AvatarRenderState state, PoseStack pose, float bodyRot, float scale, CallbackInfo ci) {
			Emotes.Drawn d = Emotes.drawn.get(state.id);
			if (d != null) EmotePoses.body(pose, d);
		}
	}

	@Mixin(PlayerModel.class)
	public abstract static class Model {
		@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
		private void skyparty$emoteLimbs(AvatarRenderState state, CallbackInfo ci) {
			Emotes.Drawn d = Emotes.drawn.get(state.id);
			if (d != null) EmotePoses.arms((PlayerModel) (Object) this, d);
		}
	}
}
