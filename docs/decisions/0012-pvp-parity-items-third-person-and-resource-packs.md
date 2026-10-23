# Architecture decision 0012: PvP parity: physics, potions, projectiles, item models, third person and resource packs

Status: accepted. Implemented 2026-10-18. Chests, connected textures, custom sky and custom colors added 2026-10-23.
Builds on decisions 0007 and 0011.

Checked against Mojang's 1.8.9 server (offline, superflat) with `--input-script` and `--screenshot`. The reference
is the **decompiled vanilla 1.8.9 client** (the MCP-mapped jar through Vineflower), not KotlinizedMCP alone: the
KotlinizedMCP copy was found to differ from vanilla in `EntityPlayerSP`'s sprint rules, so every rule below was read
from the decompiled classes.

## Movement follows vanilla's tick order, bugs included

`LocalPlayer` was rewritten to EntityPlayerSP / EntityLivingBase / Entity order: water current first, then sprint
and sneak input, then the 0.005 motion cutoff, the jump (10-tick jump delay), and moveEntityWithHeading. Vanilla
quirks are kept on purpose, because PvP players feel them:

- Air control (`jumpMovementFactor`) is set after the move, so it follows the sprint state one tick late (the
  "sprint reset" window after a hit).
- Double-tap sprint reads the previous tick's forward input; the sprint key reads the current one (≥ 0.8).
- Step-up tries two candidates and keeps the original move on a tie.
- pushOutOfBlocks keeps its south-side bias.
- The jump peaks at 1.24919 blocks (1.8's 0.005 cutoff; 1.9+ reaches 1.2522).

Ladders and vines, cobwebs, soul sand, slime bounces, landing and the water current (0.014) are ported. Speed,
Slowness, Jump Boost, Haste, Mining Fatigue, Blindness and Depth Strider apply. Attack knockback is Knockback
enchantment + sprint, and the attack slowdown applies only to hits on players. Swing length follows Haste and
Mining Fatigue. Critical hits follow vanilla's conditions. `VanillaPhysicsTest` pins speeds (4.317, 5.612 and
1.295 m/s), the jump peak, both sprint paths, the air-control lag, ladders, slime, webs, water, effects and
push-out.

Toggle Sprint & Sneak is a HUD module: a press latches sprint or sneak, and its readout says how you move. It goes
through the same input path, so vanilla's rules (no sprint while using an item, blind, hungry or colliding) still
apply.

## Potions and projectiles

- Speed and Slowness drive the FOV as vanilla computes it, and the bow draw narrows it.
- Nausea warps the view.
- Blindness has its own fog, and Night Vision brightens the lightmap and fog.
- Effect swirls show on every entity, including you.
- The heart row takes Poison, Wither, Absorption, Regeneration and Hunger variants.

Fire Resistance needs nothing client-side: the server stops fire damage, and the HUD lists the effect.

Thrown snowballs, eggs, pearls, potions, experience bottles and arrows fly between server updates with vanilla's
gravity and drag (`ObjectPhysics`). Arrows stick and shake on impact. Snowballs, eggs and pearls give their impact
particles, and critical arrows leave a trail.

## One item pipeline for hands, entities, projectiles and the HUD

`ItemCatalog` turns a stack into a model name using RenderItem's full variant table, which is generated from the
decompiled `RenderItem.registerItems`: wool colors, leaves, logs, slabs and the rest. It also handles the bow's
pulling frames, a cast fishing rod, and a resource pack's custom item texture. `ItemMeshes` builds each model once:

- Sprite items become one extruded mesh per layer, so potion liquids, dyed leather and spawn eggs tint per layer.
- Block items become their block model's quads, packed into a small atlas and grouped by tint (grass, leaves).

The same mesh draws in three places:

- **In hand:** block items doubled, as ItemRenderer does.
- **On other players** (LayerHeldItem).
- **In the HUD:** `ItemIcons` rasterizes vanilla's isometric GUI view (the two item lights, 40% ambient) on the CPU
  once per item and tint, and `HudSprites` serves it.

