package kr.asmbl.block_custom_rules.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kr.asmbl.block_custom_rules.mixin.ButtonTypeAccess;
import kr.asmbl.block_custom_rules.mixin.PressurePlateTypeAccess;
import kr.asmbl.block_custom_rules.mixin.TrapDoorTypeAccess;
import kr.asmbl.block_custom_rules.mixin.WoodTypeAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import org.slf4j.Logger;

/**
 * 주인 확인 재료(D17) — 위치로 찾은 블록이 그 소리를 낼 블록인지 보는 데 쓴다.
 * 블록마다 한 번 만들어 둔다: 블록 이름 · 세트 소리(문 · 다락문 · 버튼 · 압력판 · 울타리 문) · 별칭(jar 안 `data/aliases.json`).
 * 소리 종류의 가운데 이름은 상태마다 달라질 수 있어 그때그때 본다.
 */
public final class SoundOwners {
	private static final Logger LOG = LogUtils.getLogger();
	private static final Map<Block, Entry> CACHE = new ConcurrentHashMap<>();
	private static volatile Map<String, Set<String>> aliases;

	private record Entry(String path, Set<String> setNames, Set<String> aliases) {
	}

	private SoundOwners() {
	}

	public static boolean owns(BlockState state, String soundId) {
		Entry e = CACHE.computeIfAbsent(state.getBlock(), SoundOwners::entry);
		return SoundDecision.owns(soundId, e.path, typeMids(state.getSoundType()), e.setNames, e.aliases);
	}

	/** 소리 종류 다섯 이름의 가운데 마디. */
	static Set<String> typeMids(SoundType t) {
		Set<String> out = new HashSet<>(8);
		add(out, t.getStepSound());
		add(out, t.getFallSound());
		add(out, t.getHitSound());
		add(out, t.getPlaceSound());
		add(out, t.getBreakSound());
		return out;
	}

	private static void add(Set<String> out, SoundEvent e) {
		String m = SoundNames.mid(e.location().toString());
		if (m != null) {
			out.add(m);
		}
	}

	private static Entry entry(Block b) {
		String id = BuiltInRegistries.BLOCK.getKey(b).toString();
		String path = id.substring(id.indexOf(':') + 1);
		Set<String> names = new HashSet<>();
		try {
			BlockSetType set = null;
			if (b instanceof DoorBlock d) {
				set = d.type();
			} else if (b instanceof TrapDoorBlock) {
				set = ((TrapDoorTypeAccess) b).bcr$setType();
			} else if (b instanceof ButtonBlock) {
				set = ((ButtonTypeAccess) b).bcr$setType();
			} else if (b instanceof BasePressurePlateBlock) {
				set = ((PressurePlateTypeAccess) b).bcr$setType();
			}
			if (set != null) {
				for (SoundEvent e : new SoundEvent[] { set.doorOpen(), set.doorClose(), set.trapdoorOpen(), set.trapdoorClose(),
						set.pressurePlateClickOn(), set.pressurePlateClickOff(), set.buttonClickOn(), set.buttonClickOff() }) {
					names.add(e.location().toString());
				}
			}
			if (b instanceof FenceGateBlock) {
				WoodType w = ((WoodTypeAccess) b).bcr$woodType();
				if (w != null) {
					names.add(w.fenceGateOpen().location().toString());
					names.add(w.fenceGateClose().location().toString());
				}
			}
		} catch (RuntimeException | LinkageError ex) {
			LOG.warn("[block_custom_rules] {} 의 세트 소리를 못 읽었다 — 이름으로만 본다", id, ex);
		}
		return new Entry(path, Set.copyOf(names), aliases().getOrDefault(id, Set.of()));
	}

	private static Map<String, Set<String>> aliases() {
		Map<String, Set<String>> a = aliases;
		if (a != null) {
			return a;
		}
		Map<String, Set<String>> out = new HashMap<>();
		try (InputStream in = SoundOwners.class.getResourceAsStream("/assets/block_custom_rules/data/aliases.json")) {
			if (in != null) {
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : o.entrySet()) {
					if (e.getKey().startsWith("_")) {
						continue;
					}
					Set<String> mids = new HashSet<>();
					for (JsonElement m : e.getValue().getAsJsonArray()) {
						mids.add(m.getAsString());
					}
					out.put(e.getKey(), Set.copyOf(mids));
				}
			}
		} catch (Exception ex) {
			LOG.warn("[block_custom_rules] 별칭표를 못 읽었다 — 이름으로만 본다", ex);
		}
		aliases = out;
		return out;
	}
}
