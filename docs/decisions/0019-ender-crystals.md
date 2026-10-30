# Architecture decision 0019: Ender crystals

Status: accepted. Implemented 2026-10-30.

Spawn Object type 51 was tracked but never drawn, so the End pillars had no crystals. The behavior follows
1.8.9's `RenderEnderCrystal`, `ModelEnderCrystal`, `EntityEnderCrystal`, `RenderDragon.drawRechargeRay` and
`EntityDragon.updateDragonEnderCrystal`, decompiled from the real client.

## Behavior

- **Model.** The base plate, the glass cube drawn twice and the inner cube, sampling
  `textures/entity/endercrystal/endercrystal.png` against vanilla's 64x32 layout (the 1.8.9 image is 128x64). The
  crystal is drawn without the flip into model space, so y stays up. At twice size and half a block down, the base
  plate comes first. The glass cube is spun by the inner rotation (3 degrees a tick), lifted by the bob, and tipped
  60 degrees about the x = z diagonal. A second glass cube at 0.875 scale is tipped and spun again, and the inner
  cube is 0.875 of that, tipped and spun again.
- **Inner rotation.** Vanilla starts it at a random value below 100000 and adds one each tick. Alkaline takes the
  start from the entity id, so the phase holds steady without per-crystal state. The spin and bob phases therefore
  differ from vanilla's random ones.
- **Hitbox and picking.** The hitbox is 2x2 blocks. `EntityPicker` now picks crystals as well as living entities
  (`canBeCollidedWith`), so the crosshair can target and attack them. Other objects stay unpickable.
- **Healing beam.** While the dragon is alive, one tick in ten it takes the nearest crystal touching its box grown
  by 32 blocks as its healing crystal. It lets go of a crystal that leaves the tracker. The beam is an unculled,
  eight-sided cone from two blocks above the dragon to a block under the crystal's bob. It is 0.15 blocks across at
  the dragon and 0.75 at the crystal, and runs from black to the dragon's light with
  `textures/entity/endercrystal/endercrystal_beam.png` scrolling along it. That texture is loaded with repeat
  addressing.

## Implementation

- One cuboid model (`EntityModels.Kind.ENDER_CRYSTAL`) has three root parts. Each draw shows one part with its own
  matrix, which gives four instances per crystal. Pose parts cannot express the scale and the off-axis tilt.
- The beam needs per-vertex colors and a length that changes every frame, so it goes through `QuadRenderer`, which
  gains a quad with a color per corner, after the models flush.

## Known differences

- The beam draws without depth writes (the quad path's alpha mode) and without fog. The beam texture is fully
  opaque or fully clear, so only how it sorts against other translucent geometry and its look far into the End fog
  can differ.
- The crystal's shadow (vanilla `shadowSize` 0.5) is left to the entity-shadow work (decision 0018).
