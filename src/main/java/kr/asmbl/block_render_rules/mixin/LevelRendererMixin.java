package kr.asmbl.block_render_rules.mixin;

import kr.asmbl.block_render_rules.sway.SwayEngine;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 휘기 — 프레임 머리에서 판정 · 블렌딩 · 바뀐 기둥의 청크 다시 짓기. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void brr$swayUpdate(CallbackInfo ci) {
		SwayEngine.update();
	}
}
