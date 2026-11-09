package kr.asmbl.block_render_rules.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

/**
 * `config/block_render_rules.json` — 블록마다 규칙 하나 · 맨 위에 휘기 블렌딩 초.
 * 청크를 짓는 스레드 여럿이 동시에 읽으므로 표는 통째로 바꿔 끼운다(volatile · 바뀌지 않는 표).
 */
public final class BrrConfig {
	private static final Logger LOG = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir();
	private static final Path FILE = DIR.resolve("block_render_rules.json");
	public static final float BLEND_DEFAULT = 0.25F;

	private static volatile Map<String, Rule> entries = defaults();
	private static volatile float blendSeconds = BLEND_DEFAULT;
	// 블록 등록이 끝나기 전에 ID 를 풀면 모드 블록이 빠진다 — 처음 쓸 때 푼다
	private static volatile Map<Block, Rule> resolved = null;
	// 컬링 패스 판정이 이웃마다 표를 뒤지지 않게 — 켜진 블록이 하나도 없으면 바로 빠진다
	private static volatile boolean anyCullPass = true;

	private BrrConfig() {
	}

	/** 그 블록의 규칙 · 없으면 null. 청크 스레드에서 불린다. */
	public static Rule rule(Block block) {
		return resolved().get(block);
	}

	public static boolean anyCullPass() {
		resolved();
		return anyCullPass;
	}

	public static Map<String, Rule> entries() {
		return entries;
	}

	public static float blendSeconds() {
		return blendSeconds;
	}

	/** 등록되지 않은 ID(오타 · 없는 모드) — 옵션 화면 · 명령이 알린다. */
	public static boolean known(String id) {
		return lookup(id) != null;
	}

	public static String normalize(String id) {
		String t = id.trim();
		return t.contains(":") ? t : "minecraft:" + t;
	}

	/** 명령 «reload» — 파일을 다시 읽고 청크를 다시 짓는다. 못 읽으면 직전 값 그대로 · 파일은 덮지 않는다. */
	public static boolean reload() {
		boolean ok = load();
		Minecraft mc = Minecraft.getInstance();
		if (ok && mc.level != null) {
			mc.levelRenderer.allChanged();
		}
		return ok;
	}

	public static boolean load() {
		if (!Files.exists(FILE)) {
			Map<String, Rule> migrated = Migration.fromOldFiles(DIR);
			entries = Collections.unmodifiableMap(migrated != null ? migrated : defaults());
			resolved = null;
			save();
			return true;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
			float blend = root.has("sway_blend_seconds") ? root.get("sway_blend_seconds").getAsFloat() : BLEND_DEFAULT;
			Map<String, Rule> read = new LinkedHashMap<>();
			JsonObject blocks = root.getAsJsonObject("blocks");
			if (blocks != null) {
				for (Map.Entry<String, JsonElement> e : blocks.entrySet()) {
					read.put(normalize(e.getKey()), parseRule(e.getKey(), e.getValue().getAsJsonObject()));
				}
			}
			entries = Collections.unmodifiableMap(read);
			blendSeconds = clampBlend(blend);
			resolved = null;
			return true;
		} catch (IOException | RuntimeException e) {
			// 파일을 덮지 않는다 — 손으로 고치다 틀린 것일 수 있다 · 직전 값을 그대로 쓴다
			LOG.warn("[block_render_rules] {} 를 못 읽어 직전 설정을 쓴다: {}", FILE, e.toString());
			return false;
		}
	}

	/** 옵션 화면 «완료» · 명령 — 저장하고, 월드가 열려 있으면 청크를 다시 지어 바로 보이게 한다. */
	public static void apply(Map<String, Rule> next, float blend) {
		Map<String, Rule> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Rule> e : next.entrySet()) {
			copy.put(normalize(e.getKey()), e.getValue());
		}
		entries = Collections.unmodifiableMap(copy);
		blendSeconds = clampBlend(blend);
		resolved = null;
		save();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			mc.levelRenderer.allChanged();
		}
	}

	private static float clampBlend(float v) {
		return Float.isFinite(v) ? Math.max(0.0F, Math.min(5.0F, v)) : BLEND_DEFAULT;
	}

	private static Rule parseRule(String id, JsonObject o) {
		Render render = Render.DEFAULT;
		if (o.has("render")) {
			Render r = Render.parse(o.get("render").getAsString());
			if (r == null) {
				LOG.warn("[block_render_rules] {} 의 render 값 «{}» 을 모른다 — default 로 둔다", id, o.get("render").getAsString());
			} else {
				render = r;
			}
		}
		boolean cull = o.has("cull_pass") && o.get("cull_pass").getAsBoolean();
		float sway = o.has("sway") ? o.get("sway").getAsFloat() : 0.0F;
		return new Rule(render, cull, sway);
	}

	private static void save() {
		JsonObject root = new JsonObject();
		root.addProperty("sway_blend_seconds", blendSeconds);
		JsonObject blocks = new JsonObject();
		for (Map.Entry<String, Rule> e : entries.entrySet()) {
			Rule r = e.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("render", r.render().id);
			o.addProperty("cull_pass", r.cullPass());
			o.addProperty("sway", r.sway());
			blocks.add(e.getKey(), o);
		}
		root.add("blocks", blocks);
		try {
			Files.createDirectories(DIR);
			Files.writeString(FILE, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("[block_render_rules] {} 를 못 썼다: {}", FILE, e.toString());
		}
	}

	/** 옛 설정 파일이 없을 때의 기본 — Cull Pass 1.0 · SWAY 포크 기본값을 합친 것. */
	static Map<String, Rule> defaults() {
		Map<String, Rule> m = new LinkedHashMap<>();
		m.put("minecraft:petrified_oak_slab", new Rule(Render.DEFAULT, true, 0.0F));
		m.put("minecraft:cobweb", new Rule(Render.DEFAULT, false, 1.0F));
		m.put("minecraft:heavy_weighted_pressure_plate", new Rule(Render.DEFAULT, false, 1.0F));
		return Collections.unmodifiableMap(m);
	}

	private static Map<Block, Rule> resolved() {
		Map<Block, Rule> m = resolved;
		if (m == null) {
			Map<Block, Rule> next = new IdentityHashMap<>();
			boolean cull = false;
			for (Map.Entry<String, Rule> e : entries.entrySet()) {
				Block b = lookup(e.getKey());
				if (b != null) {
					next.put(b, e.getValue());
					cull |= e.getValue().cullPass();
				}
			}
			m = Collections.unmodifiableMap(next);
			anyCullPass = cull;
			resolved = m;
		}
		return m;
	}

	public static List<String> unknownIds() {
		List<String> out = new ArrayList<>();
		for (String id : entries.keySet()) {
			if (lookup(id) == null) {
				out.add(id);
			}
		}
		return out;
	}

	private static Block lookup(String id) {
		ResourceLocation rl = ResourceLocation.tryParse(id);
		if (rl == null) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
	}
}
