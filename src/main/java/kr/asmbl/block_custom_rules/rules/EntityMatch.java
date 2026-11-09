package kr.asmbl.block_custom_rules.rules;

import java.util.Set;

/**
 * 엔티티 조건의 잎 — 종류 이름들 또는 서버 엔티티 태그 하나. 시험하는 값은 엔티티 종류 이름(`minecraft:zombie`).
 */
public record EntityMatch(Set<String> ids, String tag) implements Cond<String> {
	@Override
	public boolean test(String type, TagView tags) {
		if (this.tag != null) {
			Set<String> m = tags.members(Registry.ENTITY_TYPE, this.tag);
			return m != null && m.contains(type);
		}
		return this.ids.contains(type);
	}

	@Override
	public Set<String> candidates(TagView tags) {
		if (this.tag != null) {
			Set<String> m = tags.members(Registry.ENTITY_TYPE, this.tag);
			return m == null ? Set.of() : m;
		}
		return this.ids;
	}

	@Override
	public String toString() {
		return this.tag != null ? "#" + this.tag : String.join("|", this.ids);
	}
}
