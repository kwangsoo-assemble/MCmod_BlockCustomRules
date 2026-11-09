package kr.asmbl.block_custom_rules.sway;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.asmbl.block_custom_rules.config.OptionsFile;
import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.rules.Resolved;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 휘는 블록 — 매 프레임 판정(히트박스가 걸친 칸 · 미는 벡터 합 · 블렌딩) · 기울기가 바뀌면 그 청크 구역을 다시 짓게 하고,
 * 청크를 지을 때 정점을 비튼다(바닐라 `SectionCompilerMixin` · Sodium `SodiumBlockRendererMixin`).
 * 블록이 지형 메시에 남아 있어야 셰이더팩의 흔들림 · 그림자가 그 위에 더해진다(M56 🎬 — 따로 그리면 둘 다 빠졌다).
 * 판정 · 블렌딩 상태는 렌더 스레드만 만진다. 청크 스레드와 나누는 것은 «자리 → 비틀기 값» 표 하나뿐이다.
 * 두 칸 블록(긴 잔디 · 큰 꽃 · 긴 해초 — `DOUBLE_BLOCK_HALF`)은 아래 칸 바닥을 축으로 한 기둥으로 다룬다(Plan D18).
 */
public final class SwayEngine {
	/** 엔티티를 찾는 반지름(카메라에서 · 칸). */
	private static final double RANGE = 8.0;
	/** 세기 1.0 일 때 블록 높이 1 당 옆으로 밀리는 양(tan 15°) — 🎬 에서 SWAY 1.0 과 맞춘다(Plan D11). */
	private static final float SHEAR_PER_STRENGTH = 0.268F;
	/** 기울기가 이보다 작고 목표가 0 이면 똑바로 세우고 지운다. */
	private static final double REST = 0.01;
	/** 비틀기 값이 이만큼 바뀌어야 청크를 다시 짓는다(약 0.2°) — 너무 잦은 다시 짓기를 막는다. */
	private static final float MARK_EPS = 0.003F;

	/** 자리(기둥이면 두 칸 각각) → {kx, kz, 기둥 안 높이(0 · 1)} — 청크 짓는 스레드가 읽는다. */
	private static final Map<Long, float[]> SHEAR = new ConcurrentHashMap<>();
	private static final Long2ObjectOpenHashMap<Tilt> TILTS = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<double[]> SUMS = new Long2ObjectOpenHashMap<>();
	private static final LongOpenHashSet SEEN = new LongOpenHashSet();
	private static final double[] TMP = new double[2];
	private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();

	private static ClientLevel lastLevel;
	private static long lastNanos;

	private SwayEngine() {
	}

	private static final class Tilt {
		final long anchor;
		final int height; // 1 · 2(두 칸 기둥)
		double cx;
		double cz;
		double tx;
		double tz;
		float markedKx; // 청크에 마지막으로 넘긴 값
		float markedKz;

		Tilt(long anchor, int height) {
			this.anchor = anchor;
			this.height = height;
		}
	}

	/** 청크 스레드 — 그 자리를 비틀 값 · 없으면 null. {kx, kz, 기둥 안 높이} */
	public static float[] shear(BlockPos pos) {
		return SHEAR.isEmpty() ? null : SHEAR.get(pos.asLong());
	}

	/** 월드 세대가 바뀔 때(접속 · 차원 이동 · 나감) — 다 버린다. 새 월드의 청크는 처음부터 지어진다. */
	public static void reset() {
		TILTS.clear();
		SUMS.clear();
		SHEAR.clear();
		lastLevel = null;
	}

	/** `renderLevel` 머리 — 판정 · 블렌딩 · 바뀐 기둥만 청크 다시 짓기. */
	public static void update() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		long now = System.nanoTime();
		if (level != lastLevel) {
			// 월드 · 차원이 바뀌면 다 버린다 — 새 월드의 청크는 처음부터 지어진다
			TILTS.clear();
			SHEAR.clear();
			lastLevel = level;
			lastNanos = now;
		}
		double dt = Math.min(0.1, (now - lastNanos) / 1.0e9);
		lastNanos = now;
		if (level == null) {
			return;
		}

		if (Rules.anySway()) {
			collect(level, mc.gameRenderer.getMainCamera().getPosition());
		} else {
			SUMS.clear();
		}

		for (Long2ObjectMap.Entry<double[]> e : SUMS.long2ObjectEntrySet()) {
			Tilt t = TILTS.get(e.getLongKey());
			double[] s = e.getValue();
			if (t == null) {
				t = new Tilt(e.getLongKey(), (int) s[3]);
				TILTS.put(t.anchor, t);
			}
			SwayMath.target(s[0], s[1], s[2], TMP);
			t.tx = TMP[0];
			t.tz = TMP[1];
		}

