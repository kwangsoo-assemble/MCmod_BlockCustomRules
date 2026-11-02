package kr.asmbl.cull_pass;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu 가 있을 때만 불린다 — 없으면 이 클래스는 읽히지 않고 설정 파일만 쓴다. */
public final class CullPassModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CullPassScreen::new;
	}
}
