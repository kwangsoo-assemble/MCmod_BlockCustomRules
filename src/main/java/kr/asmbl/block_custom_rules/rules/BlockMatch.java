package kr.asmbl.block_custom_rules.rules;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 블록 조건의 잎 — 이름들 또는 태그 하나 + 상태 값들. 이름 · 태그가 둘 다 없으면 «모든 블록» 에 상태만 본다.
 * 모르는 태그는 아무것도 안 맞는다(경고는 풀기가 낸다).
 */
public record BlockMatch(Set<String> ids, String tag, Map<String, ValueMatch> state) implements Cond<StateView> {
	public BlockMatch {
		state = state == null ? Map.of() : Collections.unmodifiableMap(state);
	}

	@Override
	public boolean test(StateView s, TagView tags) {
		if (this.tag != null) {
			Set<String> m = tags.members(Registry.BLOCK, this.tag);
			if (m == null || !m.contains(s.block())) {
				return false;
			}
		} else if (this.ids != null && !this.ids.contains(s.block())) {
			return false;
		}
		for (Map.Entry<String, ValueMatch> e : this.state.entrySet()) {
			if (!e.getValue().test(s.get(e.getKey()), s.values(e.getKey()))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Set<String> candidates(TagView tags) {
		if (this.tag != null) {
			Set<String> m = tags.members(Registry.BLOCK, this.tag);
			return m == null ? Set.of() : m;
		}
		return this.ids;
	}

	@Override
	public String toString() {
		String head = this.tag != null ? "#" + this.tag : this.ids == null ? "*" : String.join("|", this.ids);
		return this.state.isEmpty() ? head : head + this.state;
	}
}
