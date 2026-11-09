package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kr.asmbl.block_custom_rules.sound.HeldBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 내가 놓은 블록의 놓기 소리(D17) — 그 자리에 막 놓인 블록을 걸어 둔다. 클라 쪽만. */
@Mixin(BlockItem.class)
public abstract class PlaceSoundMixin {
	@WrapOperation(method = "place", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
	private void bcr$place(Level level, Entity entity, BlockPos pos, SoundEvent event, SoundSource source, float volume, float pitch,
			Operation<Void> original) {
		if (!level.isClientSide()) {
			original.call(level, entity, pos, event, source, volume, pitch);
			return;
		}
		BlockState prev = HeldBlock.swap(level.getBlockState(pos));
		try {
			original.call(level, entity, pos, event, source, volume, pitch);
		} finally {
			HeldBlock.restore(prev);
		}
	}
}
