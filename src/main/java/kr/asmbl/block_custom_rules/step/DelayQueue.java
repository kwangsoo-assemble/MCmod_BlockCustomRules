package kr.asmbl.block_custom_rules.step;

import java.util.PriorityQueue;
import java.util.function.BiConsumer;
import kr.asmbl.block_custom_rules.world.Generation;

/**
 * 지연 줄(D34 · D35) — 먼 소리 · 겹 소리 지연을 매 프레임 꺼낸다. 최대 1000ms.
 * 줄마다 만들 때의 월드 세대를 들고 있다가, 꺼낼 때 세대가 다르면 틀지 않고 버린다(D41).
 * 줄은 소리 재료만 든다 — 월드 · 엔티티를 강하게 붙잡지 않는다(D40). 마인크래프트 클래스를 모른다.
 */
public final class DelayQueue {
	public static final long MAX_NANOS = 1_000_000_000L;

	private record Item(long due, int generation, long order, Runnable play) {
	}

	private final PriorityQueue<Item> queue = new PriorityQueue<>((a, b) -> a.due != b.due ? Long.compare(a.due, b.due) : Long.compare(a.order, b.order));
	private long order;
	private BiConsumer<String, Throwable> errors = (w, t) -> {
	};

	public void errorSink(BiConsumer<String, Throwable> sink) {
		this.errors = sink;
	}

	/** 지금 시각 + 지연(0 ~ 1초로 자름)에 틀 것을 넣는다. */
	public synchronized void add(long nowNanos, long delayNanos, Runnable play) {
		long d = Math.max(0L, Math.min(MAX_NANOS, delayNanos));
		this.queue.add(new Item(nowNanos + d, Generation.current(), this.order++, play));
	}

	/** 때가 된 것을 튼다 — 세대가 다르면 버린다. 튼 개수. */
	public int drain(long nowNanos) {
		int n = 0;
		while (true) {
			Item it;
			synchronized (this) {
				it = this.queue.peek();
				if (it == null || it.due > nowNanos) {
					return n;
				}
				this.queue.poll();
			}
			if (!Generation.same(it.generation)) {
				continue;
			}
			try {
				it.play.run();
				n++;
			} catch (RuntimeException | LinkageError e) {
				this.errors.accept("delayed sound", e);
			}
		}
	}

	public synchronized void clear() {
		this.queue.clear();
	}

	public synchronized int size() {
		return this.queue.size();
	}
}
