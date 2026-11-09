package kr.asmbl.block_custom_rules.step;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.debug.SoundDebugger;
import kr.asmbl.block_custom_rules.rules.Resolved;
import kr.asmbl.block_custom_rules.sound.SoundDecision;
import kr.asmbl.block_custom_rules.sound.SoundHub;
import kr.asmbl.block_custom_rules.sound.SoundNames;
import kr.asmbl.block_custom_rules.track.EntityTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * 발소리 엔진(D24 ~ D29 · D33 ~ D35) — 이번 판(T4)은 플레이어(나 · 남)만 맡는다. 몹은 바닐라 그대로(T10).
 * 대상: 카메라 16칸 · 최대 50(가까운 순) · 빼기: 관전자 · 잠 · 탄 엔티티 · 물속(헤엄은 바닐라 그대로) · 무음은 맡되 소리를 안 낸다.
 * 맡는 플레이어는 바닐라 걸음을 끄고(내 것 = 클라 쪽 `walkingStepSound` · 남의 것 = 서버 걸음 패킷) 이 엔진이 낸다.
 * 걸음마다 발 하나(왼 · 오른 번갈아) — 발 자리 블록 · 발 칸(안쪽 · 겹침 · `step_as` · 지나감) · 자수정 종소리 · 입장 · 퇴장.
 * 소리 이름은 종류 → 행동 → 행동 부모 → 부모 종류 → 끝내 없으면 그 블록의 바닐라 걸음(웅크림 · 지나감 · 입장 · 퇴장은 무음).
 * 상태는 엔티티 id 로만 든다(D40) — 세대가 바뀌면 다 버린다.
 */
public final class FootstepEngine {
	private static final Logger LOG = LogUtils.getLogger();
	public static final double RANGE = 16.0;
	public static final int MAX = 50;
	/** 이보다 먼 소리는 ⌊거리 / 2⌋ 틱 늦게(D34). */
	public static final double FAR = 10.0;
	/** 이 모드가 내는 소리의 음량 상한(D16). */
	public static final float VOLUME_CAP = 3.0F;
	private static final String CHIME = "minecraft:block.amethyst_block.chime";

	public static final DelayQueue DELAYED = new DelayQueue();
	private static final Int2ObjectOpenHashMap<Tracked> TRACKED = new Int2ObjectOpenHashMap<>();
	/** 이번 틱에 맡은 엔티티(무음 포함) — 서버 걸음 패킷을 막을 때 본다. */
	private static final IntOpenHashSet HANDLED = new IntOpenHashSet();
	private static boolean warned;

	private enum Mode {
		NORMAL, MUFFLED, THROUGH
	}

	/** 발 하나가 밟은 것 — 주 소리 블록 · 겹침 아래(먹먹) · 지나감 블록 · 땅. */
	private record Foot(BlockState primary, BlockState bottom, BlockState through, BlockState ground) {
	}

	private static final class Tracked {
		final StepTracker steps = new StepTracker();
		boolean feetInit;
		String feetKey;
		BlockState feetState;
		int lastChimeTick = Integer.MIN_VALUE / 2;
		float chime;
	}

	private FootstepEngine() {
	}

	public static void reset() {
		TRACKED.clear();
		HANDLED.clear();
		DELAYED.clear();
	}

	public static void entityGone(int id) {
		TRACKED.remove(id);
		HANDLED.remove(id);
	}

	/** 이 엔티티의 바닐라 걸음(클라 쪽)을 끌까 — 내 플레이어를 맡을 때. 무음이어도 끈다(무음은 엔진도 안 낸다). */
	public static boolean handles(Entity e) {
		return e == Minecraft.getInstance().player && eligible(e);
	}

	private static boolean eligible(Entity e) {
		return e.isAlive() && !e.isSpectator() && !e.isPassenger() && !e.isInWater() && !(e instanceof LivingEntity l && l.isSleeping());
	}

	// ── 틱 ──

