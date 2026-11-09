package kr.asmbl.block_custom_rules.debug;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.Iterator;
import kr.asmbl.block_custom_rules.world.Generation;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * 월드 소리 디버거(D37 · D43) — 소리가 난 자리에 얼굴 쪽 글자 3줄(이름 · 어디서 · 바뀐 길)을 3초 동안 흐려지게 띄운다.
 * 켠 사람 화면만(클라 안에서 그린다 · 엔티티를 소환하지 않는다) · 기본 꺼짐 · 채팅에 아무것도 안 쓴다.
 * 안정성(D43): 글자는 «계산된 글 · 자리 · 색 · 세대 · 시각» 만 든다(월드 · 엔티티 · 블록 참조 없음) · 세대가 다르면 지운다 ·
 * 반지름(기본 32 · 최대 64) 밖 · 안 읽힌 청크 자리는 그리지 않고 3초 뒤 지운다 · 최대 64개(넘으면 오래된 것부터) ·
 * 그리기 중 예외는 그 프레임만 끈다 · 소리 기록과 그리기는 모두 렌더 스레드.
 */
public final class SoundDebugger {
	private static final Logger LOG = LogUtils.getLogger();
	public static final int DEFAULT_RADIUS = 32;
	public static final int MAX_RADIUS = 64;
	public static final int MAX_ENTRIES = 64;
	private static final long LIFE = 3_000_000_000L;
	private static final int GREEN = 0x66FF66;
	private static final int WHITE = 0xFFFFFF;
	private static final int RED = 0xFF6666;
	private static final int YELLOW = 0xFFDD55;

	/** 무엇을 보이나 — 블록 소리만 · 전부 · 막은 것만. */
	public enum Mode {
		BLOCKS("blocks"), ALL("all"), BLOCKED("blocked");

		public final String id;

		Mode(String id) {
			this.id = id;
		}

		public static Mode parse(String s) {
			for (Mode m : values()) {
				if (m.id.equals(s)) {
					return m;
				}
			}
			return null;
		}
	}

	/** 어디서 온 소리인가. */
	public enum Origin {
		CLIENT, PACKET, ENGINE
	}

	private record Entry(String[] lines, double x, double y, double z, int color, int generation, long created) {
	}

	private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();
	private static boolean on;
	private static int radius = DEFAULT_RADIUS;
	private static Mode mode = Mode.BLOCKS;
	private static MultiBufferSource.BufferSource buffer;
	private static boolean warned;

	private SoundDebugger() {
	}

	public static boolean active() {
		return on;
	}

	public static int radius() {
		return radius;
	}

	public static Mode mode() {
		return mode;
	}

	public static void set(boolean enable, int r, Mode m) {
		on = enable;
		radius = Math.max(1, Math.min(MAX_RADIUS, r));
		mode = m;
		ENTRIES.clear();
	}

	/** 세대가 바뀔 때(차원 이동 · 리스폰 · 나가기) — 글자를 다 지운다. */
	public static void reset() {
		ENTRIES.clear();
	}

	/**
	 * 기록 — 켜져 있을 때만 부른다.
	 *
	 * @param heard   실제로 난 이름(막았으면 원래 이름)
	 * @param from    원래 이름(바뀌지 않았으면 null)
	 * @param why     바뀌지 않은 까닭 · 엔진 행동 등 셋째 줄
	 * @param block   그 소리의 블록 · 종류 설명(없으면 null)
	 */
	public static void record(Origin origin, double x, double y, double z, String heard, String from, String why, String block, boolean blocked) {
		if (!on) {
			return;
		}
		boolean blockSound = heard.contains(":block.") || (from != null && from.contains(":block.")) || origin == Origin.ENGINE;
		if (mode == Mode.BLOCKED && !blocked || mode == Mode.BLOCKS && !blockSound) {
			return;
		}
		String where = Component.translatable("bcr.debug.origin." + origin.name().toLowerCase()).getString() + (block == null ? "" : " · " + block);
		String path;
		int color;
		if (blocked) {
			path = Component.translatable("bcr.debug.blocked").getString();
			color = RED;
		} else if (from != null) {
			path = "← " + from;
			color = GREEN;
		} else {
			path = why == null ? "" : why;
			color = origin == Origin.ENGINE ? YELLOW : WHITE;
		}
		ENTRIES.addLast(new Entry(new String[] { heard, where, path }, x, y, z, color, Generation.current(), System.nanoTime()));
		while (ENTRIES.size() > MAX_ENTRIES) {
			ENTRIES.removeFirst();
		}
	}

	/** 월드 그리기(엔티티 뒤) — 예외는 그 프레임만 끈다. */
	public static void render(WorldRenderContext ctx) {
		if (!on || ENTRIES.isEmpty()) {
			return;
		}
		try {
			draw(ctx);
		} catch (RuntimeException | LinkageError e) {
			if (!warned) {
				warned = true;
				LOG.warn("[block_custom_rules] 소리 디버거 그리기 예외 — 그 프레임은 건너뛴다(이 알림은 한 번만)", e);
			}
		}
	}

	private static void draw(WorldRenderContext ctx) {
		ClientLevel level = ctx.world();
		if (level == null) {
			ENTRIES.clear();
			return;
		}
		int gen = Generation.current();
		long now = System.nanoTime();
		Iterator<Entry> it = ENTRIES.iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			if (e.generation != gen || now - e.created > LIFE) {
				it.remove();
			}
		}
		if (ENTRIES.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		Vec3 cam = ctx.camera().getPosition();
		PoseStack ps = ctx.matrixStack() != null ? ctx.matrixStack() : new PoseStack();
		if (buffer == null) {
			buffer = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
		}
		double r2 = (double) radius * radius;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (Entry e : ENTRIES) {
			double dx = e.x - cam.x;
			double dy = e.y - cam.y;
			double dz = e.z - cam.z;
			if (dx * dx + dy * dy + dz * dz > r2) {
				continue;
			}
			at.set(e.x, e.y, e.z);
			if (!level.hasChunkAt(at)) {
				continue;
			}
			float life = 1.0F - (float) (now - e.created) / LIFE;
			int alpha = Math.max(0x30, (int) (255 * life));
			ps.pushPose();
			ps.translate(dx, dy + 0.6, dz);
			ps.mulPose(ctx.camera().rotation());
			ps.scale(0.02F, -0.02F, 0.02F);
			Matrix4f m = ps.last().pose();
			for (int i = 0; i < e.lines.length; i++) {
				String line = e.lines[i];
				if (line.isEmpty()) {
					continue;
				}
				int color = (alpha << 24) | (i == 0 ? e.color : WHITE);
				font.drawInBatch(line, -font.width(line) / 2.0F, i * 10.0F - 15.0F, color, false, m, buffer, Font.DisplayMode.SEE_THROUGH,
						(alpha / 3) << 24, LightTexture.FULL_BRIGHT);
			}
			ps.popPose();
		}
		buffer.endBatch();
	}
}
