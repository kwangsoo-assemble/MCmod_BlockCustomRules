# MCmod_BlockCustomRules

> A Fabric client mod that sets per-block **render rules** and **sound rules** from resource packs (Minecraft 1.21.8).
> On top of texture draw mode, face culling and sway, it groups blocks into "sound categories" to change step, break and place sounds, and plays footsteps itself (it replaces Presence Footsteps).

> This mod was written with an AI coding tool (Claude Code) and verified in real use by the maintainer.
> It continues Block Render Rules 2.0 (the old repository name `MCmod_BlockRenderRules` moves to this repository).

## At a glance

| Item | Value |
|---|---|
| Version | `3.0.0` |
| Target | Minecraft 1.21.8 · Fabric · **client only** |
| Requires | Fabric Loader 0.19.3+ · Java 21 · Fabric API (command · lifecycle events · resource loader · rendering) |
| Works with | Sodium 0.7.x (same result when Sodium renders terrain) · Iris (shader pack waving and shadows are added on top of sway) · Mod Menu (settings screen) |
| Do not install together | `block_render_rules` · `cull_pass` · `sway` · `presencefootsteps` — they would do the same work twice, so the game refuses to start (`breaks`) |

## What it does

The mod ships no rules of its own. Rule files in **resource packs** and the **mod options** file decide the rules per block.
With no rules at all it behaves almost exactly like vanilla (footsteps sound like vanilla too).

### ① Texture draw mode · face culling · sway

| Field | Effect |
|---|---|
| `render` | `default` (vanilla / shader pack as is) · `solid` (fills transparent pixels) · `cutout` (transparent pixels cut out, no translucency, no mipmaps) · `translucent` (blends alpha as is) |
| `cull_pass` | When on, the block does not hide the faces of its neighbours — so you can see past translucent bricks · faces between two of the same block use the normal check |
| `sway` | 0 – 5 · when an entity enters the block cell the block leans away and slowly stands back up when it leaves · strength 1.0 ≈ 0.27 blocks per 1 block of height (about 15°) |

- The draw mode applies to vanilla and Sodium chunks, held and dropped block items, block displays, and moving (piston / falling) blocks · shape, collision and light are unchanged
- It wins over a shader pack's `layer.*` entries in `block.properties`
- Sway starts as soon as an entity's hitbox touches the block cell · several entities add their push · spectators and zero-width hitboxes do not push
- Two-tall blocks (tall grass, large fern, tall flowers, tall seagrass, pitcher plant) lean as one stem around the bottom of the lower half
- Sway bends vertices inside the terrain mesh — shader pack waving and shadows are added on top

### ② Sound categories

Giving a block a `sound_category` changes the names of that block's sounds.

```
minecraft:block.stone.step  →  <category namespace>:block.<category>.step
```

- Steps, breaking, placing, hitting, falling and block-specific sounds (bells, doors opening, …) all take the same path · `item.*` sounds are not touched
- If the new name does not exist in any pack, it tries the action parent (e.g. `run` → `step`) and the category parent in turn, and finally plays the **original vanilla sound**
- When a name is found, volume and pitch come from the pack definition · sounds played by this mod are capped at volume 3

### ③ Footstep engine

The mod plays footsteps for players and mobs itself (within 16 blocks of the camera · at most 50 · players first).

