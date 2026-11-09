package kr.asmbl.block_custom_rules.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 조건 하나(D8) — 블록(상태) · 엔티티(종류 이름)가 같은 조합(`any_of` · `all_of` · `not` · `"*"`)을 쓴다.
 *
 * @param <T> 시험하는 것 — 블록이면 {@link StateView}, 엔티티면 종류 이름 글자
 */
public interface Cond<T> {
	boolean test(T value, TagView tags);

	/**
	 * 맞을 수 있는 이름(블록 · 엔티티 종류)의 집합 — null 이면 «모두 봐야 한다».
	 * 풀기가 모든 블록 상태를 돌지 않게 한다. 집합 밖은 반드시 거짓이어야 한다.
	 */
	Set<String> candidates(TagView tags);

	/** `"*"` — 모든 것. */
	@SuppressWarnings("unchecked")
	static <T> Cond<T> always() {
		return (Cond<T>) (Cond<?>) Always.INSTANCE;
	}

	final class Always implements Cond<Object> {
		static final Always INSTANCE = new Always();

		@Override
		public boolean test(Object value, TagView tags) {
			return true;
		}

		@Override
		public Set<String> candidates(TagView tags) {
			return null;
		}

		@Override
		public String toString() {
			return "*";
		}
	}

	record AnyOf<T>(List<Cond<T>> parts) implements Cond<T> {
		@Override
		public boolean test(T value, TagView tags) {
			for (Cond<T> c : this.parts) {
				if (c.test(value, tags)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public Set<String> candidates(TagView tags) {
			Set<String> out = new HashSet<>();
			for (Cond<T> c : this.parts) {
				Set<String> s = c.candidates(tags);
				if (s == null) {
					return null;
				}
				out.addAll(s);
			}
			return out;
		}
	}

	record AllOf<T>(List<Cond<T>> parts) implements Cond<T> {
		@Override
		public boolean test(T value, TagView tags) {
			for (Cond<T> c : this.parts) {
				if (!c.test(value, tags)) {
					return false;
				}
			}
			return true;
		}

		@Override
		public Set<String> candidates(TagView tags) {
			Set<String> out = null;
			for (Cond<T> c : this.parts) {
				Set<String> s = c.candidates(tags);
				if (s == null) {
					continue;
				}
				if (out == null) {
					out = new HashSet<>(s);
				} else {
					out.retainAll(s);
				}
			}
			return out;
		}
	}

	record Not<T>(Cond<T> inner) implements Cond<T> {
		@Override
		public boolean test(T value, TagView tags) {
			return !this.inner.test(value, tags);
		}

		@Override
		public Set<String> candidates(TagView tags) {
			return null;
		}
	}
}
