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
import kr.asmbl.block_custom_rules.game.McWorldProbe;
import kr.asmbl.block_custom_rules.rules.StateView;
import kr.asmbl.block_custom_rules.sound.SoundRules;
import kr.asmbl.block_custom_rules.sound.SoundRules.MixResult;
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
import net.minecraft.core.registries.BuiltInRegistries;
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
 * 발소리 엔진(D24 ~ D35) — 플레이어(나 · 남)와 몹.
 * 대상: 카메라 16칸 · 최대 50(플레이어 먼저 · 가까운 순) · 빼기: 관전자 · 잠 · 탄 엔티티 · 물속(헤엄은 바닐라 그대로) · `locomotion: none` ·
 * 걸음 소리를 안 내는 몹(바닐라 움직임 내보내기가 소리 없음) · 무음은 맡되 소리를 안 낸다.
 * 맡는 엔티티는 바닐라 걸음을 끄고(내 것 = 클라 쪽 `walkingStepSound` · 남 · 몹 = 서버 걸음 패킷) 이 엔진이 낸다.
 * 몹: 걸음마다 그 몹의 바닐라 걸음 함수를 불러 몹 전용 소리 · 말 질주 · 종소리를 그대로 낸다(채널 `mob`) · 블록 걸음 채널은 기본 0(D22).
 * 간격: 0.95 × 몹 고유 간격(거북 0.15 · 스트라이더 0.6 · 워든 0.55) · 네 발 · 크기는 `entities` 규칙대로.
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
	/** 지금 소리를 내는 엔티티의 높낮이 나눗수(크기) · 출처 · 몹인가 — 렌더 스레드의 틱 안에서만 쓴다. */
	private static float curSize = 1.0F;
	private static SoundSource curSource = SoundSource.PLAYERS;
	private static boolean curMob;
	/** 걸음 소리를 바꾸는 몹 고유 간격(바닐라 `nextStep`) — 나머지는 1. */
	private static final Map<String, Double> STRIDES = Map.of("minecraft:turtle", 0.15, "minecraft:strider", 0.6, "minecraft:warden", 0.55);
	/** 바닐라가 걸음 함수를 부르는 행동 — 몹은 이 행동에서만 바닐라 걸음을 낸다. */
	private static final java.util.Set<String> MOVING = java.util.Set.of("step", "run", "stairs_up", "stairs_down", "climb", "crawl");

	private enum Mode {
		NORMAL, MUFFLED, THROUGH
	}

	/** 발 하나가 밟은 것 — 주 소리 블록 · 겹침 아래(먹먹) · 지나감 블록 · 땅. */
	private record Foot(BlockState primary, BlockState bottom, BlockState through, BlockState ground, BlockPos groundPos) {
		/** 두 발이 같은 블록들을 밟았나(자리는 안 본다) — 같으면 한 번만 낸다(D28). */
		boolean sameBlocks(Foot o) {
			return this.primary == o.primary && this.bottom == o.bottom && this.through == o.through && this.ground == o.ground;
		}
	}

	private static final class Tracked {
		final StepTracker steps = new StepTracker();
		boolean feetInit;
		String feetKey;
		BlockState feetState;
		int lastChimeTick = Integer.MIN_VALUE / 2;
		float chime;
		double lx = Double.NaN;
		double lz;
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

	/** 이 엔진이 맡는 엔티티인가 — 플레이어는 늘 · 몹은 바닐라가 걸음 소리를 내는 몹이고 `locomotion: none` 이 아닐 때. */
	private static boolean takes(LivingEntity e) {
		SoundRules.EntityLine rule = Rules.snapshot().sound.entity(typeId(e));
		if (rule != null && "none".equals(rule.locomotion())) {
			return false;
		}
		return e instanceof Player || ((kr.asmbl.block_custom_rules.mixin.EntityStepAccess) e).bcr$movementEmission().emitsSounds();
	}

	private static String typeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
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
			List<LivingEntity> near = new ArrayList<>();
			EntityTracker.forEach(level, e -> {
				if (e instanceof LivingEntity l && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand) && l.distanceToSqr(cam) <= RANGE * RANGE) {
					near.add(l);
				}
			});
			// 플레이어 먼저 · 가까운 순(D24)
			near.sort(Comparator.comparing((LivingEntity l) -> !(l instanceof Player)).thenComparingDouble(l -> l.distanceToSqr(cam)));
			IntOpenHashSet live = new IntOpenHashSet();
			int n = 0;
			for (LivingEntity p : near) {
				if (n >= MAX) {
					break;
				}
				if (!eligible(p) || !takes(p)) {
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

	private static void tickOne(Minecraft mc, ClientLevel level, LivingEntity p, Tracked t, Vec3 cam) {
		boolean mob = !(p instanceof Player);
		SoundRules.EntityLine rule = Rules.snapshot().sound.entity(typeId(p));
		boolean quad = rule != null && "quadruped".equals(rule.locomotion());
		float size = rule != null && Boolean.TRUE.equals(rule.sizeScaling()) ? (float) StepTracker.sizeFactor(p.getScale()) : 1.0F;
		curSize = size <= 0.05F ? 1.0F : size;
		curSource = mob ? p.getSoundSource() : SoundSource.PLAYERS;
		curMob = mob;
		boolean sneaking = !mob && p.isShiftKeyDown();
		// 달리기 — 플레이어는 달리기 표시 · 몹은 위치 차 속도² > 0.022(PF)
		boolean sprint;
		if (mob) {
			double dx = Double.isNaN(t.lx) ? 0.0 : p.getX() - t.lx;
			double dz = Double.isNaN(t.lx) ? 0.0 : p.getZ() - t.lz;
			sprint = dx * dx + dz * dz > 0.022;
		} else {
			sprint = p.isSprinting();
		}
		t.lx = p.getX();
		t.lz = p.getZ();
		StepTracker.Input in = new StepTracker.Input(p.getX(), p.getY(), p.getZ(), p.onGround(), p.onClimbable(), sprint, sneaking,
				!mob && p.isVisuallyCrawling(), p == mc.player, STRIDES.getOrDefault(typeId(p), 1.0), quad, curSize);
		List<StepTracker.Event> events = t.steps.tick(in);

		// 입장 · 퇴장 — 발 칸의 소리 종류가 바뀔 때(D29)
		BlockPos onPos = p.getOnPosLegacy();
		BlockPos feetPos = onPos.above();
		if (level.hasChunkAt(feetPos)) {
			BlockState feet = level.getBlockState(feetPos);
			String key = feetKey(feet);
			if (!t.feetInit) {
				t.feetInit = true;
			} else if (!Objects.equals(key, t.feetKey)) {
				String sneak = sneaking ? "_sneak" : "";
				if (t.feetKey != null) {
					Map<String, BlockState> spots = Map.of("exit", t.feetState);
					playOne(mc, level, p, t.feetState, "exit" + sneak, Mode.THROUGH, false, cam, mixFor(level, p, spots, "exit", sneaking, onPos), "exit");
				}
				if (key != null) {
					Map<String, BlockState> spots = Map.of("enter", feet);
					playOne(mc, level, p, feet, "enter" + sneak, Mode.THROUGH, false, cam, mixFor(level, p, spots, "enter", sneaking, onPos), "enter");
				}
			}
			t.feetKey = key;
			t.feetState = feet;
		}

		for (StepTracker.Event ev : events) {
			if (ev.feet() == 2) {
				Foot l = foot(level, p, false, ev.y());
				Foot r = foot(level, p, true, ev.y());
				if (l != null && r != null && l.sameBlocks(r)) {
					play(mc, level, p, l, ev, cam, sneaking);
				} else {
					play(mc, level, p, l, ev, cam, sneaking);
					play(mc, level, p, r, ev, cam, sneaking);
				}
			} else {
				Foot f = foot(level, p, t.steps.nextFoot(), ev.y());
				MixResult mix = play(mc, level, p, f, ev, cam, sneaking);
				if (mob) {
					mobVanilla(mc, p, f, ev, cam, mix);
				} else if (f != null && !StepTracker.isSneak(ev.action()) && VanillaSteps.crystal(f.ground.getBlock())) {
					chime(mc, level, p, t, f.ground, cam, mix);
				}
			}
		}
	}

	/** 몹 바닐라 걸음(D32) — 움직이는 걸음에서만 그 몹의 바닐라 함수를 부른다(몹 전용 소리 · 말 질주 · 종소리 · 안쪽 블록 그대로). 채널 `mob`. */
	private static void mobVanilla(Minecraft mc, LivingEntity p, Foot f, StepTracker.Event ev, Vec3 cam, MixResult mix) {
		if (f == null || !MOVING.contains(ev.action()) || mix.volume("mob") <= 0.0F || p.isSilent()) {
			return;
		}
		double dist = Math.sqrt(p.distanceToSqr(cam));
		long delay = (dist > FAR ? (long) Math.floor(dist / 2.0) * 50_000_000L : 0L) + Math.max(0, mix.delay("mob")) * 1_000_000L;
		// 몹 소리는 몹 위치(공기)에서 나므로 밟은 블록을 걸어 둬야 블록 걸음이 그 블록 규칙으로 갈아끼워진다
		net.minecraft.world.level.block.state.BlockState prev = kr.asmbl.block_custom_rules.sound.HeldBlock.swap(f.ground);
		try {
			MobPlay.run(mix.volume("mob"), curSize / Math.max(0.01F, mix.pitch("mob")), delay,
					() -> ((kr.asmbl.block_custom_rules.mixin.EntityStepAccess) p).bcr$walkingStepSound(f.groundPos, f.ground));
		} finally {
			kr.asmbl.block_custom_rules.sound.HeldBlock.restore(prev);
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
	private static Foot foot(ClientLevel level, LivingEntity p, boolean right, double y) {
		double[] off = StepTracker.footOffset(p.yBodyRot, right, p.getBbWidth() / 0.6);
		BlockPos gp = BlockPos.containing(p.getX() + off[0], y - 0.2, p.getZ() + off[1]);
		if (!level.hasChunkAt(gp)) {
			return null;
		}
		Foot thin = thinSupport(level, gp, y);
		if (thin != null) {
			return thin;
		}
		BlockState ground = level.getBlockState(gp);
		if (ground.isAir() || ground.getSoundType() == SoundType.EMPTY) {
			gp = Math.abs(y - p.getY()) < 1.0e-6 ? p.getOnPosLegacy() : BlockPos.containing(p.getX(), y - 0.2, p.getZ());
			if (!level.hasChunkAt(gp)) {
				return null;
			}
			ground = level.getBlockState(gp);
			// ⚠ 공기의 소리 종류는 돌이다 — 끝내 공기면 소리를 내지 않는다(점프 때 공기를 «돌» 로 밟던 것)
			if (ground.isAir() || ground.getSoundType() == SoundType.EMPTY) {
				return null;
			}
		}
		BlockPos fp = gp.above();
		BlockState feet = level.getBlockState(fp);
		if (feet.isAir()) {
			return new Foot(ground, null, null, ground, gp);
		}
		Resolved fr = Rules.get(feet);
		if (VanillaSteps.inside(feet.getBlock())) {
			// 안쪽 블록 — 리팩에 그 종류의 through 가 있으면 땅 + 지나감 · 없으면 땅 대신(바닐라)
			return hasName(feet, "through") ? new Foot(ground, null, feet, ground, gp) : new Foot(feet, null, null, ground, gp);
		}
		if (VanillaSteps.combination(feet.getBlock()) || (fr != null && fr.stepAsGround())) {
			return new Foot(feet, ground, null, ground, gp);
		}
		if (feet.getCollisionShape(level, fp).isEmpty()) {
			return new Foot(ground, null, feet, ground, gp);
		}
		return new Foot(ground, null, null, ground, gp);
	}

	/**
	 * 0.2칸보다 얇은 받침(바닥 다락문 0.1875 · 중계기 0.125 등) — 발 높이 0.2칸 아래 칸을 보면 그 받침을 건너뛰어
	 * 아래 칸(공기면 무음)을 밟는다. 그 위 칸 블록의 충돌 윗면이 발 높이 이하면 그 블록이 땅이다.
	 * 겹침 블록(카펫 · 눈) · `step_as: ground` 규칙은 아래 땅과 겹쳐 내야 하니 원래 길로 — 아래가 공기면 겹칠 땅이 없어
	 * 그 블록이 땅(PF 도 허공 카펫 소리를 낸다).
	 */
	private static Foot thinSupport(ClientLevel level, BlockPos gp, double y) {
		BlockPos up = gp.above();
		BlockState s = level.getBlockState(up);
		if (s.isAir() || s.getSoundType() == SoundType.EMPTY || VanillaSteps.inside(s.getBlock())) {
			return null;
		}
		Resolved r = Rules.get(s);
		if (VanillaSteps.combination(s.getBlock()) || (r != null && r.stepAsGround())) {
			BlockState below = level.getBlockState(gp);
			if (!below.isAir() && below.getSoundType() != SoundType.EMPTY) {
				return null;
			}
		}
		net.minecraft.world.phys.shapes.VoxelShape shape = s.getCollisionShape(level, up);
		if (shape.isEmpty() || up.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) > y + 1.0e-3) {
			return null;
		}
		return new Foot(s, null, null, s, up);
	}

	/** 발 하나 — 땅(또는 겹침 위) · 겹침 아래 · 지나감 · 섞기 줄의 더하기. 그 걸음의 섞기 결과를 돌려준다(종소리가 같이 쓴다). */
	private static MixResult play(Minecraft mc, ClientLevel level, LivingEntity p, Foot f, StepTracker.Event ev, Vec3 cam, boolean sneaking) {
		if (f == null) {
			return SoundRules.NO_MIX;
		}
		Map<String, BlockState> spots = new java.util.HashMap<>();
		spots.put("ground", f.ground);
		if (f.bottom != null) {
			spots.put("top", f.primary);
			spots.put("bottom", f.bottom);
		}
		if (f.through != null) {
			spots.put("through", f.through);
		}
		String base = StepTracker.isSneak(ev.action()) ? ev.action().substring(0, ev.action().length() - 6) : ev.action();
		MixResult mix = mixFor(level, p, spots, base, sneaking, f.groundPos);
		playOne(mc, level, p, f.primary, ev.action(), Mode.NORMAL, ev.running(), cam, mix, f.bottom != null ? "top" : "ground");
		if (f.bottom != null) {
			playOne(mc, level, p, f.bottom, ev.action(), Mode.MUFFLED, ev.running(), cam, mix, "bottom");
		}
		// ⚠ 정지(stand)는 멈춘 채 나는 사건이라 발 칸을 지나가지 않는다 — 지나감을 틀면 가만히 있어도 풀숲 소리가 계속 난다
		if (f.through != null && !ev.action().startsWith("stand")) {
			playOne(mc, level, p, f.through, StepTracker.isSneak(ev.action()) ? "through_sneak" : "through", Mode.THROUGH, ev.running(), cam, mix, "through");
		}
		// 신발(D31) — 발 칸 아이템의 장착 소리 이름 + 행동 · 리팩에 있을 때만(그때 땅 반 · 지나감 · 입장 · 퇴장 끔 — 기본 섞기)
		String shoe = footwear(p, ev.action());
		if (shoe != null) {
			playNamed(mc, level, p, shoe, cam, mix, "footwear", f.ground, ev.action() + " · footwear");
		}
		// 섞기 줄의 더하기(D22 · D30) — `<이름>.<행동>` 을 더 튼다(젖음 등) · 리팩에 있을 때만
		for (String add : mix.add()) {
			playNamed(mc, level, p, add + "." + ev.action(), cam, mix, "*", f.ground, ev.action());
		}
		return mix;
	}

	/** 그 걸음의 섞기 결과 — 섞기 줄이 없으면 바로 빈 결과. */
	private static MixResult mixFor(ClientLevel level, LivingEntity p, Map<String, BlockState> spots, String action, boolean sneaking, BlockPos at) {
		SoundRules rules = Rules.snapshot().sound;
		if (rules.mix().isEmpty() && !curMob && footwear(p, action) == null) {
			return SoundRules.NO_MIX;
		}
		Map<String, StateView> views = new java.util.HashMap<>();
		spots.forEach((k, v) -> views.put(k, McWorldProbe.view(v)));
		// 위치 · 날씨 조건의 기준은 발 칸(땅 위 칸) — 땅 블록 자리는 하늘이 안 보인다
		BlockPos feet = at.above();
		return rules.mix(new SoundRules.MixContext(views, typeId(p), action, sneaking, footwear(p, action) != null, new McWorldProbe(level), feet.getX(),
				feet.getY(), feet.getZ(), curMob));
	}

	/**
	 * 신발 소리 이름(D19 · D31) — 발 칸 아이템 `equippable` 의 장착 소리 이름 + `.<행동>`(행동 부모를 따라) · 없으면 그 이름을 종류로 등록한 부모 종류.
	 * 리팩에 없으면 null — 신발 소리 · 땅 반은 이름이 있을 때만.
	 */
	static String footwear(LivingEntity p, String action) {
		net.minecraft.world.item.ItemStack feet = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
		if (feet.isEmpty()) {
			return null;
		}
		net.minecraft.world.item.equipment.Equippable eq = feet.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (eq == null) {
			return null;
		}
		String id = eq.equipSound().unwrapKey().map(k -> k.location().toString()).orElse(null);
		if (id == null) {
			return null;
		}
		Rules.Snapshot snap = Rules.snapshot();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (String a = action; a != null && !a.isEmpty() && seen.add(a); a = StepTracker.PARENTS_WALK.get(a)) {
			if (named(id + "." + a)) {
				return id + "." + a;
			}
		}
		kr.asmbl.block_custom_rules.sound.SoundCategories.Category k = snap.categories.get(id);
		return k == null || k.parent() == null ? null : snap.categories.find(k.parent(), action, StepTracker.PARENTS_WALK, FootstepEngine::named);
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

	/** 이름이 있나 — 겹 소리(`layers`) 또는 리팩 · 바닐라 `sounds.json`(D15 의 차례). */
	public static boolean named(String name) {
		return Rules.snapshot().sound.hasLayers(name) || SoundHub.defined(name);
	}

	private static boolean hasName(BlockState s, String action) {
		String cat = category(s);
		return cat != null && Rules.snapshot().categories.find(cat, action, StepTracker.PARENTS_WALK, FootstepEngine::named) != null;
	}

	/** 소리 하나 — 종류 › 행동 › 부모 › 끝내 없으면 바닐라 걸음(웅크림 · 지나감 · 입장 · 퇴장은 무음). 섞기 채널 배율 · 지연을 곱한다. */
	private static void playOne(Minecraft mc, ClientLevel level, LivingEntity p, BlockState state, String action, Mode mode, boolean running, Vec3 cam,
			MixResult mix, String channel) {
		if (mix.volume(channel) <= 0.0F) {
			return;
		}
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
		String name = snap.categories.find(cat, action, parents, FootstepEngine::named);
		SoundType t = state.getSoundType();
		String note = action + (mode == Mode.MUFFLED ? " · muffled" : "");
		if (name == null) {
			// 웅크림 · 지나감 · 입장 · 퇴장 · 정지는 리팩에 이름이 있을 때만(바닐라에 없는 소리)
			if (mode == Mode.THROUGH || StepTracker.isSneak(action) || action.startsWith("stand")) {
				return;
			}
			SoundEvent ev = t.getStepSound();
			if (ev == SoundEvents.EMPTY) {
				return;
			}
			vanillaPlay(mc, level, p, ev, t, mode, action, cam, mix, channel, state, note);
			return;
		}
		if (!snap.sound.hasLayers(name) && SoundHub.registered(name)) {
			vanillaPlay(mc, level, p, SoundHub.event(name), t, mode, action, cam, mix, channel, state, note);
			return;
		}
		// 리팩 이름 · 겹 소리 — 리팩 정의대로(D16 · D21) · 겹침 아래만 바닐라 비율(0.05 / 0.15 · 높낮이 0.8)
		float mv = mode == Mode.MUFFLED ? 1.0F / 3.0F : 1.0F;
		float mp = mode == Mode.MUFFLED ? 0.8F : 1.0F;
		for (SoundRules.Play pl : snap.sound.expand(name, level.random::nextDouble)) {
			emit(mc, level, p, SoundHub.event(pl.name()), pl.volume() * mv * mix.volume(channel), pl.pitch() * mp * mix.pitch(channel),
					pl.delayMs() + mix.delay(channel), cam, state, note);
		}
	}

	/** 바닐라답게 — 종류 음량 × 0.15(먹먹 0.05 · 높낮이 0.8) · `wander` 반 · 섞기 배율. */
	private static void vanillaPlay(Minecraft mc, ClientLevel level, LivingEntity p, SoundEvent ev, SoundType t, Mode mode, String action, Vec3 cam,
			MixResult mix, String channel, BlockState state, String note) {
		float vol = t.getVolume() * (mode == Mode.MUFFLED ? 0.05F : 0.15F) * StepTracker.vanillaFactor(action) * mix.volume(channel);
		float pitch = t.getPitch() * (mode == Mode.MUFFLED ? 0.8F : 1.0F) * mix.pitch(channel);
		emit(mc, level, p, ev, vol, pitch, mix.delay(channel), cam, state, note);
	}

	/** 섞기 줄의 더하기 이름 — 겹 소리 · 리팩에 있을 때만 리팩 정의대로. */
	private static void playNamed(Minecraft mc, ClientLevel level, LivingEntity p, String name, Vec3 cam, MixResult mix, String channel, BlockState state,
			String action) {
		SoundRules rules = Rules.snapshot().sound;
		if (!named(name)) {
			return;
		}
		for (SoundRules.Play pl : rules.expand(name, level.random::nextDouble)) {
			emit(mc, level, p, SoundHub.event(pl.name()), pl.volume() * mix.volume(channel), pl.pitch() * mix.pitch(channel), pl.delayMs() + mix.delay(channel),
					cam, state, action + " · add");
		}
	}

	/** 자수정 종소리(바닐라 식 · 20틱마다 · 세기 × 0.997^틱 + 0.07). 종류를 바꿨으면 `<종류>.chime` · 없으면 바닐라. */
	private static void chime(Minecraft mc, ClientLevel level, LivingEntity p, Tracked t, BlockState ground, Vec3 cam, MixResult mix) {
		if (p.tickCount < t.lastChimeTick + 20 || mix.volume("chime") <= 0.0F) {
			return;
		}
		t.chime = Math.min(1.0F, t.chime * (float) Math.pow(0.997, p.tickCount - t.lastChimeTick) + 0.07F);
		t.lastChimeTick = p.tickCount;
		String cat = category(ground);
		String name = cat == null ? null : Rules.snapshot().categories.find(cat, "chime", Map.of(), FootstepEngine::named);
		if (name != null && Rules.snapshot().sound.hasLayers(name)) {
			playNamed(mc, level, p, name, cam, mix, "chime", ground, "chime");
			return;
		}
		boolean vanilla = name == null || SoundHub.registered(name);
		SoundEvent ev = name == null ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundHub.event(name);
		float vol = (vanilla ? 0.1F + t.chime * 1.2F : Rules.snapshot().sound.volume.roll(level.random::nextDouble)) * mix.volume("chime");
		float pitch = (vanilla ? 0.5F + t.chime * level.random.nextFloat() * 1.2F : Rules.snapshot().sound.pitch.roll(level.random::nextDouble))
				* mix.pitch("chime");
		emit(mc, level, p, ev, vol, pitch, mix.delay("chime"), cam, ground, "chime");
	}

	/** 튼다 — 섞기 · 겹 소리 지연 + 카메라에서 10칸 넘으면 ⌊거리 / 2⌋ 틱(D34). 지연 줄에는 소리 재료만 든다. */
	private static void emit(Minecraft mc, ClientLevel level, LivingEntity p, SoundEvent ev, float vol, float pitch, int delayMs, Vec3 cam, BlockState block,
			String action) {
		if (vol <= 0.0F) {
			return;
		}
		if (SoundDebugger.active()) {
			String cat = category(block);
			SoundDebugger.record(SoundDebugger.Origin.ENGINE, p.getX(), p.getY(), p.getZ(), ev.location().toString(), null, action,
					SoundHub.describe(block) + (cat == null ? "" : " (" + cat + ")"), false);
		}
		SimpleSoundInstance si = new SimpleSoundInstance(ev, curSource, Math.min(vol, VOLUME_CAP), pitch / curSize,
				RandomSource.create(level.random.nextLong()), p.getX(), p.getY(), p.getZ());
		double dist = Math.sqrt(p.distanceToSqr(cam));
		long delay = Math.max(0, delayMs) * 1_000_000L;
		if (p != mc.player && dist > FAR) {
			delay += (long) Math.floor(dist / 2.0) * 50_000_000L;
		}
		if (delay > 0) {
			DELAYED.add(System.nanoTime(), delay, () -> mc.getSoundManager().play(si));
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
		if (HANDLED.isEmpty() || SoundNames.isMarkerSeed(seed)) {
			return false;
		}
		if (source != SoundSource.PLAYERS) {
			return blocksMobPacket(level, x, y, z, id, source);
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

	/**
	 * 몹 걸음 패킷 — 1칸 안에 이 엔진이 맡는 몹(같은 출처)이 있고 이름이 걸음 무리(`.step` · 말 `gallop` · `breathe` · 종소리)면 막는다.
	 * 몹 걸음 음량은 몹마다 달라(0.15 ~ 10) 식으로 견주지 않는다.
	 */
	private static boolean blocksMobPacket(ClientLevel level, double x, double y, double z, String id, SoundSource source) {
		if (!(id.contains(".step") || id.endsWith(".gallop") || id.endsWith("horse.breathe") || CHIME.equals(id))) {
			return false;
		}
		for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(x - 1.0, y - 1.0, z - 1.0, x + 1.0, y + 1.0, z + 1.0),
				e -> e instanceof LivingEntity && !(e instanceof Player))) {
			if (HANDLED.contains(e.getId()) && e.getSoundSource() == source) {
				return true;
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
