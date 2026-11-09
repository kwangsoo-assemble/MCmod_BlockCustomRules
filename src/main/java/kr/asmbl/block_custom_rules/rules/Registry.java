package kr.asmbl.block_custom_rules.rules;

/**
 * 규칙 파일이 이름으로 가리키는 레지스트리. 묶음(groups)은 블록 · 바이옴만 만든다 — 엔티티는 서버 태그만 본다.
 */
public enum Registry {
	BLOCK("block"),
	BIOME("biome"),
	ENTITY_TYPE("entity_type"),
	FLUID("fluid");

	public final String id;

	Registry(String id) {
		this.id = id;
	}

	/** `groups` 의 `"registry"` 칸 — 못 쓰는 값이면 null. */
	public static Registry forGroups(String s) {
		if (BLOCK.id.equals(s)) {
			return BLOCK;
		}
		if (BIOME.id.equals(s)) {
			return BIOME;
		}
		return null;
	}
}
