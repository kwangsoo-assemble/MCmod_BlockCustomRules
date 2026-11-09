package kr.asmbl.block_custom_rules.game;

import java.util.ArrayList;
import java.util.List;
import kr.asmbl.block_custom_rules.rules.StateView;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 블록 상태를 규칙 핵심에 보이는 창. 같은 BlockState 면 같다(정체성 비교 — 상태 객체는 게임 안에 하나뿐).
 */
public final class McState implements StateView {
	public final BlockState state;
	private final String block;

	McState(BlockState state, String block) {
		this.state = state;
		this.block = block;
	}

	@Override
	public String block() {
		return this.block;
	}

	@Override
	public String get(String property) {
		Property<?> p = this.state.getBlock().getStateDefinition().getProperty(property);
		return p == null ? null : name(p, this.state);
	}

	@Override
	public List<String> values(String property) {
		Property<?> p = this.state.getBlock().getStateDefinition().getProperty(property);
		return p == null ? null : names(p);
	}

	private static <T extends Comparable<T>> String name(Property<T> p, BlockState s) {
		return p.getName(s.getValue(p));
	}

	private static <T extends Comparable<T>> List<String> names(Property<T> p) {
		List<T> vs = p.getPossibleValues();
		List<String> out = new ArrayList<>(vs.size());
		for (T v : vs) {
			out.add(p.getName(v));
		}
		return out;
	}

	/** `minecraft:wheat[age=3]` 꼴 — 명령 · 디버거가 쓴다. */
	public static String describe(BlockState s, String block) {
		if (s.getProperties().isEmpty()) {
			return block;
		}
		StringBuilder sb = new StringBuilder(block).append('[');
		boolean first = true;
		for (Property<?> p : s.getProperties()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(p.getName()).append('=').append(name(p, s));
		}
		return sb.append(']').toString();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof McState m && m.state == this.state;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this.state);
	}

	@Override
	public String toString() {
		return describe(this.state, this.block);
	}
}
