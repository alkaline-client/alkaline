# Architecture decision 0009: the frame-rate cap under XWayland

Status: accepted. Implemented 2026-10-17. Partly superseded by decision 0010: native Wayland is now the default on
Wayland desktops, Frame sync became an automatic present mode with a V-Sync switch, and V-Sync is paced.

Measurements: NVIDIA GTX 1660 SUPER (outside the support ceiling), driver 615.78.08, Linux (CachyOS) on the COSMIC
Wayland desktop, 1280x760 window on a 170 Hz display. "In game" is decision 0008's scene (Mojang's 1.8.9 server, night,
rain, 42 mobs, render distance 8, default HUD), 25 s with `--stats`; "title screen" is the Fluid background, 12 s.

## The cap

Decisions 0007 and 0008 found the client presentation-bound at about 338 fps: the render thread spent most of each
frame in `vkAcquireNextImageKHR`. That was not the game's cost. The client is an X11 window on a Wayland desktop
(SDL picks its X11 driver), and the X11 surface's mailbox mode there is emulated: it accepts two frames per refresh
(340 at 170 Hz) and paces them in uneven pairs (p50 3.6 ms against a 2.9 ms mean). More swapchain images change
nothing (3, 4 and 6 all give 340 fps). FIFO behaves the same way there, so V-Sync also runs at 340, not 170.

| | XWayland mailbox | XWayland immediate | native Wayland mailbox | native Wayland immediate |
|---|---:|---:|---:|---:|
| title screen | 340 fps | 2,039 fps | 1,945 fps | 170 fps (no such mode: FIFO) |
| in game | 338 fps | 2,510 fps | 2,343 fps | — |
| in game, CPU / GPU per frame | 2.74 / 0.66 ms | 0.34 / 0.34 ms | 0.36 / 0.33 ms | — |

Without the cap the frame costs about a third of a millisecond of CPU and of GPU time.

## The fix

- **Low latency under XWayland presents immediately.** On a Wayland desktop the compositor presents every window
  itself, so an X11 client's immediate presents already reach the screen as mailbox does: the newest frame at each
  refresh, without tearing in a window. Low latency keeps mailbox everywhere else (native Wayland, X11 on Xorg,
  Windows). XWayland is detected as SDL's X11 driver with `WAYLAND_DISPLAY` set.
- **Fallbacks in order.** A surface without the requested mode now falls back immediate → mailbox → FIFO, not
  straight to FIFO. Native Wayland has no immediate mode, so Uncapped there runs as mailbox (1,945 fps) instead of
  being held at the refresh rate.
- `--stats` reports the present mode the surface actually gave.

Result with the default settings (Low latency, no frame limit), XWayland:

| | before | after |
|---|---:|---:|
| in game | 338 fps; p50 3.20 ms, p99 5.97 ms | 2,463 fps; p50 0.36 ms, p99 0.89 ms |
| title screen | 340 fps | 2,020 fps |

## Caveats and what is left

- Immediate presents may tear where the compositor allows tearing for a fullscreen window (Wayland's tearing-control
  protocol, which XWayland uses for asynchronous presents in fullscreen). Windowed play cannot tear. Tearing was not
  checked by eye on this host.
- **V-Sync under XWayland is not refresh-locked** (340 fps at 170 Hz). Not changed here.
- **Native Wayland** is the clean fix: a real mailbox mode, no X server in between, and SDL's Wayland relative-pointer
  input. It is planned and not enabled; this run (`SDL_VIDEODRIVER=wayland`) only measured presentation, not input,
  window decorations, fullscreen or text input on Wayland.
- Measured on one Linux desktop. Windows (DWM) and X11 on Xorg keep mailbox and were not measured.
