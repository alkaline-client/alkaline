# Architecture decision 0014: Objects, digging, server packs, OptiFine features, and world

Status: accepted. Implemented 2026-10-24 (wave 2: tasks 2A, 2B, 2D, OptiFine and World). Builds on decisions 0012
and 0013.

As before, vanilla rules were read from the **decompiled 1.8.9 client** and OptiFine's from its 1.8.9 sources
(`net/optifine` in KotlinizedMCP). Each branch was checked against Mojang's 1.8.9 server with `--input-script` and
`--screenshot`. No check summons, tests or touches TNT; primed TNT rendering was accepted from 2A's own session.

## Objects (2A)

Minecarts (with their display block), boats, primed TNT (flash and swell), falling blocks, paintings and item frames
with their items, XP orbs, armor stands (poses and equipment), fireworks and lightning draw as vanilla does. The code
is in `ObjectModels`, `Hanging` and `MinecartRails`, with hooks in `EntityView`, `EntityTracker`, `Entity`,
`Metadata`, `ObjectPhysics`, `ItemCatalog`, `ItemMeshes`, `ModelRenderer` and `model.vert`. Painting tiles and
lightning bolts are uploaded before the frame that first needs them.

## Digging and block breaking feedback (2B)

The client had no digging. Now:

- `Digging` holds 1.8.9's hardness table and the tool, Efficiency, Haste, Mining Fatigue, water and air rules
  (`EntityPlayer.getBreakSpeed`). `BlockBreaking` is PlayerControllerMP's click, damage and reset behind a small
  `Host`. Both keep vanilla's float arithmetic: stone by hand takes 151 ticks.
- `BlockDamage` is RenderGlobal.damagedBlocks (one entry per digger, expiry after 400 ticks, 32-block range).
  `DamageOverlay` meshes the dug block with the section mesher (`ChunkMesher.meshBlock`), so stairs, fences and plant
  offsets match, and draws `destroy_stage_N` with the overlay blend in gamma space.
- Hit particles are addBlockHitEffects. The break burst, hit and break sounds play for the local dig. Creative
  breaks on every click and repeats every five ticks.

## Server resource packs (2D)

