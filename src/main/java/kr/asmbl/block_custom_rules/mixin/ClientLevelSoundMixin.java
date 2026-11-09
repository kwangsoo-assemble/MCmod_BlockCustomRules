package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kr.asmbl.block_custom_rules.sound.SoundHub;
import kr.asmbl.block_custom_rules.step.MobPlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 소리 한 자리(D13) — 클라 위치 소리(`playLocalSound` · 내 플레이어 `playSeededSound`)와 서버 소리 패킷이 모두 이 비공개 메서드를 지난다.
 * 갈아끼울 게 있으면 이름 · 음량 · 높낮이만 바꿔 넘긴다(시드 · 거리 지연은 원래 것).
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSoundMixin {
	@WrapMethod(method = "playSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZJ)V")
	private void bcr$replace(double x, double y, double z, SoundEvent event, SoundSource source, float volume, float pitch, boolean delay, long seed,
			Operation<Void> original) {
		SoundHub.Replacement r = SoundHub.onPlay((ClientLevel) (Object) this, x, y, z, event, source, volume, pitch, seed);
		if (r == SoundHub.CANCEL) {
			return;
		}
		if (r == null) {
			original.call(x, y, z, event, source, volume, pitch, delay, seed);
		} else {
			original.call(x, y, z, r.event(), source, r.volume(), r.pitch(), delay, seed);
		}
	}

	/** 엔진이 부른 몹 바닐라 걸음의 소리 — 클라는 «남의 소리»(주체 없음)를 버리므로 여기서 받아 튼다(D32). 그 밖은 그대로. */
	@WrapMethod(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V")
	private void bcr$mobLocal(Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
			Operation<Void> original) {
		if (except == null && MobPlay.active()) {
			MobPlay.play((ClientLevel) (Object) this, x, y, z, sound.value(), source, volume, pitch, seed);
			return;
		}
		original.call(except, x, y, z, sound, source, volume, pitch, seed);
	}
}
