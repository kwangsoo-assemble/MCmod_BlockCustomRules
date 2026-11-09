# MCmod_BlockRenderRules

**[한국어](#한국어)** 설명은 아래에 있습니다.

> A Fabric client mod (Minecraft 1.21.8) that sets **render rules per block**:
> how a block's texture is drawn (solid · cutout · translucent), whether it stops hiding its neighbours' faces (cull pass), and how much it sways when entities brush through it.

> This mod was written with an AI coding assistant (Claude Code) and checked by the maintainer in real use.
> It succeeds Cull Pass 1.0 (the old repository name `MCmod_CullPass` redirects here).

## At a glance

| Item | Value |
|---|---|
| Version | `2.0.0` |
| Target | Minecraft 1.21.8 · Fabric · **client only** |
| Requires | Fabric Loader 0.19.3+ · Java 21 · Fabric API (for the `/brr` client command) |
| Works with | Sodium 0.7.x (same result when Sodium builds the terrain) · Iris (shader-pack waving and shadows stack on top of the sway) · Mod Menu (options screen) |
| Do not install together | the old `cull_pass` / `sway` jars — they would do the same work twice, so the game refuses to start (`breaks`) |

## What it does

### 1. Texture mode

Vanilla fills in the transparent and semi-transparent pixels of every block that is not grass, a flower, stained glass and the like,
so a resource pack alone cannot make "translucent bricks" or "a gold block with holes". This mod lets you choose the mode per block.

| Mode | Effect |
|---|---|
| `default` | whatever vanilla (and the shader pack) decides |
| `solid` | fills in transparent pixels |
| `cutout` | transparent pixels are cut out, no translucency (grass / flower style) · no mipmaps, so holes stay sharp at a distance |
| `translucent` | alpha is blended (stained-glass style) |

- Vanilla and Sodium chunks, block items in hand and on the ground, block displays, and blocks moved by pistons or falling all follow the rule
- The block's shape, collision, light and server-side block do not change — **only how it looks**
- If a shader pack assigns a layer to the same block via `layer.*` in `block.properties`, this mod's rule wins

### 2. Cull pass

Whether a face is drawn is decided by **the neighbour's occlusion shape**, not by the block's model or layer.
So even if bricks are drawn translucent, the stone next to them still drops the face toward the bricks and you see a hole through them.
A block with cull pass on stops hiding its neighbours' faces. Faces between two blocks of the same kind keep the original rule (the inside faces of a translucent brick wall are not drawn).

### 3. Sway

When an entity (spectators excepted) enters the block's cell, the block leans away from it and slowly straightens after it leaves.

- **Hitbox based** — it counts as soon as a corner of the hitbox touches the block, even if the hitbox centre is outside. The overlap rectangle seen from above gives the direction (away from where it came in) and the strength (overlap area)
- **Several entities are summed as vectors** — pushing from opposite sides leaves the block almost upright
- **Blending** — the tilt approaches its target over time (`sway_blend_seconds`, default 0.25 s). Near the block centre the strength shrinks instead of the direction flipping, so crossing the centre does not snap it over
- **Two-block plants** (tall grass, large fern, tall flowers such as sunflowers, tall seagrass, pitcher plant) lean as one stalk around the bottom of the lower half
- The bottom face stays put and the top is pushed the most. Strength 1.0 is about 0.27 blocks per block of height (about 15°)
- Vertices are bent inside the terrain mesh — so **shader-pack waving and shadows still apply on top of the sway**. While a tilt changes, that chunk section is rebuilt

## Settings

### Config file `config/block_render_rules.json`

```json
{
  "sway_blend_seconds": 0.25,
  "blocks": {
    "minecraft:petrified_oak_slab": { "render": "default", "cull_pass": true, "sway": 0.0 },
    "minecraft:cobweb": { "render": "default", "cull_pass": false, "sway": 1.0 },
    "minecraft:heavy_weighted_pressure_plate": { "render": "default", "cull_pass": false, "sway": 1.5 },
    "minecraft:bricks": { "render": "translucent", "cull_pass": true, "sway": 0.0 }
  }
}
```

- The first three entries are the defaults (the bricks entry is an example). Missing fields mean `default` · `false` · `0`
- `sway` is 0 to 5 (0 = no sway) · `sway_blend_seconds` is 0 to 5 (0 = follow immediately)
- An ID without a namespace (`snow`) means `minecraft:` · unknown IDs are ignored
- If the file cannot be read (a typo while editing by hand), it is not overwritten and the previous settings stay in use
- ⚠ The defaults are used only when there is no config file — an existing file is left alone

### Options screen (Mod Menu)

One row per block — mode button (click to cycle) · cull pass on / off · sway strength (− / +, steps of 0.5) · delete.
Add a block by typing its ID or with "Looked-at block" for the block you were aiming at. The sway blend time is also set here.
Switching a block to cutout or translucent for the first time turns cull pass on (you can turn it off). "Done" saves and redraws the chunks.

### Client command `/brr`

| Command | Effect |
|---|---|
| `/brr list` | list the rules |
| `/brr set <block> render <default\|solid\|cutout\|translucent>` | texture mode |
| `/brr set <block> cull <on\|off>` | cull pass |
| `/brr set <block> sway <0 to 5>` | sway strength |
| `/brr remove <block>` | remove from the list |
| `/brr blend <seconds>` | sway blend time |
| `/brr reload` | re-read the file |

Replies appear only in the chat of the player who ran the command.

## Moving from the old mods

Once, when there is no config file, it reads the old `config/cull_pass.json` (cull pass list) and `config/sway.json` (block → strength) and carries them over. The old files are not deleted.
Remove the old `cull_pass-*.jar` and `sway-*.jar` from the mods folder — if they are still there, the game tells you to remove them and does not start.

## Known limitations

- With shaders off, the lighting of a tilted block looks a little off (it looks natural with shaders on)
- Stacks of the same block (sugar cane, bamboo) and hanging vines lean cell by cell
- Only visible on clients that have the mod — install it on the other PCs too if their view should match
- ⚠ The Sodium side relies on Sodium internals (`BlockOcclusionCache.shouldDrawSide` · `BlockRenderer.renderModel` · `processQuad`) — check them again after a Sodium update

## Files

| File | What |
|---|---|
| `config/BrrConfig.java` · `Rule.java` · `Render.java` · `Migration.java` | reading / writing the config · carrying over old settings |
| `mixin/ChunkRenderTypeMixin.java` | texture mode — wraps `ItemBlockRenderTypes.getChunkRenderType` and `getMovingBlockRenderType` |
| `mixin/BlockFaceMixin.java` · `SodiumOcclusionMixin.java` | cull pass — vanilla and Sodium face checks |
| `sway/SwayMath.java` · `SwayEngine.java` | sway hit test · vector sum · blending |
| `mixin/SectionCompilerMixin.java` · `SodiumBlockRendererMixin.java` | sway — bending vertices while vanilla and Sodium build chunks |
| `screen/BrrScreen.java` · `command/BrrCommand.java` | options screen · `/brr` |

## Build

```bash
# JDK 21
./gradlew build
# → build/libs/block_render_rules-2.0.0.jar
```

## Credits · License

The idea of tilting blocks by entity position comes from [SWAY](https://github.com/RazorPlay01/SWAY) by RazorPlay01. No code was copied; it was written from scratch.

**MIT** (`LICENSE`).

---

# 한국어

> 블록마다 **그리는 규칙**을 정하는 Fabric 클라이언트 모드다 (Minecraft 1.21.8).
> 텍스처를 어떻게 그릴지(불투명 · 투명 허용 · 반투명 허용), 옆 블록의 면을 가리지 않을지(컬링 패스), 엔티티가 지나갈 때 얼마나 휠지(휘기)를 블록 하나하나에 정한다.

> 이 모드는 AI 코딩 도구(Claude Code)로 작성했고 유지보수자가 실제 사용으로 확인했다.
> Cull Pass 1.0 을 이어받은 모드다(옛 저장소 이름 `MCmod_CullPass` 는 이 저장소로 넘어온다).

## 한눈에

| 항목 | 값 |
|---|---|
| 버전 | `2.0.0` |
| 타깃 | Minecraft 1.21.8 · Fabric · **클라이언트 전용** |
| 의존 | Fabric Loader 0.19.3+ · Java 21 · Fabric API(클라 명령 `/brr` 부분) |
| 함께 쓰면 | Sodium 0.7.x(지형을 Sodium 이 그릴 때도 같게) · Iris(셰이더팩 흔들림 · 그림자가 휘기 위에 더해진다) · Mod Menu(옵션 화면) |
| 같이 깔면 안 되는 것 | 옛 `cull_pass` · `sway` jar — 같은 일을 두 번 해서 게임이 켜지지 않게 막아 두었다(`breaks`) |

## 하는 일

### ① 텍스처 방식

바닐라는 잔디 · 꽃 · 색유리 같은 블록이 아니면 텍스처의 투명 · 반투명 픽셀을 메워 그린다.
그래서 리소스팩만으로는 «반투명한 벽돌» · «구멍 뚫린 금 블록» 을 만들 수 없다. 이 모드는 블록마다 그리는 방식을 고르게 한다.

| 방식 | 하는 일 |
|---|---|
| `default` | 바닐라(와 셰이더팩)가 정한 그대로 |
| `solid` | 투명 픽셀까지 메워 그린다 |
| `cutout` | 투명 픽셀은 뚫고 반투명은 없다(잔디 · 꽃 꼴) · 밉맵을 쓰지 않아 멀리서도 구멍이 또렷하다 |
| `translucent` | 알파를 그대로 섞는다(색유리 꼴) |

- 바닐라 · Sodium 청크, 손 · 바닥의 블록 아이템, 블록 디스플레이, 피스톤으로 움직이는 블록 · 떨어지는 블록이 모두 따른다
- 블록의 모양 · 충돌 · 빛 · 서버 쪽 블록은 바뀌지 않는다 — **보이는 방식만** 바뀐다
- 셰이더팩이 `block.properties` 의 `layer.*` 로 같은 블록의 계층을 정해도 이 모드 규칙이 이긴다

### ② 컬링 패스

블록 면을 그릴지는 그 블록의 모델 · 계층이 아니라 **옆 블록의 가림 모양**이 정한다.
그래서 벽돌을 반투명으로 그려도 옆 돌은 벽돌 쪽 면을 지우고, 벽돌 너머가 뚫려 보인다.
컬링 패스를 켠 블록은 옆 블록의 면을 가리지 않는다. 같은 블록끼리 맞닿은 면은 원래 판정 그대로다(반투명 벽돌 벽 안쪽 면은 안 그린다).

### ③ 휘기

엔티티(관전자는 빼고)가 블록 칸에 들어오면 블록이 엔티티 반대쪽으로 기울고, 떠나면 서서히 선다.

- **히트박스로 판정한다** — 히트박스 중심이 블록 밖이어도 모서리만 닿으면 들어간다. 위에서 본 «겹친 사각형» 으로 방향(들어온 쪽의 반대)과 세기(겹친 넓이)를 낸다
- **여러 엔티티는 벡터 합** — 엔티티마다 미는 벡터를 더한다. 양쪽에서 같이 밀면 거의 똑바로 선다
- **블렌딩** — 기울기는 목표 쪽으로 시간을 두고 다가간다(`sway_blend_seconds` · 기본 0.25초). 블록 중심 근처에서는 방향 대신 세기가 줄어, 중심을 지나도 확 뒤집히지 않는다
- **두 칸 블록**(긴 잔디 · 큰 고사리 · 해바라기 같은 큰 꽃 · 긴 해초 · 벌레잡이풀)은 아래 칸 바닥을 축으로 한 줄기처럼 기운다
- 아래 면은 고정되고 위로 갈수록 밀린다. 세기 1.0 은 높이 1 당 약 0.27칸(약 15°)
- 지형 메시 안에서 정점을 비튼다 — 그래서 **셰이더팩의 흔들림 · 그림자가 휘기 위에 그대로 더해진다**. 기울기가 바뀌는 동안 그 청크 구역을 다시 짓는다

## 설정

### 설정 파일 `config/block_render_rules.json`

```json
{
  "sway_blend_seconds": 0.25,
  "blocks": {
    "minecraft:petrified_oak_slab": { "render": "default", "cull_pass": true, "sway": 0.0 },
    "minecraft:cobweb": { "render": "default", "cull_pass": false, "sway": 1.0 },
    "minecraft:heavy_weighted_pressure_plate": { "render": "default", "cull_pass": false, "sway": 1.5 },
    "minecraft:bricks": { "render": "translucent", "cull_pass": true, "sway": 0.0 }
  }
}
```

- 위 앞의 세 줄이 처음 기본값이다(마지막 벽돌 줄은 보기). 빠진 칸은 `default` · `false` · `0`
- `sway` 는 0 ~ 5(0 이면 안 휜다) · `sway_blend_seconds` 는 0 ~ 5(0 이면 바로 따라간다)
- 이름공간을 빼면(`snow`) `minecraft:` 로 친다 · 없는 ID 는 무시한다
- 파일을 못 읽으면(손으로 고치다 틀림) 덮어쓰지 않고 직전 설정을 그대로 쓴다
- ⚠ 기본값은 설정 파일이 없을 때만 쓰인다 — 이미 있는 파일은 그대로다

### 옵션 화면 (Mod Menu)

블록마다 한 줄 — 방식 버튼(눌러서 돌린다) · 컬링 패스 켬 / 끔 · 휘기 세기(− / +, 0.5 칸) · 지우기.
아래에서 블록 ID 를 적어 넣거나 «바라보는 블록» 으로 조준하던 블록을 넣는다. 휘기 블렌딩 초도 여기서 바꾼다.
방식을 투명 · 반투명으로 처음 바꾸면 컬링 패스가 저절로 켜진다(끌 수 있다). «완료» 를 누르면 저장하고 청크를 다시 그린다.

### 클라 명령 `/brr`

| 명령 | 하는 일 |
|---|---|
| `/brr list` | 규칙 목록 |
| `/brr set <블록> render <default\|solid\|cutout\|translucent>` | 텍스처 방식 |
| `/brr set <블록> cull <on\|off>` | 컬링 패스 |
| `/brr set <블록> sway <0 ~ 5>` | 휘기 세기 |
| `/brr remove <블록>` | 목록에서 빼기 |
| `/brr blend <초>` | 휘기 블렌딩 초 |
| `/brr reload` | 파일을 다시 읽기 |

응답은 명령을 친 사람 채팅에만 뜬다.

## 옛 모드에서 넘어오기

설정 파일이 없을 때 한 번, 옛 `config/cull_pass.json`(컬링 패스 목록)과 `config/sway.json`(블록 → 세기)을 읽어 옮긴다. 옛 파일은 지우지 않는다.
옛 `cull_pass-*.jar` · `sway-*.jar` 는 mods 폴더에서 빼야 한다 — 같이 있으면 게임이 «그 모드를 빼라» 고 알리고 켜지지 않는다.

## 알려진 한계

- 셰이더를 끈 상태에서는 기운 블록의 조명이 조금 어색하다(셰이더를 켜면 자연스럽다)
- 같은 블록이 여러 칸 쌓인 기둥(사탕수수 · 대나무)과 매달린 덩굴은 칸마다 따로 기운다
- 모드가 깔린 클라이언트에서만 보인다 — 다른 사람 화면도 같게 하려면 그 PC 에도 깐다
- ⚠ Sodium 쪽은 Sodium 내부 이름(`BlockOcclusionCache.shouldDrawSide` · `BlockRenderer.renderModel` · `processQuad`)에 기댄다 — Sodium 판을 올리면 다시 본다

## 파일

| 파일 | 무엇 |
|---|---|
| `config/BrrConfig.java` · `Rule.java` · `Render.java` · `Migration.java` | 설정 읽기 · 쓰기 · 옛 설정 옮기기 |
| `mixin/ChunkRenderTypeMixin.java` | 텍스처 방식 — `ItemBlockRenderTypes.getChunkRenderType` · `getMovingBlockRenderType` 을 감싼다 |
| `mixin/BlockFaceMixin.java` · `SodiumOcclusionMixin.java` | 컬링 패스 — 바닐라 · Sodium 면 판정 |
| `sway/SwayMath.java` · `SwayEngine.java` | 휘기 판정 · 벡터 합 · 블렌딩 |
| `mixin/SectionCompilerMixin.java` · `SodiumBlockRendererMixin.java` | 휘기 — 바닐라 · Sodium 청크를 지을 때 정점 비틀기 |
| `screen/BrrScreen.java` · `command/BrrCommand.java` | 옵션 화면 · `/brr` |

## 빌드

```bash
# JDK 21
./gradlew build
# → build/libs/block_render_rules-2.0.0.jar
```

## 출처 · 라이선스

엔티티 위치로 블록을 기울이는 생각은 [SWAY](https://github.com/RazorPlay01/SWAY)(RazorPlay01)에서 얻었다. 코드는 옮겨 오지 않고 새로 썼다.

**MIT** (`LICENSE`).
