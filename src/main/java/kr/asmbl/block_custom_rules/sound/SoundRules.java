package kr.asmbl.block_custom_rules.sound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import kr.asmbl.block_custom_rules.rules.Catalog;
import kr.asmbl.block_custom_rules.rules.Cond;
import kr.asmbl.block_custom_rules.rules.CondParser;
import kr.asmbl.block_custom_rules.rules.EnvCond;
import kr.asmbl.block_custom_rules.rules.Ids;
import kr.asmbl.block_custom_rules.rules.Origin;
import kr.asmbl.block_custom_rules.rules.Problems;
import kr.asmbl.block_custom_rules.rules.RuleFile;
import kr.asmbl.block_custom_rules.rules.StateView;
import kr.asmbl.block_custom_rules.rules.TagRef;
import kr.asmbl.block_custom_rules.rules.TagView;
import kr.asmbl.block_custom_rules.rules.WorldProbe;

/**
 * `sound` 칸의 나머지(D16 · D21 ~ D23) — `defaults` · `layers`(겹 소리) · `mix`(겹침 섞기) · `entities`. 마인크래프트 클래스를 모른다.
 * 이름표 칸(`defaults` · `layers`)은 같은 이름이면 위 층 · 나중 파일이 통째로 대신 · 목록 칸(`mix` · `entities`)은 값 칸마다 나중 줄 · 위 층이 이긴다.
 */
public final class SoundRules {
	/** 자리 · 채널 이름 한 벌(research §1-5). */
	public static final Set<String> CHANNELS = Set.of("ground", "top", "bottom", "through", "enter", "exit", "footwear", "chime", "mob", "*");
	/** `when` 의 블록 자리. */
	public static final Set<String> BLOCK_SPOTS = Set.of("ground", "top", "bottom", "through", "enter", "exit");
	private static final int MAX_DEPTH = 8;

	/** 범위 — 끝을 포함한 무작위 · 같으면 그 값. */
	public record Range(float min, float max) {
		public static final Range ONE = new Range(1.0F, 1.0F);

		public float roll(DoubleSupplier r) {
			return this.min == this.max ? this.min : (float) (this.min + (this.max - this.min) * r.getAsDouble());
		}
	}

	/** 겹 소리 줄 — `sound`(이름 그대로 · 다시 안 풂) 또는 `include`(다른 이름 끌어오기 · 묶음 한 번 굴리기). */
	public record LayerLine(String sound, String include, Range volume, Range pitch, int delayMs, float chance) {
	}

	/** 낼 소리 하나 — 이름 · 음량 · 높낮이 · 지연. */
	public record Play(String name, float volume, float pitch, int delayMs) {
	}

	/** 섞기 줄 하나. */
	public record MixLine(Map<String, Cond<StateView>> blocks, Cond<String> entity, EnvCond env, Set<String> actions, Boolean sneak, Boolean footwear,
			Map<String, Float> volume, Map<String, Float> pitch, Map<String, Integer> delay, List<String> add, int layer) {
	}

	/** 엔티티 줄 하나(D23). */
	public record EntityLine(Cond<String> target, String locomotion, Boolean sizeScaling, int layer) {
	}

	/** 섞기 재료 — 그 걸음의 자리별 블록 · 엔티티 · 행동 · 월드. */
	public record MixContext(Map<String, StateView> blocks, String entity, String action, boolean sneak, boolean footwear, WorldProbe world, int x, int y,
			int z, boolean mob) {
		public MixContext(Map<String, StateView> blocks, String entity, String action, boolean sneak, boolean footwear, WorldProbe world, int x, int y,
				int z) {
			this(blocks, entity, action, sneak, footwear, world, x, y, z, false);
		}
	}

	/** 섞기 결과 — 채널별 배율 · 지연 · 더할 이름. */
	public record MixResult(Map<String, Float> volume, Map<String, Float> pitch, Map<String, Integer> delay, List<String> add) {
		public float volume(String channel) {
			return this.volume.getOrDefault(channel, this.volume.getOrDefault("*", 1.0F));
		}

		public float pitch(String channel) {
			return this.pitch.getOrDefault(channel, this.pitch.getOrDefault("*", 1.0F));
		}

		public int delay(String channel) {
			return this.delay.getOrDefault(channel, this.delay.getOrDefault("*", 0));
		}
	}

