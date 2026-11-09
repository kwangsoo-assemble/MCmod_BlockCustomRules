package kr.asmbl.block_custom_rules.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 몹의 바닐라 걸음을 엔진이 부른다(D32) — 몹 전용 소리 35종 · 말 질주 · 종소리 · 안쪽 블록을 새로 흉내 내지 않고 바닐라 함수 그대로.
 * 클라에선 남의 엔티티가 스스로 걷지 않아 이 함수가 안 불린다 — 엔진이 걸음 판정 때 부른다.
 */
@Mixin(Entity.class)
public interface EntityStepAccess {
	@Invoker("walkingStepSound")
	void bcr$walkingStepSound(BlockPos pos, BlockState state);

	@Invoker("getMovementEmission")
	Entity.MovementEmission bcr$movementEmission();
}
