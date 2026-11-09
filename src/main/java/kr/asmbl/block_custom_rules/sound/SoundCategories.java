package kr.asmbl.block_custom_rules.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import kr.asmbl.block_custom_rules.rules.Ids;
import kr.asmbl.block_custom_rules.rules.Problems;
import kr.asmbl.block_custom_rules.rules.RuleFile;

/**
 * `sound.categories` · `sound.actions`(D19 · D20) — 이름표 칸이라 같은 이름은 위 층 · 나중 파일이 통째로 대신한다.
 * 종류 부모 사슬로 이름을 찾는다: 종류마다 «그 행동 → 행동 부모(종류 범위 → 전역)» 를 보고, 없으면 부모 종류로 넘어간다.
 * 부모로 적은 종류가 정의돼 있지 않아도 된다(`minecraft:chain` 같은 바닐라 종류) — 그 종류에서 사슬이 끝난다.
 * 마인크래프트 클래스를 모른다 — 이름이 정의돼 있는지는 부른 쪽이 넘긴다.
 */
public final class SoundCategories {
	/** 행동 부모 없음 — `"parent": null`. */
	private static final String NONE = "";
	private static final int MAX_DEPTH = 16;

	/** 종류 하나. */
	public record Category(String id, String parent, Boolean footsteps, Map<String, String> actions) {
	}

	public static final SoundCategories EMPTY = new SoundCategories(Map.of(), Map.of());

	private final Map<String, Category> categories;
	/** 전역 행동 부모 덮기(행동 → 부모 · 빈 글자 = 부모 없음). */
	private final Map<String, String> actions;

	private SoundCategories(Map<String, Category> categories, Map<String, String> actions) {
		this.categories = Collections.unmodifiableMap(categories);
		this.actions = Collections.unmodifiableMap(actions);
	}

	public Category get(String id) {
		return this.categories.get(id);
	}

	public Map<String, Category> all() {
		return this.categories;
	}

	/** 그 종류가 발소리를 내나 — `footsteps: false` 면 무음 종류(부모 사슬에서 처음 적힌 값). */
	public boolean footsteps(String category) {
		String c = category;
		for (int i = 0; c != null && i < MAX_DEPTH; i++) {
			Category k = this.categories.get(c);
			if (k == null) {
				return true;
			}
			if (k.footsteps != null) {
				return k.footsteps;
			}
			c = k.parent;
		}
		return true;
	}

	/**
	 * 이름 찾기(D15 의 sounds.json › 행동 부모 › 종류 부모) — 찾으면 그 이름 · 끝내 없으면 null(원래 소리).
	 *
	 * @param rest      소리 이름의 나머지(`place` · `open` · `imitate.zombie` · 행동 `run_sneak`)
	 * @param defaults  행동 기본 부모 표(엔진의 행동 표) — 소리 이름이 행동이 아니면 빈 표
	 * @param defined   그 이름이 리팩 · 바닐라 `sounds.json` 에 정의돼 있나
	 */
	public String find(String category, String rest, Map<String, String> defaults, Predicate<String> defined) {
		Set<String> seenCats = new HashSet<>();
		String c = category;
		for (int depth = 0; c != null && depth < MAX_DEPTH && seenCats.add(c); depth++) {
			Category k = this.categories.get(c);
			Set<String> seenActs = new HashSet<>();
			String a = rest;
			while (a != null && seenActs.add(a)) {
				String name = SoundNames.compose(c, a);
				if (defined.test(name)) {
					return name;
				}
				a = parentAction(k, a, defaults);
			}
			c = k == null ? null : k.parent;
		}
		return null;
	}

	/** 행동 부모 — 종류 범위 › 전역 덮기 › 엔진 기본 표 · 없으면 null. */
	String parentAction(Category k, String action, Map<String, String> defaults) {
		String p = null;
		if (k != null && k.actions.containsKey(action)) {
			p = k.actions.get(action);
		} else if (this.actions.containsKey(action)) {
			p = this.actions.get(action);
		} else {
			p = defaults.get(action);
		}
		return p == null || p.isEmpty() ? null : p;
	}

	// ── 읽기 ──

	/** 파일들(아래 층부터)의 `sound` 칸에서 종류 · 전역 행동을 모은다. */
	public static SoundCategories read(List<RuleFile> files, Problems problems) {
		Map<String, Category> cats = new LinkedHashMap<>();
		Map<String, String> acts = new LinkedHashMap<>();
		for (RuleFile f : files) {
			if (f.sound == null) {
				continue;
			}
			JsonElement c = f.sound.get("categories");
			if (c != null) {
				if (c.isJsonObject()) {
					readCategories(f, c.getAsJsonObject(), problems, cats);
				} else {
					problems.error(f.origin, "sound.categories", "bcr.problem.not_object");
				}
			}
			JsonElement a = f.sound.get("actions");
			if (a != null) {
				if (a.isJsonObject()) {
					Map<String, String> m = readActions(f, a.getAsJsonObject(), "sound.actions", problems);
					if (m != null) {
						acts.putAll(m);
					}
				} else {
					problems.error(f.origin, "sound.actions", "bcr.problem.not_object");
				}
			}
		}
		warnCycles(files, cats, problems);
		return new SoundCategories(cats, acts);
	}

