package kr.asmbl.block_custom_rules.sound;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.debug.SoundDebugger;
import kr.asmbl.block_custom_rules.rules.Resolved;
import kr.asmbl.block_custom_rules.step.FootstepEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * 소리 한 자리(D13) — `ClientLevel.playSound` 를 지나는 모든 위치 소리 · 서버 소리 패킷이 여기로 온다.
 * 블록: 들고 부른 자리면 그 블록 · 서버 걸음 · 떨어짐 패킷이면 발밑 후보 중 원래 이름이 맞는 블록 · 그 밖은 소리 자리 블록 + 주인 확인.
 * 안 읽힌 청크 · 다른 월드면 손대지 않는다(D41 · D42). 예외는 삼키고 원래 소리(D45).
 */
public final class SoundHub {
	private static final Logger LOG = LogUtils.getLogger();
	private static final Map<String, SoundEvent> EVENTS = new ConcurrentHashMap<>();
	private static boolean warned;

	/** 바꾼 소리 — 이름 · 음량 · 높낮이(시드는 원래 것). */
	public record Replacement(SoundEvent event, float volume, float pitch, String reason) {
	}

	private SoundHub() {
	}

	/** 막는다 — 이 엔진이 내는 남의 걸음 패킷(D33). */
	public static final Replacement CANCEL = new Replacement(null, 0.0F, 0.0F, "blocked footstep packet");

	/** 디버거용 — 블록 설명 · 안 바꾼 까닭(켜져 있을 때만 만든다). */
	private static final class Trace {
		String block;
		String reason = "no_rules";
	}

	/** 위치 소리 · 서버 패킷 — 바꿀 게 없으면 null · 막으면 {@link #CANCEL}. */
	public static Replacement onPlay(ClientLevel level, double x, double y, double z, SoundEvent ev, SoundSource source, float volume, float pitch, long seed) {
		String id = ev.location().toString();
		boolean packet = HeldBlock.inPacket();
		Trace tr = SoundDebugger.active() ? new Trace() : null;
		Replacement r = play(level, x, y, z, id, source, volume, pitch, seed, packet, tr);
		if (tr != null) {
			trace(packet ? SoundDebugger.Origin.PACKET : SoundDebugger.Origin.CLIENT, x, y, z, id, r, tr);
		}
		return r;
	}

	private static void trace(SoundDebugger.Origin origin, double x, double y, double z, String id, Replacement r, Trace tr) {
		try {
			if (r == CANCEL) {
				SoundDebugger.record(origin, x, y, z, id, null, null, tr.block, true);
			} else if (r != null) {
				SoundDebugger.record(origin, x, y, z, r.event().location().toString(), id, null, tr.block, false);
			} else {
				SoundDebugger.record(origin, x, y, z, id, null, Component.translatable("bcr.debug.reason." + tr.reason).getString(), tr.block, false);
			}
		} catch (RuntimeException | LinkageError e) {
			warnOnce(e);
		}
	}

	private static Replacement play(ClientLevel level, double x, double y, double z, String id, SoundSource source, float volume, float pitch, long seed,
			boolean packet, Trace tr) {
		if (packet) {
			try {
				if (FootstepEngine.blocksPacket(level, x, y, z, id, source, volume, pitch, seed)) {
					return CANCEL;
				}
			} catch (RuntimeException | LinkageError e) {
				warnOnce(e);
			}
		}
		Rules.Snapshot snap = Rules.snapshot();
		if (!snap.anySoundCategory) {
			return null;
		}
		String[] parts = SoundNames.split(id);
		if (parts == null) {
			if (tr != null) {
				tr.reason = "not_block_sound";
			}
			return null;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			BlockState held = HeldBlock.get();
			BlockState state;
			boolean owner;
			if (held != null) {
				state = held;
				owner = true;
			} else if (packet && ("step".equals(parts[1]) || "fall".equals(parts[1]))) {
				state = footState(level, x, y, z, id, parts[1]);
				owner = state != null;
			} else {
				BlockPos pos = BlockPos.containing(x, y, z);
				if (!level.hasChunkAt(pos)) {
					if (tr != null) {
						tr.reason = "unloaded";
					}
					return null;
				}
				state = level.getBlockState(pos);
				owner = SoundOwners.owns(state, id);
			}
			if (state == null) {
				if (tr != null) {
					tr.reason = "no_foot_block";
				}
				return null;
			}
			return decide(snap, level == mc.level, state, owner, id, source, packet, seed, volume, pitch, tr);
		} catch (RuntimeException | LinkageError e) {
			warnOnce(e);
			return null;
		}
	}

