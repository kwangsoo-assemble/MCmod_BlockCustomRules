package kr.asmbl.block_custom_rules.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 규칙 파일 하나(D4) — 리팩 `assets/<ns>/block_custom_rules/*.json` · 모드 옵션 `config/block_custom_rules.json`.
 * `groups` · `rules` 를 읽고, `sound` 는 통째로 들고 있다(소리 칸은 T3 · T9 에서 읽는다).
 * 틀린 줄만 버리고 나머지는 쓴다(D12).
 */
public final class RuleFile {
	public static final int FORMAT = 1;

	public final Origin origin;
	public final List<GroupDef> groups;
	public final List<RuleLine> rules;
	public final List<TagRef> tagRefs;
	/** `sound` 칸 그대로 · 없으면 null. */
	public final JsonObject sound;
	/** 부른 쪽이 허락한 덧칸(모드 옵션의 `options` 등). */
	public final Map<String, JsonElement> extra;

	private RuleFile(Origin origin, List<GroupDef> groups, List<RuleLine> rules, List<TagRef> tagRefs, JsonObject sound,
			Map<String, JsonElement> extra) {
		this.origin = origin;
		this.groups = Collections.unmodifiableList(groups);
		this.rules = Collections.unmodifiableList(rules);
		this.tagRefs = Collections.unmodifiableList(tagRefs);
		this.sound = sound;
		this.extra = Collections.unmodifiableMap(extra);
	}

	public static RuleFile empty(Origin origin) {
		return new RuleFile(origin, List.of(), List.of(), List.of(), null, Map.of());
	}

