package kr.asmbl.block_custom_rules;

import kr.asmbl.block_custom_rules.command.BcrCommand;
import kr.asmbl.block_custom_rules.config.BcrConfig;
import net.fabricmc.api.ClientModInitializer;

public final class BcrClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BcrConfig.load();
		BcrCommand.register();
	}
}
