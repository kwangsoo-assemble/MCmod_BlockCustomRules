package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kr.asmbl.block_custom_rules.sound.SoundHub;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 내가 블록을 때리는 소리(D17) — 이 소리만 `ClientLevel.playSound` 를 안 거치고 소리 관리자로 바로 가서 여기서 갈아끼운다.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class HitSoundMixin {
	@WrapOperation(method = "continueDestroyBlock", at = @At(value = "NEW",
			target = "(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;"))
	private SimpleSoundInstance bcr$hit(SoundEvent event, SoundSource source, float volume, float pitch, RandomSource random, BlockPos pos,
			Operation<SimpleSoundInstance> original) {
		Minecraft mc = Minecraft.getInstance();
		SoundHub.Replacement r = mc.level == null ? null : SoundHub.onHeld(mc.level.getBlockState(pos), event, source, volume, pitch,
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (r == null) {
			return original.call(event, source, volume, pitch, random, pos);
		}
		if (r == SoundHub.CANCEL) {
			// 겹 소리로 이미 틀었다 — 원래 자리는 무음으로
			return original.call(event, source, 0.0F, pitch, random, pos);
		}
		return original.call(r.event(), source, r.volume(), r.pitch(), random, pos);
	}
}