- One step every 0.95 blocks walked · separate rules for stairs and ladders · each left and right foot looks at its own block
- Actions: `step` · `run` · `wander` (stopping, turning) · `jump` · `land` · `land_light` (both feet) · `climb` · `stairs_up` · `stairs_down` · `crawl` · `stand` (now and then while standing) · `<action>_sneak` while sneaking
- Extra sounds: `through` (walking through a non-colliding block in the foot cell — grass and the like) · `enter` · `exit` (when the foot cell's sound category changes) · overlay blocks such as carpets and snow layers play both the top (`top`) and the block below (`bottom`)
- Thin supports such as bottom trapdoors and carpets are stepped on as themselves (even when placed over air)
- Footwear: the equip sound name of the feet item is used as a category — if a pack defines that category, boots get their own sound
- Mobs: the same mob-specific sounds as vanilla · four-legged (`quadruped`) rhythm · size scaling (`size_scaling`)
- Other players' step packets from the server are blocked so they do not double up with the sounds this mod plays

## Rule files

### Locations and layers

| Layer (bottom → top) | Location |
|---|---|
| Server tags | groups (`#tag`) only |
| Resource packs | `assets/<namespace>/block_custom_rules/*.json` — in pack order (the upper pack wins) · files inside a pack by name |
| Mod options | `config/block_custom_rules.json` |

- Reloaded when packs are toggled, on F3 + T, on server `/reload`, and on join
- Every field keeps its source (layer · pack · file · JSON path), so commands and the settings screen can show which line won

### Format

```json
{
  "format": 1,
  "groups": { "mypack:soft": { "values": ["minecraft:white_wool", "#minecraft:wool_carpets"] } },
  "rules": [
    { "target": "#mypack:soft", "sound_category": "mypack:cloth" },
    { "target": "minecraft:cobweb", "sway": 1.0, "sound_category": "minecraft:chain" },
    { "target": "minecraft:bricks", "render": "translucent", "cull_pass": true },
    { "target": { "blocks": "minecraft:wheat", "state": { "age": { "min": 4, "max": 7 } } }, "sway": 1.5 }
  ],
  "sound": {
    "defaults": { "volume": [0.9, 1.0], "pitch": [0.95, 1.05] },
    "categories": { "mypack:cloth": { "parent": "minecraft:wool" } },
    "actions": { "run_sneak": { "parent": "run" } },
    "layers": {
      "mypack:block.cloth.land": [
        { "sound": "mypack:cloth_soft", "volume": [0.6, 0.8] },
        { "include": "mypack:block.cloth.step", "delay_ms": 50, "chance": 0.5 }
      ]
    },
    "mix": [
      { "when": { "entity": "minecraft:player" }, "volume": { "*": 0.7, "through": 0.35 } },
      { "when": { "footwear": true }, "volume": { "through": 0 } },
      { "when": { "weather": { "raining": true }, "location": { "can_see_sky": true } }, "add": ["mypack:wet"] }
    ],
    "entities": [ { "target": "minecraft:cow", "locomotion": "quadruped", "size_scaling": true } ]
  }
}
```

### Order — only two kinds

| Kind | Fields | Order |
|---|---|---|
| List fields | `rules` · `mix` · `entities` | per value field, **the later line and the upper layer win** · a field you leave out means no rule · `"default"` resets |
| Named fields | `groups` · `categories` · `actions` · `layers` · `defaults` | for the same name, the upper layer / later file **replaces it entirely** · only `groups` can append with `"replace": false` |

### Block conditions (`target` · the slots in `mix.when`)

- Command-style string: `"minecraft:oak_stairs[half=top]"` · `"#minecraft:logs"` · `"*"`
- Range form: `{ "blocks": "minecraft:wheat", "state": { "age": { "min": 4, "max": 7 } } }`
- Combinations: `{ "any_of": [ … ] }` · `{ "all_of": [ … ] }` · `{ "not": … }`
- A group with `"registry": "biome"` becomes a biome group

### `rules` fields

| Field | Value |
|---|---|
| `render` | `default` · `solid` · `cutout` · `translucent` |
| `cull_pass` | `true` · `false` |
| `sway` | 0 – 5 |
| `sound_category` | category name (`ns:name`) |
| `step_as` | `"ground"` — treat the thin block in the foot cell as the ground, and also play the block below it as `bottom` |

### `sound` fields

| Field | Effect |
|---|---|
| `defaults` | volume / pitch ranges for `layers` lines that do not set them |
| `categories` | per category: `parent` (category to inherit missing names from) · `footsteps: false` (silent footsteps) · `actions` (action parents within that category) |
| `actions` | override global action parents · defaults: `run` · `wander` · `land` → `step`, `jump` → `wander`, `<action>_sneak` → silent |
| `layers` | one sound name as several lines — each line is `sound` (a name from the pack's `sounds.json`) or `include` (pull in another name) · `volume` · `pitch` (ranges) · `delay_ms` (up to 1000) · `chance` · `chance` on an `include` line rolls once for the whole group |
| `mix` | when the slots and situation in `when` match the step, multiply `volume` · `pitch` · `delay_ms` per channel, or play `<name>.<action>` as well with `add` |
| `entities` | per entity: `locomotion` (`biped` · `quadruped` · `none` = not handled by this mod) · `size_scaling` |

- "One of several" is chosen by weights in the pack's `sounds.json` (several files under one name)
- `mix.when` conditions: block per slot (`ground` · `top` · `bottom` · `through` · `enter` · `exit`) · `footwear` · `entity` · `location` (vanilla location predicate form — biome · dimension · light · fluid · `can_see_sky` · `precipitation`) · `weather` · `time` · `action` · `sneak` · an empty `when` always matches
- `mix` channels: `ground` · `top` · `bottom` · `through` · `enter` · `exit` · `footwear` · `chime` (amethyst chime) · `mob` (a mob's vanilla footstep) · `*` (every channel)
- Built-in default mix (packs override it): with footwear, ground × 0.5 and through / enter / exit off · overlay block top × 0.15 · below × 0.05
- ⚠ `structures` in `location` is rejected, because the client cannot know it

## Mod options `config/block_custom_rules.json`

```json
{
  "format": 1,
  "options": { "sway_blend_seconds": 0.25, "problem_toast": false },
  "rules": [
    { "target": "minecraft:petrified_oak_slab", "cull_pass": true }
  ]
}
```

- Same format as a pack rule file, and the top layer · only `options` is extra
- `sway_blend_seconds` — time for the lean to reach its target (0 – 5 s · 0 = instant)
- `problem_toast` — show a toast on join when rule files have problems (off by default)
- If the file cannot be read (a hand edit went wrong), it is not overwritten and the previous settings stay in use

## Settings screen (Mod Menu)

- Five tabs: rules · sound categories · layers · mix · entities
- Top: edit mod option lines — target · sway · draw mode · culling · sound category · ↑↓ order · invalid text turns red at once
- Bottom: browse the pack layers — the upper pack wins · `[+]` expands conditions, covered fields and reasons · single-block lines can be "copied to mod options"
- Also opens from the title screen (tag and biome conditions show as "not joined")

## Command `/bcr`

| Command | Effect |
|---|---|
| `/bcr check` | problems in rule files (file · JSON path · reason) |
| `/bcr rule <block>` | the final rule for that block and the source that won each field |
| `/bcr sound names <category>` | sound names per action for that category and what each resolves to |
| `/bcr sound template <category>` | a `sounds.json` template for that category — names not defined yet, filled with the sound you hear now |
| `/bcr debug sound [on [radius] [blocks\|all\|blocked] \| off]` | in-world sound debugger — three lines of text at each sound (what · which path · why) · radius 32 by default, 64 at most |
| `/bcr reload` | reload the mod options file (packs: F3 + T) |

Replies and debugger text only appear on the screen of the player who ran the command.

## Protecting server-side cue sounds — marker seed

A server can keep this mod from changing sounds it plays on purpose.
If the top 16 bits of the sound seed are `0x5EED`, this mod neither replaces nor blocks that sound.
The low 48 bits are still used to pick the file like vanilla, so the sound stays random as before.
Server sounds from the `master` · `ui` · `music` sources are not changed either.

## Moving over

- Remove the old `block_render_rules` · `cull_pass` · `sway` · `presencefootsteps` jars from the mods folder — if they are present, the game tells you to remove them and does not start
- The old `config/block_render_rules.json` is not read (nor deleted) — move the rules into a pack or the new mod options file

## Known limits

- With shaders off, lighting on leaning blocks looks a little off (it looks natural with shaders on)
- Columns of the same block (sugar cane, bamboo) and hanging vines lean cell by cell
- Only visible and audible on clients that have the mod — install it on other PCs for the same result there
- Other players' jumps are not sent by the server, so they make no footstep
- ⚠ The Sodium side relies on Sodium internals (`BlockOcclusionCache.shouldDrawSide` · `BlockRenderer.renderModel` · `processQuad`) — recheck after upgrading Sodium

## Files

| Folder | What |
|---|---|
| `rules/` · `config/` | reading rule files · conditions · layering · sources · mod options |
| `sound/` | sound categories · name lookup · `layers` · `mix` · server packet decisions |
| `step/` | footstep engine — step tracking · foot blocks · mobs · delay queue |
| `sway/` · `track/` | sway · entity tracking |
| `mixin/` | render · culling · sway · sound hooks |
| `screen/` · `command/` · `debug/` | settings screen · `/bcr` · sound debugger |
| `world/` · `game/` | clean-up on world change · game-side lookups |

## Build

```bash
# JDK 21
./gradlew build
# → build/libs/block_custom_rules-3.0.0.jar
```

## Credits · license

The idea of leaning blocks by entity position came from [SWAY](https://github.com/RazorPlay01/SWAY) (RazorPlay01).
The footstep ideas — actions, extra sounds and mixing — came from [Presence Footsteps](https://github.com/Sollace/Presence-Footsteps) (Sollace · MIT).
No code was copied from either mod; it was written from scratch.

**MIT** (`LICENSE`).

# 한국어

> 블록마다 **그리는 규칙**과 **소리 규칙**을 리소스팩으로 정하는 Fabric 클라이언트 모드다 (Minecraft 1.21.8).
> 텍스처 방식 · 컬링 패스 · 휘기에 더해, 블록을 «소리 종류» 로 묶어 걸음 · 부수기 · 놓기 소리를 바꾸고, 발소리를 직접 낸다(Presence Footsteps 를 대신한다).

> 이 모드는 AI 코딩 도구(Claude Code)로 작성했고 유지보수자가 실제 사용으로 확인했다.
> Block Render Rules 2.0 을 이어받은 모드다(옛 저장소 이름 `MCmod_BlockRenderRules` 는 이 저장소로 넘어온다).

## 한눈에

| 항목 | 값 |
|---|---|
| 버전 | `3.0.0` |
| 타깃 | Minecraft 1.21.8 · Fabric · **클라이언트 전용** |
| 의존 | Fabric Loader 0.19.3+ · Java 21 · Fabric API(명령 · 생명 주기 이벤트 · 리소스 로더 · 렌더링) |
| 함께 쓰면 | Sodium 0.7.x(지형을 Sodium 이 그릴 때도 같게) · Iris(셰이더팩 흔들림 · 그림자가 휘기 위에 더해진다) · Mod Menu(설정 화면) |
| 같이 깔면 안 되는 것 | `block_render_rules` · `cull_pass` · `sway` · `presencefootsteps` — 같은 일을 두 번 해서 게임이 켜지지 않게 막아 두었다(`breaks`) |

## 하는 일

모드에는 규칙이 들어 있지 않다. **리소스팩**의 규칙 파일과 **모드 옵션** 파일이 블록마다 규칙을 정한다.
규칙이 하나도 없으면 바닐라와 거의 같게 동작한다(발소리도 바닐라처럼 낸다).

### ① 텍스처 방식 · 컬링 패스 · 휘기

| 칸 | 하는 일 |
|---|---|
| `render` | `default`(바닐라 · 셰이더팩 그대로) · `solid`(투명 픽셀까지 메움) · `cutout`(투명은 뚫고 반투명 없음 · 밉맵 안 씀) · `translucent`(알파 그대로 섞음) |
| `cull_pass` | 켜면 그 블록이 옆 블록의 면을 가리지 않는다 — 반투명 벽돌 너머가 뚫려 보이지 않게 · 같은 블록끼리 맞닿은 면은 원래 판정 |
| `sway` | 0 ~ 5 · 엔티티가 블록 칸에 들어오면 반대쪽으로 기울고 떠나면 서서히 선다 · 세기 1.0 은 높이 1 당 약 0.27칸(약 15°) |

- 텍스처 방식은 바닐라 · Sodium 청크, 손 · 바닥의 블록 아이템, 블록 디스플레이, 피스톤 · 떨어지는 블록이 모두 따른다 · 모양 · 충돌 · 빛은 그대로다
- 셰이더팩이 `block.properties` 의 `layer.*` 로 계층을 정해도 이 모드 규칙이 이긴다
- 휘기는 히트박스가 블록 칸에 닿으면 들어간다 · 여러 엔티티는 미는 힘을 더한다 · 관전자 · 히트박스 폭 0 은 안 민다
- 두 칸 블록(긴 잔디 · 큰 고사리 · 큰 꽃 · 긴 해초 · 벌레잡이풀)은 아래 칸 바닥을 축으로 한 줄기처럼 기운다
- 휘기는 지형 메시 안에서 정점을 비튼다 — 셰이더팩의 흔들림 · 그림자가 그 위에 더해진다

### ② 소리 종류

블록에 `sound_category` 를 주면 그 블록의 소리 이름이 바뀐다.

```
minecraft:block.stone.step  →  <종류 이름공간>:block.<종류>.step
```

- 걸음 · 부수기 · 놓기 · 때리기 · 떨어짐과 블록 고유 소리(종소리 · 문 열기 등)가 모두 같은 길을 탄다 · `item.*` 소리는 안 건드린다
- 바뀐 이름이 리팩에 없으면 행동 부모(예 `run` → `step`) · 종류 부모를 차례로 찾고, 끝내 없으면 **원래 바닐라 소리**를 그대로 낸다
- 이름을 찾으면 음량 · 높낮이는 리팩 정의대로 낸다 · 이 모드가 내는 소리의 음량 상한은 3

### ③ 발소리 엔진

플레이어와 몹의 발소리를 이 모드가 직접 낸다(카메라 16칸 안 · 최대 50 · 플레이어 먼저).

- 간격 0.95칸(걸은 거리 기준) · 계단 · 사다리는 따로 · 왼발 · 오른발마다 발밑 블록을 따로 본다
- 행동: `step` · `run` · `wander`(멈춤 · 방향 꺾기) · `jump` · `land` · `land_light`(두 발) · `climb` · `stairs_up` · `stairs_down` · `crawl` · `stand`(멈춘 채 가끔) · 웅크리면 `<행동>_sneak`
- 덧소리: `through`(발 칸의 충돌 없는 블록을 지나감 — 풀숲 등) · `enter` · `exit`(발 칸의 소리 종류가 바뀔 때) · 카펫 · 눈 층 같은 겹침 블록은 위(`top`) · 아래(`bottom`) 둘 다
- 바닥 다락문 · 카펫처럼 얇은 받침도 그 블록을 밟는다(허공에 놓였어도)
- 신발: 발 칸 아이템의 장착 소리 이름을 종류로 쓴다 — 리팩이 그 종류를 정의하면 신발 소리가 난다
- 몹: 바닐라와 같은 몹 고유 소리 · 네 발(`quadruped`) 박자 · 크기 배율(`size_scaling`)
- 서버가 보내는 남의 걸음 패킷은 이 모드가 낸 소리와 겹치지 않게 막는다

## 규칙 파일

### 자리 · 층

| 층 (아래 → 위) | 자리 |
|---|---|
| 서버 태그 | 묶음(`#태그`)만 |
| 리소스팩 | `assets/<이름공간>/block_custom_rules/*.json` — 팩 차례대로(위 팩이 이김) · 팩 안은 파일 이름 순 |
| 모드 옵션 | `config/block_custom_rules.json` |

- 리팩을 켜고 끄거나 F3 + T · 서버 `/reload` · 접속 때 다시 읽는다
- 칸마다 출처(층 · 팩 · 파일 · JSON 경로)를 들고 있어 «어느 줄이 이겼나» 를 명령 · 설정 화면으로 볼 수 있다

### 꼴

```json
{
  "format": 1,
  "groups": { "mypack:soft": { "values": ["minecraft:white_wool", "#minecraft:wool_carpets"] } },
  "rules": [
    { "target": "#mypack:soft", "sound_category": "mypack:cloth" },
    { "target": "minecraft:cobweb", "sway": 1.0, "sound_category": "minecraft:chain" },
    { "target": "minecraft:bricks", "render": "translucent", "cull_pass": true },
    { "target": { "blocks": "minecraft:wheat", "state": { "age": { "min": 4, "max": 7 } } }, "sway": 1.5 }
  ],
  "sound": {
    "defaults": { "volume": [0.9, 1.0], "pitch": [0.95, 1.05] },
    "categories": { "mypack:cloth": { "parent": "minecraft:wool" } },
    "actions": { "run_sneak": { "parent": "run" } },
    "layers": {
      "mypack:block.cloth.land": [
        { "sound": "mypack:cloth_soft", "volume": [0.6, 0.8] },
        { "include": "mypack:block.cloth.step", "delay_ms": 50, "chance": 0.5 }
      ]
    },
    "mix": [
      { "when": { "entity": "minecraft:player" }, "volume": { "*": 0.7, "through": 0.35 } },
      { "when": { "footwear": true }, "volume": { "through": 0 } },
      { "when": { "weather": { "raining": true }, "location": { "can_see_sky": true } }, "add": ["mypack:wet"] }
    ],
    "entities": [ { "target": "minecraft:cow", "locomotion": "quadruped", "size_scaling": true } ]
  }
}
```

### 차례 — 두 꼴뿐

| 꼴 | 칸 | 차례 |
|---|---|---|
| 목록 칸 | `rules` · `mix` · `entities` | 값 칸마다 **나중 줄 · 위 층이 이긴다** · 안 적은 칸은 규칙 없음 · `"default"` 는 되돌림 |
| 이름표 칸 | `groups` · `categories` · `actions` · `layers` · `defaults` | 같은 이름이면 위 층 · 나중 파일이 **통째로 대신**한다 · `groups` 만 `"replace": false` 로 덧붙인다 |

### 블록 조건 (`target` · `mix.when` 의 자리)

- 명령 꼴 글자: `"minecraft:oak_stairs[half=top]"` · `"#minecraft:logs"` · `"*"`
- 범위 꼴: `{ "blocks": "minecraft:wheat", "state": { "age": { "min": 4, "max": 7 } } }`
- 조합: `{ "any_of": [ … ] }` · `{ "all_of": [ … ] }` · `{ "not": … }`
- `groups` 에 `"registry": "biome"` 를 주면 바이옴 묶음이 된다

### `rules` 칸

| 칸 | 값 |
|---|---|
| `render` | `default` · `solid` · `cutout` · `translucent` |
| `cull_pass` | `true` · `false` |
| `sway` | 0 ~ 5 |
| `sound_category` | 종류 이름(`ns:이름`) |
| `step_as` | `"ground"` — 발 칸의 얇은 블록을 땅으로 치고, 그 아래 블록을 `bottom` 으로 같이 낸다 |

### `sound` 칸

| 칸 | 하는 일 |
|---|---|
| `defaults` | `layers` 줄에 음량 · 높낮이를 안 적었을 때의 범위 |
| `categories` | 종류마다 `parent`(못 찾은 이름을 물려받을 종류) · `footsteps: false`(발소리 무음) · `actions`(그 종류 안 행동 부모) |
| `actions` | 전역 행동 부모 덮기 · 기본은 `run` · `wander` · `land` → `step`, `jump` → `wander`, `<행동>_sneak` → 무음 |
| `layers` | 소리 이름 하나를 여러 줄로 — 줄마다 `sound`(리팩 `sounds.json` 이름) 또는 `include`(다른 이름 끌어오기) · `volume` · `pitch`(범위) · `delay_ms`(최대 1000) · `chance` · `include` 줄의 `chance` 는 묶음 한 번 굴리기 |
| `mix` | 그 걸음에 겹친 자리 · 상황 조건(`when`)이 맞으면 채널마다 `volume` · `pitch` · `delay_ms` 를 곱하거나 · `add` 로 `<이름>.<행동>` 을 더 튼다 |
| `entities` | 엔티티마다 `locomotion`(`biped` · `quadruped` · `none` = 이 모드가 안 맡음) · `size_scaling` |

- «여럿 중 하나» 는 리팩 `sounds.json` 의 가중치로 고른다(이름 하나에 파일 여럿)
- `mix.when` 의 조건: 자리별 블록(`ground` · `top` · `bottom` · `through` · `enter` · `exit`) · `footwear` · `entity` · `location`(바닐라 위치 조건 꼴 — 바이옴 · 차원 · 빛 · 유체 · `can_see_sky` · `precipitation`) · `weather` · `time` · `action` · `sneak` · 빈 `when` 은 언제나
- `mix` 채널: `ground` · `top` · `bottom` · `through` · `enter` · `exit` · `footwear` · `chime`(자수정 종소리) · `mob`(몹의 바닐라 발소리) · `*`(모든 채널)
- 모드 안 기본 섞기(리팩이 덮는다): 신발이면 땅 × 0.5 · 지나감 · 입장 · 퇴장 끔 · 겹침 블록 위 × 0.15 · 아래 × 0.05
- ⚠ `location` 의 `structures` 는 클라이언트가 알 수 없어 거부한다

## 모드 옵션 `config/block_custom_rules.json`

```json
{
  "format": 1,
  "options": { "sway_blend_seconds": 0.25, "problem_toast": false },
  "rules": [
    { "target": "minecraft:petrified_oak_slab", "cull_pass": true }
  ]
}
```

- 리팩 규칙 파일과 같은 꼴이고 맨 위 층이다 · `options` 만 더 있다
- `sway_blend_seconds` — 기울기가 목표로 다가가는 시간(0 ~ 5초 · 0 이면 바로)
- `problem_toast` — 접속할 때 규칙 문제가 있으면 토스트로 알릴지(기본 끔)
- 파일을 못 읽으면(손으로 고치다 틀림) 덮어쓰지 않고 직전 설정을 그대로 쓴다

## 설정 화면 (Mod Menu)

- 탭 다섯: 규칙 · 소리 종류 · 겹 소리 · 섞기 · 엔티티
- 위: 모드 옵션 줄을 고친다 — 대상 · 휘기 · 텍스처 방식 · 컬링 · 소리 종류 · ↑↓ 차례 · 틀린 글자는 바로 빨강
- 아래: 리팩 층을 조회한다 — 위 팩이 이긴다 · `[+]` 로 조건 · 덮인 칸 · 이유를 펼친다 · 블록 하나짜리 줄은 «모드 옵션으로 복사»
- 타이틀 화면에서도 열린다(태그 · 바이옴 조건은 «접속 전» 으로 보인다)

## 명령 `/bcr`

| 명령 | 하는 일 |
|---|---|
| `/bcr check` | 규칙 파일 문제(파일 · JSON 경로 · 까닭) |
| `/bcr rule <블록>` | 그 블록의 최종 규칙과 칸마다 이긴 출처 |
| `/bcr sound names <종류>` | 그 종류의 행동별 소리 이름과 찾은 결과 |
| `/bcr sound template <종류>` | 그 종류의 리팩 `sounds.json` 틀 — 아직 없는 이름을 지금 들리는 소리로 채운 꼴 |
| `/bcr debug sound [on [반지름] [blocks\|all\|blocked] \| off]` | 월드 소리 디버거 — 소리 자리에 글자 3줄(무슨 소리 · 어느 길 · 왜) · 반지름 기본 32 · 최대 64 |
| `/bcr reload` | 모드 옵션 파일을 다시 읽기(리팩은 F3 + T) |

응답과 디버거 글자는 명령을 친 사람 화면에만 뜬다.

## 서버 연출 소리 지키기 — 표식 시드

서버가 일부러 트는 연출 소리를 이 모드가 바꾸지 않게 할 수 있다.
소리 시드의 위 16비트를 `0x5EED` 로 보내면, 이 모드는 그 소리를 갈아끼우지도 막지도 않는다.
아래 48비트는 바닐라처럼 파일 고르기에 쓰이므로 소리는 전처럼 무작위로 골라진다.
출처가 `master` · `ui` · `music` 인 서버 소리도 바꾸지 않는다.

## 넘어오기

- 옛 `block_render_rules` · `cull_pass` · `sway` · `presencefootsteps` jar 는 mods 폴더에서 빼야 한다 — 같이 있으면 게임이 «그 모드를 빼라» 고 알리고 켜지지 않는다
- 옛 `config/block_render_rules.json` 은 읽지 않는다(지우지도 않는다) — 규칙은 리팩이나 새 모드 옵션 파일로 옮긴다

## 알려진 한계

- 셰이더를 끈 상태에서는 기운 블록의 조명이 조금 어색하다(셰이더를 켜면 자연스럽다)
- 같은 블록이 여러 칸 쌓인 기둥(사탕수수 · 대나무)과 매달린 덩굴은 칸마다 따로 기운다
- 모드가 깔린 클라이언트에서만 보이고 들린다 — 다른 사람도 같게 하려면 그 PC 에도 깐다
- 남의 점프는 서버가 알려 주지 않아 발소리로 내지 않는다
- ⚠ Sodium 쪽은 Sodium 내부 이름(`BlockOcclusionCache.shouldDrawSide` · `BlockRenderer.renderModel` · `processQuad`)에 기댄다 — Sodium 판을 올리면 다시 본다

## 파일

| 폴더 | 무엇 |
|---|---|
| `rules/` · `config/` | 규칙 파일 읽기 · 조건 · 층 겹치기 · 출처 · 모드 옵션 |
| `sound/` | 소리 종류 · 이름 찾기 · `layers` · `mix` · 서버 패킷 판정 |
| `step/` | 발소리 엔진 — 걸음 추적 · 발밑 블록 · 몹 · 지연 줄 |
| `sway/` · `track/` | 휘기 · 엔티티 추적 |
| `mixin/` | 렌더 · 컬링 · 휘기 · 소리 자리 |
| `screen/` · `command/` · `debug/` | 설정 화면 · `/bcr` · 소리 디버거 |
| `world/` · `game/` | 월드 바뀜 정리 · 게임 쪽 조회 |

## 빌드

```bash
# JDK 21
./gradlew build
# → build/libs/block_custom_rules-3.0.0.jar
```

## 출처 · 라이선스

엔티티 위치로 블록을 기울이는 생각은 [SWAY](https://github.com/RazorPlay01/SWAY)(RazorPlay01)에서 얻었다.
발소리의 행동 · 덧소리 · 섞기 생각은 [Presence Footsteps](https://github.com/Sollace/Presence-Footsteps)(Sollace · MIT)에서 얻었다.
두 모드 모두 코드는 옮겨 오지 않고 새로 썼다.

**MIT** (`LICENSE`).
