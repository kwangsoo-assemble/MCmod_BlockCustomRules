package kr.asmbl.block_custom_rules.rules;

import java.util.List;

/**
 * 블록 상태 하나를 글자로 본 것. 풀기 결과 표의 열쇠라서 같은 상태면 equals · hashCode 가 같아야 한다.
 */
public interface StateView {
	String block();

	/** 속성 값 글자 · 그 속성이 없으면 null. */
	String get(String property);

	/** 그 속성이 가질 수 있는 값(정해진 차례 — 범위 비교에 쓴다) · 없으면 null. */
	List<String> values(String property);
}
