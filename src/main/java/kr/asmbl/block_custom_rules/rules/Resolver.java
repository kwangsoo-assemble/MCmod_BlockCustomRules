package kr.asmbl.block_custom_rules.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 규칙 풀기(D5 · D6 · D11) — 파일들을 «블록 상태 → 최종 규칙 + 출처» 표로.
 * 차례: 파일은 아래 층부터(서버 태그 < 리팩 아래 → 위 < 모드 옵션) · 같은 층은 파일 이름 차례 · 파일 안은 적은 차례.
 * 목록 칸(`rules`)은 칸마다 나중 줄이 이긴다 — 그래서 위 층 · 나중 파일 · 아래 줄이 이긴다.
 */
public final class Resolver {
	private Resolver() {
	}

	/** @param given 같은 층 안은 파일 차례대로(층끼리는 층 번호로 다시 줄 세운다) */
	public static RuleTable resolve(List<RuleFile> given, Catalog catalog, Problems problems) {
		List<RuleFile> files = new ArrayList<>(given);
		files.sort(Comparator.comparingInt(f -> f.origin.layer()));
		List<GroupDef> defs = new ArrayList<>();
		for (RuleFile f : files) {
			defs.addAll(f.groups);
		}
		Groups groups = new Groups(catalog, problems, defs);
		for (RuleFile f : files) {
			for (TagRef ref : f.tagRefs) {
				if (catalog.tagsReady(ref.registry()) && groups.members(ref.registry(), ref.tag(), ref.origin().layer()) == null) {
					problems.warn(ref.origin(), ref.path(), "bcr.problem.unknown_tag", "#" + ref.tag());
				}
			}
		}
		int n = Field.values().length;
		Map<StateView, Object[]> values = new HashMap<>();
		Map<StateView, Provenance[]> from = new HashMap<>();
		Collection<String> allBlocks = null;
		for (RuleFile f : files) {
			TagView view = groups.view(f.origin.layer());
			for (RuleLine line : f.rules) {
				int k = line.values().size();
				int[] slot = new int[k];
				Object[] val = new Object[k];
				Provenance[] prov = new Provenance[k];
				int i = 0;
				for (Map.Entry<Field, Object> e : line.values().entrySet()) {
					slot[i] = e.getKey().ordinal();
					val[i] = e.getValue();
					prov[i] = new Provenance(f.origin, Problems.child(line.path(), e.getKey().key));
					i++;
				}
				Set<String> cand = line.target().candidates(view);
				Collection<String> blocks = cand;
				if (blocks == null) {
					if (allBlocks == null) {
						allBlocks = catalog.blocks();
					}
					blocks = allBlocks;
				}
				for (String block : blocks) {
					for (StateView s : catalog.states(block)) {
						if (!line.target().test(s, view)) {
							continue;
						}
						Object[] v = values.computeIfAbsent(s, x -> new Object[n]);
						Provenance[] p = from.computeIfAbsent(s, x -> new Provenance[n]);
						for (int j = 0; j < k; j++) {
							v[slot[j]] = val[j];
							p[slot[j]] = prov[j];
						}
					}
				}
			}
		}
		Map<StateView, Resolved> out = new HashMap<>(values.size() * 2);
		for (Map.Entry<StateView, Object[]> e : values.entrySet()) {
			out.put(e.getKey(), new Resolved(e.getValue(), from.get(e.getKey())));
		}
		return new RuleTable(out, files, groups);
	}
}
