package kr.asmbl.block_custom_rules.step;

import kr.asmbl.block_custom_rules.sound.SoundHub;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 엔진이 몹의 바닐라 걸음 함수를 부르는 동안의 표시(D32) — 그 안에서 난 소리는 클라 `playSeededSound` 가 «남의 소리» 로 버리므로
 * 여기서 받아 이 클라에서 튼다(블록 소리는 허브가 갈아끼운 뒤 · 섞기 `mob` 채널 배율 · 크기 높낮이 · 먼 소리 지연).
 * 스레드마다 따로 · 렌더 스레드에서만 걸린다.
 */
public final class MobPlay {
	private record Ctx(float volume, float pitchDiv, long delayNanos) {
	}

	private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

	private MobPlay() {
	}

	public static boolean active() {
		return CURRENT.get() != null;
	}

	/** 걸고 바닐라를 부르고 푼다. */
	public static void run(float volume, float pitchDiv, long delayNanos, Runnable vanilla) {
		Ctx prev = CURRENT.get();
		CURRENT.set(new Ctx(volume, pitchDiv, delayNanos));
		try {
			vanilla.run();
		} finally {
			CURRENT.set(prev);
		}
	}

	/** 표시 안에서 난 소리 하나. */
	public static void play(ClientLevel level, double x, double y, double z, SoundEvent ev, SoundSource source, float volume, float pitch, long seed) {
		Ctx c = CURRENT.get();
		SoundHub.Replacement r = SoundHub.onPlay(level, x, y, z, ev, source, volume, pitch, seed);
		if (r == SoundHub.CANCEL) {
			return;
		}
		SoundEvent e = r == null ? ev : r.event();
		float v = (r == null ? volume : r.volume()) * c.volume;
		float p = (r == null ? pitch : r.pitch()) / c.pitchDiv;
		if (v <= 0.0F) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		SimpleSoundInstance si = new SimpleSoundInstance(e, source, Math.min(v, FootstepEngine.VOLUME_CAP), p, RandomSource.create(seed), x, y, z);
		if (c.delayNanos > 0) {
			FootstepEngine.DELAYED.add(System.nanoTime(), c.delayNanos, () -> mc.getSoundManager().play(si));
		} else {
			mc.getSoundManager().play(si);
		}
	}
}
