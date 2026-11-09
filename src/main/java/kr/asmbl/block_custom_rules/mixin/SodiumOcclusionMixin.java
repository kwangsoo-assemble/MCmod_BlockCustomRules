package kr.asmbl.block_custom_rules.mixin;

import kr.asmbl.block_custom_rules.CullPass;
import kr.asmbl.block_custom_rules.config.BcrConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium 청크 그리기의 면 판정 — 이웃(selfPos 에서 facing 쪽)이 컬링 패스 블록이면 면을 그린다.
 * ⚠ Sodium 내부 구조에 기댄다(공개 API 아님) — Sodium 판을 올리면 대상 이름 · 인자를 다시 본다. Sodium 이 없으면 @Pseudo 라 조용히 빠진다.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockOcclusionCache", remap = false)
public abstract class SodiumOcclusionMixin {
	@Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, remap = false)
	private void bcr$keepFace(BlockState self, BlockGetter view, BlockPos selfPos, Direction facing, CallbackInfoReturnable<Boolean> cir) {
		// 컬링 패스 블록이 하나도 없으면 이웃을 한 번 더 읽지 않는다
		if (!BcrConfig.anyCullPass()) {
			return;
		}
		if (CullPass.passes(self, view.getBlockState(selfPos.relative(facing)))) {
			cir.setReturnValue(true);
		}
	}
}
