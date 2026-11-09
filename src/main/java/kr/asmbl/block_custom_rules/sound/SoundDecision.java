package kr.asmbl.block_custom_rules.sound;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 소리 하나를 갈아끼울지 정하는 순수 함수(D13 ~ D18 · D41) — 게임 쪽 {@code SoundHub} 가 재료를 모아 부른다.
 * 차례: 다른 월드 › 다루는 이름 › 서버 패킷 거르기(출처 · 표식 시드) › 블록 종류 › 주인 확인 › 이름 찾기.
 * 하나라도 아니면 원래 소리 그대로(원래 음량 · 높낮이 · 시드).
 */
public final class SoundDecision {
	/** 서버 패킷이면 안 바꾸는 출처(D18) — 연출 · 화면 · 음악. */
	public static final Set<String> PROTECTED_SOURCES = Set.of("master", "ui", "music");

	/** 결과 — {@link #KEEP} 이거나 새 이름. */
	public record Result(String soundId, boolean keepVolumePitch, String reason) {
		public boolean replaces() {
			return this.soundId != null;
		}
	}

	public static final Result KEEP = new Result(null, true, "keep");

	private SoundDecision() {
	}

	/**
	 * @param soundId        원래 소리 이름
	 * @param source         출처(`blocks` · `players` · `master` …)
	 * @param fromPacket     서버 소리 패킷에서 왔나
	 * @param seed           소리 시드
	 * @param sameWorld      지금 월드 · 세대에서 난 소리인가(아니면 갈아끼우지 않는다 — D41)
	 * @param category       그 소리 블록의 소리 종류(규칙 없으면 null)
	 * @param owner          그 블록이 이 소리의 주인인가(블록을 들고 부른 자리면 늘 참)
	 * @param defined        이름이 정의돼 있나(리팩 · 바닐라 `sounds.json`)
	 * @param registered     이름이 게임 레지스트리 소리인가(바닐라 · 모드 등록 소리) — 그러면 원래 음량 · 높낮이를 둔다
	 */
	public static Result decide(String soundId, String source, boolean fromPacket, long seed, boolean sameWorld, String category, boolean owner,
			SoundCategories categories, Predicate<String> defined, Predicate<String> registered) {
		if (!sameWorld) {
			return new Result(null, true, "other world");
		}
		String[] parts = SoundNames.split(soundId);
		if (parts == null) {
			return KEEP;
		}
		if (fromPacket && PROTECTED_SOURCES.contains(source)) {
			return new Result(null, true, "protected source");
		}
		if (fromPacket && SoundNames.isMarkerSeed(seed)) {
			return new Result(null, true, "marker seed");
		}
		if (category == null) {
			return KEEP;
		}
		if (!owner) {
			return new Result(null, true, "not owner");
		}
		String found = categories.find(category, parts[1], Map.of(), defined);
		if (found == null || found.equals(soundId)) {
			return new Result(null, true, "not defined");
		}
		// 부모 사슬로 바닐라 이름에 닿았으면 바닐라처럼(코드 음량 · 높낮이) — 리팩 이름이면 리팩 정의대로(D16)
		return new Result(found, registered.test(found), "replaced");
	}

	/**
	 * 주인 확인(D17) — 위치로 찾은 블록이 이 소리를 낼 블록인가.
	 * 소리 가운데 이름이 그 블록 소리 종류의 가운데 이름 · 블록 이름(같음 · 끝말 · 앞말 · 가운데 말) · 별칭 중 하나이거나,
	 * 소리 이름이 그 블록의 세트 소리(문 · 다락문 · 버튼 · 압력판 · 울타리 문)면 참.
	 */
	public static boolean owns(String soundId, String blockPath, Set<String> typeMids, Set<String> setNames, Set<String> aliases) {
		if (setNames.contains(soundId)) {
			return true;
		}
		String mid = SoundNames.mid(soundId);
		if (mid == null) {
			return false;
		}
		if (typeMids.contains(mid) || aliases.contains(mid)) {
			return true;
		}
		return mid.equals(blockPath) || blockPath.endsWith("_" + mid) || blockPath.startsWith(mid + "_") || blockPath.contains("_" + mid + "_");
	}

	/**
	 * 서버 걸음 · 떨어짐 패킷의 블록 자리 후보(D18) — 서버는 (⌊x⌋, ⌊y − 0.2⌋, ⌊z⌋) 를 고르고 패킷 좌표는 1/8칸으로 0 쪽으로 잘린다.
	 * 그래서 참 좌표는 양수면 [p, p + 1/8) · 음수면 (p − 1/8, p] — 그 구간이 정수 경계를 넘으면 이웃 칸도 후보다
	 * (y 는 −0.2 를 빼서 양수에서도 넘을 수 있다). 첫 후보가 패킷 좌표 그대로의 자리다.
	 */
	public static int[][] footCandidates(double x, double y, double z) {
		int[] xs = axis(x, 0.0);
		int[] ys = axis(y, -0.2);
		int[] zs = axis(z, 0.0);
		int[][] out = new int[xs.length * ys.length * zs.length][];
		int n = 0;
		for (int a : xs) {
			for (int b : ys) {
				for (int c : zs) {
					out[n++] = new int[] { a, b, c };
				}
			}
		}
		return out;
	}

	private static int[] axis(double p, double offset) {
		double lo;
		double hi;
		if (p > 0.0) {
			lo = p;
			hi = p + 0.125;
		} else if (p < 0.0) {
			lo = p - 0.125;
			hi = p;
		} else {
			lo = -0.125;
			hi = 0.125;
		}
		int primary = (int) Math.floor(p + offset);
		int a = (int) Math.floor(lo + offset + 1.0e-9);
		int b = (int) Math.floor(hi + offset - 1.0e-9);
		if (a == b) {
			return a == primary ? new int[] { primary } : new int[] { primary, a };
		}
		return new int[] { primary, primary == a ? b : a };
	}
}
