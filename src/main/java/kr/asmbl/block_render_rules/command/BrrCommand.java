package kr.asmbl.block_render_rules.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.asmbl.block_render_rules.config.BrrConfig;
import kr.asmbl.block_render_rules.config.Render;
import kr.asmbl.block_render_rules.config.Rule;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 클라 명령 `/brr` — 게임 안에서 블록 규칙을 빨리 바꾼다. 응답은 친 사람 채팅에만 뜬다(클라 명령 · 일부러 치는 명령).
 */
public final class BrrCommand {
	private BrrCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("brr")
			.then(literal("list").executes(BrrCommand::list))
			.then(literal("reload").executes(c -> {
				if (!BrrConfig.reload()) {
					c.getSource().sendError(Component.translatable("brr.cmd.reload_failed"));
					return 0;
				}
				c.getSource().sendFeedback(Component.translatable("brr.cmd.reloaded", BrrConfig.entries().size()));
				return 1;
			}))
			.then(literal("blend").then(argument("seconds", FloatArgumentType.floatArg(0.0F, 5.0F)).executes(c -> {
				float s = FloatArgumentType.getFloat(c, "seconds");
				BrrConfig.apply(BrrConfig.entries(), s);
				c.getSource().sendFeedback(Component.translatable("brr.cmd.blend", String.format("%.2f", s)));
				return 1;
			})))
			.then(literal("remove").then(block().executes(c -> {
				String id = id(c);
				Map<String, Rule> next = new LinkedHashMap<>(BrrConfig.entries());
				if (next.remove(id) == null) {
					c.getSource().sendError(Component.translatable("brr.cmd.not_listed", id));
					return 0;
				}
				BrrConfig.apply(next, BrrConfig.blendSeconds());
				c.getSource().sendFeedback(Component.translatable("brr.cmd.removed", id));
				return 1;
			})))
			.then(literal("set").then(block()
				.then(literal("render").then(argument("mode", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(new String[] { "default", "solid", "cutout", "translucent" }, b))
					.executes(c -> {
						Render r = Render.parse(StringArgumentType.getString(c, "mode"));
						if (r == null) {
							c.getSource().sendError(Component.translatable("brr.cmd.bad_mode"));
							return 0;
						}
						return set(c, rule -> rule.withRender(r));
					})))
				.then(literal("cull").then(argument("onoff", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(new String[] { "on", "off" }, b))
					.executes(c -> set(c, rule -> rule.withCullPass(StringArgumentType.getString(c, "onoff").equalsIgnoreCase("on"))))))
				.then(literal("sway").then(argument("strength", FloatArgumentType.floatArg(0.0F, Rule.SWAY_MAX))
					.executes(c -> set(c, rule -> rule.withSway(FloatArgumentType.getFloat(c, "strength"))))))))));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, ResourceLocation> block() {
		return argument("block", ResourceLocationArgument.id())
			.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), b));
	}

	private static String id(CommandContext<FabricClientCommandSource> c) {
		return c.getArgument("block", ResourceLocation.class).toString();
	}

	private static int set(CommandContext<FabricClientCommandSource> c, java.util.function.UnaryOperator<Rule> change) {
		String id = id(c);
		if (!BrrConfig.known(id)) {
			c.getSource().sendError(Component.translatable("brr.cmd.unknown", id));
			return 0;
		}
		Map<String, Rule> next = new LinkedHashMap<>(BrrConfig.entries());
		Rule r = change.apply(next.getOrDefault(id, Rule.EMPTY));
		next.put(id, r);
		BrrConfig.apply(next, BrrConfig.blendSeconds());
		c.getSource().sendFeedback(Component.literal(describe(id, r)));
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> c) {
		c.getSource().sendFeedback(Component.translatable("brr.cmd.list", BrrConfig.entries().size(), String.format("%.2f", BrrConfig.blendSeconds())));
		for (Map.Entry<String, Rule> e : BrrConfig.entries().entrySet()) {
			c.getSource().sendFeedback(Component.literal(" " + describe(e.getKey(), e.getValue()) + (BrrConfig.known(e.getKey()) ? "" : " (?)")));
		}
		return 1;
	}

	private static String describe(String id, Rule r) {
		return id + " — render " + r.render().id + " · cull_pass " + (r.cullPass() ? "on" : "off") + " · sway " + String.format("%.1f", r.sway());
	}
}
