package kr.asmbl.block_custom_rules.rules;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * `rules` 줄 하나 — 대상 조건 + 적은 칸의 값({@link Field#RESET} 포함). 안 적은 칸은 map 에 없다.
 */
public record RuleLine(Cond<StateView> target, Map<Field, Object> values, Origin origin, String path) {
	public RuleLine {
		values = Collections.unmodifiableMap(new EnumMap<>(values));
	}
}
