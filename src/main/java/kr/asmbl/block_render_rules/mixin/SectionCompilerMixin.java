package kr.asmbl.block_render_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import kr.asmbl.block_render_rules.sway.SwayEngine;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 바닐라 청크 짓기 — 기울어 있는 블록은 청크에 넣지 않는다(따로 그린다).
 * Sodium 이 깔리면 이 자리를 안 거친다 — {@link SodiumBlockRendererMixin} 이 같은 일을 한다.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLjava/util/List;)V"))
	private void brr$skipTilting(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack poseStack, VertexConsumer consumer, boolean checkSides, List<BlockModelPart> parts, Operation<Void> original) {
		if (SwayEngine.isHidden(pos)) {
			return;
		}
		original.call(dispatcher, state, pos, level, poseStack, consumer, checkSides, parts);
	}
}
