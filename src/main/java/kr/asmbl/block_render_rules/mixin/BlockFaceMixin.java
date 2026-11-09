package kr.asmbl.block_render_rules.mixin;

import kr.asmbl.block_render_rules.CullPass;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 바닐라 청크 그리기의 면 판정 — 이웃이 컬링 패스 블록이면 가리개로 치지 않고 면을 그린다.
 * 면을 가리는지는 모델 · 계층이 아니라 이웃 블록의 가림 모양(`getFaceOcclusionShape`)이 정해서,
 * 블록을 반투명으로 그려도 옆 면이 지워져 구멍이 보인다 — 그래서 텍스처 방식과 짝으로 쓴다.
 * Sodium 이 깔리면 이 자리를 안 거친다 — {@link SodiumOcclusionMixin} 이 같은 일을 한다.
 */
@Mixin(Block.class)
public abstract class BlockFaceMixin {
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true, require = 1)
	private static void brr$keepFace(BlockState self, BlockState neighbor, Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (CullPass.passes(self, neighbor)) {
			cir.setReturnValue(true);
		}
	}
}
