package kr.asmbl.block_custom_rules.mixin;

import kr.asmbl.block_custom_rules.step.FootstepEngine;
import kr.asmbl.block_custom_rules.sway.SwayEngine;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 프레임 머리 — 휘기(판정 · 블렌딩 · 바뀐 기둥 다시 짓기) · 지연 소리 꺼내기(매 프레임 · D35). */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void bcr$frame(CallbackInfo ci) {
		SwayEngine.update();
		FootstepEngine.DELAYED.drain(System.nanoTime());
	}
}
