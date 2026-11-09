package kr.asmbl.block_custom_rules;

import kr.asmbl.block_custom_rules.config.BcrConfig;
import kr.asmbl.block_custom_rules.config.Rule;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 컬링 패스 판정 — 바닐라 · Sodium 믹스인이 같이 쓴다. */
public final class CullPass {
	private CullPass() {
	}

	/** 이웃이 컬링 패스 블록이면 참 — 같은 블록끼리는 원래 판정에 맡긴다(유리끼리 · 반투명 벽돌끼리 맞닿은 면). */
	public static boolean passes(BlockState self, BlockState neighbor) {
		Block block = neighbor.getBlock();
		if (block == self.getBlock()) {
			return false;
		}
		Rule r = BcrConfig.rule(block);
		return r != null && r.cullPass();
	}
}