	public static void tick(Minecraft mc) {
		HANDLED.clear();
		ClientLevel level = mc.level;
		if (level == null) {
			TRACKED.clear();
			return;
		}
		try {
			Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
			List<AbstractClientPlayer> near = new ArrayList<>();
			EntityTracker.forEach(level, e -> {
				if (e instanceof AbstractClientPlayer p && p.distanceToSqr(cam) <= RANGE * RANGE) {
					near.add(p);
				}
			});
			near.sort(Comparator.comparingDouble(p -> p.distanceToSqr(cam)));
			IntOpenHashSet live = new IntOpenHashSet();
			int n = 0;
			for (AbstractClientPlayer p : near) {
				if (n >= MAX) {
					break;
				}
				if (!eligible(p)) {
					continue;
				}
				n++;
				HANDLED.add(p.getId());
				if (p.isSilent()) {
					continue;
				}
				live.add(p.getId());
				Tracked t = TRACKED.computeIfAbsent(p.getId(), k -> new Tracked());
				try {
					tickOne(mc, level, p, t, cam);
				} catch (RuntimeException | LinkageError e) {
					warnOnce(e);
					TRACKED.remove(p.getId());
				}
			}
			var it = TRACKED.int2ObjectEntrySet().iterator();
			while (it.hasNext()) {
				Int2ObjectMap.Entry<Tracked> e = it.next();
				if (!live.contains(e.getIntKey())) {
					it.remove();
				}
			}
		} catch (RuntimeException | LinkageError e) {
			warnOnce(e);
		}
	}

	private static void tickOne(Minecraft mc, ClientLevel level, Player p, Tracked t, Vec3 cam) {
		boolean sneaking = p.isShiftKeyDown();
		StepTracker.Input in = new StepTracker.Input(p.getX(), p.getY(), p.getZ(), p.onGround(), p.onClimbable(), p.isSprinting(), sneaking,
				p.isVisuallyCrawling(), p == mc.player);
		List<StepTracker.Event> events = t.steps.tick(in);

		// 입장 · 퇴장 — 발 칸의 소리 종류가 바뀔 때(D29)
		BlockPos feetPos = p.getOnPosLegacy().above();
		if (level.hasChunkAt(feetPos)) {
			BlockState feet = level.getBlockState(feetPos);
			String key = feetKey(feet);
			if (!t.feetInit) {
				t.feetInit = true;
			} else if (!Objects.equals(key, t.feetKey)) {
				String sneak = sneaking ? "_sneak" : "";
				if (t.feetKey != null) {
					playOne(mc, level, p, t.feetState, "exit" + sneak, Mode.THROUGH, false, cam);
				}
				if (key != null) {
					playOne(mc, level, p, feet, "enter" + sneak, Mode.THROUGH, false, cam);
				}
			}
			t.feetKey = key;
			t.feetState = feet;
		}

		for (StepTracker.Event ev : events) {
			if (ev.feet() == 2) {
				Foot l = foot(level, p, false);
				Foot r = foot(level, p, true);
				if (l != null && r != null && l.equals(r)) {
					play(mc, level, p, l, ev, cam);
				} else {
					play(mc, level, p, l, ev, cam);
					play(mc, level, p, r, ev, cam);
				}
			} else {
				Foot f = foot(level, p, t.steps.nextFoot());
				play(mc, level, p, f, ev, cam);
				if (f != null && !StepTracker.isSneak(ev.action()) && VanillaSteps.crystal(f.ground.getBlock())) {
					chime(mc, level, p, t, f.ground, cam);
				}
			}
		}
	}

	/** 발 칸 키 — 커스텀 종류 이름 · 없으면 바닐라 소리 종류 자체 · 공기 · 빈 소리면 null. */
	private static String feetKey(BlockState s) {
		if (s.isAir()) {
			return null;
		}
		Resolved r = Rules.get(s);
		if (r != null && r.soundCategory() != null) {
			return r.soundCategory();
		}
		SoundType t = s.getSoundType();
		return t == SoundType.EMPTY ? null : "vanilla#" + System.identityHashCode(t);
	}

	/** 발 하나의 블록들(D27 · D29) — 발 자리(몸 방향 좌우 0.2 × 크기)가 공기 · 빈 소리면 바닐라 받침 블록. */
	private static Foot foot(ClientLevel level, Player p, boolean right) {
		double[] off = StepTracker.footOffset(p.yBodyRot, right, p.getBbWidth() / 0.6);
		BlockPos gp = BlockPos.containing(p.getX() + off[0], p.getY() - 0.2, p.getZ() + off[1]);
		if (!level.hasChunkAt(gp)) {
			return null;
		}
		BlockState ground = level.getBlockState(gp);
		if (ground.isAir() || ground.getSoundType() == SoundType.EMPTY) {
			gp = p.getOnPosLegacy();
			if (!level.hasChunkAt(gp)) {
				return null;
			}
			ground = level.getBlockState(gp);
		}
		BlockPos fp = gp.above();
		BlockState feet = level.getBlockState(fp);
		if (feet.isAir()) {
			return new Foot(ground, null, null, ground);
		}
		Resolved fr = Rules.get(feet);
		if (VanillaSteps.inside(feet.getBlock())) {
			// 안쪽 블록 — 리팩에 그 종류의 through 가 있으면 땅 + 지나감 · 없으면 땅 대신(바닐라)
			return hasName(feet, "through") ? new Foot(ground, null, feet, ground) : new Foot(feet, null, null, ground);
		}
		if (VanillaSteps.combination(feet.getBlock()) || (fr != null && fr.stepAsGround())) {
			return new Foot(feet, ground, null, ground);
		}
		if (feet.getCollisionShape(level, fp).isEmpty()) {
			return new Foot(ground, null, feet, ground);
		}
		return new Foot(ground, null, null, ground);
	}

