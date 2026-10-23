# Architecture decision 0003: Monet themes, the Fluid background, accounts, worlds and servers

Status: accepted. Implemented 2026-10-16.

## Decision summary

1. **Themes.** `ClientSettings.Theme` names a scheme generator and a brightness. Today there are two, *Monet
   Light* and *Monet Dark*: Material You Expressive schemes (`SchemeExpressive`) under the 2025 color spec that
   Material 3 Expressive introduced (`ColorSpec.SpecVersion.SPEC_2025`), generated once
   from Alkaline's seed color (`#1F8A80`) by Google's material-color-utilities, through its Kotlin port `com.materialkolor:material-color-utilities` 5.0.1 (Apache-2.0, pure Kotlin, no Compose
   dependency). Schema version 2 renamed the persisted values (`dark`/`light` to `monet_dark`/`monet_light`)
   with an explicit migration. Renderer code reads the few colors it needs (surface, ink) through
   `AlkalineThemes.palette`, so loading clears and the Fluid background follow the theme.
2. **Fluid background (default).** A port of the `generic/nice-elements` fluid/Bayer effect without its test
   obstacles. The original solves stable fluids in compute shaders over several float images. Here velocity,
   dye and pressure are packed into one `RGBA16_FLOAT` texture and each stage (advect, 18 Jacobi iterations,
   project with dye advection) is a full-screen fragment pass ping-ponging two targets, so it reuses the frame
   encoder with no compute path. The solver steps at a fixed 60 Hz (at most 3 steps per frame), independent of
   the frame rate. The composite keeps the original's turbulence, Bayer order, and constants. The pointer stirs
   the flow by hovering (the original required a drag; menus are hover-driven).
3. **Menu buttons.** Title actions share one calm tonal style. Hover is the only highlight: the control under
   the pointer animates to the primary container (140 ms). This replaced the filled primary Singleplayer
   button at the user's direction.
4. **FPS counter.** In-world only. It is a `client.fps` value in the data-driven HUD layout, gated by
   `interface.fps_counter` through the `hud.fps.visible` value; `FrameRateMeter` produces the number
   (half-second windows, allocation-free). Menus never show it. The in-world HUD host arrives with the game
   adapter.
5. **Client services** (`modules/client`, `ClientServices`), all off the render thread:
   - `WorldLibrary` reads `.minecraft/saves/*/level.dat` (with the existing NBT port) and `icon.png`.
   - `ServerList` keeps Alkaline's own `servers.json`, seeded once from vanilla's `servers.dat`; Alkaline never
     writes vanilla's file. `ServerPinger` implements the protocol 47 Server List Ping (handshake, status
     JSON, ping/pong latency) with SRV lookup and 5 s timeouts on virtual threads.
   - `AccountManager`/`MicrosoftAuth` sign in with the system browser and a one-shot loopback redirect
     (`state`-checked), then Xbox Live, XSTS and Minecraft services. Only name, UUID and the Microsoft refresh
     token are stored (`account.json`, `rw-------` on POSIX); access tokens stay in memory and out of
     `toString()`.
6. **Singleplayer and Multiplayer pages** show real data and say plainly that play needs the game adapter.
   Multiplayer merges vanilla's search, direct connect and add-server into one field: typing filters saved
   servers, and a typed address that is not saved gets a live ping card with Save. The selected card opens its
   actions in place.

## Open items and caveats

- **OAuth application.** Alkaline has no Azure registration yet. The default client id is the public desktop
  client used by the referenced sign-in (redirect `http://localhost:1919/login`), so Microsoft's consent page
  names that application. Register Alkaline's own app, get it approved for Minecraft services, and set
  `ALKALINE_MSA_CLIENT_ID` / `ALKALINE_MSA_REDIRECT_PORT` (or change the default). The browser flow has not
  been exercised end to end with a real account in this change.
- Joining servers and opening worlds need the game adapter; Play and Join are present but disabled.

## Measurements

Development host, outside the support ceiling: GTX 1660 SUPER, NVIDIA 615.78.08, Linux/X11, 1280x760, title
screen, uncapped present, 8 s run with 2 s warm-up:

| Background | Mean | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| Fluid | 0.663 ms | 1.143 ms | 1.238 ms | 1.556 ms |
| Blue Planet | 1.325 ms | 1.198 ms | 2.282 ms | 2.367 ms |

With the default low-latency present mode all backgrounds measured the same (about 334 fps, present-limited).
Fluid, Overworld (with plants and animated water) and the new pages ran with the Khronos validation layer and
reported no errors. This is not target-hardware evidence.
