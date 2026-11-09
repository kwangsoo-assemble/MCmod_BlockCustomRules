package kr.asmbl.block_custom_rules.rules;

import kr.asmbl.block_custom_rules.config.Render;

/**
 * 블록 상태 하나의 최종 규칙 + 칸마다 출처(D11). 바뀌지 않는다.
 * 값 getter 는 «규칙 없음» 과 «되돌림» 을 둘 다 null 로 준다 — 둘 다 바닐라대로라는 뜻이다. 출처는 되돌림도 남긴다.
 */
public final class Resolved {
	private final Object[] values;
	private final Provenance[] from;

	Resolved(Object[] values, Provenance[] from) {
		this.values = values.clone();
		this.from = from.clone();
	}

	/** 적은 그대로 — 칸 없음 null · 되돌림 {@link Field#RESET}. */
	public Object raw(Field f) {
		return this.values[f.ordinal()];
	}

	/** 마지막으로 이 칸을 적은 자리 · 아무도 안 적었으면 null. */
	public Provenance from(Field f) {
		return this.from[f.ordinal()];
	}

	private Object value(Field f) {
		Object v = this.values[f.ordinal()];
		return v == Field.RESET ? null : v;
	}

	public Render render() {
		return (Render) value(Field.RENDER);
	}

	public boolean cullPass() {
		return Boolean.TRUE.equals(value(Field.CULL_PASS));
	}

	/** 휘기 세기 · 규칙 없으면 0. */
	public float sway() {
		Object v = value(Field.SWAY);
		return v == null ? 0.0F : (Float) v;
	}

	public String soundCategory() {
		return (String) value(Field.SOUND_CATEGORY);
	}

	public boolean stepAsGround() {
		return "ground".equals(value(Field.STEP_AS));
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("{");
		for (Field f : Field.values()) {
			if (this.values[f.ordinal()] != null) {
				if (sb.length() > 1) {
					sb.append(", ");
				}
				sb.append(f.key).append('=').append(this.values[f.ordinal()]);
			}
		}
		return sb.append('}').toString();
	}
}