Offers follow NetHandlerPlayClient.handleResourcePack. A saved server remembers Ask, Use or Never (`resourcePacks`
in servers.json, imported from vanilla's `acceptTextures`); otherwise a card asks "Use this server's resource pack?"
with Use pack as the primary action. `ServerPacks` downloads on a virtual thread with vanilla's headers and 50 MB
cap, keeps the newest 10 under `server-resource-packs/<sha1>`, and reuses a stored file whose SHA-1 matches. The
pack layers above the player's packs through the normal reload and is removed on leaving. A pill shows download and
apply progress while the player keeps playing.

Kept on purpose:

- A downloaded file whose hash does not match is still used and logged, as vanilla does.
- **Deviation:** only http and https are fetched. Vanilla's URLConnection would also open `file:` and `jar:`
  addresses a server sends; `level://` answers failed, since Alkaline has no saves.

## OptiFine features (wave2/optifine)

- **Connected textures** (ConnectedTextures, ConnectedProperties): glass panes and iron bars, then full-face model
  quads; `method=overlay`, `ctm_compact`, and the `biomes` and `heights` conditions; emissive block quads.
  Sections now carry their padded biome columns (`BlockSource.fillBiomes`) for the biome conditions.
- **Custom sky:** per-layer `biomes`, `heights` and `transition`, smoothed like SmoothFloat at Entity.getPosition.
- **Custom colors:** 7×7 colormap smoothing for sky, fog and underwater colors with CustomColorFader fading;
  redstone wire and particle palettes; `xporb.png` and `xporb.time`; `sheep.<dye>` and `collar.<dye>`;
  `text.code.N`, `text.xpbar`, `text.boss`, `text.sign`; `map.<name>`.
- **Random entities** (`optifine/random`, `mcpatcher/mob`): numbered copies with gaps of up to 10, and properties
  rules for biomes, heights, health, name, professions, collar colors, baby, weather, moon phase, day time and
  weights. Dependent textures (`spider_eyes`) use their parent's properties.
- **Emissive entity textures:** a second pass at block light 15 with the sky light kept.
- **Texture animations** (`anim` properties) on entity textures: each distinct state is composed and uploaded in
  `EntityView.beforeFrame`, at most two per frame and 256 per texture, then reused.

Kept on purpose:

- The CTM border on a pane's west face is mirrored, because the vanilla pane model's UV there is [16,0,0,16] and
  OptiFine's `makeSpriteQuad` keeps relative UVs.
- Panes next to a full block grow arms in 1.8.9, so a wall right behind panes shows bordered strips.
- **Deviations:** random-entity `dayTime` uses the time of day (world time mod 24000), and `!` negates `name`
  rules, where this OptiFine build compares raw world time and has no negation.

## World and gameplay (wave2/world)

- **Name tags** (`NameTags`, `NameTagRenderer`): RendererLivingEntity.renderName and Render.renderLivingLabel,
  with range, the see-through and sneaking passes, team visibility rules, prefixes and suffixes, and the below-name
  score. `VanillaFont` has FontRenderer's widths and § styles.
- **Maps** (`MapStore`, `MapColors`, `MapView`): Map Data packets, vanilla colors, the map in hand on its sheet with
  markers, and maps in item frames.
- **World border** (`WorldBorder`, `BorderView`): packets, the wall, the red warning vignette and collision.
- **Timing:** `AttackCooldown` is Minecraft's `leftClickCounter`; the swing restart rule and duration were checked.
- Teams and the tab list follow vanilla's flags and comparator.

## Integration

The branches merged with `git merge --no-ff` in order 2A, 2B, 2D, optifine, world, and the client built after
each merge. Conflicts kept both sides:

- **2D:** `Overlay` has both `CHAT` and `SERVER_PACK`; `ClientApp` registers tab completion and the pack offer, and
  `endGame` stops audio and drops the server pack.
- **optifine:** `ChunkMesher` has both the biome-carrying `mesh` and 2B's `meshBlock` (which passes no biomes);
  `EntityView.beforeFrame` uploads 2A's objects and then the texture animations, and keeps both the packed light for
  orbs and the emissive light.
- **world:** `EntityView` has 2A's object state and world's labels, quads and maps; `close()` releases all of them.
  `GameView` sets both the custom colors and world's viewer, maps and border.

Integration changes beyond conflict resolution:

- **One leftClickCounter.** 2B had its own counter in `PlaySession`; it is gone. A click that hits nothing calls
  `AttackCooldown.missed`, `attack()` returns until `ready()`, and the held-key dig runs through `sustain(held)`
  (sendClickBlockToController), which also replaced world's separate end-of-tick call. Adventure-mode clicks on a
  block no longer count as misses, as in vanilla.
- **Maps in item frames:** 2A's `itemFrame` queues the map through world's `framedMap`.
- **OptiFine colors for 2A and world:** XP orbs use `xporb.png` and `xporb.time`; map textures use `map.<name>`
  through `MapColors.argb(pixel, index, custom)` and are rebuilt when the colors change.

## Verification

- `./gradlew test --rerun`: 607 tests, 0 failures. New suites include `DiggingTest`, `BlockBreakingTest`,
  `BlockDamageTest`, `ServerPacksTest`, `ConnectedTexturesTest`, `ConnectedTexturesJarTest`, `RandomEntitiesTest`,
  `TextureAnimationsTest`, `NameTagsTest`, `MapTest` (including pack map colors), `WorldBorderTest` and
  `AttackCooldownTest`.
- One combined run with Xe Lite on port 25643 (flat world, survival): the server's pack offer showed and Use pack
  applied it (status 3 then 0, one HTTP GET; the red wool turned into the pack's checker); a minecart, an armor
  stand with chestplate and helmet and an item frame with a sword drew; "Alkaline" and "Bacon" name tags showed
  (the second faint through the wool); holding the attack key on oak planks showed the crack overlay; the glass pane
  and iron bar wall drew with Xe Lite's connected textures. A painting summoned for the scene was dropped by the
  server (placement), so paintings rely on 2A's own check.
- Earlier per-branch checks are in each section's source notes: 2B on stone, pickaxes and fences; 2D on direct and
  saved servers; OptiFine on a tiny test pack for random, emissive and animated textures, overlays, `ctm_compact`,
  heights and biomes, and sky conditions; world with two bots and a proxy for team prefixes and map pixels.

## Not done (carried over)

- **2A:** TNT was checked only by its own session.
- **2B:** no relighting on block changes, so a dug hole stays dark until the server resends the chunk. No cracks on
  chests, ender chests, signs and skulls (tile entity models), fire, portals or liquids. Adventure mode with
  CanDestroy and tool damage are not applied locally.
- **2D:** leaving and the progress pill were not observed at runtime. `--proxy` is not applied to downloads.
- **OptiFine:** collar, boss and sign text colors have no consumer yet. Animations reach entity textures only; no
  emissive pass for child models or armor. Mob random ids hash the entity id when there is no UUID; villager careers
  are 0. Sky smoothing is not reset on world change. Per-biome block tints and log/quartz `vertAxis` CTM are not
  done.
- **World:** mobs without a custom name never get a tag; framed maps have no name label; no F1 toggle; tab list
  objective values and the ordinary darkness vignette are not drawn; `EntityTypes.height` gives sneaking players
  1.65.
