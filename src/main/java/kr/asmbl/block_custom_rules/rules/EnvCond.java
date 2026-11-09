package kr.asmbl.block_custom_rules.rules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 위치 · 날씨 · 시간 조건(D8 · S2) — 바닐라 `location` 꼴 + 전리품 `weather_check` · `time_check` 꼴 + 덧칸 `precipitation`.
 * `location` 칸: `biomes`(이름 · 목록 · `#태그`) · `dimension` · `light` {`light`: 범위} · `can_see_sky` · `fluid` {`fluids`} ·
 * `block`(블록 조건) · `position` {`x` · `y` · `z` 범위} · `precipitation`(덧칸 `rain` · `snow` · `none`).
 * `structures` 는 클라에 구조물 정보가 없어 거부 · `smokey` 는 안 받는다. 기준 자리 = 소리를 낸 엔티티의 발 블록.
 * 판정은 직접 한다(바닐라 판정은 서버 레벨을 받는다). 안 읽힌 청크면 거짓.
 */
public final class EnvCond {
	/** 정수 범위(끝 포함) · 빈 끝은 열림. */
	public record IntRange(Double min, Double max) {
		boolean test(double v) {
			return (this.min == null || v >= this.min) && (this.max == null || v <= this.max);
		}
	}

	private Set<String> biomeIds;
	private String biomeTag;
	private String dimension;
	private IntRange light;
	private Boolean canSeeSky;
	private Set<String> fluidIds;
	private String fluidTag;
	private Cond<StateView> block;
	private IntRange px;
	private IntRange py;
	private IntRange pz;
	private String precipitation;
	private Boolean raining;
	private Boolean thundering;
	private Long period;
	private IntRange time;

	private EnvCond() {
	}

	public boolean test(WorldProbe w, TagView tags, int x, int y, int z) {
		if (this.raining != null && this.raining != w.raining() || this.thundering != null && this.thundering != w.thundering()) {
			return false;
		}
		if (this.time != null) {
			long t = w.dayTime();
			if (this.period != null && this.period > 0) {
				t = Math.floorMod(t, this.period);
			}
			if (!this.time.test(t)) {
				return false;
			}
		}
		if (this.dimension != null && !this.dimension.equals(w.dimension())) {
			return false;
		}
		if (this.px != null && !this.px.test(x) || this.py != null && !this.py.test(y) || this.pz != null && !this.pz.test(z)) {
			return false;
		}
		boolean needsWorld = this.biomeIds != null || this.biomeTag != null || this.light != null || this.canSeeSky != null || this.fluidIds != null
				|| this.fluidTag != null || this.block != null || this.precipitation != null;
		if (!needsWorld) {
			return true;
		}
		if (!w.loaded(x, y, z)) {
			return false;
		}
		if (this.biomeIds != null || this.biomeTag != null) {
			String b = w.biome(x, y, z);
			boolean in = b != null && (this.biomeIds != null && this.biomeIds.contains(b) || this.biomeTag != null && contains(tags.members(Registry.BIOME, this.biomeTag), b));
			if (!in) {
				return false;
			}
		}
		if (this.light != null && !this.light.test(w.light(x, y, z))) {
			return false;
		}
		if (this.canSeeSky != null && this.canSeeSky != w.canSeeSky(x, y, z)) {
			return false;
		}
		if (this.fluidIds != null || this.fluidTag != null) {
			String f = w.fluid(x, y, z);
			if (f == null || !(this.fluidIds != null && this.fluidIds.contains(f) || this.fluidTag != null && contains(tags.members(Registry.FLUID, this.fluidTag), f))) {
				return false;
			}
		}
		if (this.block != null) {
			StateView s = w.block(x, y, z);
			if (s == null || !this.block.test(s, tags)) {
				return false;
			}
		}
		return this.precipitation == null || this.precipitation.equals(w.precipitation(x, y, z));
	}

	private static boolean contains(Set<String> s, String v) {
		return s != null && s.contains(v);
	}

	// ── 읽기 ──

