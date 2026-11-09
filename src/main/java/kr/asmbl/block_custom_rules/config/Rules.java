package kr.asmbl.block_custom_rules.config;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.asmbl.block_custom_rules.game.McCatalog;
import kr.asmbl.block_custom_rules.game.McState;
import kr.asmbl.block_custom_rules.rules.Origin;
import kr.asmbl.block_custom_rules.rules.Problems;
import kr.asmbl.block_custom_rules.rules.Resolved;
import kr.asmbl.block_custom_rules.rules.Resolver;
import kr.asmbl.block_custom_rules.rules.RuleFile;
import kr.asmbl.block_custom_rules.rules.RuleTable;
import kr.asmbl.block_custom_rules.rules.StateView;
import kr.asmbl.block_custom_rules.sound.SoundCategories;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * 규칙 보관소(D5 · D11 · D44) — 리팩 규칙 파일 · 모드 옵션 · 서버 태그를 풀어 «블록 상태 → 최종 규칙» 표 하나로 들고 있다.
 * 다시 풀기: 리소스 리로드 · 태그 도착(접속 · 서버 `/reload`) · 월드 나감 · 모드 옵션 바꿈.
 * 새 표를 다 만든 뒤 한 번에 바꿔 끼운다(volatile · 바뀌지 않는 표) — 청크 스레드 여럿이 동시에 읽고, 만드는 동안엔 옛 표를 본다.
 * 풀기가 예외로 죽으면 옛 표를 그대로 쓴다(D45).
 */
public final class Rules {
	private static final Logger LOG = LogUtils.getLogger();
	/** 리팩 안 자리 — `assets/<ns>/block_custom_rules/*.json`. */
	public static final String DIR = "block_custom_rules";

	/** 리팩에서 읽어 둔 파일 하나 — 글이 없으면 읽기 오류. */
	public record PackFile(String pack, int layer, String location, String text, String error) {
	}

	/** 풀기 결과 한 벌. */
	public static final class Snapshot {
		static final Snapshot EMPTY = new Snapshot(RuleTable.EMPTY, new Reference2ObjectOpenHashMap<>(), SoundCategories.EMPTY, new Problems(), List.of(), 1,
				"init");

		public final RuleTable table;
		private final Reference2ObjectOpenHashMap<BlockState, Resolved> byState;
		/** `sound.categories` · `sound.actions`(이름표 칸 — 위 층 · 나중 파일이 통째로 대신). */
		public final SoundCategories categories;
		/** 소리 종류를 바꾼 블록이 하나라도 있나 — 없으면 소리 갈아끼우기를 바로 건너뛴다. */
		public final boolean anySoundCategory;
		public final Problems problems;
		/** 규칙 파일이 있든 없든 리팩 전부(아래 → 위) — 차례 실측 · 설정 화면 조회. */
		public final List<String> packOrder;
		/** 모드 옵션 층 번호(= 리팩 수 + 1). */
		public final int optionsLayer;
		public final String why;
		public final boolean anyRender;

		Snapshot(RuleTable table, Reference2ObjectOpenHashMap<BlockState, Resolved> byState, SoundCategories categories, Problems problems,
				List<String> packOrder, int optionsLayer, String why) {
			this.table = table;
			this.byState = byState;
			this.categories = categories;
			this.problems = problems;
			this.packOrder = packOrder;
			this.optionsLayer = optionsLayer;
			this.why = why;
			boolean render = false;
			boolean sound = false;
			for (Resolved r : byState.values()) {
				render |= r.render() != null;
				sound |= r.soundCategory() != null;
			}
			this.anyRender = render;
			this.anySoundCategory = sound;
		}

		public Resolved get(BlockState s) {
			return this.byState.get(s);
		}

		public int size() {
			return this.byState.size();
		}
	}

	private static volatile Snapshot current = Snapshot.EMPTY;
	private static volatile List<PackFile> packFiles = List.of();
	private static volatile List<String> packOrder = List.of();
	private static volatile RegistryAccess registries;
	private static volatile boolean tagsBound;
	private static List<String> lastLogged = List.of();

