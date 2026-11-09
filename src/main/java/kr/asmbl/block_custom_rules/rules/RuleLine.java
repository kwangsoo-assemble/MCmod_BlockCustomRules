package kr.asmbl.block_custom_rules.rules;

import com.google.gson.JsonElement;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * `rules` 줄 하나 — 대상 조건 + 적은 칸의 값({@link Field#RESET} 포함). 안 적은 칸은 map 에 없다.
 * `targetJson` 은 적은 그대로의 대상(설정 화면이 보여 주고 «모드 옵션으로 복사» 를 가른다).
 */
public record RuleLine(Cond<StateView> target, Map<Field, Object> values, Origin origin, String path, JsonElement targetJson) {
	public RuleLine {
		values = Collections.unmodifiableMap(new EnumMap<>(values));
	}
}
