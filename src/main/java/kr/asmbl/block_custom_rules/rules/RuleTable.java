package kr.asmbl.block_custom_rules.rules;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 풀기 결과(D11) — 블록 상태 → 최종 규칙. 바뀌지 않는 표라 통째로 바꿔 끼운다(청크 스레드 여럿이 읽는다).
 * 아무 줄도 안 맞은 상태는 표에 없다.
 */
public final class RuleTable {
	public static final RuleTable EMPTY = new RuleTable(Map.of(), List.of(), null, Map.of());

	private final Map<StateView, Resolved> map;
	private final List<RuleFile> files;
	private final Groups groups;
	/** 줄 → 그 줄이 맞은 상태(설정 화면 덮임 조회) — 줄은 같음이 아니라 같은 객체로 찾는다. */
	private final Map<RuleLine, List<StateView>> matched;
	public final boolean anyCullPass;
	public final boolean anySway;

	RuleTable(Map<StateView, Resolved> map, List<RuleFile> files, Groups groups, Map<RuleLine, List<StateView>> matched) {
		this.map = Collections.unmodifiableMap(map);
		this.matched = matched;
		this.files = List.copyOf(files);
		this.groups = groups;
		boolean cull = false;
		boolean sway = false;
		for (Resolved r : map.values()) {
			cull |= r.cullPass();
			sway |= r.sway() > 0.0F;
		}
		this.anyCullPass = cull;
		this.anySway = sway;
	}

	/** 그 상태의 규칙 · 없으면 null. */
	public Resolved get(StateView s) {
		return this.map.get(s);
	}

	public Map<StateView, Resolved> all() {
		return this.map;
	}

	/** 읽은 파일(아래 층부터) — 설정 화면 · `sound` 칸 읽기가 쓴다. */
	public List<RuleFile> files() {
		return this.files;
	}

	/** 그 줄이 맞은 상태 — 이 표를 만든 줄이 아니면 빈 목록. */
	public List<StateView> matches(RuleLine line) {
		List<StateView> l = this.matched.get(line);
		return l == null ? List.of() : l;
	}

	/** 층 하나에서 본 태그 창 — 소리 조건 등 나중 칸이 같은 C안으로 본다. 빈 표면 아무 태그도 없다. */
	public TagView tags(int layer) {
		return this.groups == null ? (registry, tag) -> null : this.groups.view(layer);
	}
}
