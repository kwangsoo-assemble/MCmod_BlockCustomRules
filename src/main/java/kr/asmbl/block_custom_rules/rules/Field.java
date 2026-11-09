package kr.asmbl.block_custom_rules.rules;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import kr.asmbl.block_custom_rules.config.Render;

/**
 * `rules` 줄의 값 칸(D10). 값은 셋(D7): 칸 없음 = 규칙 없음 · `"default"` = 아래 층 · 앞 줄 값을 지우고 바닐라로 되돌림 · 값.
 */
public enum Field {
	/** 텍스처 그리는 방식 — {@link Render}(DEFAULT 빼고). */
	RENDER("render"),
	/** 옆 블록 면을 가리지 않기 — Boolean. */
	CULL_PASS("cull_pass"),
	/** 휘기 세기 0 ~ {@link #SWAY_MAX} — Float. */
	SWAY("sway"),
	/** 소리 종류 이름 `ns:종류` — String. */
	SOUND_CATEGORY("sound_category"),
	/** 발 칸의 얇은 블록을 땅으로 볼까 — 지금 값은 `"ground"` 하나. */
	STEP_AS("step_as");

	public static final float SWAY_MAX = 5.0F;
	/** `"default"` — 되돌림. */
	public static final Object RESET = new Object() {
		@Override
		public String toString() {
			return "default";
		}
	};

	public final String key;

	Field(String key) {
		this.key = key;
	}

	public static Field byKey(String key) {
		for (Field f : values()) {
			if (f.key.equals(key)) {
				return f;
			}
		}
		return null;
	}

	/** JSON 값 → 값 · {@link #RESET} · 틀리면 오류를 적고 null(그 줄을 버린다). */
	Object parse(JsonElement e, Origin origin, String path, Problems problems) {
		JsonPrimitive p = e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
		if (p != null && p.isString() && "default".equals(p.getAsString())) {
			return RESET;
		}
		switch (this) {
			case RENDER -> {
				Render r = p != null && p.isString() ? Render.parse(p.getAsString()) : null;
				if (r != null && r != Render.DEFAULT) {
					return r;
				}
			}
			case CULL_PASS -> {
				if (p != null && p.isBoolean()) {
					return p.getAsBoolean();
				}
			}
			case SWAY -> {
				if (p != null && p.isNumber()) {
					float v = p.getAsFloat();
					if (!Float.isFinite(v) || v < 0.0F || v > SWAY_MAX) {
						problems.warn(origin, path, "bcr.problem.out_of_range", this.key, p.getAsString(), 0, SWAY_MAX);
						return Float.isFinite(v) ? Math.max(0.0F, Math.min(SWAY_MAX, v)) : 0.0F;
					}
					return v;
				}
			}
			case SOUND_CATEGORY -> {
				String id = p != null && p.isString() ? Ids.normalize(p.getAsString()) : null;
				if (id != null) {
					return id;
				}
			}
			case STEP_AS -> {
				if (p != null && p.isString() && "ground".equals(p.getAsString())) {
					return "ground";
				}
			}
		}
		problems.error(origin, path, "bcr.problem.bad_value." + this.key, e.toString());
		return null;
	}
}
