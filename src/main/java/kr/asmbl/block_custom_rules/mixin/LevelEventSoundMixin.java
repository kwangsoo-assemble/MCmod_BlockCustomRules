package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kr.asmbl.block_custom_rules.sound.HeldBlock;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 부수기 소리의 블록(D17) — 레벨 이벤트 2001 은 블록이 이미 공기라 이벤트에 든 블록 상태를 걸어 둔다(내 것 · 남의 것 모두 이 길).
 * 이벤트 처리가 끝나면 앞 값으로 되돌린다.
 */
@Mixin(LevelEventHandler.class)
public abstract class LevelEventSoundMixin {
	@WrapMethod(method = "levelEvent")
	private void bcr$scope(int type, BlockPos pos, int data, Operation<Void> original) {
		BlockState prev = HeldBlock.get();
		try {
			original.call(type, pos, data);
		} finally {
			HeldBlock.restore(prev);
		}
	}

	@WrapOperation(method = "levelEvent", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/Block;stateById(I)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState bcr$hold(int id, Operation<BlockState> original) {
		BlockState s = original.call(id);
		HeldBlock.swap(s);
		return s;
	}
}
