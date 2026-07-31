# GUI 2D Transform Migration

**Status:** the immediate call-chain repair shipped in 1.3.0-snapshot.2. Stored stack values and more complex operations still need a dataflow pass.

## Problem

Minecraft 26.x moved GUI transforms from the 3D `PoseStack` API to JOML's `Matrix3x2fStack`.

Old GUI code may call:

```java
guiGraphics.pose().pushPose();
guiGraphics.pose().translate(x, y, z);
```

The same `PoseStack` type still serves real 3D rendering, so a global class redirect would corrupt world and entity renderers.

## Shipped Repair

`Gui2DTransformMigration` handles immediate chains where `pose()` is followed directly by a supported operation. It rewrites only that short, provable instruction sequence and leaves other uses alone.

Frame computation must succeed or the original bytes are returned.

## Stored Values

This pattern needs more analysis:

```java
PoseStack stack = guiGraphics.pose();
stack.pushPose();
```

The proposed pass tracks values originating from `GuiGraphics.pose()` within one method. A value remains eligible only when every use is a supported 2D operation. Passing it elsewhere, storing it in a field, returning it, or mixing it with a 3D stack cancels the rewrite.

## Operation Mapping

| Old GUI operation | 26.x operation | Extra work |
|---|---|---|
| `pushPose()` | `pushMatrix()` | Pop fluent return |
| `popPose()` | `popMatrix()` | Pop fluent return |
| `translate(FFF)` | `translate(FF)` | Drop Z and pop return |
| `scale(FFF)` | `scale(FF)` | Drop Z and pop return |
| `translate(DDD)` | `translate(FF)` | Narrow X/Y, drop Z |
| `mulPose(Quaternionf)` | `rotate(float)` | Needs a verified 2D angle conversion |

`last()` and `last().pose()` feed a different text and vertex path and are not part of the first stored-value phase.

## Safety Rules

- Analyze one method at a time.
- Rewrite only values proven to come from GUI `pose()`.
- Cancel the entire source value when it escapes.
- Never rewrite a real 3D `PoseStack`.
- Recompute frames and keep the original class on failure.

## Verification

1. Unit-test direct, local, branch, merge, and escape cases.
2. Run `CheckClassAdapter` over a representative mod corpus.
3. Compare screenshots in game on Fabric, NeoForge, and Forge.
4. Confirm world and entity rendering remain unchanged.

Pixel output is the acceptance test. Structurally valid bytecode alone is not enough.
