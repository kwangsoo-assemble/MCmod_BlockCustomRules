package kr.asmbl.block_custom_rules.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Set;
import java.util.TreeSet;
import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.debug.SoundDebugger;
import kr.asmbl.block_custom_rules.sound.SoundCategories;
import kr.asmbl.block_custom_rules.sound.SoundHub;
import kr.asmbl.block_custom_rules.sound.SoundNames;
import kr.asmbl.block_custom_rules.step.StepTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import kr.asmbl.block_custom_rules.game.McState;
import kr.asmbl.block_custom_rules.rules.Field;
import kr.asmbl.block_custom_rules.rules.Problems;
import kr.asmbl.block_custom_rules.rules.Provenance;
import kr.asmbl.block_custom_rules.rules.Resolved;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 클라 명령 `/bcr`(D38) — 응답은 친 사람 채팅에만 뜬다(클라 명령 · 일부러 치는 진단 명령).
 * `reload` 모드 옵션 다시 읽기(리팩은 F3 + T) · `check` 규칙 문제 · `rule [블록 상태]` 최종 규칙과 출처(없으면 바라보는 블록) ·
 * `debug sound [on [반지름 [blocks|all|blocked]] | off]`(인자 없으면 켜고 끄기) · `sound names <종류>` 행동마다 찾아지는 이름 ·
 * `sound template <종류>` `sounds.json` 뼈대를 클립보드로 · `get_blocks_from_tags <#태그>` 최종 블록 묶음(서버 태그 › 리팩 › 모드 옵션 재정의).
 */
public final class BcrCommand {
	/** `check` 가 채팅에 보이는 최대 줄 — 나머지는 로그에. */
	private static final int CHECK_LINES = 30;
	/** `get_blocks_from_tags` 한 줄에 보일 블록 수. */
	private static final int TAG_LINE = 12;

