package kr.asmbl.block_custom_rules.game;

import kr.asmbl.block_custom_rules.rules.StateView;
import kr.asmbl.block_custom_rules.rules.WorldProbe;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 클라 월드를 위치 · 환경 조건에 보이는 창 — 소리 하나를 정할 동안만 쓰고 버린다(월드를 붙잡아 두지 않는다 · D40).
 * 안 읽힌 청크면 «모름» — 조건 쪽이 거짓으로 본다(D42).
 */
public final class McWorldProbe implements WorldProbe {
	private final ClientLevel level;
	private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

	public McWorldProbe(ClientLevel level) {
		this.level = level;
	}

	private BlockPos pos(int x, int y, int z) {
		return this.at.set(x, y, z);
	}

	@Override
	public boolean loaded(int x, int y, int z) {
		return this.level.hasChunkAt(pos(x, y, z));
	}

	@Override
	public String biome(int x, int y, int z) {
		return this.level.getBiome(pos(x, y, z)).unwrapKey().map(k -> k.location().toString()).orElse(null);
	}

	@Override
	public String dimension() {
		return this.level.dimension().location().toString();
	}

	@Override
	public int light(int x, int y, int z) {
		return this.level.getMaxLocalRawBrightness(pos(x, y, z));
	}

	@Override
	public boolean canSeeSky(int x, int y, int z) {
		return this.level.canSeeSky(pos(x, y, z));
	}

	@Override
	public String fluid(int x, int y, int z) {
		FluidState f = this.level.getFluidState(pos(x, y, z));
		return f.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(f.getType()).toString();
	}

	@Override
	public StateView block(int x, int y, int z) {
		BlockState s = this.level.getBlockState(pos(x, y, z));
		return new McState(s, BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
	}

	@Override
	public boolean raining() {
		return this.level.isRaining();
	}

	@Override
	public boolean thundering() {
		return this.level.isThundering();
	}

	@Override
	public String precipitation(int x, int y, int z) {
		Biome.Precipitation p = this.level.getBiome(pos(x, y, z)).value().getPrecipitationAt(pos(x, y, z), this.level.getSeaLevel());
		return switch (p) {
			case RAIN -> "rain";
			case SNOW -> "snow";
			default -> "none";
		};
	}

	@Override
	public long dayTime() {
		return this.level.getDayTime();
	}

	/** 블록 상태를 규칙 핵심 창으로. */
	public static StateView view(BlockState s) {
		return new McState(s, BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
	}
}