	private Rules() {
	}

	/** 그 상태의 규칙 · 없으면 null — 청크 스레드 · 소리 · 휘기가 부른다. */
	public static Resolved get(BlockState s) {
		Snapshot c = current;
		return c.byState.isEmpty() ? null : c.byState.get(s);
	}

	public static boolean anyCullPass() {
		return current.table.anyCullPass;
	}

	public static boolean anySway() {
		return current.table.anySway;
	}

	public static Snapshot snapshot() {
		return current;
	}

	/** 태그 · 바이옴이 지금 있나(접속 뒤). */
	public static boolean tagsReady() {
		return tagsBound;
	}

	// ── 다시 풀 때 ──

	/** 리소스 리로드(시작 · F3 + T · 팩 바꿈) — 규칙 파일 글을 팩 차례대로 읽어 두고 다시 푼다. */
	public static void onResourceReload(ResourceManager rm) {
		try {
			List<String> order = new ArrayList<>();
			rm.listPacks().map(PackResources::packId).forEach(order::add);
			Map<String, Integer> layerOf = new HashMap<>();
			for (int i = 0; i < order.size(); i++) {
				layerOf.putIfAbsent(order.get(i), i + 1);
			}
			Map<ResourceLocation, List<Resource>> stacks = rm.listResourceStacks(DIR, rl -> rl.getPath().endsWith(".json"));
			List<PackFile> files = new ArrayList<>();
			for (Map.Entry<ResourceLocation, List<Resource>> e : stacks.entrySet()) {
				for (Resource r : e.getValue()) {
					String id = r.sourcePackId();
					Integer layer = layerOf.get(id);
					if (layer == null) {
						order.add(id);
						layer = order.size();
						layerOf.put(id, layer);
					}
					String loc = e.getKey().toString();
					try (BufferedReader rd = r.openAsReader()) {
						files.add(new PackFile(display(id), layer, loc, rd.lines().collect(Collectors.joining("\n")), null));
					} catch (IOException | RuntimeException ex) {
						files.add(new PackFile(display(id), layer, loc, null, ex.toString()));
					}
				}
			}
			files.sort(Comparator.comparingInt(PackFile::layer).thenComparing(PackFile::location));
			List<String> shown = new ArrayList<>(order.size());
			for (String id : order) {
				shown.add(display(id));
			}
			packOrder = Collections.unmodifiableList(shown);
			packFiles = List.copyOf(files);
			LOG.info("[block_custom_rules] 리팩 차례(아래 → 위 · 위가 이김): {} · 규칙 파일 {}개", shown, files.size());
		} catch (RuntimeException e) {
			LOG.warn("[block_custom_rules] 리팩 규칙을 못 읽어 직전 것을 쓴다", e);
		}
		rebuild("resources");
	}

	/** 접속 · 서버 `/reload` — 서버 태그 · 바이옴 레지스트리가 왔다. 클라 스레드에서만(D44). */
	public static void onTagsLoaded(RegistryAccess regs) {
		registries = regs;
		tagsBound = true;
		rebuild("tags");
	}

	/** 월드에서 나갔다 — 서버 태그 · 바이옴을 잊는다(다음 서버는 다를 수 있다). */
	public static void onLeftWorld() {
		registries = null;
		tagsBound = false;
		rebuild("left world");
	}

	/** 모드 옵션을 다시 읽고 푼다(명령 `reload`). */
	public static boolean reloadOptions() {
		boolean ok = OptionsFile.load();
		rebuild("options");
		return ok;
	}

