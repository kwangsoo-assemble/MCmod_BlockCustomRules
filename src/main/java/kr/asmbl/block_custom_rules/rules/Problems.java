package kr.asmbl.block_custom_rules.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 규칙 파일을 읽으며 모은 문제(D12). 오류 = 그 줄을 버렸다 · 경고 = 줄은 쓰되 일부가 안 맞을 수 있다.
 * 글은 언어 키 + 인자로 들고 있다 — 화면 · 로그가 번역해 보인다.
 */
public final class Problems {
	public enum Severity {
		ERROR, WARNING
	}

	/** @param path JSON 경로(`rules[3].target` · 배열 번호는 0 부터) — 파일 전체면 빈 글자 */
	public record Problem(Severity severity, Origin origin, String path, String key, List<String> args) {
	}

	private final List<Problem> list = new ArrayList<>();
	// 같은 묶음을 여러 층이 물어도 한 번만 적는다
	private final Set<Problem> seen = new HashSet<>();

	public void error(Origin origin, String path, String key, Object... args) {
		add(Severity.ERROR, origin, path, key, args);
	}

	public void warn(Origin origin, String path, String key, Object... args) {
		add(Severity.WARNING, origin, path, key, args);
	}

	private void add(Severity s, Origin origin, String path, String key, Object... args) {
		List<String> a = new ArrayList<>(args.length);
		for (Object o : args) {
			a.add(String.valueOf(o));
		}
		Problem p = new Problem(s, origin, path, key, Collections.unmodifiableList(a));
		if (this.seen.add(p)) {
			this.list.add(p);
		}
	}

	public List<Problem> all() {
		return Collections.unmodifiableList(this.list);
	}

	public int count(Severity s) {
		int n = 0;
		for (Problem p : this.list) {
			if (p.severity() == s) {
				n++;
			}
		}
		return n;
	}

	/** JSON 경로 잇기 — 뿌리는 빈 글자. */
	public static String child(String parent, String key) {
		return parent.isEmpty() ? key : parent + "." + key;
	}

	public static String index(String parent, int i) {
		return parent + "[" + i + "]";
	}
}
