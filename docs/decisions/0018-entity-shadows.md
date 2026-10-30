# Architecture decision 0018: Entity shadows

Status: accepted. Implemented 2026-10-30.

No entity had a shadow. They now follow 1.8.9's `Render.doRenderShadowAndFire` and `Render.renderShadow`,
decompiled from the real client.

## Behavior

- **Option.** Video → Entity shadows (`world.entity_shadows`, on by default), vanilla's `entityShadows`. In
  1.8.9, unlike 1.7, shadows do not depend on fancy graphics, so there is no graphics check.
- **Who casts one.** Each renderer's `shadowSize`: players, zombies, skeletons, creepers and most mobs 0.5;
  spiders and withers 1.0; cows, pigs, sheep, mooshrooms, squid and cave spiders 0.7; horses 0.75; ocelots 0.4;
  chickens, rabbits, silverfish and endermites 0.3; bats and magma cubes 0.25; slimes 0.25 times their size;
  giants 3.0; villagers 0.5 (0.25 for a child). Children of any mob are halved again. Dropped items and experience
  orbs cast 0.15 at `shadowOpaque` 0.75. Boats, minecarts, falling blocks, primed TNT and ender crystals cast 0.5.
  Armor stands, projectiles, paintings and frames cast none. Invisible entities cast none.
- **Shape.** `misc/shadow.png` (a clamped black disc) is laid over the top face (+1/64) of every full-cube block
  with a render type under the entity, within its shadow size and from its feet down by that size. Slabs, glass,
  air and barriers take no shadow.
- **Fade.** Opacity is `(1 − d² / 256) × shadowOpaque`, with `d` from the viewer's feet (`viewerPos`), so shadows
  are gone at 16 blocks. Each block's quad then has alpha `(opacity − height / 2) × 0.5 × brightness`, where the
  brightness comes from the dimension's light table at `getLightFromNeighbors` of the cell above the block. That
  is sky light less `skylightSubtracted`, or block light if brighter, with the brightest neighbor for slabs and
  stairs. Cells at level 3 or darker get none.
- **Blending.** Vanilla's alpha test (> 0.1) and source-alpha blending over the encoded framebuffer. The draw runs
  in a gamma-space segment (`QuadRenderer.Mode.GAMMA_ALPHA`), because blending linear values leaves shadows
  visibly paler. It falls back to linear blending only on devices without a UNORM alias.

## Implementation

`EntityView.renderShadows` writes every shadow quad into the shared `QuadRenderer` and draws them in one call
before the entity models. It opens a gamma-space segment only when a frame has at least one shadow. Vanilla draws
each shadow right after its entity, but the picture is the same: a shadow writes no depth and lies on the ground
under the models. The pass allocates nothing per frame. World reads go through `EntityView.Lighting`, which
gained `state`.

## Verification

- `EntityShadowTest`: the size table, child halving, which blocks take a shadow, and the light rule.
- The same scene against real vanilla 1.8.9 on fresh flat worlds: items on the ground and on a block edge,
  cows, a calf, pigs, and a pig floating 1.2 blocks up. The shapes and positions match. Under the floating pig,
  the shadow darkens the grass to 0.837 of its value in both clients; the formula predicts 0.842.

## Deviations

- Shadows take no fog. The disc is black and fades out at 16 blocks, so this shows only in dense fog (water,
  lava, blindness), where vanilla tints the shadow toward the fog color.
- An entity outside the view frustum still lays its shadow. Vanilla culls the entity, and its shadow with it.

Frame time before and after was not measured.
