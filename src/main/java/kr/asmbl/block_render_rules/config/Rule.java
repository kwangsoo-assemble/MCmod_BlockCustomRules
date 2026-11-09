package kr.asmbl.block_render_rules.config;

/**
 * 블록 하나의 규칙 — 텍스처 그리는 방식 · 옆 블록 면을 가리지 않기(컬링 패스) · 휘기 세기(0 이면 안 휜다).
 */
public record Rule(Render render, boolean cullPass, float sway) {
	public static final float SWAY_MAX = 5.0F;
	public static final Rule EMPTY = new Rule(Render.DEFAULT, false, 0.0F);

	public Rule {
		if (render == null) {
			render = Render.DEFAULT;
		}
		sway = Math.max(0.0F, Math.min(SWAY_MAX, Float.isFinite(sway) ? sway : 0.0F));
	}

	public Rule withRender(Render r) {
		return new Rule(r, this.cullPass, this.sway);
	}

	public Rule withCullPass(boolean c) {
		return new Rule(this.render, c, this.sway);
	}

	public Rule withSway(float s) {
		return new Rule(this.render, this.cullPass, s);
	}

	/** 아무 일도 하지 않는 규칙 — 저장할 때 빼지 않는다(🙋 가 넣어 둔 줄은 남긴다). */
	public boolean isNoop() {
		return this.render == Render.DEFAULT && !this.cullPass && this.sway <= 0.0F;
	}
}
