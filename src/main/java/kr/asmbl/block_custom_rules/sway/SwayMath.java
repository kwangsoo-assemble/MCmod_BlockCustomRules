package kr.asmbl.block_custom_rules.sway;

/**
 * 휘기 계산 — 마인크래프트에 기대지 않는 순수 함수(게임 없이 시험한다 · `src/test-standalone`).
 * 좌표는 위에서 본 X · Z(블록 칸은 [bx, bx+1] × [bz, bz+1]).
 */
public final class SwayMath {
	/** 중심 근처 반지름(오프셋 단위 · 0.25 = 블록 중심에서 0.125칸) — 이 안에서는 방향 대신 세기가 준다(Plan D8). */
	public static final double CENTER_SOFT = 0.25;
	/** 히트박스를 가로로 부풀리는 양 — 맞닿아 지나가도 들어가게(Plan D7). */
	public static final double TOUCH_MARGIN = 0.02;

	private SwayMath() {
	}

	/**
	 * 엔티티 하나가 블록 칸 하나를 미는 벡터 — 겹치지 않으면 null.
	 * 방향 = 블록 중심 − 겹친 사각형 중심(들어온 쪽의 반대) · 세기 = 겹친 넓이 ÷ min(히트박스 넓이, 블록 칸 1).
	 * 히트박스가 칸 안에 다 들어오면 1 — 사람(0.6칸)이든 큰 몹이든 한 엔티티의 최대는 같다.
	 * 히트박스 중심이 칸 밖이어도 모서리만 걸치면 들어간다(🙋 2026-11-09).
	 */
	public static double[] push(double bx, double bz, double hx0, double hz0, double hx1, double hz1) {
		double ox0 = Math.max(hx0, bx);
		double ox1 = Math.min(hx1, bx + 1.0);
		double oz0 = Math.max(hz0, bz);
		double oz1 = Math.min(hz1, bz + 1.0);
		if (ox1 <= ox0 || oz1 <= oz0) {
			return null;
		}
		double full = Math.min((hx1 - hx0) * (hz1 - hz0), 1.0);
		double area = full > 0.0 ? Math.min(1.0, (ox1 - ox0) * (oz1 - oz0) / full) : 0.0;
		double ox = ((bx + 0.5) - (ox0 + ox1) * 0.5) / 0.5;
		double oz = ((bz + 0.5) - (oz0 + oz1) * 0.5) / 0.5;
		double len = Math.sqrt(ox * ox + oz * oz);
		if (len > 1.0) {
			ox /= len;
			oz /= len;
			len = 1.0;
		}
		// 중심 근처에서 길이 1 로 펴면 몇 픽셀 움직임에 방향이 뒤집힌다(SWAY 의 어색함) — 펴지 않고 줄인다
		double d = Math.max(len, CENTER_SOFT);
		return new double[] { ox / d * area, oz / d * area };
	}

	/** 미는 벡터 합을 목표 기울기로 — 길이를 1 로 자르고 블록 세기를 곱한다. 결과는 out 에. */
	public static void target(double sx, double sz, double strength, double[] out) {
		double len = Math.sqrt(sx * sx + sz * sz);
		if (len > 1.0) {
			sx /= len;
			sz /= len;
		}
		out[0] = sx * strength;
		out[1] = sz * strength;
	}

	/** 한 프레임에 목표 쪽으로 다가가는 비율 — 시정수 τ = 블렌딩 초 ÷ 3(블렌딩 초에 약 95%). */
	public static double blendAlpha(double dtSeconds, double blendSeconds) {
		if (blendSeconds <= 0.0) {
			return 1.0;
		}
		return 1.0 - Math.exp(-dtSeconds / (blendSeconds / 3.0));
	}
}
