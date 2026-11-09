package kr.asmbl.block_custom_rules.rules;

import java.util.Set;

/**
 * 한 층에서 보이는 태그 · 묶음(C안 — 자기 층 정의 › 아래 층 중 가장 가까운 정의 › 서버 태그).
 */
public interface TagView {
	/** 구성원 이름 · 그런 태그 · 묶음이 없으면 null. */
	Set<String> members(Registry registry, String tag);
}