	public static final MixResult NO_MIX = new MixResult(Map.of(), Map.of(), Map.of(), List.of());
	public static final SoundRules EMPTY = new SoundRules(Range.ONE, Range.ONE, Map.of(), List.of(), List.of(), null);

	/** 모드 안 기본 섞기(D22) — 신발이면 땅 반 · 지나감 · 입장 · 퇴장 끔. 리팩 줄이 덮는다. */
	static final MixLine DEFAULT_FOOTWEAR = new MixLine(Map.of(), null, null, null, null, true,
			Map.of("ground", 0.5F, "through", 0.0F, "enter", 0.0F, "exit", 0.0F), Map.of(), Map.of(), List.of(), 0);

	/** 모드 안 기본 섞기(D22) — 몹은 바닐라 몹 걸음(`mob`) 그대로 · 블록 걸음 · 겹침 위 · 아래 · 덧소리는 끔. 리팩 줄이 덮는다. */
	static final Map<String, Float> DEFAULT_MOB = Map.of("mob", 1.0F, "ground", 0.0F, "top", 0.0F, "bottom", 0.0F, "through", 0.0F, "enter", 0.0F,
			"exit", 0.0F);

	public final Range volume;
	public final Range pitch;
	private final Map<String, List<LayerLine>> layers;
	private final List<MixLine> mix;
	private final List<EntityLine> entities;
	private final TagView tags;

	private SoundRules(Range volume, Range pitch, Map<String, List<LayerLine>> layers, List<MixLine> mix, List<EntityLine> entities, TagView tags) {
		this.volume = volume;
		this.pitch = pitch;
		this.layers = Collections.unmodifiableMap(layers);
		this.mix = List.copyOf(mix);
		this.entities = List.copyOf(entities);
		this.tags = tags;
	}

	public boolean hasLayers(String name) {
		return this.layers.containsKey(name);
	}

	public Map<String, List<LayerLine>> layers() {
		return this.layers;
	}

	public List<MixLine> mix() {
		return this.mix;
	}

	public List<EntityLine> entities() {
		return this.entities;
	}

	// ── 펼치기 · 섞기 ──

	/**
	 * 이름 하나를 낼 소리들로 — `layers` 에 있으면 줄마다(include 는 묶음 한 번 굴려 안으로 · 지연은 더한다) · 없으면 그 이름 하나(기본 범위).
	 * 서로 물고 도는 include 는 끊는다.
	 */
	public List<Play> expand(String name, DoubleSupplier rnd) {
		List<Play> out = new ArrayList<>();
		expand(name, 0, new HashSet<>(), rnd, out, 0);
		return out;
	}

	private void expand(String name, int delay, Set<String> seen, DoubleSupplier rnd, List<Play> out, int depth) {
		List<LayerLine> lines = this.layers.get(name);
		if (lines == null) {
			out.add(new Play(name, this.volume.roll(rnd), this.pitch.roll(rnd), delay));
			return;
		}
		if (depth >= MAX_DEPTH || !seen.add(name)) {
			return;
		}
		for (LayerLine l : lines) {
			if (l.chance < 1.0F && rnd.getAsDouble() >= l.chance) {
				continue;
			}
			if (l.include != null) {
				int before = out.size();
				expand(l.include, delay + l.delayMs, seen, rnd, out, depth + 1);
				// include 줄의 음량 · 높낮이 범위는 그 묶음 전체에 곱한다
				float v = l.volume.roll(rnd);
				float p = l.pitch.roll(rnd);
				for (int i = before; i < out.size(); i++) {
					Play x = out.get(i);
					out.set(i, new Play(x.name, x.volume * v, x.pitch * p, x.delayMs));
				}
			} else {
				out.add(new Play(l.sound, l.volume.roll(rnd), l.pitch.roll(rnd), delay + l.delayMs));
			}
		}
		seen.remove(name);
	}

