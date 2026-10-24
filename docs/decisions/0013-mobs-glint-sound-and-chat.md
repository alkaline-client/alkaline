# Architecture decision 0013: Remaining mobs, enchantment glint, sound and chat input

Status: accepted. Implemented 2026-10-23 (wave 1: tasks 1A, 1E, 1D and 1B). Builds on decisions 0011 and 0012.

As in 0012, every rule below was read from the **decompiled vanilla 1.8.9 client** (the MCP-mapped jar through
Vineflower), and each piece was checked against Mojang's 1.8.9 server with `--input-script` and `--screenshot`.
Task 1C (chest-like container screens) was not run: `Window.open` and `ContainerScreen` already covered every
chest-like window (checked 2026-10-23, see 0012's Chests), so wave 1 had no `wave1/1C` branch to merge.

## Remaining mob models (1A)

New models with vanilla's boxes, pivots, texture sizes and animation math (`MobModels`, `HorseModels`,
`BossModels`): witch, bat, rabbit (eight coats), ocelot and cat, silverfish, endermite, magma cube, snow golem,
zombie villager, mooshroom mushrooms, horse, donkey, mule, zombie and skeleton horse, guardian and elder guardian,
wither and ender dragon. Horse textures composite coat, marking and armor as `LayeredTexture` does.

`MobAnimator` holds per-entity animation state in one int-keyed table, ticked at 20 Hz after `EntityTracker.tick`;
`EntityView` only reads it, so drawing does not allocate. It carries the rabbit hop (Entity Status 1), horse
grazing, rearing, mouth and tail swish, slime and magma cube squish, the wither's side-head aim, guardian spikes,
tail and charging beam, and the dragon's 64-tick yaw and height history.

Kept on purpose:

- RenderVillager's 0.9375 scale (missing before) and silverfish and endermite dying with a 180° tilt.
- Horse rearing and mouth follow metadata edges, so a flag set and cleared between two ticks is missed, as in
  vanilla.
- The witch's held item is drawn as small as `LayerHeldItemWitch` draws it.

## Enchantment glint (1E)

`GlintEffect` describes one glint layer. It covers vanilla items (`RenderItem.renderEffect`: color 0x8040CC,
scale 8, two passes at −50° and 10°), vanilla armor (`LayerArmorBase.renderGlint`: 1/3 scale, ±30°, the wearer's
age) and OptiFine CIT `type=enchantment` layers. `ModelRenderer` draws it in a pass grouped by effect, model and
texture, with one pipeline per blend, and repeats the model's alpha test so armor's transparent texels stay clear.

HUD and inventory icons stay rasterized once on the CPU. `HudGlint` animates the glint on the GPU over the
composited Compose layer at `glint:` anchors that `ItemIcon` publishes, only where the icon's opaque texels still
show. Compose is not re-rastered per frame.

Kept on purpose:

- `ItemStack.glint()` follows `hasEffect`: written books, bottles o' enchanting, nether stars, enchanted golden
  apples and potions with effects glint too.
- CIT enchantment rules follow OptiFine 1.8.9: only the first sorted rule per enchantment is tried, and `items` is
  not checked.

Fitting vanilla's GUI glint to a real vanilla 1.8.9 hotbar sword gives 0.37/255 mean error; Alkaline's HUD sword
fits the same model at 0.4–0.7/255, with a constant phase offset because Alkaline has no atlas position.

## Sound (1D)

New module `modules/audio`: OpenAL Soft and stb_vorbis through LWJGL 3.4.3. Callers queue requests and never
block. An audio thread owns the OpenAL context and a decode thread decodes `.ogg` files. Clips are cached in AL
buffers under a 48 MiB cap. The pool has 28 clip voices and 4 stream voices, paulscode's counts. Vanilla sounds are
read from the launcher's asset index (`AssetIndexSource`), layered under the jar as `DefaultResourcePack` is.

Kept on purpose:

- **sounds.json** follows SoundHandler: every layer applies lowest first, a repeated event appends unless
  `"replace": true`, picks are weighted, `type: event` references resolve when played, and an invalid layer is
  skipped whole.
- **Attenuation** is vanilla's linear fade over 16 blocks (16 × volume above 1), computed by Alkaline with
  `AL_DISTANCE_MODEL = AL_NONE` while OpenAL pans by direction; pitch clamps to 0.5–2.
