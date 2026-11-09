package kr.asmbl.block_render_rules.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import kr.asmbl.block_render_rules.sway.SwayEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 휘기 — 프레임 머리에서 판정 · 블렌딩, 블록 엔티티를 다 그린 끝에서 기운 블록을 따로 그린다.
 * ⚠ 우선순위 1100 — Sodium 이 `renderBlockEntities` 를 @Overwrite 한다(Sodium `LevelRendererMixin:247`).
 * 우선순위가 높을수록 나중에 붙으므로, Sodium 이 덮은 몸통에 이 끝 주입이 붙는다.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class LevelRendererMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void brr$swayUpdate(CallbackInfo ci) {
		SwayEngine.update();
	}

	@Inject(method = "renderBlockEntities", at = @At("TAIL"))
	private void brr$swayRender(PoseStack poseStack, MultiBufferSource.BufferSource buffers, MultiBufferSource.BufferSource crumbling, Camera camera, float partialTick, CallbackInfo ci) {
		SwayEngine.render(buffers, camera);
	}
}
