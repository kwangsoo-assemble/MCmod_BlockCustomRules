package kr.asmbl.block_custom_rules.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.asmbl.block_custom_rules.rules.Catalog;
import kr.asmbl.block_custom_rules.rules.Origin;
import kr.asmbl.block_custom_rules.rules.Problems;
import kr.asmbl.block_custom_rules.rules.RuleFile;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * 모드 옵션 층 `config/block_custom_rules.json`(D4 · D5 맨 위 층) — 리팩 규칙 파일과 같은 꼴 + 맨 위 `options` 칸.
 * 모드 안 기본 규칙은 없다 — 처음엔 빈 `rules` 로 만든다. 옛 2.0.0 의 `block_render_rules.json` 은 읽지 않고 지우지도 않는다.
 * 못 읽으면 파일을 덮지 않고 직전 내용을 쓴다(손으로 고치다 틀린 것일 수 있다).
 */
public final class OptionsFile {
	private static final Logger LOG = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final String NAME = "block_custom_rules.json";
	public static final float BLEND_DEFAULT = 0.25F;
	public static final float BLEND_MAX = 5.0F;
	private static final String DEFAULT_TEXT = "{\n  \"format\": 1,\n  \"options\": {\n    \"sway_blend_seconds\": 0.25,\n    \"problem_toast\": false\n  },\n  \"rules\": []\n}\n";

	private static volatile String text = DEFAULT_TEXT;
	private static volatile float blend = BLEND_DEFAULT;
	private static volatile boolean problemToast = false;

