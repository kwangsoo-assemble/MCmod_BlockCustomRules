# MCmod_CullPass

**[한국어](#한국어)** 설명은 아래에 있습니다.

> A Fabric client mod (Minecraft 1.21.8) that makes the blocks you pick **stop hiding the faces of the blocks next to them**.
> Example: a snow layer (`minecraft:snow`) made invisible with a resource pack no longer leaves a hole in the floor under it.

> This mod was written with an AI coding assistant (Claude Code) and checked by the maintainer in real use.

## At a glance

| Item | Value |
|---|---|
| Version | `1.0.0` |
| Target | Minecraft 1.21.8 · Fabric · **client only** |
| Requires | Fabric Loader 0.19.3+ · Java 21 (Fabric API is not needed) |
| Works with | Sodium 0.7.x (same result when Sodium draws the terrain) · Mod Menu (options screen) — runs without either |

## The problem

Whether a block face is drawn is decided **not by that block's model but by the neighbour's occlusion shape** (`getFaceOcclusionShape`).
So even if you point a block state at the `minecraft:block/air` model to hide it, the neighbour still thinks it is covered and skips the face.
Put a block with one full side — a snow layer, a closed trapdoor — on the floor, and the top face of the block below disappears like a hole.

## What it does

- When the neighbour is a listed block, the face check returns "draw" — at the head of vanilla `Block.shouldRenderFace` and of Sodium `BlockOcclusionCache.shouldDrawSide`
- Faces between two blocks of the same kind keep the normal check (glass against glass, etc.)
- The listed block's own shape, collision and light are unchanged — only more faces get drawn

| File | What |
|---|---|
| `mixin/BlockFaceMixin.java` | Vanilla face check (1.21.8 intermediary `method_9607`) |
| `mixin/SodiumOcclusionMixin.java` | Sodium face check · `@Pseudo`, so it quietly drops out without Sodium |
| `CullPassConfig.java` | Reads / writes the list (`config/cull_pass.json`) · rebuilds chunks when it changes |
| `CullPassScreen.java` · `CullPassModMenu.java` | Mod Menu options screen |

⚠ The Sodium side relies on Sodium internals — check the target name and arguments again when upgrading Sodium.

## Settings

In Mod Menu › Cull Pass, write block IDs separated by commas. "Add the block I was looking at" adds the block you were aiming at before opening the screen.
"Done" saves and redraws the chunks. Unknown IDs are shown in red and ignored.

You can also edit the file (read when the game starts):

```json
{
  "blocks": [
    "minecraft:snow",
    "minecraft:waxed_oxidized_copper_trapdoor"
  ]
}
```

The first default is the two above. Without a namespace (`snow`) the ID is read as `minecraft:`.

⚠ Only clients with the mod see it — install it on other players' PCs too if they should see the same thing.

## Build

```bash
# JDK 21
./gradlew build
# → build/libs/cull_pass-1.0.0.jar
```

## License

**MIT** (`LICENSE`).

---

# 한국어

> 옵션에서 고른 블록이 **옆 블록의 면을 가리지 않게** 하는 Fabric 클라이언트 모드다 (Minecraft 1.21.8).
> 예: 리소스팩으로 투명하게 만든 눈(`minecraft:snow`)을 바닥에 깔아도 그 아래 블록 윗면이 뚫려 보이지 않는다.

> 이 모드는 AI 코딩 도구(Claude Code)로 작성했고 유지보수자가 실제 사용으로 확인했다.

## 한눈에

| 항목 | 값 |
|---|---|
| 버전 | `1.0.0` |
| 타깃 | Minecraft 1.21.8 · Fabric · **클라이언트 전용** |
| 의존 | Fabric Loader 0.19.3+ · Java 21 (Fabric API 는 필요 없다) |
| 함께 쓰면 | Sodium 0.7.x(지형을 Sodium 이 그릴 때도 같게) · Mod Menu(옵션 화면) — 둘 다 없어도 돈다 |

## 무엇이 문제였나

블록 면을 그릴지는 **그 블록의 모델이 아니라 옆 블록의 가림 모양**(`getFaceOcclusionShape`)이 정한다.
그래서 블록 상태를 `minecraft:block/air` 모델로 바꿔 안 보이게 해도, 옆 블록은 여전히 «가려졌다» 고 보고 면을 안 그린다.
눈 한 층 · 닫힌 다락문처럼 한쪽 면이 꽉 찬 블록을 바닥에 놓으면 아래 블록 윗면이 빠져 구멍처럼 보인다.

## 하는 일

- 목록 블록이 이웃이면 면 판정을 «그린다» 로 바꾼다 — 바닐라 `Block.shouldRenderFace` · Sodium `BlockOcclusionCache.shouldDrawSide` 머리에서
- 같은 블록끼리 맞닿은 면은 원래 판정 그대로다(유리끼리 등)
- 목록 블록 자신의 모양 · 충돌 · 빛은 안 바꾼다 — 그리는 면만 늘어난다

| 파일 | 무엇 |
|---|---|
| `mixin/BlockFaceMixin.java` | 바닐라 면 판정(1.21.8 intermediary `method_9607`) |
| `mixin/SodiumOcclusionMixin.java` | Sodium 면 판정 · `@Pseudo` 라 Sodium 이 없으면 조용히 빠진다 |
| `CullPassConfig.java` | 목록(`config/cull_pass.json`) 읽기 · 쓰기 · 바꾸면 청크 다시 짓기 |
| `CullPassScreen.java` · `CullPassModMenu.java` | Mod Menu 옵션 화면 |

⚠ Sodium 쪽은 Sodium 내부 이름에 기댄다 — Sodium 판을 올리면 대상 이름 · 인자를 다시 본다.

## 설정

Mod Menu › Cull Pass 에서 블록 ID 를 쉼표로 적는다. «바라보던 블록 추가» 는 화면을 열기 전 조준하던 블록을 넣는다.
«완료» 를 누르면 저장하고 청크를 다시 그린다. 없는 ID 는 빨갛게 보이고 무시된다.

파일로 고쳐도 된다(게임을 다시 켜면 읽는다):

```json
{
  "blocks": [
    "minecraft:snow",
    "minecraft:waxed_oxidized_copper_trapdoor"
  ]
}
```

처음 기본값은 위 둘이다. 이름공간을 빼면(`snow`) `minecraft:` 로 친다.

⚠ 모드가 깔린 클라이언트에서만 보인다 — 다른 사람 화면도 같게 하려면 그 PC 에도 깐다.

## 빌드

```bash
# JDK 21
./gradlew build
# → build/libs/cull_pass-1.0.0.jar
```

## 라이선스

**MIT** (`LICENSE`).
