package kr.asmbl.block_custom_rules.mixin;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 다락문의 블록 세트 — 주인 확인이 세트 소리 이름을 읽는다.
 * ⚠ 접근자는 클래스마다 따로 — 한 접근자로 여러 클래스의 `type` 을 가리키면 매핑이 하나로 겹쳐 실행 때 깨진다.
 */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorTypeAccess {
	@Accessor("type")
	BlockSetType bcr$setType();
}