	private OptionsFile() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(NAME);
	}

	/** 휘기 블렌딩 초(0 ~ 5). */
	public static float blendSeconds() {
		return blend;
	}

	/** 접속할 때 규칙 문제가 있으면 토스트로 알릴까 — 기본 끔(촬영 규율: 진단은 탤런트 화면에 안 띄운다). */
	public static boolean problemToast() {
		return problemToast;
	}

	/** 파일을 읽는다 — 없으면 기본 꼴로 만든다. 못 읽으면 false · 직전 내용 그대로. */
	public static boolean load() {
		Path f = file();
		try {
			if (!Files.exists(f)) {
				Files.createDirectories(f.getParent());
				Files.writeString(f, DEFAULT_TEXT, StandardCharsets.UTF_8);
				text = DEFAULT_TEXT;
				return true;
			}
			text = Files.readString(f, StandardCharsets.UTF_8);
			return true;
		} catch (IOException e) {
			LOG.warn("[block_custom_rules] {} 를 못 읽어 직전 내용을 쓴다: {}", f, e.toString());
			return false;
		}
	}

	/** 맨 위 층 규칙 파일로 읽는다 — `options` 칸도 여기서 읽어 둔다. */
	public static RuleFile ruleFile(int layer, Catalog catalog, Problems problems) {
		Origin origin = Origin.options(layer, NAME);
		RuleFile rf = RuleFile.parse(origin, text, catalog, problems, Set.of("options"));
		float b = BLEND_DEFAULT;
		boolean toast = false;
		JsonElement o = rf.extra.get("options");
		if (o != null && o.isJsonObject()) {
			for (var e : o.getAsJsonObject().entrySet()) {
				String path = Problems.child("options", e.getKey());
				JsonElement v = e.getValue();
				switch (e.getKey()) {
					case "sway_blend_seconds" -> {
						if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && Float.isFinite(v.getAsFloat())) {
							b = Math.max(0.0F, Math.min(BLEND_MAX, v.getAsFloat()));
						} else {
							problems.error(origin, path, "bcr.problem.bad_value.sway_blend_seconds", v.toString());
						}
					}
					case "problem_toast" -> {
						if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
							toast = v.getAsBoolean();
						} else {
							problems.error(origin, path, "bcr.problem.bad_value.problem_toast", v.toString());
						}
					}
					default -> problems.warn(origin, path, "bcr.problem.unknown_key", e.getKey());
				}
			}
		} else if (o != null) {
			problems.error(origin, "options", "bcr.problem.not_object");
		}
		blend = b;
		problemToast = toast;
		return rf;
	}

	// ── 설정 화면이 고치는 줄 ──

	/**
	 * 모드 옵션 줄 하나. 화면은 대상 글자 · 렌더 · 컬링 · 휘기만 고친다(🙋 «단순 문자열 입력하는 방식만»).
	 * 그 밖의 꼴(조합 대상 · `"default"` 컬링 등)은 {@link #editable} 이 false — 화면은 흐리게 보이고 저장할 때 그대로 둔다.
	 */
	public static final class Line {
		public String target;
		/** null = 칸 없음 · {@link Render#DEFAULT} = `"default"`(되돌림). */
		public Render render;
		public Boolean cullPass;
		public Float sway;
		/** 화면이 안 고치는 나머지 칸(소리 종류 · step_as …) — 그대로 다시 쓴다. */
		public JsonObject rest = new JsonObject();
		public boolean editable = true;
		/** editable 이 false 면 원래 줄 통째. */
		public JsonElement raw;

		public Line(String target) {
			this.target = target;
		}

		public Line copy() {
			Line l = new Line(this.target);
			l.render = this.render;
			l.cullPass = this.cullPass;
			l.sway = this.sway;
			l.rest = this.rest.deepCopy();
			l.editable = this.editable;
			l.raw = this.raw == null ? null : this.raw.deepCopy();
			return l;
		}

		JsonElement toJson() {
			if (!this.editable) {
				return this.raw;
			}
			JsonObject o = new JsonObject();
			o.addProperty("target", this.target);
			if (this.render != null) {
				o.addProperty("render", this.render.id);
			}
			if (this.cullPass != null) {
				o.addProperty("cull_pass", this.cullPass);
			}
			if (this.sway != null) {
				o.addProperty("sway", this.sway);
			}
			for (var e : this.rest.entrySet()) {
				o.add(e.getKey(), e.getValue());
			}
			return o;
		}
	}

	/** 지금 파일의 줄들(파일 차례 = 아래 줄이 이긴다) · 못 읽으면 빈 목록. */
	public static List<Line> lines() {
		List<Line> out = new ArrayList<>();
		JsonObject root;
		try {
			JsonElement e = JsonParser.parseString(text);
			if (!e.isJsonObject()) {
				return out;
			}
			root = e.getAsJsonObject();
		} catch (RuntimeException e) {
			return out;
		}
		JsonElement rules = root.get("rules");
		if (rules == null || !rules.isJsonArray()) {
			return out;
		}
		for (JsonElement e : rules.getAsJsonArray()) {
			out.add(toLine(e));
		}
		return out;
	}

	/** JSON 줄 하나를 화면 줄로(설정 화면 «모드 옵션으로 복사»). */
	public static Line lineFrom(JsonElement e) {
		return toLine(e);
	}

	private static Line toLine(JsonElement e) {
		if (!e.isJsonObject() || !isString(e.getAsJsonObject().get("target"))) {
			Line l = new Line(e.toString());
			l.editable = false;
			l.raw = e;
			return l;
		}
		JsonObject o = e.getAsJsonObject();
		Line l = new Line(o.get("target").getAsString());
		boolean ok = true;
		for (var f : o.entrySet()) {
			JsonElement v = f.getValue();
			switch (f.getKey()) {
				case "target" -> {
				}
				case "render" -> {
					Render r = isString(v) ? Render.parse(v.getAsString()) : null;
					ok &= r != null;
					l.render = r;
				}
				case "cull_pass" -> {
					ok &= v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
					l.cullPass = ok ? v.getAsBoolean() : null;
				}
				case "sway" -> {
					ok &= v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
					l.sway = ok ? v.getAsFloat() : null;
				}
				default -> l.rest.add(f.getKey(), v);
			}
		}
		if (!ok) {
			l.editable = false;
			l.raw = e;
		}
		return l;
	}

	private static boolean isString(JsonElement e) {
		return e instanceof JsonPrimitive p && p.isString();
	}

	/** 화면 «완료» — 줄(파일 차례) · 블렌딩을 쓰고 새 내용으로 바꾼다. 다시 풀기는 부른 쪽이 한다. */
	public static boolean save(List<Line> lines, float blendSeconds) {
		JsonObject root;
		try {
			JsonElement e = JsonParser.parseString(text);
			root = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException e) {
			root = new JsonObject();
		}
		JsonObject next = new JsonObject();
		next.addProperty("format", RuleFile.FORMAT);
		JsonObject opts = root.get("options") instanceof JsonObject old ? old.deepCopy() : new JsonObject();
		opts.addProperty("sway_blend_seconds", Math.max(0.0F, Math.min(BLEND_MAX, blendSeconds)));
		if (!opts.has("problem_toast")) {
			opts.addProperty("problem_toast", false);
		}
		next.add("options", opts);
		for (var e : root.entrySet()) {
			if (!e.getKey().equals("format") && !e.getKey().equals("options") && !e.getKey().equals("rules")) {
				next.add(e.getKey(), e.getValue());
			}
		}
		JsonArray arr = new JsonArray();
		for (Line l : lines) {
			arr.add(l.toJson());
		}
		next.add("rules", arr);
		String out = GSON.toJson(next) + "\n";
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			Files.writeString(f, out, StandardCharsets.UTF_8);
			text = out;
			return true;
		} catch (IOException e) {
			LOG.warn("[block_custom_rules] {} 를 못 썼다: {}", f, e.toString());
			return false;
		}
	}
}
