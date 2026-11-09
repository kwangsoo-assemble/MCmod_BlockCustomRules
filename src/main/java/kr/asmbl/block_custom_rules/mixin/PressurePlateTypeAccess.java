package kr.asmbl.block_custom_rules.mixin;

import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 압력판(가벼운 · 무거운 무게 포함)의 블록 세트 — 주인 확인이 세트 소리 이름을 읽는다. */
@Mixin(BasePressurePlateBlock.class)
public interface PressurePlateTypeAccess {
	@Accessor("type")
	BlockSetType bcr$setType();
}
