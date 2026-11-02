package kr.asmbl.cull_pass;

import net.fabricmc.api.ClientModInitializer;

public final class CullPassClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CullPassConfig.load();
	}
}