		double alpha = SwayMath.blendAlpha(dt, OptionsFile.blendSeconds());
		Iterator<Tilt> it = TILTS.values().iterator();
		while (it.hasNext()) {
			Tilt t = it.next();
			if (!SUMS.containsKey(t.anchor)) {
				t.tx = 0.0;
				t.tz = 0.0;
			}
			t.cx += (t.tx - t.cx) * alpha;
			t.cz += (t.tz - t.cz) * alpha;
			boolean gone = !stillSways(level, t);
			if (gone || (t.tx == 0.0 && t.tz == 0.0 && t.cx * t.cx + t.cz * t.cz < REST * REST)) {
				// 똑바로 세운 모습으로 한 번 더 짓고 지운다
				clearShear(t);
				markDirty(t, mc);
				it.remove();
				continue;
			}
			float kx = (float) t.cx * SHEAR_PER_STRENGTH;
			float kz = (float) t.cz * SHEAR_PER_STRENGTH;
			if (Math.abs(kx - t.markedKx) >= MARK_EPS || Math.abs(kz - t.markedKz) >= MARK_EPS) {
				t.markedKx = kx;
				t.markedKz = kz;
				for (int i = 0; i < t.height; i++) {
					SHEAR.put(BlockPos.offset(t.anchor, 0, i, 0), new float[] { kx, kz, i });
				}
				markDirty(t, mc);
			}
		}
	}

	/** 엔티티마다 히트박스가 걸친 칸 → 미는 벡터를 기둥(아래 칸) 자리마다 더한다. */
	private static void collect(ClientLevel level, Vec3 cam) {
		SUMS.clear();
		AABB around = new AABB(cam, cam).inflate(RANGE);
		List<Entity> entities = level.getEntities((Entity) null, around, e -> e.isAlive() && !e.isSpectator());
		for (Entity e : entities) {
			AABB box = e.getBoundingBox().inflate(SwayMath.TOUCH_MARGIN, 0.0, SwayMath.TOUCH_MARGIN);
			SEEN.clear();
			int x0 = (int) Math.floor(box.minX);
			int x1 = (int) Math.floor(box.maxX);
			int z0 = (int) Math.floor(box.minZ);
			int z1 = (int) Math.floor(box.maxZ);
			int y0 = (int) Math.floor(box.minY) - 1; // 두 칸 기둥의 아래 칸이 발 밑일 수 있다
			int y1 = (int) Math.floor(box.maxY);
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					for (int y = y0; y <= y1; y++) {
						CURSOR.set(x, y, z);
						BlockState state = level.getBlockState(CURSOR);
						Resolved r = Rules.get(state);
						if (r == null || r.sway() <= 0.0F) {
							continue;
						}
						int anchorY = y;
						int height = 1;
						if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
							height = 2;
							if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
								anchorY = y - 1;
							}
						}
						long anchor = BlockPos.asLong(x, anchorY, z);
						if (!SEEN.add(anchor)) {
							continue; // 같은 엔티티가 기둥 두 칸에 걸쳐도 한 번만
						}
						double top = anchorY + (height - 1) + shapeTop(level, x, anchorY + height - 1, z);
						if (box.minY >= top || box.maxY <= anchorY) {
							continue; // 높이로 안 닿았다(압력판은 발 밑에서만)
						}
						double[] p = SwayMath.push(x, z, box.minX, box.minZ, box.maxX, box.maxZ);
						if (p == null) {
							continue;
						}
						double[] s = SUMS.get(anchor);
						if (s == null) {
							s = new double[] { 0.0, 0.0, r.sway(), height };
							SUMS.put(anchor, s);
						}
						s[0] += p[0];
						s[1] += p[1];
					}
				}
			}
		}
	}

	private static double shapeTop(ClientLevel level, int x, int y, int z) {
		CURSOR.set(x, y, z);
		VoxelShape shape = level.getBlockState(CURSOR).getShape(level, CURSOR);
		return shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
	}

	private static boolean stillSways(ClientLevel level, Tilt t) {
		CURSOR.set(t.anchor);
		Resolved r = Rules.get(level.getBlockState(CURSOR));
		return r != null && r.sway() > 0.0F;
	}

	private static void clearShear(Tilt t) {
		for (int i = 0; i < t.height; i++) {
			SHEAR.remove(BlockPos.offset(t.anchor, 0, i, 0));
		}
	}

	/** Sodium 이 깔려 있으면 이 바닐라 메서드를 자기 다시 짓기로 받는다(Plan §0 ①). */
	private static void markDirty(Tilt t, Minecraft mc) {
		int x = BlockPos.getX(t.anchor);
		int y = BlockPos.getY(t.anchor);
		int z = BlockPos.getZ(t.anchor);
		mc.levelRenderer.setBlocksDirty(x, y, z, x, y + t.height - 1, z);
	}
}
