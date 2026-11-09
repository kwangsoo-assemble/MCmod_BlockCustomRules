package kr.asmbl.block_custom_rules.rules;

/**
 * 규칙이 온 자리. 층은 아래부터 0 = 서버 태그 → 리팩(아래 팩부터) → 맨 위 모드 옵션 · 위 층이 이긴다(D5).
 *
 * @param pack 리팩 이름(모드 옵션 · 서버면 null)
 * @param file 팩 안 경로 또는 설정 파일 이름
 */
public record Origin(Kind kind, int layer, String pack, String file) {
	public enum Kind {
		SERVER, PACK, OPTIONS
	}

	public static Origin pack(int layer, String pack, String file) {
		return new Origin(Kind.PACK, layer, pack, file);
	}

	public static Origin options(int layer, String file) {
		return new Origin(Kind.OPTIONS, layer, null, file);
	}

	/** 로그 · 명령에 쓰는 한 줄 — `팩 이름 › 파일` 꼴. */
	public String describe() {
		return switch (this.kind) {
			case SERVER -> "server";
			case OPTIONS -> "options › " + this.file;
			case PACK -> this.pack + " › " + this.file;
		};
	}
}
