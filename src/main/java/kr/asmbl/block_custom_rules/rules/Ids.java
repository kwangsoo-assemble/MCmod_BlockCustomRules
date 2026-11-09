package kr.asmbl.block_custom_rules.rules;

/**
 * `ns:path` 이름 다루기 — 바닐라 ResourceLocation 과 같은 글자만 받는다(마인크래프트 클래스 없이).
 */
public final class Ids {
	private Ids() {
	}

	/** 이름공간이 없으면 `minecraft:` 를 붙인다 · 못 쓰는 글자면 null. */
	public static String normalize(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		int c = t.indexOf(':');
		String ns = c < 0 ? "minecraft" : t.substring(0, c);
		String path = c < 0 ? t : t.substring(c + 1);
		if (ns.isEmpty() || path.isEmpty()) {
			return null;
		}
		for (int i = 0; i < ns.length(); i++) {
			if (!nsChar(ns.charAt(i))) {
				return null;
			}
		}
		for (int i = 0; i < path.length(); i++) {
			char ch = path.charAt(i);
			if (!nsChar(ch) && ch != '/') {
				return null;
			}
		}
		return ns + ":" + path;
	}

	private static boolean nsChar(char ch) {
		return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.';
	}

	/** 상태 속성 이름 · 값 — 바닐라는 소문자 · 숫자 · 밑줄. */
	public static boolean propertyToken(String s) {
		if (s == null || s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) {
				return false;
			}
		}
		return true;
	}
}
