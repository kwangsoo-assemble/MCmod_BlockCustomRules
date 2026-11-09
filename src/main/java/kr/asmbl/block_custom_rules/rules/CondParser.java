package kr.asmbl.block_custom_rules.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 조건 읽기(D8). 블록: 명령 꼴 글자(`minecraft:wheat[age=6]` · `#minecraft:crops`) · 발전과제 꼴
 * `{"blocks": …, "state": {"age": {"min": 4}}}` · 엔티티: `"minecraft:zombie"` · `"#minecraft:skeletons"`.
 * 둘 다 `"*"` · 배열(= `any_of`) · `{"any_of": […]}` · `{"all_of": […]}` · `{"not": …}`.
 * 틀리면 오류를 적고 null — 그 줄을 버린다. 없는 이름은 경고만(다른 모드 블록일 수 있다).
 */
public final class CondParser {
	private static final String ANY_OF = "any_of";
	private static final String ALL_OF = "all_of";
	private static final String NOT = "not";

	private final Origin origin;
	private final Catalog catalog;
	private final Problems problems;
	private final List<TagRef> tagRefs;

	public CondParser(Origin origin, Catalog catalog, Problems problems, List<TagRef> tagRefs) {
		this.origin = origin;
		this.catalog = catalog;
		this.problems = problems;
		this.tagRefs = tagRefs;
	}

	private interface Kind<T> {
		Cond<T> string(String s, String path);

		Cond<T> object(JsonObject o, String path);
	}

	public Cond<StateView> block(JsonElement e, String path) {
		return parse(e, path, this.blockKind);
	}

	public Cond<String> entity(JsonElement e, String path) {
		return parse(e, path, this.entityKind);
	}

	private <T> Cond<T> parse(JsonElement e, String path, Kind<T> kind) {
		if (e == null || e.isJsonNull()) {
			this.problems.error(this.origin, path, "bcr.problem.bad_condition", "null");
			return null;
		}
		if (e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			if (!p.isString()) {
				this.problems.error(this.origin, path, "bcr.problem.bad_condition", p.toString());
				return null;
			}
			String s = p.getAsString().trim();
			return "*".equals(s) ? Cond.always() : kind.string(s, path);
		}
		if (e.isJsonArray()) {
			return list(e.getAsJsonArray(), path, kind, true);
		}
		JsonObject o = e.getAsJsonObject();
		String comb = o.has(ANY_OF) ? ANY_OF : o.has(ALL_OF) ? ALL_OF : o.has(NOT) ? NOT : null;
		if (comb == null) {
			return kind.object(o, path);
		}
		if (o.size() != 1) {
			this.problems.error(this.origin, path, "bcr.problem.combinator_alone", comb);
			return null;
		}
		String sub = Problems.child(path, comb);
		if (comb.equals(NOT)) {
			Cond<T> inner = parse(o.get(NOT), sub, kind);
			return inner == null ? null : new Cond.Not<>(inner);
		}
		JsonElement v = o.get(comb);
		if (!v.isJsonArray()) {
			this.problems.error(this.origin, sub, "bcr.problem.not_array");
			return null;
		}
		return list(v.getAsJsonArray(), sub, kind, comb.equals(ANY_OF));
	}

	private <T> Cond<T> list(JsonArray a, String path, Kind<T> kind, boolean anyOf) {
		if (a.isEmpty()) {
			this.problems.error(this.origin, path, "bcr.problem.empty_list");
			return null;
		}
		List<Cond<T>> parts = new ArrayList<>(a.size());
		boolean ok = true;
		for (int i = 0; i < a.size(); i++) {
			Cond<T> c = parse(a.get(i), Problems.index(path, i), kind);
			ok &= c != null;
			parts.add(c);
		}
		if (!ok) {
			return null;
		}
		if (parts.size() == 1) {
			return parts.get(0);
		}
		return anyOf ? new Cond.AnyOf<>(List.copyOf(parts)) : new Cond.AllOf<>(List.copyOf(parts));
	}

	// ── 블록 ──

