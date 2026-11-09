package kr.asmbl.block_custom_rules.rules;

/**
 * 위치 · 환경 조건이 월드를 묻는 창(D8 · S2) — 게임 쪽 구현은 클라 월드를 본다 · 시험은 가짜를 넣는다.
 * 안 읽힌 청크 자리는 «모름» — 그 자리를 묻는 조건은 거짓이 된다(D42).
 */
public interface WorldProbe {
	/** 그 자리 청크가 읽혀 있나. */
	boolean loaded(int x, int y, int z);

	String biome(int x, int y, int z);

	String dimension();

	/** 밝기 0 ~ 15(블록 · 하늘 중 큰 것). */
	int light(int x, int y, int z);

	boolean canSeeSky(int x, int y, int z);

	/** 그 자리 유체 이름(`minecraft:water` 등) · 없으면 null. */
	String fluid(int x, int y, int z);

	StateView block(int x, int y, int z);

	boolean raining();

	boolean thundering();

	/** 그 자리 강수 — `none` · `rain` · `snow`. */
	String precipitation(int x, int y, int z);

	long dayTime();
}
