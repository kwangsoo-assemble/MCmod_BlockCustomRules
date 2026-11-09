package kr.asmbl.block_custom_rules.world;

import com.mojang.logging.LogUtils;
import kr.asmbl.block_custom_rules.config.OptionsFile;
import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.rules.Problems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

/**
 * 게임 이벤트 → 세대 · 규칙 다시 풀기(D40 · D44).
 * 세대 올림: 월드 바뀜(접속 · 차원 이동 · 리스폰 — Fabric 이 `setLevel` 끝에서 부른다) · 월드에서 나감(틱에서 월드가 사라진 것을 본다 —
 * 생명 주기 모듈엔 끊김 이벤트가 없다) · 게임 종료. 엔티티 내려감은 그 엔티티 상태만 지운다.
 * 태그 도착은 클라 쪽만 본다 — 혼자 하기 · LAN 호스트의 서버 스레드에서 이 모드 코드가 돌지 않게.
 */
public final class WorldHooks {
	private static final Logger LOG = LogUtils.getLogger();
	private static ClientLevel lastLevel;
	private static boolean toastShown;

	private WorldHooks() {
	}

	public static void register() {
		Generation.errorSink((what, t) -> LOG.warn("[block_custom_rules] 세대 비우기 중 예외({}) — 나머지는 비웠다", what, t));

		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return ResourceLocation.fromNamespaceAndPath("block_custom_rules", "rules");
			}

			@Override
			public void onResourceManagerReload(ResourceManager manager) {
				Rules.onResourceReload(manager);
			}
		});

		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
			if (client) {
				Rules.onTagsLoaded(registries);
			}
		});

		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((mc, level) -> {
			lastLevel = level;
			Generation.bump("world change");
			maybeToast(mc);
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null && lastLevel != null) {
				lastLevel = null;
				toastShown = false;
				Generation.bump("left world");
				Rules.onLeftWorld();
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Generation.bump("stopping"));

		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> Generation.entityGone(entity.getId()));
	}

	/** 규칙 문제를 접속할 때 한 번 알린다 — 모드 옵션 `problem_toast` 가 켜졌을 때만(기본 끔 · 촬영 규율). */
	private static void maybeToast(Minecraft mc) {
		if (toastShown || !OptionsFile.problemToast()) {
			return;
		}
		Problems p = Rules.snapshot().problems;
		int errors = p.count(Problems.Severity.ERROR);
		int warnings = p.count(Problems.Severity.WARNING);
		if (errors + warnings == 0) {
			return;
		}
		toastShown = true;
		SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PACK_LOAD_FAILURE,
				Component.translatable("bcr.toast.title"), Component.translatable("bcr.toast.body", errors, warnings));
	}
}
