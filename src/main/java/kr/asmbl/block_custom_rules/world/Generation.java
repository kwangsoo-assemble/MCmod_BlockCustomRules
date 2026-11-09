package kr.asmbl.block_custom_rules.world;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * 월드 세대 번호(D40 · D41) — 바닥 규칙 «월드에 묶인 상태는 그 월드와 같이 버린다».
 * 접속 · 차원 이동 · 리스폰 · 나가기 · 종료 때 번호를 올리고, 등록된 비우기를 모두 부른다.
 * 지연 소리 · 디버거 글자처럼 나중에 꺼내 쓰는 것은 만들 때의 번호를 들고 있다가, 꺼낼 때 번호가 다르면 버린다.
 * 마인크래프트 클래스를 모른다(이벤트 잇기는 {@link WorldHooks}) — 게임 없이 시험한다.
 */
public final class Generation {
	private static volatile int current = 1;
	private static final List<Runnable> RESETS = new CopyOnWriteArrayList<>();
	private static final List<IntConsumer> ENTITY_GONE = new CopyOnWriteArrayList<>();
	/** 비우기 · 엔티티 지우기에서 난 예외를 알리는 곳 — 게임 쪽이 로그를 단다. */
	private static volatile BiConsumer<String, Throwable> errors = (what, t) -> {
	};

	private Generation() {
	}

	public static int current() {
		return current;
	}

	/** 만들 때의 번호와 지금이 같은가 — 다르면 다른 월드의 것이니 버린다. */
	public static boolean same(int generation) {
		return generation == current;
	}

	/** 세대가 바뀔 때 비울 것 — 등록 차례대로 부른다. 하나가 예외를 내도 나머지는 부른다. */
	public static void onReset(Runnable reset) {
		RESETS.add(reset);
	}

	/** 엔티티가 내려갈 때(id) — 그 엔티티의 걸음 · 추적 상태를 지운다. */
	public static void onEntityGone(IntConsumer gone) {
		ENTITY_GONE.add(gone);
	}

	public static void errorSink(BiConsumer<String, Throwable> sink) {
		errors = sink;
	}

	/** 번호를 올리고 모두 비운다 — 렌더 스레드(게임 주 스레드)에서만 부른다. */
	public static void bump(String why) {
		current++;
		for (Runnable r : RESETS) {
			try {
				r.run();
			} catch (RuntimeException | LinkageError e) {
				errors.accept("reset (" + why + ")", e);
			}
		}
	}

	public static void entityGone(int id) {
		for (IntConsumer c : ENTITY_GONE) {
			try {
				c.accept(id);
			} catch (RuntimeException | LinkageError e) {
				errors.accept("entity gone " + id, e);
			}
		}
	}
}