	/**
	 * @param extraKeys 맨 위에 더 받을 칸 — 리팩은 빈 집합
	 */
	public static RuleFile parse(Origin origin, String text, Catalog catalog, Problems problems, Set<String> extraKeys) {
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (JsonParseException | IllegalStateException ex) {
			String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			problems.error(origin, "", "bcr.problem.json_syntax", msg);
			return empty(origin);
		}
		if (root == null || !root.isJsonObject()) {
			problems.error(origin, "", "bcr.problem.not_object");
			return empty(origin);
		}
		JsonObject o = root.getAsJsonObject();
		if (!o.has("format")) {
			problems.warn(origin, "format", "bcr.problem.format_missing", FORMAT);
		} else {
			JsonElement f = o.get("format");
			if (!f.isJsonPrimitive() || !f.getAsJsonPrimitive().isNumber() || f.getAsDouble() != FORMAT) {
				problems.error(origin, "format", "bcr.problem.format_unsupported", f.toString(), FORMAT);
				return empty(origin);
			}
		}
		List<TagRef> tagRefs = new ArrayList<>();
		CondParser conds = new CondParser(origin, catalog, problems, tagRefs);
		List<GroupDef> groups = new ArrayList<>();
		List<RuleLine> rules = new ArrayList<>();
		JsonObject sound = null;
		Map<String, JsonElement> extra = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			String k = e.getKey();
			JsonElement v = e.getValue();
			switch (k) {
				case "format" -> {
				}
				case "groups" -> {
					if (v.isJsonObject()) {
						readGroups(v.getAsJsonObject(), origin, problems, groups);
					} else {
						problems.error(origin, k, "bcr.problem.not_object");
					}
				}
				case "rules" -> {
					if (v.isJsonArray()) {
						readRules(v.getAsJsonArray(), origin, problems, conds, rules);
					} else {
						problems.error(origin, k, "bcr.problem.not_array");
					}
				}
				case "sound" -> {
					if (v.isJsonObject()) {
						sound = v.getAsJsonObject();
					} else {
						problems.error(origin, k, "bcr.problem.not_object");
					}
				}
				default -> {
					if (extraKeys.contains(k)) {
						extra.put(k, v);
					} else {
						problems.warn(origin, k, "bcr.problem.unknown_key", k);
					}
				}
			}
		}
		return new RuleFile(origin, groups, rules, tagRefs, sound, extra);
	}

	private static void readGroups(JsonObject g, Origin origin, Problems problems, List<GroupDef> out) {
		for (Map.Entry<String, JsonElement> e : g.entrySet()) {
			String path = Problems.child("groups", e.getKey());
			String raw = e.getKey().trim();
			String name = Ids.normalize(raw.startsWith("#") ? raw.substring(1) : raw);
			if (name == null) {
				problems.error(origin, path, "bcr.problem.bad_id", raw);
				continue;
			}
			JsonElement v = e.getValue();
			boolean replace = true;
			Registry registry = Registry.BLOCK;
			JsonElement values;
			if (v.isJsonArray()) {
				values = v;
			} else if (v.isJsonObject()) {
				JsonObject d = v.getAsJsonObject();
				boolean ok = true;
				for (String key : d.keySet()) {
					if (!key.equals("replace") && !key.equals("registry") && !key.equals("values")) {
						problems.error(origin, Problems.child(path, key), "bcr.problem.unknown_key", key);
						ok = false;
					}
				}
				if (d.has("replace")) {
					JsonElement r = d.get("replace");
					if (r.isJsonPrimitive() && r.getAsJsonPrimitive().isBoolean()) {
						replace = r.getAsBoolean();
					} else {
						problems.error(origin, Problems.child(path, "replace"), "bcr.problem.bad_value.replace", r.toString());
						ok = false;
					}
				}
				if (d.has("registry")) {
					JsonElement r = d.get("registry");
					registry = r.isJsonPrimitive() && r.getAsJsonPrimitive().isString() ? Registry.forGroups(r.getAsString()) : null;
					if (registry == null) {
						problems.error(origin, Problems.child(path, "registry"), "bcr.problem.bad_value.registry", r.toString());
						ok = false;
					}
				}
				values = d.get("values");
				if (!ok) {
					continue;
				}
			} else {
				problems.error(origin, path, "bcr.problem.not_object");
				continue;
			}
			String vp = Problems.child(path, "values");
			if (values == null || !values.isJsonArray()) {
				problems.error(origin, vp, "bcr.problem.not_array");
				continue;
			}
			List<String> list = new ArrayList<>();
			JsonArray a = values.getAsJsonArray();
			boolean ok = true;
			for (int i = 0; i < a.size(); i++) {
				JsonElement x = a.get(i);
				String s = x.isJsonPrimitive() && x.getAsJsonPrimitive().isString() ? x.getAsString().trim() : null;
				boolean isTag = s != null && s.startsWith("#");
				String id = s == null ? null : Ids.normalize(isTag ? s.substring(1) : s);
				if (id == null) {
					problems.error(origin, Problems.index(vp, i), "bcr.problem.bad_id", x.toString());
					ok = false;
					break;
				}
				list.add(isTag ? "#" + id : id);
			}
			if (ok) {
				out.add(new GroupDef(registry, name, replace, List.copyOf(list), origin, path));
			}
		}
	}

	private static void readRules(JsonArray a, Origin origin, Problems problems, CondParser conds, List<RuleLine> out) {
		for (int i = 0; i < a.size(); i++) {
			String path = Problems.index("rules", i);
			JsonElement e = a.get(i);
			if (!e.isJsonObject()) {
				problems.error(origin, path, "bcr.problem.not_object");
				continue;
			}
			JsonObject r = e.getAsJsonObject();
			if (!r.has("target")) {
				problems.error(origin, path, "bcr.problem.missing_target");
				continue;
			}
			Cond<StateView> target = conds.block(r.get("target"), Problems.child(path, "target"));
			boolean ok = target != null;
			Map<Field, Object> values = new EnumMap<>(Field.class);
			for (Map.Entry<String, JsonElement> f : r.entrySet()) {
				if (f.getKey().equals("target")) {
					continue;
				}
				Field field = Field.byKey(f.getKey());
				String fp = Problems.child(path, f.getKey());
				if (field == null) {
					problems.warn(origin, fp, "bcr.problem.unknown_key", f.getKey());
					continue;
				}
				Object v = field.parse(f.getValue(), origin, fp, problems);
				if (v == null) {
					ok = false;
				} else {
					values.put(field, v);
				}
			}
			if (!ok) {
				continue;
			}
			if (values.isEmpty()) {
				problems.warn(origin, path, "bcr.problem.no_fields");
				continue;
			}
			out.add(new RuleLine(target, values, origin, path));
		}
	}
}
