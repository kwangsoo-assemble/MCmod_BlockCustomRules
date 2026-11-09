package kr.asmbl.block_custom_rules;

import com.mojang.logging.LogUtils;
import kr.asmbl.block_custom_rules.command.BcrCommand;
import kr.asmbl.block_custom_rules.step.FootstepEngine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import kr.asmbl.block_custom_rules.config.OptionsFile;
import kr.asmbl.block_custom_rules.sway.SwayEngine;
import kr.asmbl.block_custom_rules.world.Generation;
import kr.asmbl.block_custom_rules.world.WorldHooks;
import net.fabricmc.api.ClientModInitializer;

public final class BcrClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 리팩 규칙은 첫 리소스 리로드에서 읽는다 — 그 전에 모드 옵션이 있어야 한다
		OptionsFile.load();
		WorldHooks.register();
		Generation.onReset(SwayEngine::reset);
		Generation.onReset(FootstepEngine::reset);
		Generation.onEntityGone(FootstepEngine::entityGone);
		FootstepEngine.DELAYED.errorSink((what, t) -> LogUtils.getLogger().warn("[block_custom_rules] 지연 소리 예외({}) — 그 소리만 버렸다", what, t));
		ClientTickEvents.END_CLIENT_TICK.register(FootstepEngine::tick);
		BcrCommand.register();
	}
}