	/** 맞는 섞기 줄을 차례로 겹친다 — 값 칸마다 나중 줄이 이기고, 더할 이름은 모은다. 신발이면 모드 기본 줄이 맨 아래. */
	public MixResult mix(MixContext c) {
		Map<String, Float> v = new LinkedHashMap<>();
		Map<String, Float> p = new LinkedHashMap<>();
		Map<String, Integer> d = new LinkedHashMap<>();
		Set<String> add = new LinkedHashSet<>();
		if (c.mob) {
			v.putAll(DEFAULT_MOB);
		}
		if (c.footwear) {
			v.putAll(DEFAULT_FOOTWEAR.volume);
		}
		boolean any = c.footwear || c.mob;
		for (MixLine l : this.mix) {
			if (!matches(l, c)) {
				continue;
			}
			any = true;
			// 나중 줄의 `*` 는 앞 줄의 채널 값까지 덮는다(«모든 채널») — 같은 줄의 채널 값은 그 위에
			over(v, l.volume);
			over(p, l.pitch);
			over(d, l.delay);
			add.addAll(l.add);
		}
		return any ? new MixResult(v, p, d, List.copyOf(add)) : NO_MIX;
	}

	private static <T> void over(Map<String, T> acc, Map<String, T> line) {
		if (line.containsKey("*")) {
			acc.clear();
		}
		acc.putAll(line);
	}

	private boolean matches(MixLine l, MixContext c) {
		if (l.actions != null && !l.actions.contains(c.action)) {
			return false;
		}
		if (l.sneak != null && l.sneak != c.sneak || l.footwear != null && l.footwear != c.footwear) {
			return false;
		}
		TagView t = this.tags == null ? (r, x) -> null : this.tags;
		for (Map.Entry<String, Cond<StateView>> e : l.blocks.entrySet()) {
			StateView s = c.blocks.get(e.getKey());
			if (s == null || !e.getValue().test(s, t)) {
				return false;
			}
		}
		if (l.entity != null && (c.entity == null || !l.entity.test(c.entity, t))) {
			return false;
		}
		return l.env == null || c.world != null && l.env.test(c.world, t, c.x, c.y, c.z);
	}

	/** 그 엔티티 종류의 엔티티 규칙 — 칸마다 나중 줄이 이긴 값 · 없으면 null 칸. */
	public EntityLine entity(String type) {
		TagView t = this.tags == null ? (r, x) -> null : this.tags;
		String loco = null;
		Boolean size = null;
		boolean any = false;
		for (EntityLine e : this.entities) {
			if (e.target.test(type, t)) {
				any = true;
				if (e.locomotion != null) {
					loco = e.locomotion;
				}
				if (e.sizeScaling != null) {
					size = e.sizeScaling;
				}
			}
		}
		return any ? new EntityLine(null, loco, size, 0) : null;
	}

	// ── 읽기 ──

	/** 파일들(아래 층부터)의 `sound` 칸 — 조건의 태그는 맨 위 층 창으로 본다. */
	public static SoundRules read(List<RuleFile> files, Catalog cat, Problems p, TagView tags, List<TagRef> refs) {
		Range vol = Range.ONE;
		Range pit = Range.ONE;
		Map<String, List<LayerLine>> layers = new LinkedHashMap<>();
		List<MixLine> mix = new ArrayList<>();
		List<EntityLine> ents = new ArrayList<>();
		for (RuleFile f : files) {
			JsonObject s = f.sound;
			if (s == null) {
				continue;
			}
			for (Map.Entry<String, JsonElement> e : s.entrySet()) {
				String path = Problems.child("sound", e.getKey());
				JsonElement v = e.getValue();
				switch (e.getKey()) {
					case "categories", "actions" -> {
					}
					case "defaults" -> {
						if (!v.isJsonObject()) {
							p.error(f.origin, path, "bcr.problem.not_object");
							break;
						}
						for (Map.Entry<String, JsonElement> d : v.getAsJsonObject().entrySet()) {
							Range r = range(d.getValue(), Problems.child(path, d.getKey()), f.origin, p);
							if (r == null) {
								continue;
							}
							if (d.getKey().equals("volume")) {
								vol = r;
							} else if (d.getKey().equals("pitch")) {
								pit = r;
							} else {
								p.warn(f.origin, Problems.child(path, d.getKey()), "bcr.problem.unknown_key", d.getKey());
							}
						}
					}
					case "layers" -> {
						if (v.isJsonObject()) {
							readLayers(f.origin, v.getAsJsonObject(), path, p, layers);
						} else {
							p.error(f.origin, path, "bcr.problem.not_object");
						}
					}
					case "mix" -> {
						if (v.isJsonArray()) {
							readMix(f.origin, v.getAsJsonArray(), path, cat, p, refs, mix);
						} else {
							p.error(f.origin, path, "bcr.problem.not_array");
						}
					}
					case "entities" -> {
						if (v.isJsonArray()) {
							readEntities(f.origin, v.getAsJsonArray(), path, cat, p, refs, ents);
						} else {
							p.error(f.origin, path, "bcr.problem.not_array");
						}
					}
					default -> p.warn(f.origin, path, "bcr.problem.unknown_key", e.getKey());
				}
			}
		}
		warnLayerCycles(files, layers, p);
		return new SoundRules(vol, pit, layers, mix, ents, tags);
	}

