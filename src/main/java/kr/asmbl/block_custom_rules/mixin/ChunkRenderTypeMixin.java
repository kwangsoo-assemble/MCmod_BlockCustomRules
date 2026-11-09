package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kr.asmbl.block_custom_rules.config.BcrConfig;
import kr.asmbl.block_custom_rules.config.Render;
import kr.asmbl.block_custom_rules.config.Rule;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 블록 텍스처 그리는 방식 — 바닐라 · Sodium 청크, 손 · 바닥 아이템(`getRenderType` → 여기), 블록 디스플레이가
 * 모두 `getChunkRenderType` 하나로 묻는다. 움직이는 블록(피스톤 · 떨어지는 블록)만 표를 직접 읽어서 둘을 감싼다.
 * HEAD 가로채기가 아니라 메서드를 통째로 감싼다 — Iris 가 같은 메서드 HEAD 에서 셰이더팩 `layer.*` 로 돌려주는데,
 * 감싸기는 그 가로채기까지 품은 바깥이라 순서에 기대지 않고 이 모드 규칙이 이긴다(Plan D4 · 🎬 12 로 확인).
 */
@Mixin(ItemBlockRenderTypes.class)
public abstract class ChunkRenderTypeMixin {
	@WrapMethod(method = "getChunkRenderType")
	private static ChunkSectionLayer bcr$chunkLayer(BlockState state, Operation<ChunkSectionLayer> original) {
		Rule r = BcrConfig.rule(state.getBlock());
		if (r == null) {
			return original.call(state);
		}
		return switch (r.render()) {
			case SOLID -> ChunkSectionLayer.SOLID;
			case CUTOUT -> ChunkSectionLayer.CUTOUT;
			case TRANSLUCENT -> ChunkSectionLayer.TRANSLUCENT;
			case DEFAULT -> original.call(state);
		};
	}

	@WrapMethod(method = "getMovingBlockRenderType")
	private static RenderType bcr$movingLayer(BlockState state, Operation<RenderType> original) {
		Rule r = BcrConfig.rule(state.getBlock());
		if (r == null || r.render() == Render.DEFAULT) {
			return original.call(state);
		}
		return switch (r.render()) {
			case SOLID -> RenderType.solid();
			case CUTOUT -> RenderType.cutout();
			default -> RenderType.translucentMovingBlock();
		};
	}
}