	private static void play(Minecraft mc, ClientLevel level, Player p, Foot f, StepTracker.Event ev, Vec3 cam) {
		if (f == null) {
			return;
		}
		playOne(mc, level, p, f.primary, ev.action(), Mode.NORMAL, ev.running(), cam);
		if (f.bottom != null) {
			playOne(mc, level, p, f.bottom, ev.action(), Mode.MUFFLED, ev.running(), cam);
		}
		if (f.through != null) {
			playOne(mc, level, p, f.through, StepTracker.isSneak(ev.action()) ? "through_sneak" : "through", Mode.THROUGH, ev.running(), cam);
		}
	}

	/** 그 블록의 소리 종류 — 규칙의 종류 · 없으면 바닐라 걸음 소리의 가운데 이름(`minecraft:<가운데>`) · 빈 소리면 null. */
	public static String category(BlockState s) {
		Resolved r = Rules.get(s);
		if (r != null && r.soundCategory() != null) {
			return r.soundCategory();
		}
		String mid = SoundNames.mid(s.getSoundType().getStepSound().location().toString());
		return mid == null ? null : "minecraft:" + mid;
	}

	private static boolean hasName(BlockState s, String action) {
		String cat = category(s);
		return cat != null && Rules.snapshot().categories.find(cat, action, StepTracker.PARENTS_WALK, SoundHub::defined) != null;
	}

	/** 소리 하나 — 종류 › 행동 › 부모 › 끝내 없으면 바닐라 걸음(웅크림 · 지나감 · 입장 · 퇴장은 무음). */
	private static void playOne(Minecraft mc, ClientLevel level, Player p, BlockState state, String action, Mode mode, boolean running, Vec3 cam) {
		String cat = category(state);
		if (cat == null) {
			return;
		}
		Rules.Snapshot snap = Rules.snapshot();
		Resolved r = snap.get(state);
		if (r != null && r.soundCategory() != null && !snap.categories.footsteps(cat)) {
			return;
		}
		Map<String, String> parents = running ? StepTracker.PARENTS_RUN : StepTracker.PARENTS_WALK;
		String name = snap.categories.find(cat, action, parents, SoundHub::defined);
		SoundType t = state.getSoundType();
		SoundEvent ev;
		boolean vanilla;
		if (name == null) {
			if (mode == Mode.THROUGH || StepTracker.isSneak(action)) {
				return;
			}
			ev = t.getStepSound();
			if (ev == SoundEvents.EMPTY) {
				return;
			}
			vanilla = true;
		} else {
			ev = SoundHub.event(name);
			vanilla = SoundHub.registered(name);
		}
		float vol;
		float pitch;
		if (vanilla) {
			vol = t.getVolume() * (mode == Mode.MUFFLED ? 0.05F : 0.15F) * StepTracker.vanillaFactor(action);
			pitch = t.getPitch() * (mode == Mode.MUFFLED ? 0.8F : 1.0F);
		} else {
			// 리팩 이름은 리팩 정의대로(D16) — 겹침 아래만 바닐라 비율(0.05 / 0.15 · 높낮이 0.8)
			vol = mode == Mode.MUFFLED ? 1.0F / 3.0F : 1.0F;
			pitch = mode == Mode.MUFFLED ? 0.8F : 1.0F;
		}
		emit(mc, level, p, ev, vol, pitch, cam, state, action + (mode == Mode.MUFFLED ? " · muffled" : ""));
	}