	private static void readLayers(Origin o, JsonObject obj, String path, Problems p, Map<String, List<LayerLine>> out) {
		for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
			String lp = Problems.child(path, e.getKey());
			String name = Ids.normalize(e.getKey());
			if (name == null || !e.getValue().isJsonArray()) {
				p.error(o, lp, name == null ? "bcr.problem.bad_id" : "bcr.problem.not_array", e.getKey());
				continue;
			}
			List<LayerLine> lines = new ArrayList<>();
			boolean ok = true;
			JsonArray a = e.getValue().getAsJsonArray();
			for (int i = 0; i < a.size(); i++) {
				LayerLine l = layerLine(a.get(i), Problems.index(lp, i), o, p);
				if (l == null) {
					ok = false;
					break;
				}
				lines.add(l);
			}
			if (ok) {
				out.put(name, List.copyOf(lines));
			}
		}
	}

	private static LayerLine layerLine(JsonElement e, String path, Origin o, Problems p) {
		if (!e.isJsonObject()) {
			p.error(o, path, "bcr.problem.not_object");
			return null;
		}
		JsonObject l = e.getAsJsonObject();
		String sound = null;
		String include = null;
		Range vol = Range.ONE;
		Range pit = Range.ONE;
		int delay = 0;
		float chance = 1.0F;
		for (Map.Entry<String, JsonElement> f : l.entrySet()) {
			String fp = Problems.child(path, f.getKey());
			JsonElement v = f.getValue();
			switch (f.getKey()) {
				case "sound", "include" -> {
					String id = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? Ids.normalize(v.getAsString()) : null;
					if (id == null) {
						p.error(o, fp, "bcr.problem.bad_id", v.toString());
						return null;
					}
					if (f.getKey().equals("sound")) {
						sound = id;
					} else {
						include = id;
					}
				}
				case "volume" -> {
					vol = range(v, fp, o, p);
					if (vol == null) {
						return null;
					}
				}
				case "pitch" -> {
					pit = range(v, fp, o, p);
					if (pit == null) {
						return null;
					}
				}
				case "delay_ms" -> {
					if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) || v.getAsInt() < 0 || v.getAsInt() > 1000) {
						p.error(o, fp, "bcr.problem.bad_value.delay_ms", v.toString());
						return null;
					}
					delay = v.getAsInt();
				}
				case "chance" -> {
					if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) || v.getAsFloat() < 0 || v.getAsFloat() > 1) {
						p.error(o, fp, "bcr.problem.bad_value.chance", v.toString());
						return null;
					}
					chance = v.getAsFloat();
				}
				default -> p.warn(o, fp, "bcr.problem.unknown_key", f.getKey());
			}
		}
		if ((sound == null) == (include == null)) {
			p.error(o, path, "bcr.problem.layer_line");
			return null;
		}
		return new LayerLine(sound, include, vol, pit, delay, chance);
	}

	private static void readMix(Origin o, JsonArray a, String path, Catalog cat, Problems p, List<TagRef> refs, List<MixLine> out) {
		CondParser cp = new CondParser(o, cat, p, refs);
		for (int i = 0; i < a.size(); i++) {
			String lp = Problems.index(path, i);
			if (!a.get(i).isJsonObject()) {
				p.error(o, lp, "bcr.problem.not_object");
				continue;
			}
			JsonObject l = a.get(i).getAsJsonObject();
			Map<String, Cond<StateView>> blocks = new LinkedHashMap<>();
			Cond<String> entity = null;
			EnvCond env = null;
			Set<String> actions = null;
			Boolean sneak = null;
			Boolean footwear = null;
			boolean ok = true;
			JsonElement when = l.get("when");
			if (when != null) {
				if (!when.isJsonObject()) {
					p.error(o, Problems.child(lp, "when"), "bcr.problem.not_object");
					continue;
				}
				String wp = Problems.child(lp, "when");
				JsonObject w = when.getAsJsonObject();
				for (Map.Entry<String, JsonElement> f : w.entrySet()) {
					String k = f.getKey();
					String fp = Problems.child(wp, k);
					JsonElement v = f.getValue();
					if (BLOCK_SPOTS.contains(k)) {
						Cond<StateView> c = cp.block(v, fp);
						ok &= c != null;
						blocks.put(k, c);
					} else if (k.equals("entity")) {
						entity = cp.entity(v, fp);
						ok &= entity != null;
					} else if (k.equals("action")) {
						actions = new LinkedHashSet<>();
						for (JsonElement x : v.isJsonArray() ? v.getAsJsonArray() : wrap(v)) {
							if (!(x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) || !SoundCategories.actionToken(x.getAsString())) {
								p.error(o, fp, "bcr.problem.bad_action", x.toString());
								ok = false;
							} else {
								actions.add(x.getAsString());
							}
						}
					} else if (k.equals("sneak") || k.equals("footwear")) {
						if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean())) {
							p.error(o, fp, "bcr.problem.bad_condition", v.toString());
							ok = false;
						} else if (k.equals("sneak")) {
							sneak = v.getAsBoolean();
						} else {
							footwear = v.getAsBoolean();
						}
					} else if (!k.equals("location") && !k.equals("weather") && !k.equals("time")) {
						p.error(o, fp, "bcr.problem.unknown_spot", k);
						ok = false;
					}
				}
				env = EnvCond.parse(w.get("location"), w.get("weather"), w.get("time"), wp, o, cat, p, refs);
				ok &= env != EnvCond.INVALID;
			}
			Map<String, Float> vol = channelMap(l.get("volume"), Problems.child(lp, "volume"), o, p, false);
			Map<String, Float> pit = channelMap(l.get("pitch"), Problems.child(lp, "pitch"), o, p, false);
			Map<String, Float> del = channelMap(l.get("delay_ms"), Problems.child(lp, "delay_ms"), o, p, true);
			List<String> add = new ArrayList<>();
			JsonElement ad = l.get("add");
			if (ad != null) {
				for (JsonElement x : ad.isJsonArray() ? ad.getAsJsonArray() : wrap(ad)) {
					String id = x.isJsonPrimitive() && x.getAsJsonPrimitive().isString() ? Ids.normalize(x.getAsString()) : null;
					if (id == null) {
						p.error(o, Problems.child(lp, "add"), "bcr.problem.bad_id", x.toString());
						ok = false;
					} else {
						add.add(id);
					}
				}
			}
			for (String k : l.keySet()) {
				if (!Set.of("when", "volume", "pitch", "delay_ms", "add").contains(k)) {
					p.warn(o, Problems.child(lp, k), "bcr.problem.unknown_key", k);
				}
			}
			if (!ok || vol == null || pit == null || del == null) {
				continue;
			}
			Map<String, Integer> delay = new LinkedHashMap<>();
			del.forEach((k, v) -> delay.put(k, Math.round(v)));
			out.add(new MixLine(blocks, entity, env, actions, sneak, footwear, vol, pit, delay, List.copyOf(add), o.layer()));
		}
	}

	private static JsonArray wrap(JsonElement e) {
		JsonArray a = new JsonArray();
		a.add(e);
		return a;
	}

	/** 채널 → 수 · 틀린 채널 이름은 오류 · 칸이 없으면 빈 표. */
	private static Map<String, Float> channelMap(JsonElement e, String path, Origin o, Problems p, boolean delay) {
		Map<String, Float> out = new LinkedHashMap<>();
		if (e == null) {
			return out;
		}
		if (!e.isJsonObject()) {
			p.error(o, path, "bcr.problem.not_object");
			return null;
		}
		for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet()) {
			String fp = Problems.child(path, f.getKey());
			if (!CHANNELS.contains(f.getKey())) {
				p.error(o, fp, "bcr.problem.unknown_spot", f.getKey());
				return null;
			}
			JsonElement v = f.getValue();
			float max = delay ? 1000.0F : 10.0F;
			if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) || v.getAsFloat() < 0 || v.getAsFloat() > max) {
				p.error(o, fp, "bcr.problem.bad_value.channel", v.toString(), max);
				return null;
			}
			out.put(f.getKey(), v.getAsFloat());
		}
		return out;
	}

	private static void readEntities(Origin o, JsonArray a, String path, Catalog cat, Problems p, List<TagRef> refs, List<EntityLine> out) {
		CondParser cp = new CondParser(o, cat, p, refs);
		for (int i = 0; i < a.size(); i++) {
			String lp = Problems.index(path, i);
			if (!a.get(i).isJsonObject() || !a.get(i).getAsJsonObject().has("target")) {
				p.error(o, lp, a.get(i).isJsonObject() ? "bcr.problem.missing_target" : "bcr.problem.not_object");
				continue;
			}
			JsonObject l = a.get(i).getAsJsonObject();
			Cond<String> target = cp.entity(l.get("target"), Problems.child(lp, "target"));
			String loco = null;
			Boolean size = null;
			boolean ok = target != null;
			for (Map.Entry<String, JsonElement> f : l.entrySet()) {
				JsonElement v = f.getValue();
				String fp = Problems.child(lp, f.getKey());
				switch (f.getKey()) {
					case "target" -> {
					}
					case "locomotion" -> {
						String s = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
						if (!"biped".equals(s) && !"quadruped".equals(s) && !"none".equals(s)) {
							p.error(o, fp, "bcr.problem.bad_value.locomotion", v.toString());
							ok = false;
						}
						loco = s;
					}
					case "size_scaling" -> {
						if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean())) {
							p.error(o, fp, "bcr.problem.bad_value.size_scaling", v.toString());
							ok = false;
						} else {
							size = v.getAsBoolean();
						}
					}
					default -> p.warn(o, fp, "bcr.problem.unknown_key", f.getKey());
				}
			}
			if (ok) {
				out.add(new EntityLine(target, loco, size, o.layer()));
			}
		}
	}

	/** 수 하나 또는 [최소, 최대]. */
	static Range range(JsonElement v, String path, Origin o, Problems p) {
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsFloat() >= 0) {
			return new Range(v.getAsFloat(), v.getAsFloat());
		}
		if (v.isJsonArray() && v.getAsJsonArray().size() == 2) {
			JsonArray a = v.getAsJsonArray();
			if (a.get(0).isJsonPrimitive() && a.get(1).isJsonPrimitive() && a.get(0).getAsJsonPrimitive().isNumber() && a.get(1).getAsJsonPrimitive().isNumber()
					&& a.get(0).getAsFloat() >= 0 && a.get(0).getAsFloat() <= a.get(1).getAsFloat()) {
				return new Range(a.get(0).getAsFloat(), a.get(1).getAsFloat());
			}
		}
		p.error(o, path, "bcr.problem.bad_value.range", v.toString());
		return null;
	}

	/** include 가 서로 물고 돌면 경고(펼치기는 끊는다). */
	private static void warnLayerCycles(List<RuleFile> files, Map<String, List<LayerLine>> layers, Problems p) {
		for (String start : layers.keySet()) {
			if (reaches(layers, start, start, new HashSet<>(), 0)) {
				Origin at = files.isEmpty() ? null : files.get(files.size() - 1).origin;
				for (RuleFile f : files) {
					if (f.sound != null && f.sound.has("layers") && f.sound.get("layers").isJsonObject() && f.sound.getAsJsonObject("layers").has(start)) {
						at = f.origin;
					}
				}
				if (at != null) {
					p.warn(at, Problems.child("sound.layers", start), "bcr.problem.layer_cycle", start);
				}
			}
		}
	}

	private static boolean reaches(Map<String, List<LayerLine>> layers, String from, String target, Set<String> seen, int depth) {
		List<LayerLine> lines = layers.get(from);
		if (lines == null || depth > MAX_DEPTH || !seen.add(from)) {
			return false;
		}
		for (LayerLine l : lines) {
			if (l.include != null && (l.include.equals(target) || reaches(layers, l.include, target, seen, depth + 1))) {
				return true;
			}
		}
		return false;
	}
}