- **Who plays what** follows vanilla's call sites, so nothing plays twice: Sound Effect packets play as received;
  the client plays only the local player's own footsteps, landing, eating and drinking, and hurt and death (the
  server leaves that player out); other entities' Entity Status is silent; World Effects 1000–1022, 2001, 2002 and
  records play as `RenderGlobal.playAuxSFX` does.
- UI clicks play `gui.button.press` at 0.25 on enabled buttons, toggles and sliders only.
- Settings → Audio has master and per-category volumes. Music & jukeboxes default to 0.

## Chat input (1B)

T opens chat and "/" opens it with "/" typed. Enter sends the trimmed line, Escape closes. The open chat is one
frosted panel with the 100-message scrollback (20 lines visible), wheel and PageUp/PageDown scrolling, and the field.

Kept on purpose (GuiChat, GuiNewChat, ChatAllowedCharacters):

- The 100-character cut; §, control characters and DEL are dropped while typing.
- Up and Down history, skipping a line equal to the last sent one; Tab completion through Tab-Complete, common
  prefix first, then cycling.
- Click and hover components (`ChatText.rich`): run_command, suggest_command, Shift-click insertion, open_url
  behind a confirm prompt (http and https only), and show_text, show_item, show_entity and show_achievement
  tooltips. open_file is dropped, as GuiScreen drops it.

Typed characters reached Compose as key-up events with code point 0, so no text field received them. They now
arrive as typed events.

## Integration

The four branches merged with `git merge --no-ff` in order 1A, 1E, 1D, 1B without conflicts, and `./gradlew
build` passed after each merge. The hot files kept both sides' hooks: `EntityView` draws 1A's held and worn items
through 1E's `submitItem` glint path (a witch's enchanted item glints), and `ComposeLayerHost` has both 1D's click
sound hook and 1B's typed-character fix.

## Verification

- `./gradlew test --rerun-tasks`: 480 tests, 0 failures. New suites include `MobModelsTest`, `MobAnimatorTest`,
  `SoundEventsTest` (including the real 1.8.9 sounds.json: 246 events), `AttenuationTest`, `VoicePolicyTest`,
  `ChatInputTest` and `ChatTextTest`.
- Combined runtime check on port 25639 (flat world), one scripted client run captured at 8.4, 11.4, 12.75 and 16 s:
  - **Chest:** rclick on a placed chest opened "Chest" with 5 diamonds and 3 golden apples, the lid open.
    `random.chestopen` and, after Escape, `random.chestclosed` played at gain 0.428 from 2.3 blocks.
  - **Chat:** the open field showed "hello wave1". After Enter the server logged `<Player> hello wave1`, and the
    line showed in the HUD chat.
  - **Witch with Sharpness V diamond sword:** the sword glinted in hand. The NoAI witch took the hit (red hurt tint,
    enchantment-hit particles) and died on the second hit; the XP orb pickup played `random.orb`. The hit itself is
    silent, as in vanilla: 1.8.9's `EntityWitch` returns `null` for its living, hurt and death sounds, and the 1.8
    asset index has no witch sounds.
  - The default output's monitor, recorded with `parec`, had bursts that line up with the item pop, chest open,
    chest close and orb.

## Not done (carried over)

- **1A:** the wither's armor glow and the dragon's eye layer and death effects need an additive or emissive layer.
  The guardian beam's end cap is not drawn. Mushroom planes are 16 wide (the block model is 14.4).
- **1E:** CIT `type=enchantment` compiles but was not runtime-tested with a pack (no installed pack has such rules),
  and `CustomItems.enchantments` has no unit test. `replace` blend does no alpha test. The third-person held item
  looks edge-on and small compared with vanilla.
- **1D:** some runs show one ~450 ms frame, very likely the join counted inside the stats window (not confirmed).
  Not built: the music ticker, rain, lightning, minecart and guardian moving sounds, portal and nausea sounds, and
  reopening a disconnected device. Only the `minecraft` domain's sounds.json is read. Music & jukeboxes at 0 also
  silences note blocks (1.8.9 files `note.*` under `record`). Windows was not run.
- **1B:** show_item shows only the item's name (no SNBT parser yet). "/" opens chat by physical key position, so
  layouts where "/" needs Shift open chat with T. Tab cycling with several candidates and the link prompt's Open and
  Copy were covered by unit tests only.
