package kr.asmbl.block_render_rules.sway;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kr.asmbl.block_render_rules.config.BrrConfig;
import kr.asmbl.block_render_rules.config.Rule;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

/**
 * 휘는 블록 — 매 프레임 판정(히트박스가 걸친 칸 · 미는 벡터 합 · 블렌딩) · 기울어 있는 동안만 청크에서 빼고 따로 그린다.
 * 상태는 렌더 스레드만 만진다. 청크 스레드와 나누는 것은 «청크에서 뺄 자리» 집합 하나뿐이다.
 * 두 칸 블록(긴 잔디 · 큰 꽃 · 긴 해초 — `DOUBLE_BLOCK_HALF`)은 아래 칸을 축으로 한 기둥으로 다룬다(Plan D18).
 */
public final class SwayEngine {
	/** 엔티티를 찾는 반지름(카메라에서 · 칸). */
	private static final double RANGE = 8.0;
	/** 세기 1.0 일 때 위끝이 밀리는 정도(tan 15°) — 🎬 에서 SWAY 1.0 과 맞춘다(Plan D11). */
	private static final float SHEAR_PER_STRENGTH = 0.268F;
	/** 기울기가 이보다 작고 목표가 0 이면 다시 청크로 돌려보낸다. */
	private static final double REST = 0.01;
	/** 청크로 돌려보낸 뒤에도 따로 그리는 시간 — 청크가 다시 지어지기 전에 블록이 사라지지 않게(Plan D12 · R1). */
	private static final long LINGER_NANOS = 200_000_000L;

	/** 청크에서 뺄 자리(기둥이면 두 칸) — 청크 짓는 스레드가 읽는다. */
	private static final Set<Long> HIDDEN = ConcurrentHashMap.newKeySet();
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
		long lingerUntil; // 0 = 아직 청크에서 빠져 있다

		Tilt(long anchor, int height) {
			this.anchor = anchor;
			this.height = height;
		}
	}

	public static boolean isHidden(BlockPos pos) {
		return !HIDDEN.isEmpty() && HIDDEN.contains(pos.asLong());
	}

	/** `renderLevel` 머리 — 판정 · 블렌딩 · 청크에서 빼고 넣기. */
	public static void update() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		long now = System.nanoTime();
		if (level != lastLevel) {
			// 월드 · 차원이 바뀌면 다 버린다 — 새 월드의 청크는 처음부터 지어진다
			TILTS.clear();
			HIDDEN.clear();
			lastLevel = level;
			lastNanos = now;
		}
		double dt = Math.min(0.1, (now - lastNanos) / 1.0e9);
		lastNanos = now;
		if (level == null) {
			return;
		}

		collect(level, mc.gameRenderer.getMainCamera().getPosition());

		double alpha = SwayMath.blendAlpha(dt, BrrConfig.blendSeconds());
		for (Long2ObjectMap.Entry<double[]> e : SUMS.long2ObjectEntrySet()) {
			Tilt t = TILTS.get(e.getLongKey());
			double[] s = e.getValue();
			if (t == null) {
				t = new Tilt(e.getLongKey(), (int) s[3]);
				TILTS.put(t.anchor, t);
				hide(t, mc);
			} else if (t.lingerUntil != 0L) {
				// 돌려보내는 중에 다시 밀렸다
				t.lingerUntil = 0L;
				hide(t, mc);
			}
			SwayMath.target(s[0], s[1], s[2], TMP);
			t.tx = TMP[0];
			t.tz = TMP[1];
		}

		Iterator<Tilt> it = TILTS.values().iterator();
		while (it.hasNext()) {
			Tilt t = it.next();
			if (!SUMS.containsKey(t.anchor)) {
				t.tx = 0.0;
				t.tz = 0.0;
			}
			if (t.lingerUntil != 0L) {
				if (now >= t.lingerUntil) {
					it.remove();
				}
				continue;
			}
			t.cx += (t.tx - t.cx) * alpha;
			t.cz += (t.tz - t.cz) * alpha;
			boolean gone = !stillSways(level, t);
			if (gone || (t.tx == 0.0 && t.tz == 0.0 && t.cx * t.cx + t.cz * t.cz < REST * REST)) {
				t.cx = 0.0;
				t.cz = 0.0;
				unhide(t, mc);
				if (gone) {
					it.remove();
				} else {
					t.lingerUntil = now + LINGER_NANOS;
				}
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
						Rule r = BrrConfig.rule(state.getBlock());
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
		Rule r = BrrConfig.rule(level.getBlockState(CURSOR).getBlock());
		return r != null && r.sway() > 0.0F;
	}

	private static void hide(Tilt t, Minecraft mc) {
		for (int i = 0; i < t.height; i++) {
			HIDDEN.add(BlockPos.offset(t.anchor, 0, i, 0));
		}
		markDirty(t, mc);
	}

	private static void unhide(Tilt t, Minecraft mc) {
		for (int i = 0; i < t.height; i++) {
			HIDDEN.remove(BlockPos.offset(t.anchor, 0, i, 0));
		}
		markDirty(t, mc);
	}

	/** Sodium 이 깔려 있으면 이 바닐라 메서드를 자기 다시 짓기로 받는다(Plan §0 ①). */
	private static void markDirty(Tilt t, Minecraft mc) {
		int x = BlockPos.getX(t.anchor);
		int y = BlockPos.getY(t.anchor);
		int z = BlockPos.getZ(t.anchor);
		mc.levelRenderer.setBlocksDirty(x, y, z, x, y + t.height - 1, z);
	}

	/** `renderBlockEntities` 끝 — 기운 블록을 따로 그린다(피스톤 블록과 같은 계층 · 레벨을 줘 AO · 색 · 빛을 지형처럼). */
	public static void render(MultiBufferSource.BufferSource buffers, Camera camera) {
		if (TILTS.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
		Vec3 cam = camera.getPosition();
		PoseStack ps = new PoseStack();
		Matrix4f shear = new Matrix4f();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Tilt t : TILTS.values()) {
			int ax = BlockPos.getX(t.anchor);
			int ay = BlockPos.getY(t.anchor);
			int az = BlockPos.getZ(t.anchor);
			// 아래 면은 고정 · 위로 갈수록 기운다 — x' = x + kx·y, z' = z + kz·y
			shear.identity();
			shear.m10((float) t.cx * SHEAR_PER_STRENGTH);
			shear.m12((float) t.cz * SHEAR_PER_STRENGTH);
			for (int i = 0; i < t.height; i++) {
				pos.set(ax, ay + i, az);
				BlockState state = level.getBlockState(pos);
				if (state.isAir()) {
					continue;
				}
				ps.pushPose();
				ps.translate(ax - cam.x, ay - cam.y, az - cam.z);
				ps.mulPose(shear);
				ps.translate(0.0F, (float) i, 0.0F); // 위 칸은 기둥 축(아래 칸 바닥)에서 이어진다
				VertexConsumer vc = buffers.getBuffer(ItemBlockRenderTypes.getMovingBlockRenderType(state));
				List<BlockModelPart> parts = dispatcher.getBlockModel(state).collectParts(RandomSource.create(state.getSeed(pos)));
				dispatcher.renderBatched(state, pos, level, ps, vc, false, parts);
				ps.popPose();
			}
		}
	}
}
