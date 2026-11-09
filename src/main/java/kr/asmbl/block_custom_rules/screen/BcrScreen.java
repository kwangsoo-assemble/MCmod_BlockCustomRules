package kr.asmbl.block_custom_rules.screen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.asmbl.block_custom_rules.config.OptionsFile;
import kr.asmbl.block_custom_rules.config.Render;
import kr.asmbl.block_custom_rules.config.Rules;
import kr.asmbl.block_custom_rules.game.McCatalog;
import kr.asmbl.block_custom_rules.rules.CondParser;
import kr.asmbl.block_custom_rules.rules.Field;
import kr.asmbl.block_custom_rules.rules.Ids;
import kr.asmbl.block_custom_rules.rules.Lookup;
import kr.asmbl.block_custom_rules.rules.Origin;
import kr.asmbl.block_custom_rules.rules.Problems;
import kr.asmbl.block_custom_rules.rules.Provenance;
import kr.asmbl.block_custom_rules.rules.RuleFile;
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
 * 설정 화면(D39).
 * 탭 «규칙»: 맨 위 모드 옵션 줄(고침 — 대상 · 소리 종류 · 렌더 · 컬링 · 휘기 · ↑↓ · 지우기 · 위 줄이 이김) → 아래 리팩 층 조회(위 팩이 이김 ·
 * 줄을 눌러 조건 · 칸마다 이김 / 덮임 + 누가 덮었나 펼치기 · 덮인 칸 흐림 · 블록 하나짜리 줄은 «모드 옵션으로 복사»).
 * 탭 «소리 종류 · 겹 소리 · 섞기 · 엔티티»: `sound` 칸 조회(이름표 갈래는 같은 이름을 위 팩이 덮으면 흐림).
 * 휠로 넘긴다 · 조회는 저장된 규칙(지금 고치는 모드 옵션은 «완료» 뒤에 반영) · 타이틀 화면에서 열면 태그 · 바이옴은 «접속 전».
 */
