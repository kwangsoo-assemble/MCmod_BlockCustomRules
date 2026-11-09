package kr.asmbl.block_custom_rules.screen;

import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.asmbl.block_custom_rules.config.OptionsFile;
import kr.asmbl.block_custom_rules.config.Render;
import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.game.McCatalog;
import kr.asmbl.block_custom_rules.rules.CondParser;
import kr.asmbl.block_custom_rules.rules.Origin;
import kr.asmbl.block_custom_rules.rules.Problems;
import kr.asmbl.block_custom_rules.rules.TagRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 모드 옵션 화면(D39 의 모드 옵션 부분) — 줄마다 대상(명령 꼴 글자) · 렌더 · 컬링 · 휘기 · ↑↓ · 지우기.
 * **위 줄이 이긴다**(파일은 거꾸로 — 파일은 아래 줄이 이긴다) · 새 줄은 맨 위 · 대상 글자는 바로 검사해 빨강(틀림) · 노랑(없는 이름).
 * 화면이 못 고치는 줄(조합 대상 · 소리 칸 `"default"` 등)은 흐리게 보이고 저장할 때 그대로 둔다.
 * 리팩 층 조회 · 소리 탭은 T11.
 */
public final class BcrScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFDD55;
	private static final int ROW = 22;
	private static final int TOP = 34;
	private static final int BUTTONS = 296;

	private final Screen parent;
	/** 화면 차례(위가 이김). */
	private final List<OptionsFile.Line> draft = new ArrayList<>();
	private float blend;
	private int page;
	private String typed = "";
	private List<Integer> rowIdx = List.of();
	/** 대상 글자 → 검사 색(프레임마다 다시 검사하지 않게). */
	private final Map<String, Integer> colors = new HashMap<>();
	private int rowX;
	private int rowW;

	public BcrScreen(Screen parent) {
		super(Component.translatable("bcr.title"));
		this.parent = parent;
		for (OptionsFile.Line l : OptionsFile.lines()) {
			this.draft.add(l.copy());
		}
		Collections.reverse(this.draft);
		this.blend = OptionsFile.blendSeconds();
	}

	private int perPage() {
		return Math.max(1, (this.height - TOP - 86) / ROW);
	}

	@Override
	protected void init() {
		this.rowW = Math.min(460, this.width - 20);
		this.rowX = (this.width - this.rowW) / 2;
		int per = perPage();
		int pages = Math.max(1, (this.draft.size() + per - 1) / per);
		this.page = Math.max(0, Math.min(this.page, pages - 1));
		List<Integer> idx = new ArrayList<>();
		for (int i = this.page * per; i < Math.min(this.draft.size(), (this.page + 1) * per); i++) {
			idx.add(i);
		}
		this.rowIdx = idx;
		int y = TOP;
		for (int i : this.rowIdx) {
			addRow(i, y);
			y += ROW;
		}

		int by = this.height - 78;
		EditBox box = new EditBox(this.font, this.rowX, by, this.rowW - 168, 20, Component.translatable("bcr.add_id"));
		box.setMaxLength(256);
		box.setValue(this.typed);
		box.setResponder(v -> this.typed = v);
		this.addRenderableWidget(box);
		this.addRenderableWidget(Button.builder(Component.translatable("bcr.add"), b -> addLine(box.getValue()))
			.bounds(this.rowX + this.rowW - 164, by, 60, 20).build());
		Button look = Button.builder(Component.translatable("bcr.add_target"), b -> addTarget())
			.bounds(this.rowX + this.rowW - 100, by, 100, 20).build();
		// 월드 밖(타이틀 화면의 Mod Menu)에서는 바라보는 블록이 없다
		look.active = this.minecraft != null && this.minecraft.level != null;
		this.addRenderableWidget(look);

		by += 24;
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			this.blend = Math.max(0.0F, Math.round((this.blend - 0.05F) * 100.0F) / 100.0F);
			this.rebuildWidgets();
		}).bounds(this.rowX, by, 20, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("bcr.blend", String.format("%.2f", this.blend)), b -> {
		}).bounds(this.rowX + 22, by, 120, 20).build()).active = false;
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			this.blend = Math.min(OptionsFile.BLEND_MAX, Math.round((this.blend + 0.05F) * 100.0F) / 100.0F);
			this.rebuildWidgets();
		}).bounds(this.rowX + 144, by, 20, 20).build());
		Button prev = Button.builder(Component.literal("<"), b -> {
			this.page--;
			this.rebuildWidgets();
		}).bounds(this.rowX + this.rowW - 100, by, 20, 20).build();
		prev.active = this.page > 0;
		this.addRenderableWidget(prev);
		this.addRenderableWidget(Button.builder(Component.literal((this.page + 1) + " / " + pages), b -> {
		}).bounds(this.rowX + this.rowW - 78, by, 56, 20).build()).active = false;
		Button next = Button.builder(Component.literal(">"), b -> {
			this.page++;
			this.rebuildWidgets();
		}).bounds(this.rowX + this.rowW - 20, by, 20, 20).build();
		next.active = this.page < pages - 1;
		this.addRenderableWidget(next);

		by += 26;
		int half = (this.rowW - 4) / 2;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
			List<OptionsFile.Line> fileOrder = new ArrayList<>(this.draft);
			Collections.reverse(fileOrder);
			if (OptionsFile.save(fileOrder, this.blend)) {
				Rules.rebuild("options screen");
			}
			this.onClose();
		}).bounds(this.rowX, by, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(this.rowX + this.rowW - half, by, half, 20).build());
	}

	private void addRow(int i, int y) {
		OptionsFile.Line l = this.draft.get(i);
		int x = this.rowX + this.rowW - BUTTONS + 6;
		Button up = Button.builder(Component.literal("↑"), b -> move(i, -1)).bounds(x, y, 16, 20).build();
		up.active = i > 0;
		this.addRenderableWidget(up);
		Button down = Button.builder(Component.literal("↓"), b -> move(i, 1)).bounds(x + 18, y, 16, 20).build();
		down.active = i < this.draft.size() - 1;
		this.addRenderableWidget(down);
		if (l.editable) {
			this.addRenderableWidget(Button.builder(renderLabel(l.render), b -> {
				l.render = nextRender(l.render);
				// 투명 · 반투명으로 처음 바꾸면 컬링 패스를 켠다 — 안 켜면 옆 블록 면이 지워져 너머가 뚫려 보인다
				if ((l.render == Render.CUTOUT || l.render == Render.TRANSLUCENT) && l.cullPass == null) {
					l.cullPass = true;
				}
				this.rebuildWidgets();
			}).bounds(x + 38, y, 70, 20).build());
			this.addRenderableWidget(Button.builder(cullLabel(l.cullPass), b -> {
				l.cullPass = l.cullPass == null ? Boolean.TRUE : l.cullPass ? Boolean.FALSE : null;
				this.rebuildWidgets();
			}).bounds(x + 110, y, 66, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
				l.sway = l.sway == null ? null : l.sway <= 0.0F ? null : Math.max(0.0F, Math.round((l.sway - 0.5F) * 2.0F) / 2.0F);
				this.rebuildWidgets();
			}).bounds(x + 178, y, 16, 20).build());
			this.addRenderableWidget(Button.builder(l.sway == null ? Component.translatable("bcr.sway.none")
				: Component.translatable("bcr.sway", String.format("%.1f", l.sway)), b -> {
				}).bounds(x + 196, y, 50, 20).build()).active = false;
			this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
				l.sway = l.sway == null ? 0.0F : Math.min(kr.asmbl.block_custom_rules.rules.Field.SWAY_MAX, Math.round((l.sway + 0.5F) * 2.0F) / 2.0F);
				this.rebuildWidgets();
			}).bounds(x + 248, y, 16, 20).build());
		}
		this.addRenderableWidget(Button.builder(Component.literal("x"), b -> {
			this.draft.remove(i);
			this.rebuildWidgets();
		}).bounds(x + 268, y, 20, 20).build());
	}

	private void move(int i, int d) {
		int j = i + d;
		if (j < 0 || j >= this.draft.size()) {
			return;
		}
		Collections.swap(this.draft, i, j);
		this.rebuildWidgets();
	}

	private static Render nextRender(Render r) {
		if (r == null) {
			return Render.SOLID;
		}
		return switch (r) {
			case SOLID -> Render.CUTOUT;
			case CUTOUT -> Render.TRANSLUCENT;
			case TRANSLUCENT -> Render.DEFAULT;
			case DEFAULT -> null;
		};
	}

	private static Component renderLabel(Render r) {
		return Component.translatable(r == null ? "bcr.render.none" : "bcr.render." + r.id);
	}

	private static Component cullLabel(Boolean c) {
		return Component.translatable(c == null ? "bcr.cull.none" : c ? "bcr.cull.on" : "bcr.cull.off");
	}

	private void addLine(String raw) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		this.draft.add(0, new OptionsFile.Line(raw.trim()));
		this.typed = "";
		this.page = 0;
		this.rebuildWidgets();
	}

	private void addTarget() {
		if (this.minecraft == null || this.minecraft.level == null) {
			return;
		}
		HitResult hit = this.minecraft.hitResult;
		if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		addLine(BuiltInRegistries.BLOCK.getKey(this.minecraft.level.getBlockState(bhr.getBlockPos()).getBlock()).toString());
	}

	/** 대상 글자 바로 검사 — 오류면 빨강 · 경고(없는 이름 · 속성)면 노랑. */
	private int targetColor(OptionsFile.Line l) {
		if (!l.editable) {
			return GRAY;
		}
		return this.colors.computeIfAbsent(l.target, BcrScreen::checkTarget);
	}

	private static int checkTarget(String target) {
		Problems p = new Problems();
		Origin o = Origin.options(0, OptionsFile.NAME);
		new CondParser(o, new McCatalog(null, false), p, new ArrayList<TagRef>()).block(new JsonPrimitive(target), "target");
		if (p.count(Problems.Severity.ERROR) > 0) {
			return RED;
		}
		return p.count(Problems.Severity.WARNING) > 0 ? YELLOW : WHITE;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(this.font, this.title, this.width / 2, 10, WHITE);
		g.drawCenteredString(this.font, Component.translatable("bcr.help"), this.width / 2, 22, GRAY);
		int y = TOP;
		int maxW = this.rowW - BUTTONS;
		for (int i : this.rowIdx) {
			OptionsFile.Line l = this.draft.get(i);
			String shown = this.font.plainSubstrByWidth(l.target, maxW);
			g.drawString(this.font, shown, this.rowX, y + 6, targetColor(l));
			y += ROW;
		}
		if (this.rowIdx.isEmpty()) {
			g.drawCenteredString(this.font, Component.translatable("bcr.empty"), this.width / 2, TOP + 6, GRAY);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
