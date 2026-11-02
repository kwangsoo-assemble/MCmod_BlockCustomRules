package kr.asmbl.cull_pass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * 옵션 화면 — 블록 ID 를 쉼표로 적는 칸 하나 · «바라보는 블록 추가» · 완료 / 취소.
 * Cloth Config 같은 라이브러리를 안 써서 Mod Menu 만 있으면 열린다.
 */
public final class CullPassScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;

	private final Screen parent;
	private EditBox box;
	private String draft;
	private List<String> unknown = List.of();

	public CullPassScreen(Screen parent) {
		super(Component.translatable("cull_pass.title"));
		this.parent = parent;
		this.draft = String.join(", ", CullPassConfig.ids());
	}

	@Override
	protected void init() {
		int w = Math.min(360, this.width - 40);
		int x = (this.width - w) / 2;
		int y = this.height / 2 - 20;
		this.box = new EditBox(this.font, x, y, w, 20, Component.translatable("cull_pass.blocks"));
		this.box.setMaxLength(8192);
		this.box.setValue(this.draft);
		this.box.setResponder(v -> {
			this.draft = v;
			this.unknown = CullPassConfig.unknown(parse(v));
		});
		this.unknown = CullPassConfig.unknown(parse(this.draft));
		this.addRenderableWidget(this.box);

		Button target = Button.builder(Component.translatable("cull_pass.add_target"), b -> this.addTarget())
			.bounds(x, y + 28, w, 20).build();
		// 월드 밖(타이틀 화면의 Mod Menu)에서는 바라보는 블록이 없다
		target.active = this.minecraft != null && this.minecraft.level != null;
		this.addRenderableWidget(target);

		int half = (w - 4) / 2;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
			CullPassConfig.apply(parse(this.box.getValue()));
			this.onClose();
		}).bounds(x, y + 56, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
			.bounds(x + w - half, y + 56, half, 20).build());
		this.setInitialFocus(this.box);
	}

	private void addTarget() {
		if (this.minecraft == null || this.minecraft.level == null) {
			return;
		}
		HitResult hit = this.minecraft.hitResult;
		if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		String id = BuiltInRegistries.BLOCK.getKey(this.minecraft.level.getBlockState(bhr.getBlockPos()).getBlock()).toString();
		List<String> cur = new ArrayList<>(parse(this.box.getValue()));
		if (!cur.contains(id)) {
			cur.add(id);
			this.box.setValue(String.join(", ", cur));
		}
	}

	private static List<String> parse(String v) {
		List<String> out = new ArrayList<>();
		for (String s : Arrays.asList(v.split(","))) {
			String t = s.trim();
			if (!t.isEmpty()) {
				out.add(t.contains(":") ? t : "minecraft:" + t);
			}
		}
		return out;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int cx = this.width / 2;
		int y = this.height / 2 - 20;
		g.drawCenteredString(this.font, this.title, cx, 20, WHITE);
		g.drawCenteredString(this.font, Component.translatable("cull_pass.help"), cx, y - 26, GRAY);
		g.drawCenteredString(this.font, Component.translatable("cull_pass.help2"), cx, y - 14, GRAY);
		if (!this.unknown.isEmpty()) {
			g.drawCenteredString(this.font, Component.translatable("cull_pass.unknown", String.join(", ", this.unknown)), cx, y + 84, RED);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