	/** 새 표를 만들어 바꿔 끼운다. 렌더 · 컬링이 바뀌었고 월드가 열려 있으면 청크를 다시 짓는다. */
	public static void rebuild(String why) {
		long t0 = System.nanoTime();
		Snapshot next;
		try {
			Problems problems = new Problems();
			McCatalog cat = new McCatalog(registries, tagsBound);
			List<RuleFile> files = new ArrayList<>();
			for (PackFile f : packFiles) {
				Origin o = Origin.pack(f.layer(), f.pack(), f.location());
				if (f.text() == null) {
					problems.error(o, "", "bcr.problem.io", f.error());
					continue;
				}
				files.add(RuleFile.parse(o, f.text(), cat, problems, Set.of()));
			}
			int top = packOrder.size() + 1;
			files.add(OptionsFile.ruleFile(top, cat, problems));
			RuleTable table = Resolver.resolve(files, cat, problems);
			Reference2ObjectOpenHashMap<BlockState, Resolved> map = new Reference2ObjectOpenHashMap<>(table.all().size());
			for (Map.Entry<StateView, Resolved> e : table.all().entrySet()) {
				map.put(((McState) e.getKey()).state, e.getValue());
			}
			SoundCategories cats = SoundCategories.read(table.files(), problems);
			next = new Snapshot(table, map, cats, problems, packOrder, top, why);
		} catch (RuntimeException | LinkageError e) {
			LOG.warn("[block_custom_rules] 규칙 풀기 실패({}) — 직전 표를 쓴다", why, e);
			return;
		}
		Snapshot old = current;
		current = next;
		long ms = (System.nanoTime() - t0) / 1_000_000L;
		LOG.info("[block_custom_rules] 규칙 풀기({}) {}ms · 규칙 든 상태 {}개 · 파일 {}개 · 오류 {} · 경고 {}", why, ms, next.size(),
				next.table.files().size(), next.problems.count(Problems.Severity.ERROR), next.problems.count(Problems.Severity.WARNING));
		logProblems(next.problems);
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.level != null && renderDiffers(old, next)) {
			mc.levelRenderer.allChanged();
		}
	}

	private static boolean renderDiffers(Snapshot a, Snapshot b) {
		if (a.byState.size() != b.byState.size() && (a.anyRender || b.anyRender || a.table.anyCullPass || b.table.anyCullPass)) {
			return true;
		}
		for (var e : b.byState.reference2ObjectEntrySet()) {
			if (!sameLook(a.byState.get(e.getKey()), e.getValue())) {
				return true;
			}
		}
		for (var e : a.byState.reference2ObjectEntrySet()) {
			if (!b.byState.containsKey(e.getKey()) && !sameLook(e.getValue(), null)) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameLook(Resolved x, Resolved y) {
		Render rx = x == null ? null : x.render();
		Render ry = y == null ? null : y.render();
		boolean cx = x != null && x.cullPass();
		boolean cy = y != null && y.cullPass();
		return rx == ry && cx == cy;
	}

	/** 문제는 바뀌었을 때만 로그에 — 리로드마다 같은 줄이 쌓이지 않게. */
	private static void logProblems(Problems problems) {
		List<String> lines = new ArrayList<>();
		for (Problems.Problem p : problems.all()) {
			lines.add(format(p));
		}
		if (lines.equals(lastLogged)) {
			return;
		}
		lastLogged = lines;
		for (String l : lines) {
			LOG.warn("[block_custom_rules] {}", l);
		}
	}

	/** 한 줄 — `[오류] 팩 › 파일 › 경로: 글`. 명령 · 로그가 같이 쓴다. */
	public static String format(Problems.Problem p) {
		String sev = Component.translatable(p.severity() == Problems.Severity.ERROR ? "bcr.problem.error" : "bcr.problem.warning").getString();
		String msg = Component.translatable(p.key(), p.args().toArray()).getString();
		String where = p.origin().describe() + (p.path().isEmpty() ? "" : " › " + p.path());
		return "[" + sev + "] " + where + ": " + msg;
	}

	/** 팩 이름 — 폴더 · zip 팩의 `file/` 머리를 뗀다. */
	static String display(String packId) {
		return packId.startsWith("file/") ? packId.substring(5) : packId;
	}
}