	private static void readCategories(RuleFile f, JsonObject o, Problems problems, Map<String, Category> out) {
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			String path = Problems.child("sound.categories", e.getKey());
			String id = Ids.normalize(e.getKey());
			if (id == null) {
				problems.error(f.origin, path, "bcr.problem.bad_id", e.getKey());
				continue;
			}
			if (!e.getValue().isJsonObject()) {
				problems.error(f.origin, path, "bcr.problem.not_object");
				continue;
			}
			JsonObject d = e.getValue().getAsJsonObject();
			String parent = null;
			Boolean footsteps = null;
			Map<String, String> actions = Map.of();
			boolean ok = true;
			for (Map.Entry<String, JsonElement> k : d.entrySet()) {
				String kp = Problems.child(path, k.getKey());
				JsonElement v = k.getValue();
				switch (k.getKey()) {
					case "parent" -> {
						if (v.isJsonNull()) {
							parent = null;
						} else {
							parent = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? Ids.normalize(v.getAsString()) : null;
							if (parent == null) {
								problems.error(f.origin, kp, "bcr.problem.bad_id", v.toString());
								ok = false;
							}
						}
					}
					case "footsteps" -> {
						if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
							footsteps = v.getAsBoolean();
						} else {
							problems.error(f.origin, kp, "bcr.problem.bad_value.footsteps", v.toString());
							ok = false;
						}
					}
					case "actions" -> {
						if (v.isJsonObject()) {
							Map<String, String> m = readActions(f, v.getAsJsonObject(), kp, problems);
							ok &= m != null;
							actions = m == null ? Map.of() : m;
						} else {
							problems.error(f.origin, kp, "bcr.problem.not_object");
							ok = false;
						}
					}
					default -> problems.warn(f.origin, kp, "bcr.problem.unknown_key", k.getKey());
				}
			}
			if (ok) {
				// 이름표 칸 — 같은 이름은 통째로 대신
				out.put(id, new Category(id, parent, footsteps, actions));
			}
		}
	}

	/** `{"run_sneak": {"parent": "run"}}` → 행동 → 부모(빈 글자 = 없음) · 틀리면 null. */
	private static Map<String, String> readActions(RuleFile f, JsonObject o, String path, Problems problems) {
		Map<String, String> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			String ap = Problems.child(path, e.getKey());
			if (!actionToken(e.getKey())) {
				problems.error(f.origin, ap, "bcr.problem.bad_action", e.getKey());
				return null;
			}
			JsonElement v = e.getValue();
			if (!v.isJsonObject() || !v.getAsJsonObject().has("parent")) {
				problems.error(f.origin, ap, "bcr.problem.bad_value.action", v.toString());
				return null;
			}
			JsonElement p = v.getAsJsonObject().get("parent");
			if (p.isJsonNull()) {
				out.put(e.getKey(), NONE);
			} else if (p.isJsonPrimitive() && p.getAsJsonPrimitive().isString() && actionToken(p.getAsString())) {
				out.put(e.getKey(), p.getAsString());
			} else {
				problems.error(f.origin, Problems.child(ap, "parent"), "bcr.problem.bad_action", p.toString());
				return null;
			}
			for (String k : v.getAsJsonObject().keySet()) {
				if (!k.equals("parent")) {
					problems.warn(f.origin, Problems.child(ap, k), "bcr.problem.unknown_key", k);
				}
			}
		}
		return out;
	}

	/** 행동 이름 — 소문자 · 숫자 · 밑줄 · 점(`imitate.zombie` 같은 나머지도 받는다). */
	static boolean actionToken(String s) {
		if (s == null || s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.')) {
				return false;
			}
		}
		return true;
	}

	/** 종류 부모가 서로 물고 돌면 경고 — 찾기는 같은 종류를 두 번 안 보므로 끊긴다. */
	private static void warnCycles(List<RuleFile> files, Map<String, Category> cats, Problems problems) {
		for (Category k : cats.values()) {
			List<String> seen = new ArrayList<>();
			String c = k.id;
			while (c != null && !seen.contains(c)) {
				seen.add(c);
				Category n = cats.get(c);
				c = n == null ? null : n.parent;
			}
			if (c != null && c.equals(k.id)) {
				RuleFile at = null;
				for (RuleFile f : files) {
					if (f.sound != null && f.sound.has("categories") && f.sound.get("categories").isJsonObject()
							&& hasKey(f.sound.getAsJsonObject("categories"), k.id)) {
						at = f;
					}
				}
				if (at != null) {
					problems.warn(at.origin, Problems.child("sound.categories", k.id), "bcr.problem.category_cycle", k.id);
				}
			}
		}
	}

	private static boolean hasKey(JsonObject o, String id) {
		for (String k : o.keySet()) {
			if (id.equals(Ids.normalize(k))) {
				return true;
			}
		}
		return false;
	}
}
