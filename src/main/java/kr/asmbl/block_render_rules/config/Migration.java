package kr.asmbl.block_render_rules.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * 새 설정 파일이 없을 때 한 번 — 옛 Cull Pass(`cull_pass.json` 의 `blocks` 배열)와
 * SWAY 포크(`sway.json` 의 `blocks` 표: ID → 세기)를 읽어 합친다. 옛 파일은 지우지 않는다(되돌릴 수 있게).
 */
public final class Migration {
	private static final Logger LOG = LogUtils.getLogger();

	private Migration() {
	}

	/** 옛 파일이 하나도 없으면 null. */
	public static Map<String, Rule> fromOldFiles(Path dir) {
		Map<String, Rule> out = new LinkedHashMap<>();
		boolean any = false;
		Path cull = dir.resolve("cull_pass.json");
		if (Files.exists(cull)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(cull, StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement e : root.getAsJsonArray("blocks")) {
					String id = BrrConfig.normalize(e.getAsString());
					if (!id.equals("minecraft:")) {
						out.merge(id, new Rule(Render.DEFAULT, true, 0.0F), (a, b) -> a.withCullPass(true));
					}
				}
				any = true;
			} catch (Exception e) {
				LOG.warn("[block_render_rules] 옛 {} 를 못 읽어 건너뛴다: {}", cull, e.toString());
			}
		}
		Path sway = dir.resolve("sway.json");
		if (Files.exists(sway)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(sway, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject blocks = root.getAsJsonObject("blocks");
				if (blocks != null) {
					for (Map.Entry<String, JsonElement> e : blocks.entrySet()) {
						float s = e.getValue().isJsonNull() ? 1.0F : e.getValue().getAsFloat();
						String id = BrrConfig.normalize(e.getKey());
						out.merge(id, new Rule(Render.DEFAULT, false, s), (a, b) -> a.withSway(b.sway()));
					}
				}
				any = true;
			} catch (Exception e) {
				LOG.warn("[block_render_rules] 옛 {} 를 못 읽어 건너뛴다: {}", sway, e.toString());
			}
		}
		if (any) {
			LOG.info("[block_render_rules] 옛 설정에서 블록 {} 개를 옮겼다", out.size());
		}
		return any ? out : null;
	}
}
