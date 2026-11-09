package kr.asmbl.block_custom_rules.mixin;

import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 버튼의 블록 세트 — 주인 확인이 세트 소리 이름을 읽는다. */
@Mixin(ButtonBlock.class)
public interface ButtonTypeAccess {
	@Accessor("type")
	BlockSetType bcr$setType();
}