	private final Kind<StateView> blockKind = new Kind<>() {
		@Override
		public Cond<StateView> string(String s, String path) {
			return command(s, path);
		}

		@Override
		public Cond<StateView> object(JsonObject o, String path) {
			return blockObject(o, path);
		}
	};

	/** 명령 꼴 `ns:id[k=v,…]` · `#ns:tag[k=v]`. */
	BlockMatch command(String text, String path) {
		if (text.indexOf('{') >= 0) {
			this.problems.error(this.origin, path, "bcr.problem.nbt", text);
			return null;
		}
		String head = text;
		String props = null;
		int open = text.indexOf('[');
		if (open >= 0) {
			if (!text.endsWith("]") || text.indexOf('[', open + 1) >= 0) {
				this.problems.error(this.origin, path, "bcr.problem.bad_condition", text);
				return null;
			}
			head = text.substring(0, open).trim();
			props = text.substring(open + 1, text.length() - 1);
		}
		boolean isTag = head.startsWith("#");
		String id = Ids.normalize(isTag ? head.substring(1) : head);
		if (id == null) {
			this.problems.error(this.origin, path, "bcr.problem.bad_id", text);
			return null;
		}
		Map<String, ValueMatch> state = new LinkedHashMap<>();
		if (props != null && !props.isBlank()) {
			for (String part : props.split(",", -1)) {
				int eq = part.indexOf('=');
				String k = eq < 0 ? part.trim() : part.substring(0, eq).trim();
				String v = eq < 0 ? "" : part.substring(eq + 1).trim();
				if (!Ids.propertyToken(k) || !Ids.propertyToken(v)) {
					this.problems.error(this.origin, path, "bcr.problem.bad_condition", text);
					return null;
				}
				if (state.put(k, ValueMatch.exact(v)) != null) {
					this.problems.error(this.origin, path, "bcr.problem.duplicate_property", k);
					return null;
				}
			}
		}
		if (isTag) {
			this.tagRefs.add(new TagRef(Registry.BLOCK, id, this.origin, path));
			return new BlockMatch(null, id, state);
		}
		Set<String> ids = Set.of(id);
		checkBlocks(ids, state, path);
		return new BlockMatch(ids, null, state);
	}

	private Cond<StateView> blockObject(JsonObject o, String path) {
		for (String k : o.keySet()) {
			if (!k.equals("blocks") && !k.equals("state")) {
				this.problems.error(this.origin, Problems.child(path, k), "bcr.problem.unknown_key", k);
				return null;
			}
		}
		Set<String> ids = null;
		String tag = null;
		if (o.has("blocks")) {
			String bp = Problems.child(path, "blocks");
			JsonElement b = o.get("blocks");
			if (b.isJsonPrimitive() && b.getAsJsonPrimitive().isString()) {
				String s = b.getAsString().trim();
				boolean isTag = s.startsWith("#");
				String id = Ids.normalize(isTag ? s.substring(1) : s);
				if (id == null) {
					this.problems.error(this.origin, bp, "bcr.problem.bad_id", s);
					return null;
				}
				if (isTag) {
					tag = id;
					this.tagRefs.add(new TagRef(Registry.BLOCK, id, this.origin, bp));
				} else {
					ids = Set.of(id);
				}
			} else if (b.isJsonArray()) {
				JsonArray a = b.getAsJsonArray();
				if (a.isEmpty()) {
					this.problems.error(this.origin, bp, "bcr.problem.empty_list");
					return null;
				}
				Set<String> set = new LinkedHashSet<>();
				for (int i = 0; i < a.size(); i++) {
					JsonElement x = a.get(i);
					String id = x.isJsonPrimitive() && x.getAsJsonPrimitive().isString() ? Ids.normalize(x.getAsString()) : null;
					if (id == null || x.getAsString().trim().startsWith("#")) {
						this.problems.error(this.origin, Problems.index(bp, i), "bcr.problem.bad_id", x.toString());
						return null;
					}
					set.add(id);
				}
				ids = Set.copyOf(set);
			} else {
				this.problems.error(this.origin, bp, "bcr.problem.bad_condition", b.toString());
				return null;
			}
		}
		Map<String, ValueMatch> state = new LinkedHashMap<>();
		if (o.has("state")) {
			String sp = Problems.child(path, "state");
			JsonElement se = o.get("state");
			if (!se.isJsonObject()) {
				this.problems.error(this.origin, sp, "bcr.problem.not_object");
				return null;
			}
			for (Map.Entry<String, JsonElement> e : se.getAsJsonObject().entrySet()) {
				ValueMatch vm = valueMatch(e.getValue(), Problems.child(sp, e.getKey()));
				if (vm == null || !Ids.propertyToken(e.getKey())) {
					if (vm != null) {
						this.problems.error(this.origin, Problems.child(sp, e.getKey()), "bcr.problem.bad_condition", e.getKey());
					}
					return null;
				}
				state.put(e.getKey(), vm);
			}
		}
		if (ids != null) {
			checkBlocks(ids, state, path);
		}
		return new BlockMatch(ids, tag, state);
	}

