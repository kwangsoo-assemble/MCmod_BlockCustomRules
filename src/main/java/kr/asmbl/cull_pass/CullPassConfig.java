package kr.asmbl.cull_pass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * 면을 가리지 않을 블록 목록 — `config/cull_pass.json` 의 `blocks`(블록 ID 배열).
 * 청크를 짓는 스레드 여럿이 동시에 읽으므로 목록은 통째로 바꿔 끼운다(volatile · 바뀌지 않는 Set).
 */
public final class CullPassConfig {
	private static final Logger LOG = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cull_pass.json");
	private static final List<String> DEFAULT_IDS = List.of("minecraft:petrified_oak_slab");

	private static volatile List<String> ids = DEFAULT_IDS;
	// 블록 등록이 끝나기 전에 ID 를 풀면 모드 블록이 빠진다 — 처음 쓸 때 푼다
	private static volatile Set<Block> blocks = null;

	private CullPassConfig() {
	}

	/** 이웃이 목록 블록이면 참 — 같은 블록끼리는 원래 판정에 맡긴다(유리끼리 맞닿은 면 등). */
	public static boolean passes(BlockState self, BlockState neighbor) {
		Block block = neighbor.getBlock();
		return block != self.getBlock() && resolved().contains(block);
	}

	public static boolean isEmpty() {
		return resolved().isEmpty();
	}

	public static List<String> ids() {
		return ids;
	}

	/** 등록되지 않은 ID(오타 · 없는 모드) — 옵션 화면이 빨갛게 보여 준다. */
	public static List<String> unknown(List<String> candidates) {
		List<String> out = new ArrayList<>();
		for (String id : candidates) {
			if (lookup(id) == null) {
				out.add(id);
			}
		}
		return out;
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			save();
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
			List<String> read = new ArrayList<>();
			for (JsonElement e : root.getAsJsonArray("blocks")) {
				read.add(e.getAsString());
			}
			ids = List.copyOf(clean(read));
			blocks = null;
		} catch (IOException | RuntimeException e) {
			// 파일을 덮지 않는다 — 손으로 고치다 틀린 것일 수 있다
			LOG.warn("[cull_pass] {} 를 못 읽어 기본 목록을 쓴다: {}", FILE, e.toString());
		}
	}

	/** 옵션 화면의 «완료» — 저장하고, 월드가 열려 있으면 청크를 다시 지어 바로 보이게 한다. */
	public static void apply(List<String> next) {
		ids = List.copyOf(clean(next));
		blocks = null;
		save();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			mc.levelRenderer.allChanged();
		}
	}

	private static void save() {
		JsonObject root = new JsonObject();
		JsonArray arr = new JsonArray();
		for (String id : ids) {
			arr.add(id);
		}
		root.add("blocks", arr);
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("[cull_pass] {} 를 못 썼다: {}", FILE, e.toString());
		}
	}

	private static Set<String> clean(List<String> raw) {
		Set<String> out = new LinkedHashSet<>();
		for (String id : raw) {
			String t = id.trim();
			if (!t.isEmpty()) {
				out.add(t.contains(":") ? t : "minecraft:" + t);
			}
		}
		return out;
	}

	private static Set<Block> resolved() {
		Set<Block> s = blocks;
		if (s == null) {
			Set<Block> next = new HashSet<>();
			for (String id : ids) {
				Block b = lookup(id);
				if (b != null) {
					next.add(b);
				}
			}
			s = Set.copyOf(next);
			blocks = s;
		}
		return s;
	}

	private static Block lookup(String id) {
		ResourceLocation rl = ResourceLocation.tryParse(id);
		if (rl == null) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
	}
}