	private BcrCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("bcr")
			.then(literal("reload").executes(BcrCommand::reload))
			.then(literal("check").executes(BcrCommand::check))
			.then(literal("get_blocks_from_tags").then(argument("tag", StringArgumentType.greedyString())
				.suggests(BcrCommand::tagSuggest).executes(BcrCommand::blocksFromTag)))
			.then(literal("rule")
				.executes(c -> rule(c, lookedAt()))
				.then(argument("block", BlockStateArgument.block(ctx))
					.executes(c -> rule(c, c.getArgument("block", BlockInput.class).getState()))))
			.then(literal("debug").then(literal("sound")
				.executes(c -> debug(c, !SoundDebugger.active(), SoundDebugger.radius(), SoundDebugger.mode()))
				.then(literal("off").executes(c -> debug(c, false, SoundDebugger.radius(), SoundDebugger.mode())))
				.then(literal("on")
					.executes(c -> debug(c, true, SoundDebugger.DEFAULT_RADIUS, SoundDebugger.Mode.BLOCKS))
					.then(argument("radius", IntegerArgumentType.integer(1, SoundDebugger.MAX_RADIUS))
						.executes(c -> debug(c, true, IntegerArgumentType.getInteger(c, "radius"), SoundDebugger.Mode.BLOCKS))
						.then(argument("mode", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(new String[] { "blocks", "all", "blocked" }, b))
							.executes(c -> debug(c, true, IntegerArgumentType.getInteger(c, "radius"), SoundDebugger.Mode.parse(StringArgumentType.getString(c, "mode")))))))))
			.then(literal("sound")
				.then(literal("names").then(category().executes(BcrCommand::names)))
				.then(literal("template").then(category().executes(BcrCommand::template))))));
	}

	/** 블록 태그 이름 — 서버 태그 + 리팩 · 모드 옵션 `groups`(블록) · `#ns:이름` 꼴. */
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> tagSuggest(CommandContext<FabricClientCommandSource> c,
			com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
		Set<String> ids = new TreeSet<>();
		try {
			BuiltInRegistries.BLOCK.getTags().forEach(n -> ids.add("#" + n.key().location()));
		} catch (RuntimeException e) {
			// 접속 전 · 태그가 아직 안 묶였다 — 리팩 묶음만
		}
		for (String g : Rules.snapshot().table.groupNames(kr.asmbl.block_custom_rules.rules.Registry.BLOCK)) {
			ids.add("#" + g);
		}
		return SharedSuggestionProvider.suggest(ids, b);
	}

	/** 맨 위 층(모드 옵션)에서 본 블록 묶음 구성원 — 서버 태그 위에 리팩 · 모드 옵션 `groups` 재정의를 다 접은 것. */
	private static int blocksFromTag(CommandContext<FabricClientCommandSource> c) {
		String raw = StringArgumentType.getString(c, "tag").trim();
		String id = raw.startsWith("#") ? raw.substring(1) : raw;
		ResourceLocation rl = id.isEmpty() ? null : ResourceLocation.tryParse(id);
		if (rl == null) {
			c.getSource().sendError(Component.translatable("bcr.cmd.tag_bad"));
			return 0;
		}
		String tag = rl.toString();
		kr.asmbl.block_custom_rules.rules.Registry reg = kr.asmbl.block_custom_rules.rules.Registry.BLOCK;
		Rules.Snapshot snap = Rules.snapshot();
		Set<String> members = snap.table.tags(snap.optionsLayer).members(reg, tag);
		if (members == null) {
			c.getSource().sendFeedback(Component.translatable("bcr.cmd.tag_none", "#" + tag,
					Component.translatable(Rules.tagsReady() ? "bcr.cmd.tags_ready" : "bcr.cmd.tags_not_ready")));
			return 0;
		}
		boolean server;
		try {
			server = BuiltInRegistries.BLOCK.get(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, rl)).isPresent();
		} catch (RuntimeException e) {
			server = false;
		}
		java.util.List<String> sorted = new java.util.ArrayList<>(new TreeSet<>(members));
		c.getSource().sendFeedback(Component.translatable("bcr.cmd.tag_head", "#" + tag, sorted.size()));
		c.getSource().sendFeedback(Component.translatable(server ? "bcr.cmd.tag_server" : "bcr.cmd.tag_no_server").withStyle(ChatFormatting.GRAY));
		for (kr.asmbl.block_custom_rules.rules.GroupDef d : snap.table.groupDefs(reg, tag)) {
			c.getSource().sendFeedback(Component.translatable("bcr.cmd.tag_def", d.origin().describe(),
					Component.translatable(d.replace() ? "bcr.cmd.tag_replace" : "bcr.cmd.tag_append"), d.values().size()).withStyle(ChatFormatting.GRAY));
		}
		for (int i = 0; i < sorted.size(); i += TAG_LINE) {
			c.getSource().sendFeedback(Component.literal(" " + String.join(", ", sorted.subList(i, Math.min(sorted.size(), i + TAG_LINE)))));
		}
		return 1;
	}

	/** 소리 종류 인자 — 리팩 · 모드 옵션에 나오는 종류를 제안한다. */
	private static RequiredArgumentBuilder<FabricClientCommandSource, ResourceLocation> category() {
		return argument("category", ResourceLocationArgument.id()).suggests((c, b) -> {
			Set<String> ids = new TreeSet<>(Rules.snapshot().categories.all().keySet());
			for (Resolved r : Rules.snapshot().table.all().values()) {
				if (r.soundCategory() != null) {
					ids.add(r.soundCategory());
				}
			}
			return SharedSuggestionProvider.suggest(ids, b);
		});
	}

	private static int debug(CommandContext<FabricClientCommandSource> c, boolean on, int radius, SoundDebugger.Mode mode) {
		if (mode == null) {
			c.getSource().sendError(Component.translatable("bcr.cmd.debug_bad_mode"));
			return 0;
		}
		SoundDebugger.set(on, radius, mode);
		c.getSource().sendFeedback(on ? Component.translatable("bcr.cmd.debug_on", radius, mode.id) : Component.translatable("bcr.cmd.debug_off"));
		return 1;
	}

	/** 소리 이름 찾기에 보일 행동 · 나머지 — 엔진 행동 + 블록 다섯 소리 + 종소리. */
	private static final String[] NAMES = { "step", "run", "wander", "jump", "land", "land_light", "climb", "stairs_up", "stairs_down", "crawl", "through",
			"enter", "exit", "step_sneak", "climb_sneak", "chime", "place", "break", "hit", "fall" };

	private static int names(CommandContext<FabricClientCommandSource> c) {
		String cat = c.getArgument("category", ResourceLocation.class).toString();
		SoundCategories cats = Rules.snapshot().categories;
		StringBuilder chain = new StringBuilder(cat);
		String p = cats.get(cat) == null ? null : cats.get(cat).parent();
		for (int i = 0; p != null && i < 16; i++) {
			chain.append(" → ").append(p);
			p = cats.get(p) == null ? null : cats.get(p).parent();
		}
		c.getSource().sendFeedback(Component.translatable("bcr.cmd.names_head", chain.toString()));
		for (String a : NAMES) {
			String found = cats.find(cat, a, StepTracker.PARENTS_WALK, SoundHub::defined);
			Component tail;
			if (found == null) {
				boolean silent = StepTracker.isSneak(a) || a.equals("through") || a.equals("enter") || a.equals("exit");
				tail = Component.translatable(silent ? "bcr.cmd.names_silent" : "bcr.cmd.names_original").withStyle(ChatFormatting.GRAY);
			} else {
				boolean own = found.equals(SoundNames.compose(cat, a));
				tail = Component.literal(found).withStyle(own ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
			}
			c.getSource().sendFeedback(Component.literal(" " + a + " → ").append(tail));
		}
		return 1;
	}

	/** `sounds.json` 뼈대 — 지금 찾아지는 소리를 그대로 가리키는 이름들(바꿀 곳만 고치면 된다) · 클립보드로. */
	private static int template(CommandContext<FabricClientCommandSource> c) {
		String cat = c.getArgument("category", ResourceLocation.class).toString();
		SoundCategories cats = Rules.snapshot().categories;
		JsonObject root = new JsonObject();
		for (String a : NAMES) {
			String own = SoundNames.compose(cat, a);
			String found = cats.find(cat, a, StepTracker.PARENTS_WALK, SoundHub::defined);
			// 이미 리팩에 있는 이름 · 끝내 없는 이름은 뺀다 — 빈 목록을 넣으면 무음이 되니 지금 들리는 소리를 가리키게 한다
			if (found == null || found.equals(own)) {
				continue;
			}
			JsonObject entry = new JsonObject();
			JsonArray sounds = new JsonArray();
			JsonObject ref = new JsonObject();
			ref.addProperty("name", found);
			ref.addProperty("type", "event");
			sounds.add(ref);
			entry.add("sounds", sounds);
			root.add(own.substring(own.indexOf(':') + 1), entry);
		}
		String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
		Minecraft.getInstance().keyboardHandler.setClipboard(json);
		c.getSource().sendFeedback(Component.translatable("bcr.cmd.template_copied", root.size(), cat));
		return 1;
	}

	private static int reload(CommandContext<FabricClientCommandSource> c) {
		boolean ok = Rules.reloadOptions();
		if (!ok) {
			c.getSource().sendError(Component.translatable("bcr.cmd.reload_failed"));
			return 0;
		}
		Rules.Snapshot s = Rules.snapshot();
		c.getSource().sendFeedback(Component.translatable("bcr.cmd.reloaded", s.table.files().size(), s.size()));
		return 1;
	}

	private static int check(CommandContext<FabricClientCommandSource> c) {
		Rules.Snapshot s = Rules.snapshot();
		Problems p = s.problems;
		c.getSource().sendFeedback(Component.translatable("bcr.cmd.check_head", s.table.files().size(), p.count(Problems.Severity.ERROR),
				p.count(Problems.Severity.WARNING), Component.translatable(Rules.tagsReady() ? "bcr.cmd.tags_ready" : "bcr.cmd.tags_not_ready")));
		int n = 0;
		for (Problems.Problem x : p.all()) {
			if (n++ >= CHECK_LINES) {
				c.getSource().sendFeedback(Component.translatable("bcr.cmd.check_more", p.all().size() - CHECK_LINES));
				break;
			}
			c.getSource().sendFeedback(Component.literal(" " + Rules.format(x)));
		}
		return p.all().isEmpty() ? 1 : 0;
	}

	private static int rule(CommandContext<FabricClientCommandSource> c, BlockState state) {
		if (state == null) {
			c.getSource().sendError(Component.translatable("bcr.cmd.no_target"));
			return 0;
		}
		String name = McState.describe(state, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		Resolved r = Rules.get(state);
		if (r == null) {
			c.getSource().sendFeedback(Component.translatable("bcr.cmd.rule_none", name));
			return 1;
		}
		c.getSource().sendFeedback(Component.translatable("bcr.cmd.rule_head", name));
		for (Field f : Field.values()) {
			Object v = r.raw(f);
			Provenance from = r.from(f);
			if (v == null || from == null) {
				continue;
			}
			c.getSource().sendFeedback(Component.literal(" " + f.key + " = " + v + "  ← " + from.describe()));
		}
		return 1;
	}

	private static BlockState lookedAt() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		return mc.level.getBlockState(hit.getBlockPos());
	}
}