	private ValueMatch valueMatch(JsonElement v, String path) {
		if (v.isJsonPrimitive()) {
			return ValueMatch.exact(v.getAsString());
		}
		if (v.isJsonObject()) {
			JsonObject r = v.getAsJsonObject();
			for (String k : r.keySet()) {
				if (!k.equals("min") && !k.equals("max")) {
					this.problems.error(this.origin, Problems.child(path, k), "bcr.problem.unknown_key", k);
					return null;
				}
			}
			String min = r.has("min") && r.get("min").isJsonPrimitive() ? r.get("min").getAsString() : null;
			String max = r.has("max") && r.get("max").isJsonPrimitive() ? r.get("max").getAsString() : null;
			if ((min == null) != !r.has("min") || (max == null) != !r.has("max") || (min == null && max == null)) {
				this.problems.error(this.origin, path, "bcr.problem.bad_condition", r.toString());
				return null;
			}
			return ValueMatch.range(min, max);
		}
		this.problems.error(this.origin, path, "bcr.problem.bad_condition", v.toString());
		return null;
	}

	/** 없는 블록 · 그 블록에 없는 속성 · 값 — 경고만(줄은 쓴다 · 안 맞을 뿐). */
	private void checkBlocks(Set<String> ids, Map<String, ValueMatch> state, String path) {
		if (!this.catalog.ready(Registry.BLOCK)) {
			return;
		}
		for (String id : ids) {
			if (!this.catalog.exists(Registry.BLOCK, id)) {
				this.problems.warn(this.origin, path, "bcr.problem.unknown_block", id);
				continue;
			}
			List<? extends StateView> states = this.catalog.states(id);
			if (states.isEmpty()) {
				continue;
			}
			StateView any = states.get(0);
			for (Map.Entry<String, ValueMatch> e : state.entrySet()) {
				List<String> values = any.values(e.getKey());
				if (values == null) {
					this.problems.warn(this.origin, path, "bcr.problem.unknown_property", id, e.getKey());
				} else if (!e.getValue().fits(values)) {
					this.problems.warn(this.origin, path, "bcr.problem.bad_property_value", id, e.getKey(), e.getValue());
				}
			}
		}
	}

	// ── 엔티티 ──

	private final Kind<String> entityKind = new Kind<>() {
		@Override
		public Cond<String> string(String s, String path) {
			boolean isTag = s.startsWith("#");
			String id = Ids.normalize(isTag ? s.substring(1) : s);
			if (id == null || s.indexOf('[') >= 0 || s.indexOf('{') >= 0) {
				problems.error(origin, path, "bcr.problem.bad_id", s);
				return null;
			}
			if (isTag) {
				tagRefs.add(new TagRef(Registry.ENTITY_TYPE, id, origin, path));
				return new EntityMatch(null, id);
			}
			if (catalog.ready(Registry.ENTITY_TYPE) && !catalog.exists(Registry.ENTITY_TYPE, id)) {
				problems.warn(origin, path, "bcr.problem.unknown_entity", id);
			}
			return new EntityMatch(Set.of(id), null);
		}

		@Override
		public Cond<String> object(JsonObject o, String path) {
			problems.error(origin, path, "bcr.problem.bad_condition", o.toString());
			return null;
		}
	};
}