public final class BcrScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int DIM = 0xFF6A6A6A;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFDD55;
	private static final int AQUA = 0xFF66CCFF;
	private static final int ROW = 22;
	private static final int LINE = 12;
	private static final int TOP = 56;
	/** 줄 오른쪽 버튼 자리(↑↓ · 렌더 · 컬링 · 휘기 · 지우기). */
	private static final int BUTTONS = 296;
	/** 소리 종류 글자 칸. */
	private static final int SOUND_W = 86;
	private static final int STEP = 3;
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private enum Tab {
		RULES("rules"), CATEGORIES("categories"), LAYERS("layers"), MIX("mix"), ENTITIES("entities");

		final String key;

		Tab(String key) {
			this.key = key;
		}

		/** 이 탭이 보여 주는 `sound` 갈래. */
		List<String> sections() {
			return switch (this) {
				case RULES -> List.of();
				case CATEGORIES -> List.of("categories", "actions");
				case LAYERS -> List.of("defaults", "layers");
				case MIX -> List.of("mix");
				case ENTITIES -> List.of("entities");
			};
		}
	}

	/** 목록 한 줄 — 모드 옵션 줄(opt ≥ 0 · 버튼이 붙는다) 또는 글 줄. */
	private static final class Row {
		final int h;
		int opt = -1;
		int indent;
		final List<Seg> segs = new ArrayList<>();
		/** 누르면 펼치기 · 접기 열쇠. */
		Object toggle;
		/** «모드 옵션으로 복사» 할 줄. */
		JsonObject copy;
		/** 마우스를 올리면 보일 전체 글. */
		String tip;

		Row(int h) {
			this.h = h;
		}

		Row seg(String text, int color) {
			this.segs.add(new Seg(text, color));
			return this;
		}
	}

	private record Seg(String text, int color) {
	}

	private record Placed(Row row, int y) {
	}

	private final Screen parent;
	/** 화면 차례(위가 이김). */
	private final List<OptionsFile.Line> draft = new ArrayList<>();
	private float blend;
	private String typed = "";
	private Tab tab = Tab.RULES;
	private int scroll;
	private final Set<Object> open = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<Placed> placed = new ArrayList<>();
	private boolean more;
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

	private int listBottom() {
		return this.tab == Tab.RULES ? this.height - 82 : this.height - 32;
	}

	@Override
	protected void init() {
		this.rowW = Math.min(560, this.width - 20);
		this.rowX = (this.width - this.rowW) / 2;

		// 탭
		Tab[] tabs = Tab.values();
		int tw = (this.rowW - (tabs.length - 1) * 2) / tabs.length;
		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			Button b = Button.builder(Component.translatable("bcr.tab." + t.key), x -> {
				this.tab = t;
				this.scroll = 0;
				this.rebuildWidgets();
			}).bounds(this.rowX + i * (tw + 2), 18, tw, 20).build();
			b.active = t != this.tab;
			this.addRenderableWidget(b);
		}

		// 목록 — 휠 자리부터 바닥까지 들어가는 만큼
		List<Row> rows = this.tab == Tab.RULES ? ruleRows() : soundRows();
		int area = listBottom() - TOP;
		int maxScroll = rows.size();
		int sum = 0;
		while (maxScroll > 0 && sum + rows.get(maxScroll - 1).h <= area) {
			sum += rows.get(maxScroll - 1).h;
			maxScroll--;
		}
		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
		this.placed.clear();
		int y = TOP;
		int i = this.scroll;
		for (; i < rows.size(); i++) {
			Row r = rows.get(i);
			if (y + r.h > listBottom()) {
				break;
			}
			this.placed.add(new Placed(r, y));
			if (r.opt >= 0) {
				addOptionRow(r.opt, y);
			}
			y += r.h;
		}
		this.more = i < rows.size();

		if (this.tab == Tab.RULES) {
			int by = this.height - 78;
			EditBox box = new EditBox(this.font, this.rowX, by, this.rowW - 168, 20, Component.translatable("bcr.add_id"));
			box.setMaxLength(256);
			box.setValue(this.typed);
			box.setHint(Component.translatable("bcr.add_id"));
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
		}

		int by = this.height - 28;
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

	// ── 탭 «규칙» ──

	private List<Row> ruleRows() {
		List<Row> rows = new ArrayList<>();
		rows.add(new Row(LINE).seg(tr("bcr.lookup.options_head"), AQUA));
		if (this.draft.isEmpty()) {
			rows.add(new Row(LINE).seg(tr("bcr.empty"), GRAY));
		}
		for (int i = 0; i < this.draft.size(); i++) {
			Row r = new Row(ROW);
			r.opt = i;
			r.tip = this.draft.get(i).target;
			rows.add(r);
		}
		Rules.Snapshot snap = Rules.snapshot();
		rows.add(new Row(LINE));
		rows.add(new Row(LINE).seg(tr("bcr.lookup.packs_head"), AQUA));
		statusRows(snap, rows);
		List<String> order = new ArrayList<>(snap.packOrder);
		Collections.reverse(order);
		String o = tr("bcr.lookup.order", String.join(" › ", order));
		Row orderRow = new Row(LINE).seg(o, GRAY);
		orderRow.tip = o;
		rows.add(orderRow);
		boolean any = false;
		for (Lookup.FileView fv : Lookup.rules(snap.table)) {
			if (fv.file().origin.kind() != Origin.Kind.PACK || fv.lines().isEmpty()) {
				continue;
			}
			any = true;
			rows.add(new Row(LINE).seg(fv.file().origin.describe(), WHITE));
			for (Lookup.LineView lv : fv.lines()) {
				lineRows(lv, fv.file(), rows);
			}
		}
		if (!any) {
			rows.add(new Row(LINE).seg(tr("bcr.lookup.no_pack_rules"), GRAY));
		}
		return rows;
	}

	private void statusRows(Rules.Snapshot snap, List<Row> rows) {
		if (!Rules.tagsReady()) {
			rows.add(new Row(LINE).seg(tr("bcr.lookup.before_join"), YELLOW));
		}
		int err = snap.problems.count(Problems.Severity.ERROR);
		int warn = snap.problems.count(Problems.Severity.WARNING);
		if (err + warn > 0) {
			rows.add(new Row(LINE).seg(tr("bcr.lookup.problems", err, warn), err > 0 ? RED : YELLOW));
		}
	}

	private void lineRows(Lookup.LineView lv, RuleFile file, List<Row> rows) {
		boolean isOpen = this.open.contains(lv.line());
		String target = lv.line().targetJson() == null ? "?" : compact(lv.line().targetJson());
		Row head = new Row(LINE);
		head.indent = 1;
		head.toggle = lv.line();
		head.tip = target;
		head.seg(isOpen ? "▾ " : "▸ ", GRAY).seg(target, WHITE);
		for (Lookup.FieldView f : lv.fields()) {
			head.seg("  " + fieldLabel(f.field(), f.value()), f.covered() ? DIM : f.partial() ? YELLOW : WHITE);
		}
		if (lv.single() != null) {
			head.copy = copyJson(lv);
		}
		rows.add(head);
		if (!isOpen) {
			return;
		}
		int w = this.rowW - 30;
		for (String s : wrap(tr("bcr.lookup.target", target), w)) {
			rows.add(sub(s, GRAY));
		}
		rows.add(sub(tr("bcr.lookup.matched", lv.matched()) + (lv.matched() == 0 && !Rules.tagsReady() ? " " + tr("bcr.lookup.matched_before_join") : ""),
				GRAY));
		for (Lookup.FieldView f : lv.fields()) {
			String label = fieldLabel(f.field(), f.value());
			String why;
			int color;
			if (f.total() == 0) {
				why = tr("bcr.lookup.field_none", label);
				color = DIM;
			} else if (f.covered()) {
				why = tr("bcr.lookup.field_covered", label, describe(f.beatenBy()));
				color = DIM;
			} else if (f.partial()) {
				why = tr("bcr.lookup.field_partial", label, f.won(), f.total(), describe(f.beatenBy()));
				color = YELLOW;
			} else {
				why = tr("bcr.lookup.field_won", label, f.won());
				color = WHITE;
			}
			for (String s : wrap(why, w)) {
				rows.add(sub(s, color));
			}
		}
		rows.add(sub(tr("bcr.lookup.source", file.origin.describe() + " › " + lv.line().path()), DIM));
	}

	private static Row sub(String text, int color) {
		Row r = new Row(LINE);
		r.indent = 3;
		return r.seg(text, color);
	}

	private static String describe(Provenance p) {
		return p == null ? "?" : p.describe();
	}

	/** 칸 이름 + 값 한 토막 — 「휘기 2.0」 · 「렌더 투명 허용」 · 「컬링 되돌림」. */
	private static String fieldLabel(Field f, Object v) {
		String value;
		if (v == Field.RESET) {
			value = tr("bcr.value.default");
		} else if (v instanceof Render r) {
			value = tr("bcr.render." + r.id);
		} else if (v instanceof Boolean b) {
			value = tr(b ? "bcr.value.on" : "bcr.value.off");
		} else if (v instanceof Float x) {
			value = String.format("%.1f", x);
		} else {
			value = String.valueOf(v);
		}
		return tr("bcr.field." + f.key, value);
	}

	/** 블록 하나짜리 줄을 모드 옵션 줄 꼴로. */
	private static JsonObject copyJson(Lookup.LineView lv) {
		JsonObject o = new JsonObject();
		o.addProperty("target", lv.single());
		for (var e : lv.line().values().entrySet()) {
			Object v = e.getValue();
			String k = e.getKey().key;
			if (v == Field.RESET) {
				o.addProperty(k, "default");
			} else if (v instanceof Render r) {
				o.addProperty(k, r.id);
			} else if (v instanceof Boolean b) {
				o.addProperty(k, b);
			} else if (v instanceof Float x) {
				o.addProperty(k, x);
			} else {
				o.addProperty(k, String.valueOf(v));
			}
		}
		return o;
	}

	// ── 탭 «소리» ──

	private List<Row> soundRows() {
		List<Row> rows = new ArrayList<>();
		Rules.Snapshot snap = Rules.snapshot();
		rows.add(new Row(LINE).seg(tr("bcr.lookup.head." + this.tab.key), AQUA));
		statusRows(snap, rows);
		boolean any = false;
		int w = this.rowW - 30;
		for (String section : this.tab.sections()) {
			List<Lookup.SoundEntry> entries = Lookup.sound(snap.table.files(), section);
			if (entries.isEmpty()) {
				continue;
			}
			any = true;
			rows.add(new Row(LINE));
			rows.add(new Row(LINE).seg("sound." + section, GRAY));
			Origin last = null;
			for (Lookup.SoundEntry e : entries) {
				if (!e.origin().equals(last)) {
					last = e.origin();
					Row fh = new Row(LINE).seg(last.describe(), WHITE);
					fh.indent = 1;
					rows.add(fh);
				}
				boolean isOpen = this.open.contains(e);
				boolean covered = e.coveredBy() != null;
				String text = e.key() + ": " + compact(e.value());
				Row r = new Row(LINE);
				r.indent = 2;
				r.toggle = e;
				r.tip = text;
				r.seg(isOpen ? "▾ " : "▸ ", GRAY).seg(text, covered ? DIM : WHITE);
				rows.add(r);
				if (!isOpen) {
					continue;
				}
				if (covered) {
					for (String s : wrap(tr("bcr.lookup.entry_covered", e.coveredBy().describe()), w)) {
						rows.add(sub(s, YELLOW));
					}
				}
				for (String jl : PRETTY.toJson(e.value()).split("\n")) {
					for (String s : wrap(jl, w)) {
						rows.add(sub(s, covered ? DIM : GRAY));
					}
				}
			}
		}
		if (!any) {
			rows.add(new Row(LINE).seg(tr("bcr.lookup.none"), GRAY));
		}
		return rows;
	}

	// ── 모드 옵션 줄 ──

	private void addOptionRow(int i, int y) {
		OptionsFile.Line l = this.draft.get(i);
		int x = this.rowX + this.rowW - BUTTONS + 6;
		if (l.editable) {
			EditBox sound = new EditBox(this.font, x - SOUND_W - 4, y + 1, SOUND_W, 18, Component.translatable("bcr.sound_category"));
			sound.setMaxLength(128);
			JsonElement cur = l.rest.get("sound_category");
			sound.setValue(cur instanceof JsonPrimitive p && p.isString() ? p.getAsString() : cur == null ? "" : cur.toString());
			sound.setHint(Component.translatable("bcr.sound_category"));
			sound.setTextColor(soundColor(sound.getValue()));
			sound.setResponder(v -> {
				String t = v.trim();
				if (t.isEmpty()) {
					l.rest.remove("sound_category");
				} else {
					l.rest.addProperty("sound_category", t);
				}
				sound.setTextColor(soundColor(t));
			});
			this.addRenderableWidget(sound);
		}
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
				l.sway = l.sway == null ? 0.0F : Math.min(Field.SWAY_MAX, Math.round((l.sway + 0.5F) * 2.0F) / 2.0F);
				this.rebuildWidgets();
			}).bounds(x + 248, y, 16, 20).build());
		}
		this.addRenderableWidget(Button.builder(Component.literal("x"), b -> {
			this.draft.remove(i);
			this.rebuildWidgets();
		}).bounds(x + 268, y, 20, 20).build());
	}

	/** 소리 종류 글자 — 비었거나 `"default"` · 이름 꼴이면 흰색 · 아니면 빨강. */
	private static int soundColor(String t) {
		return t.isEmpty() || t.equals("default") || t.equals("\"default\"") || Ids.normalize(t) != null ? WHITE : RED;
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
		this.scroll = 0;
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

	/** 리팩 줄을 모드 옵션 맨 위로 — 저장은 «완료» 때. */
	private void copyLine(JsonObject o) {
		this.tab = Tab.RULES;
		this.draft.add(0, OptionsFile.lineFrom(o));
		this.scroll = 0;
		this.rebuildWidgets();
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

	// ── 입력 ──

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) {
			return true;
		}
		if (button != 0) {
			return false;
		}
		for (Placed p : this.placed) {
			Row r = p.row();
			if (r.opt >= 0 || my < p.y() || my >= p.y() + r.h || mx < this.rowX || mx >= this.rowX + this.rowW) {
				continue;
			}
			if (r.copy != null && mx >= this.rowX + this.rowW - copyWidth()) {
				copyLine(r.copy);
				return true;
			}
			if (r.toggle != null) {
				if (!this.open.remove(r.toggle)) {
					this.open.add(r.toggle);
				}
				this.rebuildWidgets();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (sy == 0.0) {
			return super.mouseScrolled(mx, my, sx, sy);
		}
		int next = Math.max(0, this.scroll - (int) Math.signum(sy) * STEP);
		if (next != this.scroll && (sy > 0 || this.more)) {
			this.scroll = next;
			this.rebuildWidgets();
		}
		return true;
	}

	private int copyWidth() {
		return this.font.width(tr("bcr.lookup.copy")) + 4;
	}

	// ── 그리기 ──

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(this.font, this.title, this.width / 2, 6, WHITE);
		g.drawCenteredString(this.font, Component.translatable("bcr.help." + this.tab.key), this.width / 2, 43, GRAY);
		String tip = null;
		for (Placed p : this.placed) {
			Row r = p.row();
			int y = p.y();
			boolean hover = mouseY >= y && mouseY < y + r.h && mouseX >= this.rowX && mouseX < this.rowX + this.rowW;
			if (r.opt >= 0) {
				OptionsFile.Line l = this.draft.get(r.opt);
				int maxW = this.rowW - BUTTONS - (l.editable ? SOUND_W + 6 : 0);
				String shown = this.font.plainSubstrByWidth(l.target, maxW);
				g.drawString(this.font, shown, this.rowX, y + 6, targetColor(l));
				if (hover && !shown.equals(l.target) && mouseX < this.rowX + maxW) {
					tip = l.target;
				}
				continue;
			}
			int right = this.rowX + this.rowW - (r.copy != null ? copyWidth() : 0);
			int x = this.rowX + r.indent * 8;
			boolean cut = false;
			for (Seg s : r.segs) {
				int room = right - x;
				if (room <= 0) {
					cut = true;
					break;
				}
				String shown = this.font.plainSubstrByWidth(s.text(), room);
				g.drawString(this.font, shown, x, y + 2, s.color());
				x += this.font.width(shown);
				if (!shown.equals(s.text())) {
					cut = true;
					break;
				}
			}
			if (r.copy != null) {
				boolean on = hover && mouseX >= right;
				g.drawString(this.font, tr("bcr.lookup.copy"), right + 2, y + 2, on ? YELLOW : AQUA);
				if (on) {
					tip = tr("bcr.lookup.copy_tip");
				}
			}
			if (hover && cut && r.tip != null && tip == null) {
				tip = r.tip;
			}
		}
		if (this.scroll > 0) {
			g.drawString(this.font, "▲", this.rowX + this.rowW - 8, TOP - 10, GRAY);
		}
		if (this.more) {
			g.drawString(this.font, "▼", this.rowX + this.rowW - 8, listBottom() - 2, GRAY);
		}
		if (tip != null) {
			g.setTooltipForNextFrame(this.font, Component.literal(tip), mouseX, mouseY);
		}
	}

	// ── 돕는 것 ──

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	private static String compact(JsonElement e) {
		return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : e.toString();
	}

	/** 폭에 맞춰 자른 여러 줄. */
	private List<String> wrap(String s, int w) {
		List<String> out = new ArrayList<>();
		String rest = s;
		while (!rest.isEmpty()) {
			String take = this.font.plainSubstrByWidth(rest, w);
			if (take.isEmpty()) {
				take = rest.substring(0, 1);
			}
			out.add(take);
			rest = rest.substring(take.length());
		}
		if (out.isEmpty()) {
			out.add("");
		}
		return out;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
