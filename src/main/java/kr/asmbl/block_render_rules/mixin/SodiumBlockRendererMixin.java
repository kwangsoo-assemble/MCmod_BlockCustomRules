package kr.asmbl.block_render_rules.mixin;

import kr.asmbl.block_render_rules.sway.SwayEngine;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 청크 짓기 — 기울어 있는 블록은 청크에 넣지 않는다(따로 그린다).
 * ⚠ Sodium 내부 구조에 기댄다(공개 API 아님 · 0.7.3 공개 소스 `BlockRenderer.renderModel(model, state, pos, origin)`).
 * Sodium 판을 올리면 대상 이름 · 인자를 다시 본다. Sodium 이 없으면 @Pseudo 라 조용히 빠진다.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockRendererMixin {
	@Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false)
	private void brr$skipTilting(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		if (SwayEngine.isHidden(pos)) {
			ci.cancel();
		}
	}
}
