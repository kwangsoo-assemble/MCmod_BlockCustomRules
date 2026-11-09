package kr.asmbl.block_custom_rules.rules;

import java.util.List;

/**
 * 상태 속성 값 하나 — 같은 값(`exact`) 또는 범위(`min` · `max` 중 하나 이상 · 끝 포함).
 * 범위는 둘 다 정수면 수로, 아니면 속성 값 차례(바닐라 `StatePropertiesPredicate` 와 같은 뜻)로 견준다.
 */
public record ValueMatch(String exact, String min, String max) {
	public static ValueMatch exact(String v) {
		return new ValueMatch(v, null, null);
	}

	public static ValueMatch range(String min, String max) {
		return new ValueMatch(null, min, max);
	}

	public boolean test(String value, List<String> values) {
		if (value == null) {
			return false;
		}
		if (this.exact != null) {
			return this.exact.equals(value);
		}
		if (this.min != null) {
			Integer c = compare(value, this.min, values);
			if (c == null || c < 0) {
				return false;
			}
		}
		if (this.max != null) {
			Integer c = compare(value, this.max, values);
			if (c == null || c > 0) {
				return false;
			}
		}
		return true;
	}

	/** 값이 이 속성의 값 목록에 들 수 있나 — 범위 끝은 정수면 목록 밖이어도 된다. */
	public boolean fits(List<String> values) {
		if (values == null) {
			return false;
		}
		if (this.exact != null) {
			return values.contains(this.exact);
		}
		return bound(this.min, values) && bound(this.max, values);
	}

	private static boolean bound(String b, List<String> values) {
		return b == null || values.contains(b) || isInt(b);
	}

	/** 음수 · 0 · 양수 = 작다 · 같다 · 크다 · 견줄 수 없으면 null. */
	static Integer compare(String value, String bound, List<String> values) {
		if (isInt(value) && isInt(bound)) {
			return Integer.compare(Integer.parseInt(value), Integer.parseInt(bound));
		}
		if (values == null) {
			return null;
		}
		int a = values.indexOf(value);
		int b = values.indexOf(bound);
		if (a < 0 || b < 0) {
			return null;
		}
		return Integer.compare(a, b);
	}

	static boolean isInt(String s) {
		if (s == null || s.isEmpty() || s.length() > 9) {
			return false;
		}
		int i = s.charAt(0) == '-' ? 1 : 0;
		if (i == s.length()) {
			return false;
		}
		for (; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		if (this.exact != null) {
			return this.exact;
		}
		return (this.min == null ? "" : this.min) + ".." + (this.max == null ? "" : this.max);
	}
}
