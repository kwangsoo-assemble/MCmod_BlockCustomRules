package kr.asmbl.block_custom_rules.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.context.CommandContext;
import kr.asmbl.block_custom_rules.config.Rules;
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
 * `reload` 모드 옵션 다시 읽기(리팩은 F3 + T) · `check` 규칙 문제 · `rule [블록 상태]` 최종 규칙과 출처(없으면 바라보는 블록).
 */
public final class BcrCommand {
	/** `check` 가 채팅에 보이는 최대 줄 — 나머지는 로그에. */
	private static final int CHECK_LINES = 30;

	private BcrCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("bcr")
			.then(literal("reload").executes(BcrCommand::reload))
			.then(literal("check").executes(BcrCommand::check))
			.then(literal("rule")
				.executes(c -> rule(c, lookedAt()))
				.then(argument("block", BlockStateArgument.block(ctx))
					.executes(c -> rule(c, c.getArgument("block", BlockInput.class).getState()))))));
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
