package kr.asmbl.block_custom_rules.rules;

/**
 * 칸 값이 어디서 왔나 — 층 · 팩 · 파일 · JSON 경로(`rules[3].sway`).
 */
public record Provenance(Origin origin, String path) {
	public String describe() {
		return this.origin.describe() + " › " + this.path;
	}
}
