package kr.asmbl.block_custom_rules.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import kr.asmbl.block_custom_rules.sway.SwayEngine;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 바닐라 청크 짓기 — 기울어 있는 블록은 그리기 직전 포즈에 기울임을 곱한다(아래 면 고정 · 위로 갈수록 밀림).
 * 지형 메시에 남으므로 셰이더팩의 흔들림 · 그림자가 그 위에 더해진다.
 * Sodium 이 깔리면 이 자리를 안 거친다 — {@link SodiumBlockRendererMixin} 이 같은 일을 한다.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLjava/util/List;)V"))
	private void bcr$tilt(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack poseStack, VertexConsumer consumer, boolean checkSides, List<BlockModelPart> parts, Operation<Void> original) {
		float[] s = SwayEngine.shear(pos);
		if (s == null) {
			original.call(dispatcher, state, pos, level, poseStack, consumer, checkSides, parts);
			return;
		}
		poseStack.pushPose();
		// 기둥 위 칸은 아래 칸 바닥을 축으로 — 이미 밀린 만큼 옮기고 같은 기울임
		poseStack.translate(s[0] * s[2], 0.0F, s[1] * s[2]);
		poseStack.mulPose(new Matrix4f().m10(s[0]).m12(s[1]));
		original.call(dispatcher, state, pos, level, poseStack, consumer, checkSides, parts);
		poseStack.popPose();
	}
}