	/** `location` · `weather` · `time` 세 칸을 한 조건으로 — 다 없으면 null(언제나). 틀리면 오류를 적고 {@link #INVALID}. */
	public static EnvCond parse(JsonElement location, JsonElement weather, JsonElement time, String base, Origin o, Catalog cat, Problems p,
			List<TagRef> refs) {
		if (location == null && weather == null && time == null) {
			return null;
		}
		EnvCond c = new EnvCond();
		boolean ok = true;
		if (location != null) {
			ok &= readLocation(c, location, Problems.child(base, "location"), o, cat, p, refs);
		}
		if (weather != null) {
			ok &= readWeather(c, weather, Problems.child(base, "weather"), o, p);
		}
		if (time != null) {
			ok &= readTime(c, time, Problems.child(base, "time"), o, p);
		}
		return ok ? c : INVALID;
	}

	/** 틀린 조건 — 부른 쪽이 그 줄을 버린다. */
	public static final EnvCond INVALID = new EnvCond();

	private static boolean readLocation(EnvCond c, JsonElement e, String path, Origin o, Catalog cat, Problems p, List<TagRef> refs) {
		if (!e.isJsonObject()) {
			p.error(o, path, "bcr.problem.not_object");
			return false;
		}
		for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet()) {
			String fp = Problems.child(path, f.getKey());
			JsonElement v = f.getValue();
			switch (f.getKey()) {
				case "biomes" -> {
					Object[] r = holderSet(v, fp, o, p);
					if (r == null) {
						return false;
					}
					if (r[1] != null) {
						c.biomeTag = (String) r[1];
						refs.add(new TagRef(Registry.BIOME, c.biomeTag, o, fp));
					} else {
						@SuppressWarnings("unchecked")
						Set<String> ids = (Set<String>) r[0];
						c.biomeIds = ids;
					}
				}
				case "dimension" -> {
					c.dimension = str(v) == null ? null : Ids.normalize(str(v));
					if (c.dimension == null) {
						p.error(o, fp, "bcr.problem.bad_id", v.toString());
						return false;
					}
				}
				case "light" -> {
					JsonElement in = v.isJsonObject() ? v.getAsJsonObject().get("light") : null;
					c.light = in == null ? null : range(in, Problems.child(fp, "light"), o, p);
					if (c.light == null) {
						if (in == null) {
							p.error(o, fp, "bcr.problem.bad_condition", v.toString());
						}
						return false;
					}
				}
				case "can_see_sky" -> {
					if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean())) {
						p.error(o, fp, "bcr.problem.bad_condition", v.toString());
						return false;
					}
					c.canSeeSky = v.getAsBoolean();
				}
				case "fluid" -> {
					JsonElement fl = v.isJsonObject() ? v.getAsJsonObject().get("fluids") : null;
					Object[] r = fl == null ? null : holderSet(fl, Problems.child(fp, "fluids"), o, p);
					if (r == null) {
						if (fl == null) {
							p.error(o, fp, "bcr.problem.bad_condition", v.toString());
						}
						return false;
					}
					if (r[1] != null) {
						c.fluidTag = (String) r[1];
					} else {
						@SuppressWarnings("unchecked")
						Set<String> ids = (Set<String>) r[0];
						c.fluidIds = ids;
					}
				}
				case "block" -> {
					c.block = new CondParser(o, cat, p, refs).block(v, fp);
					if (c.block == null) {
						return false;
					}
				}
				case "position" -> {
					if (!v.isJsonObject()) {
						p.error(o, fp, "bcr.problem.not_object");
						return false;
					}
					JsonObject po = v.getAsJsonObject();
					c.px = po.has("x") ? range(po.get("x"), Problems.child(fp, "x"), o, p) : null;
					c.py = po.has("y") ? range(po.get("y"), Problems.child(fp, "y"), o, p) : null;
					c.pz = po.has("z") ? range(po.get("z"), Problems.child(fp, "z"), o, p) : null;
					if (po.has("x") && c.px == null || po.has("y") && c.py == null || po.has("z") && c.pz == null) {
						return false;
					}
				}
				case "precipitation" -> {
					String s = str(v);
					if (!"rain".equals(s) && !"snow".equals(s) && !"none".equals(s)) {
						p.error(o, fp, "bcr.problem.bad_value.precipitation", v.toString());
						return false;
					}
					c.precipitation = s;
				}
				case "structures" -> {
					p.error(o, fp, "bcr.problem.structures");
					return false;
				}
				default -> {
					p.error(o, fp, "bcr.problem.unknown_key", f.getKey());
					return false;
				}
			}
		}
		return true;
	}

	private static boolean readWeather(EnvCond c, JsonElement e, String path, Origin o, Problems p) {
		if (!e.isJsonObject()) {
			p.error(o, path, "bcr.problem.not_object");
			return false;
		}
		for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet()) {
			JsonElement v = f.getValue();
			if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) || !f.getKey().equals("raining") && !f.getKey().equals("thundering")) {
				p.error(o, Problems.child(path, f.getKey()), "bcr.problem.bad_condition", f.getKey());
				return false;
			}
			if (f.getKey().equals("raining")) {
				c.raining = v.getAsBoolean();
			} else {
				c.thundering = v.getAsBoolean();
			}
		}
		return true;
	}

	private static boolean readTime(EnvCond c, JsonElement e, String path, Origin o, Problems p) {
		if (!e.isJsonObject() || !e.getAsJsonObject().has("value")) {
			p.error(o, path, "bcr.problem.bad_condition", e.toString());
			return false;
		}
		JsonObject t = e.getAsJsonObject();
		c.time = range(t.get("value"), Problems.child(path, "value"), o, p);
		if (t.has("period")) {
			JsonElement pe = t.get("period");
			if (!(pe.isJsonPrimitive() && pe.getAsJsonPrimitive().isNumber())) {
				p.error(o, Problems.child(path, "period"), "bcr.problem.bad_condition", pe.toString());
				return false;
			}
			c.period = pe.getAsLong();
		}
		return c.time != null;
	}

	/** 수 하나 또는 {min, max}. */
	static IntRange range(JsonElement v, String path, Origin o, Problems p) {
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
			double d = v.getAsDouble();
			return new IntRange(d, d);
		}
		if (v.isJsonObject()) {
			JsonObject r = v.getAsJsonObject();
			Double min = num(r.get("min"));
			Double max = num(r.get("max"));
			if ((min != null || max != null) && (!r.has("min") || min != null) && (!r.has("max") || max != null)) {
				return new IntRange(min, max);
			}
		}
		p.error(o, path, "bcr.problem.bad_condition", v.toString());
		return null;
	}

	private static Double num(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
	}

	private static String str(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	/** 바닐라 HolderSet 꼴 — 이름 · 목록 · `#태그` → {이름 집합, 태그}. */
	private static Object[] holderSet(JsonElement v, String path, Origin o, Problems p) {
		String s = str(v);
		if (s != null) {
			boolean tag = s.startsWith("#");
			String id = Ids.normalize(tag ? s.substring(1) : s);
			if (id == null) {
				p.error(o, path, "bcr.problem.bad_id", s);
				return null;
			}
			return tag ? new Object[] { null, id } : new Object[] { Set.of(id), null };
		}
		if (v.isJsonArray() && !v.getAsJsonArray().isEmpty()) {
			Set<String> ids = new LinkedHashSet<>();
			for (JsonElement x : v.getAsJsonArray()) {
				String id = str(x) == null || str(x).startsWith("#") ? null : Ids.normalize(str(x));
				if (id == null) {
					p.error(o, path, "bcr.problem.bad_id", x.toString());
					return null;
				}
				ids.add(id);
			}
			return new Object[] { Set.copyOf(ids), null };
		}
		p.error(o, path, "bcr.problem.bad_condition", v.toString());
		return null;
	}

	/** 시험 · 화면용 — 무엇을 보는 조건인가. */
	public List<String> describe() {
		List<String> out = new ArrayList<>();
		if (this.biomeIds != null || this.biomeTag != null) {
			out.add("biomes");
		}
		if (this.raining != null || this.thundering != null) {
			out.add("weather");
		}
		if (this.time != null) {
			out.add("time");
		}
		return out;
	}
}
