package kr.asmbl.block_custom_rules.rules;

/**
 * 조건이 가리킨 태그 하나 — 묶음을 다 모은 뒤에야 있는지 알 수 있어 풀기가 한꺼번에 본다.
 */
public record TagRef(Registry registry, String tag, Origin origin, String path) {
}