	/** 자수정 종소리(바닐라 식 · 20틱마다 · 세기 × 0.997^틱 + 0.07). 종류를 바꿨으면 `<종류>.chime` · 없으면 바닐라. */
	private static void chime(Minecraft mc, ClientLevel level, Player p, Tracked t, BlockState ground, Vec3 cam) {
		if (p.tickCount < t.lastChimeTick + 20) {
			return;
		}
		t.chime = Math.min(1.0F, t.chime * (float) Math.pow(0.997, p.tickCount - t.lastChimeTick) + 0.07F);
		t.lastChimeTick = p.tickCount;
		String cat = category(ground);
		String name = cat == null ? null : Rules.snapshot().categories.find(cat, "chime", Map.of(), SoundHub::defined);
		boolean vanilla = name == null || SoundHub.registered(name);
		SoundEvent ev = name == null ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundHub.event(name);
		float vol = vanilla ? 0.1F + t.chime * 1.2F : 1.0F;
		float pitch = vanilla ? 0.5F + t.chime * level.random.nextFloat() * 1.2F : 1.0F;
		emit(mc, level, p, ev, vol, pitch, cam, ground, "chime");
	}

	/** 튼다 — 카메라에서 10칸 넘으면 ⌊거리 / 2⌋ 틱 늦게(D34). 지연 줄에는 소리 재료만 든다. */
	private static void emit(Minecraft mc, ClientLevel level, Player p, SoundEvent ev, float vol, float pitch, Vec3 cam, BlockState block, String action) {
		if (SoundDebugger.active()) {
			String cat = category(block);
			SoundDebugger.record(SoundDebugger.Origin.ENGINE, p.getX(), p.getY(), p.getZ(), ev.location().toString(), null, action,
					SoundHub.describe(block) + (cat == null ? "" : " (" + cat + ")"), false);
		}
		SimpleSoundInstance si = new SimpleSoundInstance(ev, SoundSource.PLAYERS, Math.min(vol, VOLUME_CAP), pitch,
				RandomSource.create(level.random.nextLong()), p.getX(), p.getY(), p.getZ());
		double dist = Math.sqrt(p.distanceToSqr(cam));
		if (p != mc.player && dist > FAR) {
			DELAYED.add(System.nanoTime(), (long) Math.floor(dist / 2.0) * 50_000_000L, () -> mc.getSoundManager().play(si));
		} else {
			mc.getSoundManager().play(si);
		}
	}

	// ── 서버 걸음 패킷 막기(D33) ──

	/**
	 * 남의 걸음 패킷인가 — 다 맞을 때만 막는다: 표식 시드 아님 · 출처 players · 걸음 이름(블록 `.step` · 자수정 종소리) ·
	 * 1칸 안에 이 엔진이 맡는 플레이어(무음 포함) · 그 발밑 블록의 걸음 이름과 맞음 · 음량 · 높낮이가 바닐라 식과 맞음.
	 */
	public static boolean blocksPacket(ClientLevel level, double x, double y, double z, String id, SoundSource source, float vol, float pitch,
			long seed) {
		if (HANDLED.isEmpty() || source != SoundSource.PLAYERS || SoundNames.isMarkerSeed(seed)) {
			return false;
		}
		boolean chime = CHIME.equals(id);
		if (!chime && !id.endsWith(".step")) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		for (Player p : level.players()) {
			if (p == mc.player || !HANDLED.contains(p.getId()) || p.distanceToSqr(x, y, z) > 1.0) {
				continue;
			}
			if (chime) {
				if (vol >= 0.1F - 1.0e-3F && vol <= 1.3F + 1.0e-3F && pitch >= 0.5F - 1.0e-3F && pitch <= 1.7F + 1.0e-3F) {
					return true;
				}
				continue;
			}
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int[] c : SoundDecision.footCandidates(x, y, z)) {
				for (int up = 0; up <= 1; up++) {
					m.set(c[0], c[1] + up, c[2]);
					if (!level.hasChunkAt(m)) {
						continue;
					}
					SoundType t = level.getBlockState(m).getSoundType();
					if (!id.equals(t.getStepSound().location().toString())) {
						continue;
					}
					if (near(vol, t.getVolume() * 0.15F) && near(pitch, t.getPitch()) || near(vol, t.getVolume() * 0.05F) && near(pitch, t.getPitch() * 0.8F)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < 1.0e-3F;
	}

	private static void warnOnce(Throwable e) {
		if (!warned) {
			warned = true;
			LOG.warn("[block_custom_rules] 발소리 엔진 예외 — 그 플레이어만 이번 틱 건너뛴다(이 알림은 한 번만)", e);
		}
	}
}
