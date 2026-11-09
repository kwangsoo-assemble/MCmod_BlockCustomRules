package kr.asmbl.block_render_rules.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.asmbl.block_render_rules.config.BrrConfig;
import kr.asmbl.block_render_rules.config.Render;
import kr.asmbl.block_render_rules.config.Rule;
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
 * 옵션 화면 — 블록마다 한 줄(ID · 방식 · 컬링 패스 · 휘기 세기 · 지우기) · 쪽 넘기기 · 블록 추가 · 블렌딩 초 · 완료 / 취소.
 * Cloth Config 같은 라이브러리 없이 바닐라 버튼만 쓴다 — 버튼을 누르면 초안을 바꾸고 위젯을 다시 만든다.
 */
public final class BrrScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int ROW = 22;
	private static final int TOP = 34;

	private final Screen parent;
	private final Map<String, Rule> draft;
	private float blend;
	private int page;
	private String typed = "";
	private EditBox idBox;
	private List<String> rowIds = List.of();
	private int rowX;
	private int rowW;

	public BrrScreen(Screen parent) {
		super(Component.translatable("brr.title"));
		this.parent = parent;
		this.draft = new LinkedHashMap<>(BrrConfig.entries());
		this.blend = BrrConfig.blendSeconds();
	}

	private int perPage() {
		return Math.max(1, (this.height - TOP - 86) / ROW);
	}

	@Override
	protected void init() {
		this.rowW = Math.min(440, this.width - 20);
		this.rowX = (this.width - this.rowW) / 2;
		List<String> ids = new ArrayList<>(this.draft.keySet());
		int per = perPage();
		int pages = Math.max(1, (ids.size() + per - 1) / per);
		this.page = Math.max(0, Math.min(this.page, pages - 1));
		this.rowIds = ids.subList(this.page * per, Math.min(ids.size(), (this.page + 1) * per));

		int y = TOP;
		for (String id : this.rowIds) {
			addRow(id, y);
			y += ROW;
		}

		int by = this.height - 78;
		// 블록 추가 — ID 칸 · 추가 · 바라보는 블록
		this.idBox = new EditBox(this.font, this.rowX, by, this.rowW - 168, 20, Component.translatable("brr.add_id"));
		this.idBox.setMaxLength(256);
		this.idBox.setValue(this.typed);
		this.idBox.setResponder(v -> this.typed = v);
		this.addRenderableWidget(this.idBox);
		this.addRenderableWidget(Button.builder(Component.translatable("brr.add"), b -> addId(this.idBox.getValue()))
			.bounds(this.rowX + this.rowW - 164, by, 60, 20).build());
		Button look = Button.builder(Component.translatable("brr.add_target"), b -> addTarget())
			.bounds(this.rowX + this.rowW - 100, by, 100, 20).build();
		// 월드 밖(타이틀 화면의 Mod Menu)에서는 바라보는 블록이 없다
		look.active = this.minecraft != null && this.minecraft.level != null;
		this.addRenderableWidget(look);

		// 블렌딩 초 · 쪽 넘기기
		by += 24;
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			this.blend = Math.max(0.0F, Math.round((this.blend - 0.05F) * 100.0F) / 100.0F);
			this.rebuildWidgets();
		}).bounds(this.rowX, by, 20, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("brr.blend", String.format("%.2f", this.blend)), b -> {
		}).bounds(this.rowX + 22, by, 120, 20).build()).active = false;
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			this.blend = Math.min(5.0F, Math.round((this.blend + 0.05F) * 100.0F) / 100.0F);
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

		// 완료 / 취소
		by += 26;
		int half = (this.rowW - 4) / 2;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
			BrrConfig.apply(this.draft, this.blend);
			this.onClose();
		}).bounds(this.rowX, by, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(this.rowX + this.rowW - half, by, half, 20).build());
	}

	private void addRow(String id, int y) {
		Rule r = this.draft.get(id);
		int x = this.rowX + this.rowW - 270;
		this.addRenderableWidget(Button.builder(Component.translatable("brr.render." + r.render().id), b -> {
			Rule cur = this.draft.get(id);
			Render nextMode = cur.render().next();
			Rule changed = cur.withRender(nextMode);
			// 투명 · 반투명으로 처음 바꾸면 컬링 패스를 켠다 — 안 켜면 옆 블록 면이 지워져 너머가 뚫려 보인다(Plan D13 · Q5)
			boolean opens = nextMode == Render.CUTOUT || nextMode == Render.TRANSLUCENT;
			boolean wasOpen = cur.render() == Render.CUTOUT || cur.render() == Render.TRANSLUCENT;
			if (opens && !wasOpen) {
				changed = changed.withCullPass(true);
			}
			this.draft.put(id, changed);
			this.rebuildWidgets();
		}).bounds(x, y, 70, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable(r.cullPass() ? "brr.cull.on" : "brr.cull.off"), b -> {
			this.draft.put(id, this.draft.get(id).withCullPass(!this.draft.get(id).cullPass()));
			this.rebuildWidgets();
		}).bounds(x + 72, y, 78, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			this.draft.put(id, this.draft.get(id).withSway(Math.round((this.draft.get(id).sway() - 0.5F) * 2.0F) / 2.0F));
			this.rebuildWidgets();
		}).bounds(x + 152, y, 16, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("brr.sway", String.format("%.1f", r.sway())), b -> {
		}).bounds(x + 170, y, 46, 20).build()).active = false;
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			this.draft.put(id, this.draft.get(id).withSway(Math.round((this.draft.get(id).sway() + 0.5F) * 2.0F) / 2.0F));
			this.rebuildWidgets();
		}).bounds(x + 218, y, 16, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("x"), b -> {
			this.draft.remove(id);
			this.rebuildWidgets();
		}).bounds(x + 250, y, 20, 20).build());
	}

	private void addId(String raw) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		String id = BrrConfig.normalize(raw);
		this.draft.putIfAbsent(id, Rule.EMPTY);
		this.typed = "";
		// 새 줄이 보이게 마지막 쪽으로
		this.page = Integer.MAX_VALUE;
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
		addId(BuiltInRegistries.BLOCK.getKey(this.minecraft.level.getBlockState(bhr.getBlockPos()).getBlock()).toString());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(this.font, this.title, this.width / 2, 10, WHITE);
		g.drawCenteredString(this.font, Component.translatable("brr.help"), this.width / 2, 22, GRAY);
		int y = TOP;
		int maxW = this.rowW - 276;
		for (String id : this.rowIds) {
			int color = BrrConfig.known(id) ? WHITE : RED;
			String shown = this.font.plainSubstrByWidth(id, maxW);
			g.drawString(this.font, shown, this.rowX, y + 6, color);
			y += ROW;
		}
		if (this.rowIds.isEmpty()) {
			g.drawCenteredString(this.font, Component.translatable("brr.empty"), this.width / 2, TOP + 6, GRAY);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
