package kr.asmbl.block_custom_rules.step;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

/**
 * 바닐라 걸음 목록(D32) — 1.21.8 태그 `combination_step_sound_blocks` · `inside_step_sound_blocks` · `crystal_sound_blocks` 를 jar 안
 * `data/vanilla_steps.json` 으로 옮겨 둔 것. 데이터팩이 태그를 바꿔도 이 모드 판정은 안 바뀐다(🙋 데이터팩 태그 무시).
 */
public final class VanillaSteps {
	private static final Logger LOG = LogUtils.getLogger();
	private static volatile Lists lists;

	private record Lists(Set<Block> combination, Set<Block> inside, Set<Block> crystal) {
	}

	private VanillaSteps() {
	}

	/** 발 칸에 있으면 위 블록 보통 · 아래 블록 먹먹(플레이어만). */
	public static boolean combination(Block b) {
		return lists().combination.contains(b);
	}

	/** 발 칸에 있으면 땅 대신 그 블록(플레이어만). */
	public static boolean inside(Block b) {
		return lists().inside.contains(b);
	}

	/** 밟으면 자수정 종소리. */
	public static boolean crystal(Block b) {
		return lists().crystal.contains(b);
	}

	private static Lists lists() {
		Lists l = lists;
		if (l != null) {
			return l;
		}
		Set<Block> comb = new HashSet<>();
		Set<Block> in = new HashSet<>();
		Set<Block> cry = new HashSet<>();
		try (InputStream s = VanillaSteps.class.getResourceAsStream("/assets/block_custom_rules/data/vanilla_steps.json")) {
			JsonObject o = JsonParser.parseReader(new InputStreamReader(s, StandardCharsets.UTF_8)).getAsJsonObject();
			fill(o, "combination", comb);
			fill(o, "inside", in);
			fill(o, "crystal", cry);
		} catch (Exception e) {
			LOG.warn("[block_custom_rules] 바닐라 걸음 목록을 못 읽었다 — 겹침 · 안쪽 · 종소리 없이 낸다", e);
		}
		l = new Lists(Set.copyOf(comb), Set.copyOf(in), Set.copyOf(cry));
		lists = l;
		return l;
	}

	private static void fill(JsonObject o, String key, Set<Block> out) {
		JsonElement a = o.get(key);
		if (a == null || !a.isJsonArray()) {
			return;
		}
		for (JsonElement e : a.getAsJsonArray()) {
			ResourceLocation rl = ResourceLocation.tryParse(e.getAsString());
			if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
				out.add(BuiltInRegistries.BLOCK.getValue(rl));
			}
		}
	}
}
