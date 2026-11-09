package kr.asmbl.block_render_rules;

import kr.asmbl.block_render_rules.command.BrrCommand;
import kr.asmbl.block_render_rules.config.BrrConfig;
import net.fabricmc.api.ClientModInitializer;

public final class BrrClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BrrConfig.load();
		BrrCommand.register();
	}
}
