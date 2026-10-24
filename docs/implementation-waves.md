# Implementation waves

Run one session per task, in `~/Projects/Alkaline/legacy`, in its own worktree. Set the model and effort shown, then
paste the block. Sessions share no memory, so each block points to `docs/agent-brief.md`. Tasks in the same wave
touch different files and can run together. When a wave is done, run its integrator.

**Model choice:**
- **Opus:** Compose UI, new subsystems, hard mesher or shader work, and the integrators.
- **Sonnet:** most features.
- **Haiku:** tests, docs and small fixes.

If a session reports something as not done without saying what it tried, ask it once more in the same session.

## Done
- **Wave 0:** pack work, tests, docs, and the git reset.
- **1A:** the remaining mobs (`wave1/1A`).
- **1B:** chat (`wave1/1B`).
- **2D:** server resource packs (`wave2/2D`).
- **1C and 2E:** container screens. These were already built.

## Wave 1

### 1D Sound (Opus High, port 25634)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave1/1D):

Sound system
- Play vanilla 1.8.9 sounds from the active assets (resource packs included), with LWJGL OpenAL and stb_vorbis; pin versions in gradle/libs.versions.toml
- sounds.json rules: categories, random variants, stream, volume and pitch, 16-block attenuation (SoundManager, SoundEventAccessor)
- Sources: the Sound Effect packet, sounds the vanilla client plays itself (footsteps, hurt, bow, eating), menu clicks; never play one twice
- Decode off the render thread; bounded source pool; free OpenAL handles on shutdown and pack reload
- Volume per category in an Audio settings group
- Check: tests for sounds.json and attenuation; in game, hit a zombie and draw a bow and confirm the sounds play
```

### 1E Enchantment glint (Opus Medium, port 25635)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave1/1E):

Enchantment glint
- Vanilla glint (RenderItem.renderEffect, LayerArmorBase) on held items, dropped items, HUD icons and armor
- OptiFine CIT type=enchantment (already parsed in CustomItems)
- Check: screenshots a second apart of an enchanted sword in hand, enchanted armor in F5, and an enchanted book in the hotbar; the glint must move
```

### 1Z Integrator (Opus Medium, on main)
```
Tasks for legacy (read docs/agent-brief.md first; you may edit README.md and docs/decisions and push main):

Merge wave 1
- Merge with --no-ff in this order: wave1/1A, wave1/1E, wave1/1D, wave1/1B. Build after each merge
- On conflicts keep both sides; read the branches' docs/handoff notes
- Run all tests, then one in-game check: chat, a witch, an enchanted sword, sound
- Write docs/decisions/0013 from the handoff notes, delete docs/handoff, commit, push main
```

## Wave 2

### 2A Objects (Sonnet High, port 25641)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave2/2A):

Draw tracked objects like vanilla
- Minecarts (with the block inside), boats, paintings, item frames with their item, XP orbs, primed TNT, falling blocks, armor stands, fireworks, lightning
- Check: summon each one and take screenshots
```

### 2B Block breaking (Sonnet High, port 25642)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave2/2B):

Block breaking feedback
- Crack stages (destroy_stage_0..9) from your own digging and from Block Break Animation packets (RenderGlobal.drawBlockDamageTexture)
- Block-crack particles while digging and when a block breaks (EffectRenderer.addBlockHitEffects and addBlockDestroyEffects)
- Check: screenshots while digging stone; a test for the packet path
```

### 2C CTM completion (Opus High, port 25643)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave2/2C):

Finish connected textures (OptiFine 1.8.9 ConnectedTextures and ConnectedProperties)
- Glass panes and iron bars, then other full-face model quads
- method=overlay, ctm_compact, and the biomes and heights conditions
- Check: tests for the tile rules; screenshots of pane walls with Xe Lite; mesh time on a pane-heavy section within 20% of now
```

### 2F Small parity fixes (Haiku Extra, port 25646)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave2/2F):

Check against the decompiled jar and fix only what is wrong
- leftClickCounter = 10 after a left click that hits nothing (Minecraft.clickMouse, runTick)
- The swing restart rule (EntityLivingBase.swingItem)
- One test per rule
```

### 2Z Integrator (Opus Medium, on main)
```
Tasks for legacy (read docs/agent-brief.md first; you may edit README.md and docs/decisions and push main):

Merge wave 2
- Merge with --no-ff in this order: wave2/2F, wave2/2C, wave2/2B, wave2/2A, wave2/2D. Build after each merge
- On conflicts keep both sides; read the branches' docs/handoff notes
- Run all tests, then one in-game check with Xe Lite: digging, a minecart, a pane wall, a server pack
- Write docs/decisions/0014, delete docs/handoff, commit, push main
```

## Wave 3

### 3A Name tags and teams (Sonnet High, port 25651)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave3/3A):

Name tags (RendererLivingEntity.renderLivingLabel)
- Range, sneaking dimming, see-through, background
- Team prefix, suffix and visibility from the Teams packet; the below-name score
- Team prefixes and sorting in the tab list, if missing
- The sidebar already exists; leave it unless it is wrong
- Check: screenshots of a named armor stand and of a team with a prefix
```

### 3B OptiFine extras (Opus Medium, port 25652)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave3/3B):

From OptiFine 1.8.9 (net/optifine)
- Random entities (mob textures and properties)
- Texture animations (anim properties); upload changed layers in beforeFrame only
- Emissive textures
- Check: tests for parsing; screenshots, using a tiny test pack when Xe Lite lacks a feature
```

### 3C Sky and color leftovers (Sonnet Medium, port 25653)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave3/3C):

Match OptiFine 1.8.9
- Custom sky biomes and heights conditions, and transitions
- Custom colors: smoothing radius, xporb, redstone, text, sheep and map colors
- Check: tests; screenshots with Xe Lite at sunset
```

### 3D Maps and world border (Sonnet Medium, port 25654)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave3/3D):

- Filled maps in hand (ItemRenderer.renderItemMap) and in item frames
- World border wall, warning tint and collision (RenderGlobal.renderWorldBorder)
- Check: screenshots of a map in hand and of /worldborder set 20
```

### 3Z Integrator (Opus Medium, on main)
```
Tasks for legacy (read docs/agent-brief.md first; you may edit README.md and docs/decisions and push main):

Merge wave 3
- Merge with --no-ff in this order: wave3/3C, wave3/3D, wave3/3B, wave3/3A. Build after each merge
- Run all tests, then one in-game check with Xe Lite at sunset: a scoreboard, a map, a named armor stand
- Write docs/decisions/0015, delete docs/handoff, commit, push main
```

### 3E Performance pass (Opus High, after 3Z, on main)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave3/3E, merge it into main and push when done):

Performance
- One fixed scene: Xe Lite, render distance 8, 30 mobs and objects, sound on, a 60-second walking script
- Measure frame-time p50, p99 and max, plus allocation rate (JFR); fix the biggest costs and re-measure after each fix
- Add a before-and-after table to docs/decisions/0015
```

## Not scheduled
- **Vulkan 1.1 compatibility mode:** AGENTS.md says not to build it until it is scheduled.
- **Singleplayer:** it needs an integrated server.
- **Windows and target-GPU qualification:** they need that hardware.
