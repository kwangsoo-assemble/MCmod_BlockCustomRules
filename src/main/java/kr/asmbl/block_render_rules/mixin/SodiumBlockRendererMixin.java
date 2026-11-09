package kr.asmbl.block_render_rules.mixin;

import kr.asmbl.block_render_rules.sway.SwayEngine;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 청크 짓기 — 기울어 있는 블록의 사각면 정점을 비튼다(아래 면 고정 · 위로 갈수록 밀림).
 * 블록을 시작할 때 비틀기 값을 잡아 두고, 사각면마다(조명 · 버퍼 쓰기 전) 블록 안 좌표를 바꾼다.
 * BlockRenderer 는 청크 짓는 스레드마다 하나라 인스턴스 칸에 들어도 섞이지 않는다.
 * ⚠ Sodium 내부 구조에 기댄다(공개 API 아님 · 0.7.3 공개 소스 `BlockRenderer.renderModel` · `processQuad(MutableQuadViewImpl)`).
 * Sodium 판을 올리면 대상 이름 · 인자를 다시 본다. Sodium 이 없으면 @Pseudo 라 조용히 빠진다.
 * 사각면은 Fabric `QuadEmitter` 를 따르는 Sodium 클래스라 @Coerce 로 받는다.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockRendererMixin {
	@Unique
	private float[] brr$shear;

	@Inject(method = "renderModel", at = @At("HEAD"), remap = false)
	private void brr$begin(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		this.brr$shear = SwayEngine.shear(pos);
	}

	@Inject(method = "processQuad", at = @At("HEAD"), remap = false)
	private void brr$tilt(@Coerce QuadEmitter quad, CallbackInfo ci) {
		float[] s = this.brr$shear;
		if (s == null) {
			return;
		}
		for (int i = 0; i < 4; i++) {
			float y = quad.y(i);
			float h = y + s[2]; // 기둥 위 칸은 아래 칸 바닥에서 잰 높이
			quad.pos(i, quad.x(i) + s[0] * h, y, quad.z(i) + s[1] * h);
		}
	}
}