	/** 블록을 들고 직접 트는 소리(때리기 — `SoundManager` 로 바로 간다). */
	public static Replacement onHeld(BlockState state, SoundEvent ev, SoundSource source, float volume, float pitch, double x, double y, double z) {
		Rules.Snapshot snap = Rules.snapshot();
		String id = ev.location().toString();
		Trace tr = SoundDebugger.active() ? new Trace() : null;
		Replacement r = null;
		if (snap.anySoundCategory && SoundNames.split(id) != null) {
			try {
				r = decide(snap, true, state, true, id, source, false, 0L, volume, pitch, tr);
			} catch (RuntimeException | LinkageError e) {
				warnOnce(e);
			}
		}
		if (tr != null) {
			trace(SoundDebugger.Origin.CLIENT, x, y, z, id, r, tr);
		}
		return r;
	}

	private static Replacement decide(Rules.Snapshot snap, boolean sameWorld, BlockState state, boolean owner, String id, SoundSource source,
			boolean packet, long seed, float volume, float pitch, Trace tr) {
		Resolved r = snap.get(state);
		String cat = r == null ? null : r.soundCategory();
		SoundDecision.Result res = SoundDecision.decide(id, source.getName(), packet, seed, sameWorld, cat, owner, snap.categories,
				SoundHub::defined, SoundHub::registered);
		if (tr != null) {
			tr.block = describe(state) + (cat == null ? "" : " (" + cat + ")");
			tr.reason = res.reason().replace(' ', '_');
		}
		if (!res.replaces()) {
			return null;
		}
		SoundEvent ne = event(res.soundId());
		return res.keepVolumePitch() ? new Replacement(ne, volume, pitch, res.reason()) : new Replacement(ne, 1.0F, 1.0F, res.reason());
	}

	/** 서버 걸음 · 떨어짐 패킷 — 발밑 후보 중 원래 그 이름을 낼 블록(첫 후보 먼저) · 없으면 null(바닐라 그대로). */
	private static BlockState footState(ClientLevel level, double x, double y, double z, String id, String action) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int[] c : SoundDecision.footCandidates(x, y, z)) {
			p.set(c[0], c[1], c[2]);
			if (!level.hasChunkAt(p)) {
				continue;
			}
			BlockState s = level.getBlockState(p);
			SoundType t = s.getSoundType();
			SoundEvent orig = "fall".equals(action) ? t.getFallSound() : t.getStepSound();
			if (id.equals(orig.location().toString())) {
				return s;
			}
		}
		return null;
	}

	/** 리팩 · 바닐라 `sounds.json` 에 정의된 이름인가(빈 목록 = 정의됨 · 무음). */
	public static boolean defined(String name) {
		ResourceLocation rl = ResourceLocation.tryParse(name);
		return rl != null && Minecraft.getInstance().getSoundManager().getSoundEvent(rl) != null;
	}

	/** 게임 레지스트리 소리인가(바닐라 · 모드가 등록한 소리). */
	public static boolean registered(String name) {
		ResourceLocation rl = ResourceLocation.tryParse(name);
		return rl != null && BuiltInRegistries.SOUND_EVENT.containsKey(rl);
	}

	/** 이름 → 소리 — 등록 소리면 그것 · 아니면 거리 따라 줄어드는 새 소리(리팩에만 있는 이름). */
	public static SoundEvent event(String name) {
		return EVENTS.computeIfAbsent(name, n -> {
			ResourceLocation rl = ResourceLocation.parse(n);
			return BuiltInRegistries.SOUND_EVENT.containsKey(rl) ? BuiltInRegistries.SOUND_EVENT.getValue(rl) : SoundEvent.createVariableRangeEvent(rl);
		});
	}

	/** 디버거 · 명령용 블록 이름. */
	public static String describe(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
	}

	private static void warnOnce(Throwable e) {
		if (!warned) {
			warned = true;
			LOG.warn("[block_custom_rules] 소리 갈아끼우기 중 예외 — 원래 소리를 쓴다(이 알림은 한 번만)", e);
		}
	}
}
