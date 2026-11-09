package kr.asmbl.block_custom_rules.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.asmbl.block_custom_rules.rules.Catalog;
import kr.asmbl.block_custom_rules.rules.Registry;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 게임 레지스트리 · 태그를 규칙 핵심에 보이는 창 — 풀기 한 번에 하나 만들어 쓰고 버린다.
 * 블록 · 엔티티 이름은 늘 있다. 서버 태그와 바이옴은 접속 뒤 `TAGS_LOADED` 가 넘긴 레지스트리로 본다(D44 «아직 없음 = 원래 동작»).
 */
public final class McCatalog implements Catalog {
	private final RegistryAccess registries;
	private final boolean tagsBound;
	private final Map<String, List<McState>> states = new HashMap<>();
	private List<String> blockIds;

	/**
	 * @param registries 접속 뒤 클라 레지스트리(바이옴) · 접속 전이면 null
	 * @param tagsBound  서버 태그가 묶였나(접속 뒤 · 리로드 뒤)
	 */
	public McCatalog(RegistryAccess registries, boolean tagsBound) {
		this.registries = registries;
		this.tagsBound = tagsBound;
	}

	private net.minecraft.core.Registry<Biome> biomes() {
		return this.registries == null ? null : this.registries.lookup(Registries.BIOME).orElse(null);
	}

	private net.minecraft.core.Registry<?> registry(Registry r) {
		return switch (r) {
			case BLOCK -> BuiltInRegistries.BLOCK;
			case ENTITY_TYPE -> BuiltInRegistries.ENTITY_TYPE;
			case BIOME -> biomes();
		};
	}

	@Override
	public boolean idsReady(Registry r) {
		return r != Registry.BIOME || biomes() != null;
	}

	@Override
	public boolean tagsReady(Registry r) {
		return this.tagsBound && idsReady(r);
	}

	@Override
	public boolean exists(Registry r, String id) {
		net.minecraft.core.Registry<?> reg = registry(r);
		ResourceLocation rl = ResourceLocation.tryParse(id);
		return reg != null && rl != null && reg.containsKey(rl);
	}

	@Override
	public Collection<String> tag(Registry r, String tagId) {
		if (!tagsReady(r)) {
			return null;
		}
		net.minecraft.core.Registry<?> reg = registry(r);
		return reg == null ? null : members(reg, tagId);
	}

	private static <T> Collection<String> members(net.minecraft.core.Registry<T> reg, String tagId) {
		ResourceLocation rl = ResourceLocation.tryParse(tagId);
		if (rl == null) {
			return null;
		}
		try {
			Optional<HolderSet.Named<T>> set = reg.get(TagKey.create(reg.key(), rl));
			if (set.isEmpty()) {
				return null;
			}
			List<String> out = new ArrayList<>();
			for (Holder<T> h : set.get()) {
				h.unwrapKey().ifPresent(k -> out.add(k.location().toString()));
			}
			return out;
		} catch (RuntimeException e) {
			// 태그가 아직 안 묶였다(접속 전) — 모름
			return null;
		}
	}

	@Override
	public Collection<String> blocks() {
		if (this.blockIds == null) {
			List<String> ids = new ArrayList<>();
			for (ResourceLocation rl : BuiltInRegistries.BLOCK.keySet()) {
				ids.add(rl.toString());
			}
			this.blockIds = ids;
		}
		return this.blockIds;
	}

	@Override
	public List<McState> states(String blockId) {
		return this.states.computeIfAbsent(blockId, id -> {
			ResourceLocation rl = ResourceLocation.tryParse(id);
			if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
				return List.of();
			}
			Block b = BuiltInRegistries.BLOCK.getValue(rl);
			List<McState> out = new ArrayList<>();
			for (BlockState s : b.getStateDefinition().getPossibleStates()) {
				out.add(new McState(s, id));
			}
			return out;
		});
	}
}
