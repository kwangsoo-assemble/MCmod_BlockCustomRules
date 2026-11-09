package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kr.asmbl.block_custom_rules.sound.HeldBlock;
import kr.asmbl.block_custom_rules.step.FootstepEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 걸음 소리가 «어느 블록» 의 것인지 걸어 둔다(D17) — 걸음 · 자수정 종소리(`walkingStepSound`) · 블록 하나 걸음 · 겹침 아래 먹먹한 걸음.
 * 클라 쪽만(혼자 하기의 내장 서버 스레드는 건드리지 않는다).
 */
@Mixin(Entity.class)
public abstract class EntityStepSoundMixin {
	@WrapMethod(method = "walkingStepSound")
	private void bcr$walking(BlockPos pos, BlockState state, Operation<Void> original) {
		Entity self = (Entity) (Object) this;
		if (!self.level().isClientSide()) {
			original.call(pos, state);
			return;
		}
		// 발소리 엔진이 맡는 엔티티는 바닐라 걸음 · 종소리를 끈다(D33 — 클라 쪽만 · 서버 쪽은 그대로라 모드 없는 손님도 듣는다)
		if (FootstepEngine.handles(self)) {
			return;
		}
		BlockState prev = HeldBlock.swap(state);
		try {
			original.call(pos, state);
		} finally {
			HeldBlock.restore(prev);
		}
	}

	@WrapMethod(method = "playStepSound")
	private void bcr$step(BlockPos pos, BlockState state, Operation<Void> original) {
		if (!((Entity) (Object) this).level().isClientSide()) {
			original.call(pos, state);
			return;
		}
		BlockState prev = HeldBlock.swap(state);
		try {
			original.call(pos, state);
		} finally {
			HeldBlock.restore(prev);
		}
	}

	/** 겹침(카펫 · 눈 층 위) — 위 블록 걸음은 위 블록 것 · 아래 먹먹한 걸음은 아래에서 다시 건다. */
	@WrapMethod(method = "playCombinationStepSounds")
	private void bcr$combination(BlockState primary, BlockState secondary, Operation<Void> original) {
		if (!((Entity) (Object) this).level().isClientSide()) {
			original.call(primary, secondary);
			return;
		}
		BlockState prev = HeldBlock.swap(primary);
		try {
			original.call(primary, secondary);
		} finally {
			HeldBlock.restore(prev);
		}
	}

	@WrapMethod(method = "playMuffledStepSound")
	private void bcr$muffled(BlockState state, Operation<Void> original) {
		if (!((Entity) (Object) this).level().isClientSide()) {
			original.call(state);
			return;
		}
		BlockState prev = HeldBlock.swap(state);
		try {
			original.call(state);
		} finally {
			HeldBlock.restore(prev);
		}
	}
}