Block items now look like blocks everywhere. Uploads happen in `beforeFrame`; drawing never allocates GPU memory.

Chests are drawn as 3D models, in the world, in hand and in the HUD (see [Chests](#chests)). Ender chests, skulls
and banners are not drawn yet.

## First-person animations, with room to customize

`FirstPersonItem.transform` composes ItemRenderer's eat/drink, sword block and bow draw with the swing. Vanilla
1.8 freezes the swing while an item is in use. The Animations module's `Style` keeps it in two cases:

- **Block-hit:** the 1.7 look, with the swing visible while blocking.
- **Swing while using:** the same while eating, drinking or drawing a bow.

So blocking never overrides the swing. The module also offers item offsets and scale. Overlaying the projected
vanilla geometry on screenshots matched the idle and blocking diamond sword texel for texel; a pack's model may
change the size (Xe Lite uses scale 1.6).

## Third person

The perspective key cycles first person, behind and in front, as `EntityRenderer.orientCamera` does:

- The camera sits 4 blocks out and is pulled in by eight jittered rays. Vanilla's x-axis jitter bug is kept.
- The local player is drawn as an entity that copies the player after each tick, so its walking, swinging,
  blocking, bow aim, riding pose and armor match what others see.
- RenderPlayer's rule that only *other* sneaking players are lowered is kept.
- The hand is not drawn in third person.

## Resource packs

`ResourcePacks` scans Alkaline's `~/.alkaline/legacy/resourcepacks` and Minecraft's own `resourcepacks` folder for
zips and folders with a root `pack.mcmeta`, as vanilla does. Packs nested in a subfolder are ignored, as in vanilla.
The selected packs layer over the vanilla jar, the first one winning. HD textures work: block layers scale to the
largest texture.

The selection is the `resource_packs.selected` setting (pack ids, one per line). It is a compatible schema
addition.

**Apply** rebuilds everything drawn from assets, without leaving the server:

- The palette and fonts load off the render thread.
- The world, entity, cloud and particle renderers are rebuilt from the new assets, and the view keeps its time,
  weather, perspective and hand state.
- The menu background, item catalog and HUD sprites are rebuilt too.

**The screen replaces vanilla's two columns with one ordered list:**

- Active packs come first, highest priority on top. Each can be moved up or down or removed in place.
- The vanilla assets show beneath them.
- Every other pack found follows, with one Add button each.
- Edits save immediately. The reload waits for Apply, the screen's one primary action, shown only while the
  selection differs from what is loaded.
- Names and descriptions drop their `§` codes.

### OptiFine / MCPatcher

**Custom item textures (CIT) are implemented** (`CustomItems`), under `optifine/cit/` and `mcpatcher/cit/`:

- `type=item` and `type=armor`, with `items`.
- `damage` (ranges or %), `damageMask` and `stackSize`.
- `enchantmentIDs` (ids or names) with `enchantmentLevels`, and books' stored enchantments.
- `nbt.*` with exact, `pattern:`, `ipattern:`, `regex:` and `iregex:` values, and `*` for list elements.
- `texture`, `texture.<name>` and `model`. Models may be sprites or element-based 3D models, and `./` paths resolve
  beside the file.
- `weight`, then path order.

The installed Xe Lite pack (86 rules) drew its custom gun models and icons in hand and in the hotbar. `type=enchantment`
(custom glint) is parsed but not drawn.

#### Connected textures (CTM)

Done. `ConnectedTextures` reads `optifine/ctm` and `mcpatcher/ctm`, with the `ctm`, `horizontal`, `vertical`, `h+v`,
`v+h`, `top`, `random`, `repeat` and `fixed` methods.

- Faces with different CTM tiles never merge in the mesher.
- Block textures live in two texture arrays of up to 2048 layers each (the GPU limit), addressed by 16-bit texture
  ids. The shaders pick the sampler per vertex.
- Height conditions (`minHeight`, `maxHeight`, `heights`) work.

Not supported:

- `ctm_compact` and the overlay methods. Rules that use them are skipped.
- Biome conditions. Rules that use them are skipped.
- Non-cube models, such as panes and plants.

Known gaps:

- Random tiles use the port's own hash, so the tile at a given position differs from OptiFine's. Stability, symmetry
  and weights are tested.
- An unknown word in `faces=` means all faces. It is not rejected.

Checked: `ConnectedTexturesTest` (29 tests). No in-game check is recorded.

#### Custom sky

Done. `CustomSky` and `SkyRenderer` follow OptiFine's rules for time, fade, rotation, axis, weather and blend. Every
blend mode works, including `replace`.

- The sun, moon and stars were drawn vertically mirrored. The sky geometry now uses the y-up viewport.
- The image decoder accepts up to 4096 × 4096, so 3072 × 2048 skies load.

Not supported:

- Biome conditions.
- Transition smoothing.

Known gaps:

- Fade times are not checked against OptiFine's `parseTime` (tick 0 is 6:00) with Xe Lite's skybox.

Checked: `CustomSkyTest` (24 tests), and Xe Lite's skybox at 13300 and noon in game.

#### Custom colors

Done. `CustomColors` reads `color.properties` and the custom colormaps:

- Sky, fog and underwater colormaps. A `format=fixed` map loads without an image.
- Block colormaps, such as grass and water.
- Potions by effect (`potion.*`, with `potion.water` for the water bottle), and spawn eggs (`egg.shell.*`,
  `egg.spots.*`).
- Water bubble, splash and rain drop particles (`drop.water`).
- The sky, fog and void fog use the player's feet block, as OptiFine does.

Not supported:

- The smoothing radius. Colormaps are sampled at one biome, not averaged.
- `xporb`, `redstone`, `text`, `sheep` and map colors.
- Water biome multipliers (`waterColorMultiplier`) in the mesher and the particle hook.

Known gaps:

- Hex colors accept `#` and `0x` prefixes. OptiFine's `parseColor` accepts neither.
- Wake and drip particles keep their own colors. OptiFine does not tint them either.

Checked: `CustomColorsTest`, `SkyTest`, `WaterParticleTintTest` and `ItemColorsTest`. In game with a test pack: the
sky, fog, underwater, `drop.water` and potion and egg colors.

#### Still not implemented

- Random mobs.
- Custom animations.
- Emissive textures.
- Fonts beyond vanilla's.

Server resource packs are still declined (decision 0011).

## Chests

Done.

- `ChestModels` draws single and large chests: the lid (it pivots on its back edge), the knob and the base.
- `ChestTracker` rescans changed sections, up to 24 per frame.
- Block Action 1 sets how many players have a chest open. The lid swings 0.1 per tick toward open while someone uses
  the chest, then back.
- Chest items draw as 3D chests in hand and in the HUD.

Not drawn yet: ender chests, skulls and banners, in the world and in hand.

Checked: `ChestTrackerTest` (8 tests). No in-game check is recorded.

## Fixes from the 2026-10-23 round

- `CustomColors`: the static `NONE` field read the potion table before it was set, so class loading threw. Every pack
  load failed. `NONE` now follows the tables.
- Sky colormaps read the eye height plus 1. OptiFine reads the feet (`posY + 1`), and so does the void fog now.
- A server with `doDaylightCycle` off sends negative time. The client stores the absolute value.
- The lily pad item is tinted 0x71C35C, as vanilla's `ItemLilyPad` does. The placed block stays 0x208030.
- `ConnectedTextures`: `matchBlocks` metadata lists split on commas, so `glass:0,1` also matched stone. Now only
  whitespace splits entries.
- `ConnectedTextures`: `innerSeams` applies to CTM only. The strip methods never read it in OptiFine.
- `CustomColors`: `format=fixed` sky, fog and underwater colormaps were dropped.
- `CustomSky`: thunder weighting now follows OptiFine's `getWeatherBrightness`: rain-only is `rain - thunder`,
  thunder-only is `thunder`.
