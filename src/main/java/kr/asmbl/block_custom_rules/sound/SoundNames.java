package kr.asmbl.block_custom_rules.sound;

/**
 * 소리 이름 다루기(D14) — `minecraft:block.<가운데>.<나머지>` ↔ `<종류 ns>:block.<종류 path>.<나머지>`.
 * 가운데 한 마디만 바꾸고 나머지는 점이 있어도 그대로(`block.note_block.imitate.zombie` → `<종류>.imitate.zombie`).
 * `minecraft` 이름공간의 `block.` 소리만 다룬다 — `item.*` · 다른 모드 소리는 안 건드린다.
 */
public final class SoundNames {
	private static final String PREFIX = "minecraft:block.";

	private SoundNames() {
	}

	/** {가운데, 나머지} · 다루는 이름이 아니면 null. */
	public static String[] split(String soundId) {
		if (soundId == null || !soundId.startsWith(PREFIX)) {
			return null;
		}
		String tail = soundId.substring(PREFIX.length());
		int dot = tail.indexOf('.');
		if (dot <= 0 || dot == tail.length() - 1) {
			return null;
		}
		return new String[] { tail.substring(0, dot), tail.substring(dot + 1) };
	}

	/** 종류 `ns:path` + 나머지 → `ns:block.path.나머지`. */
	public static String compose(String category, String rest) {
		int c = category.indexOf(':');
		String ns = c < 0 ? "minecraft" : category.substring(0, c);
		String path = c < 0 ? category : category.substring(c + 1);
		return ns + ":block." + path + "." + rest;
	}

	/** 바닐라 소리 이름의 가운데 마디(`minecraft:block.wood.step` → `wood`) · 아니면 null. */
	public static String mid(String soundId) {
		String[] s = split(soundId);
		return s == null ? null : s[0];
	}

	/** 표식 시드(D18 · D50) — 위 16비트가 `0x5EED` 면 연출이 일부러 튼 소리. 클라 소리 난수는 아래 48비트만 쓴다. */
	public static final long MARKER = 0x5EEDL;

	public static boolean isMarkerSeed(long seed) {
		return (seed >>> 48) == MARKER;
	}
}
