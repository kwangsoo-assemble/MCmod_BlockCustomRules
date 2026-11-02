package kr.asmbl.cull_pass.mixin;

import kr.asmbl.cull_pass.CullPassConfig;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 바닐라 청크 그리기의 면 판정 — 이웃이 목록 블록이면 가리개로 치지 않고 면을 그린다.
 * 면을 가리는지는 모델이 아니라 이웃 블록의 가림 모양(`getFaceOcclusionShape`)이 정해서, 모델을 air 로 바꿔도 아래 면이 뚫린다.
 * Sodium 이 깔리면 이 자리를 안 거친다 — {@link SodiumOcclusionMixin} 이 같은 일을 한다.
 */
@Mixin(Block.class)
public abstract class BlockFaceMixin {
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true, require = 1)
	private static void cullPass$keepFace(BlockState self, BlockState neighbor, Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (CullPassConfig.passes(self, neighbor)) {
			cir.setReturnValue(true);
		}
	}
}
