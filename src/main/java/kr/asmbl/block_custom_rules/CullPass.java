package kr.asmbl.block_custom_rules;

import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.rules.Resolved;
import net.minecraft.world.level.block.state.BlockState;

/** 컬링 패스 판정 — 바닐라 · Sodium 믹스인이 같이 쓴다. */
public final class CullPass {
	private CullPass() {
	}

	/** 이웃이 컬링 패스 블록이면 참 — 같은 블록끼리는 원래 판정에 맡긴다(유리끼리 · 반투명 벽돌끼리 맞닿은 면). */
	public static boolean passes(BlockState self, BlockState neighbor) {
		if (!Rules.anyCullPass() || neighbor.getBlock() == self.getBlock()) {
			return false;
		}
		Resolved r = Rules.get(neighbor);
		return r != null && r.cullPass();
	}
}
