package kr.asmbl.block_custom_rules.rules;

import java.util.Collection;
import java.util.List;

/**
 * 게임 레지스트리 · 서버 태그를 규칙 핵심에 보여 주는 창. 게임 쪽 구현은 T2 · 시험은 가짜를 넣는다.
 * 이름은 모두 `ns:path` 글자다(규칙 핵심은 마인크래프트 클래스를 모른다).
 */
public interface Catalog {
	/** 이 레지스트리가 지금 있나 — 바이옴 · 엔티티 태그는 접속 뒤에야 온다. 없으면 «모름» 으로 다루고 경고하지 않는다. */
	boolean ready(Registry registry);

	boolean exists(Registry registry, String id);

	/** 서버 태그 구성원(태그 안 태그까지 펼친 것) · 그런 태그가 없으면 null. */
	Collection<String> tag(Registry registry, String tagId);

	/** 모든 블록 이름. */
	Collection<String> blocks();

	/** 그 블록의 상태 전부 · 없는 블록이면 빈 목록. */
	List<? extends StateView> states(String blockId);
}
