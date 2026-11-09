package kr.asmbl.block_custom_rules.rules;

import java.util.List;

/**
 * `groups` 의 묶음 정의 하나(D9). 같은 이름의 서버 태그 · 아래 정의를 대신한다(`replace: false` 면 덧붙인다).
 *
 * @param name   `ns:path`(앞의 `#` 뺀 것)
 * @param values 이름 또는 `#태그` — 이름공간을 붙여 둔 것
 */
public record GroupDef(Registry registry, String name, boolean replace, List<String> values, Origin origin, String path) {
}
