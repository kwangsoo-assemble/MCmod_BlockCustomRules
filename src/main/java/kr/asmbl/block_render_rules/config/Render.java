package kr.asmbl.block_render_rules.config;

import java.util.Locale;

/**
 * 텍스처 그리는 방식. 바닐라 청크 계층으로 옮기면 SOLID · CUTOUT · TRANSLUCENT.
 * 투명 허용은 밉맵 없는 CUTOUT — 밉맵을 쓰면 멀리서 구멍이 메워진다(Research §2 ②).
 */
public enum Render {
	/** 바닐라(와 셰이더팩)가 정한 그대로. */
	DEFAULT("default"),
	/** 투명 픽셀까지 메워 그린다. */
	SOLID("solid"),
	/** 투명 픽셀은 뚫고 반투명은 없다(잔디 · 꽃 꼴). */
	CUTOUT("cutout"),
	/** 알파를 그대로 섞는다(색유리 꼴). */
	TRANSLUCENT("translucent");

	public final String id;

	Render(String id) {
		this.id = id;
	}

	public Render next() {
		Render[] all = values();
		return all[(this.ordinal() + 1) % all.length];
	}

	public static Render parse(String s) {
		if (s == null) {
			return DEFAULT;
		}
		String t = s.trim().toLowerCase(Locale.ROOT);
		for (Render r : values()) {
			if (r.id.equals(t)) {
				return r;
			}
		}
		return null;
	}
}
