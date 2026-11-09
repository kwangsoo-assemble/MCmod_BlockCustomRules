package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kr.asmbl.block_custom_rules.sound.HeldBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 블록 떨어짐 소리의 블록(D17) — 바닐라와 같은 자리 (⌊x⌋, ⌊y − 0.2⌋, ⌊z⌋) 를 걸어 둔다. 클라 쪽만(내 플레이어).
 */
@Mixin(LivingEntity.class)
public abstract class FallSoundMixin {
	@WrapMethod(method = "playBlockFallSound")
	private void bcr$fall(Operation<Void> original) {
		LivingEntity self = (LivingEntity) (Object) this;
		Level level = self.level();
		if (!level.isClientSide()) {
			original.call();
			return;
		}
		BlockPos pos = BlockPos.containing(self.getX(), self.getY() - 0.2F, self.getZ());
		BlockState state = level.hasChunkAt(pos) ? level.getBlockState(pos) : null;
		BlockState prev = HeldBlock.swap(state);
		try {
			original.call();
		} finally {
			HeldBlock.restore(prev);
		}
	}
}
