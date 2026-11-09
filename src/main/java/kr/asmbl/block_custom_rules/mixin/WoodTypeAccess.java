package kr.asmbl.block_custom_rules.mixin;

import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.WoodType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 울타리 문의 나무 종류 — 주인 확인이 여닫는 소리 이름을 읽는다. */
@Mixin(FenceGateBlock.class)
public interface WoodTypeAccess {
	@Accessor("type")
	WoodType bcr$woodType();
}
