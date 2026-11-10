package kr.asmbl.block_custom_rules.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 묶음 · 태그 풀기 — C안 «아래에서 위로 물려받기»(R11 · D9).
 * 층 L 에서 본 `#x` = 층 L 이하의 `x` 정의를 아래 → 위 · 파일 차례로 접은 것(맨 아래는 서버 태그).
 * 정의 하나는 `replace` 면 그 앞을 버리고, 아니면 덧붙인다. 정의 안 `#y` 는 그 정의가 있는 층에서 본다.
 * 그래서 위 팩이 다시 정의해도 아래 팩 규칙은 안 바뀐다. 서로 물고 돌면 경고하고 그 고리는 빈 것으로 끊는다.
 */
public final class Groups {
	private record Name(Registry registry, String tag) {
	}

	private record Key(Name name, int visible) {
	}

	private final Catalog catalog;
	private final Problems problems;
	private final Map<Name, List<GroupDef>> defs = new HashMap<>();
	private final Map<Key, Set<String>> memo = new HashMap<>();
	private final Set<Key> inProgress = new HashSet<>();
	private final Map<Integer, TagView> views = new HashMap<>();

	/** @param ordered 아래 층부터 · 같은 층은 파일 차례 · 파일 안은 적은 차례 */
	public Groups(Catalog catalog, Problems problems, List<GroupDef> ordered) {
		this.catalog = catalog;
		this.problems = problems;
		for (GroupDef d : ordered) {
			this.defs.computeIfAbsent(new Name(d.registry(), d.name()), n -> new ArrayList<>()).add(d);
		}
	}

	/** 이 이름을 정의한 `groups` 줄 — 아래 층부터 · 파일 차례(명령이 «누가 다시 정의했나» 를 보인다). */
	public synchronized List<GroupDef> definitions(Registry registry, String tag) {
		return List.copyOf(this.defs.getOrDefault(new Name(registry, tag), List.of()));
	}

	/** 리팩 · 모드 옵션이 정의한 묶음 이름(`#` 뺀 것). */
	public synchronized Set<String> names(Registry registry) {
		Set<String> out = new java.util.TreeSet<>();
		for (Name n : this.defs.keySet()) {
			if (n.registry() == registry) {
				out.add(n.tag());
			}
		}
		return out;
	}

	/** 층 하나에서 보이는 태그 창(같은 층이면 같은 창). */
	public synchronized TagView view(int layer) {
		return this.views.computeIfAbsent(layer, l -> (registry, tag) -> members(registry, tag, l));
	}

	/**
	 * 층 L 에서 본 구성원 · 그런 태그 · 묶음이 없으면 null.
	 * 풀기가 끝난 뒤에도 소리 쪽이 처음 보는 태그를 물으면 기억표를 채우므로 잠근다.
	 */
	public synchronized Set<String> members(Registry registry, String tag, int layer) {
		Name n = new Name(registry, tag);
		List<GroupDef> list = this.defs.getOrDefault(n, List.of());
		int visible = 0;
		while (visible < list.size() && list.get(visible).origin().layer() <= layer) {
			visible++;
		}
		return fold(n, list, visible);
	}

	private Set<String> fold(Name n, List<GroupDef> list, int visible) {
		Key key = new Key(n, visible);
		if (this.memo.containsKey(key)) {
			return this.memo.get(key);
		}
		if (!this.inProgress.add(key)) {
			GroupDef at = visible > 0 ? list.get(visible - 1) : null;
			if (at != null) {
				this.problems.warn(at.origin(), at.path(), "bcr.problem.group_cycle", "#" + n.tag());
			}
			return Set.of();
		}
		Set<String> result;
		try {
			if (visible == 0) {
				Collection<String> server = this.catalog.tag(n.registry(), n.tag());
				result = server == null ? null : Set.copyOf(server);
			} else {
				GroupDef d = list.get(visible - 1);
				Set<String> acc = new LinkedHashSet<>();
				if (!d.replace()) {
					Set<String> below = fold(n, list, visible - 1);
					if (below != null) {
						acc.addAll(below);
					}
				}
				for (int i = 0; i < d.values().size(); i++) {
					String v = d.values().get(i);
					String vp = Problems.index(Problems.child(d.path(), "values"), i);
					if (v.startsWith("#")) {
						Set<String> sub = members(n.registry(), v.substring(1), d.origin().layer());
						if (sub == null) {
							if (this.catalog.tagsReady(n.registry())) {
								this.problems.warn(d.origin(), vp, "bcr.problem.unknown_tag", v);
							}
						} else {
							acc.addAll(sub);
						}
					} else {
						if (this.catalog.idsReady(n.registry()) && !this.catalog.exists(n.registry(), v)) {
							this.problems.warn(d.origin(), vp, n.registry() == Registry.BIOME ? "bcr.problem.unknown_biome" : "bcr.problem.unknown_block", v);
						}
						acc.add(v);
					}
				}
				result = Set.copyOf(acc);
			}
		} finally {
			this.inProgress.remove(key);
		}
		this.memo.put(key, result);
		return result;
	}
}
