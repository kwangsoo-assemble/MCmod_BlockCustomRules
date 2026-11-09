package kr.asmbl.block_custom_rules.rules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 설정 화면의 층 조회(D39) — 규칙 줄 · `sound` 칸을 «위가 이김» 차례로 펼치고, 칸마다 이겼나 · 덮였나 · 누가 덮었나를 센다.
 * 게임 없이 돈다(시험이 직접 부른다).
 */
public final class Lookup {
	/** 이름표 갈래 — 같은 이름을 위 층 · 나중 파일이 적으면 통째로 대신한다. 나머지(`mix` · `entities`)는 목록 갈래. */
	public static final Set<String> NAMED = Set.of("categories", "actions", "layers", "defaults");

	private Lookup() {
	}

	/**
	 * 칸 하나의 형편.
	 *
	 * @param won      이 줄의 값이 최종 값인 상태 수
	 * @param total    이 줄이 맞은 상태 수
	 * @param beatenBy 진 상태 하나의 최종 출처(다 이겼으면 null)
	 */
	public record FieldView(Field field, Object value, int won, int total, Provenance beatenBy) {
		/** 맞은 상태가 있는데 하나도 못 이겼다. */
		public boolean covered() {
			return this.total > 0 && this.won == 0;
		}

		public boolean partial() {
			return this.won > 0 && this.won < this.total;
		}
	}

	/** @param single 대상이 블록 하나(태그 · 상태 없음)면 그 이름 — «모드 옵션으로 복사» 를 보인다 */
	public record LineView(RuleLine line, int matched, List<FieldView> fields, String single) {
	}

	public record FileView(RuleFile file, List<LineView> lines) {
	}

	/** `sound` 갈래 한 칸 — 이름표 갈래면 key 가 이름 · 목록 갈래면 `[i]`. coveredBy = 같은 이름을 더 위에서 적은 곳. */
	public record SoundEntry(Origin origin, String section, String key, JsonElement value, Origin coveredBy) {
	}

	/** 규칙 줄 — 위가 이김 차례(높은 층 · 같은 층은 나중 파일 · 파일 안은 나중 줄 먼저). */
	public static List<FileView> rules(RuleTable table) {
		List<RuleFile> files = new ArrayList<>(table.files());
		Collections.reverse(files);
		List<FileView> out = new ArrayList<>(files.size());
		for (RuleFile f : files) {
			List<LineView> lines = new ArrayList<>(f.rules.size());
			for (int i = f.rules.size() - 1; i >= 0; i--) {
				lines.add(line(table, f, f.rules.get(i)));
			}
			out.add(new FileView(f, List.copyOf(lines)));
		}
		return List.copyOf(out);
	}

	private static LineView line(RuleTable table, RuleFile f, RuleLine line) {
		List<StateView> states = table.matches(line);
		List<FieldView> fields = new ArrayList<>(line.values().size());
		for (Map.Entry<Field, Object> e : line.values().entrySet()) {
			Field field = e.getKey();
			// Resolver 가 적는 출처와 같은 꼴 — 같으면 이 줄이 이긴 것
			Provenance mine = new Provenance(f.origin, Problems.child(line.path(), field.key));
			int won = 0;
			Provenance beaten = null;
			for (StateView s : states) {
				Resolved r = table.get(s);
				Provenance p = r == null ? null : r.from(field);
				if (mine.equals(p)) {
					won++;
				} else if (beaten == null) {
					beaten = p;
				}
			}
			fields.add(new FieldView(field, e.getValue(), won, states.size(), beaten));
		}
		return new LineView(line, states.size(), List.copyOf(fields), single(line.targetJson()));
	}

	/** 대상이 블록 이름 하나인가 — 태그 · 상태 · 조합 · `*` 면 null. */
	static String single(JsonElement t) {
		if (t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isString()) {
			return null;
		}
		String s = t.getAsString().trim();
		if (s.isEmpty() || s.startsWith("#") || s.contains("[") || s.equals("*")) {
			return null;
		}
		return Ids.normalize(s);
	}

	/** `sound` 칸 한 갈래 — 위가 이김 차례(높은 층 · 나중 파일 먼저 · 목록 갈래는 파일 안도 나중 줄 먼저). */
	public static List<SoundEntry> sound(List<RuleFile> files, String section) {
		boolean named = NAMED.contains(section);
		// 아래 → 위로 훑어 이름마다 마지막(가장 위) 자리를 잡는다
		Map<String, Origin> top = new HashMap<>();
		List<List<SoundEntry>> perFile = new ArrayList<>();
		for (RuleFile f : files) {
			List<SoundEntry> mine = new ArrayList<>();
			JsonElement v = f.sound == null ? null : f.sound.get(section);
			if (v != null && named && v.isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
					String key = key(section, e.getKey());
					top.put(key, f.origin);
					mine.add(new SoundEntry(f.origin, section, key, e.getValue(), null));
				}
			} else if (v != null && !named && v.isJsonArray()) {
				for (int i = v.getAsJsonArray().size() - 1; i >= 0; i--) {
					mine.add(new SoundEntry(f.origin, section, "[" + i + "]", v.getAsJsonArray().get(i), null));
				}
			} else if (v != null) {
				// 꼴이 틀린 갈래도 보여 준다(읽기에선 오류로 버렸다)
				mine.add(new SoundEntry(f.origin, section, "?", v, null));
			}
			perFile.add(mine);
		}
		List<SoundEntry> out = new ArrayList<>();
		for (int i = perFile.size() - 1; i >= 0; i--) {
			for (SoundEntry e : perFile.get(i)) {
				Origin winner = named ? top.get(e.key()) : null;
				out.add(winner == null || winner.equals(e.origin()) ? e : new SoundEntry(e.origin(), section, e.key(), e.value(), winner));
			}
		}
		return List.copyOf(out);
	}

	/** 같은 이름을 견줄 열쇠 — 종류 · 겹 소리는 `ns:` 를 채운다(`stone` = `minecraft:stone`). */
	private static String key(String section, String raw) {
		if (section.equals("categories") || section.equals("layers")) {
			String id = Ids.normalize(raw);
			return id == null ? raw : id;
		}
		return raw;
	}

	/** 파일에 그 갈래가 있나(빈 객체 · 배열은 없음). */
	public static boolean has(RuleFile f, String section) {
		JsonElement v = f.sound == null ? null : f.sound.get(section);
		if (v == null) {
			return false;
		}
		return v instanceof JsonObject o ? !o.isEmpty() : !v.isJsonArray() || !v.getAsJsonArray().isEmpty();
	}
}
