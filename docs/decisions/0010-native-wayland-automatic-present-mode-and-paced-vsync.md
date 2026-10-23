# Architecture decision 0010: native Wayland, automatic present mode, and paced V-Sync

Status: accepted. Implemented 2026-10-17. Builds on decision 0009.

Measurements: NVIDIA GTX 1660 SUPER (outside the support ceiling), driver 615.78.08, Linux (CachyOS), COSMIC Wayland
desktop, 170 Hz display, 1280x760 window unless fullscreen. "In game" is decision 0008's scene (Mojang's 1.8.9 server,
night, rain, 42 mobs, render distance 8), 25 s with `--stats`; "title screen" is the Fluid background.

**Input to screen** is new in `--stats`: the time from sampling a frame's input to `vkWaitForPresent2KHR` reporting
that frame presented. On native Wayland that report comes from the compositor's presentation feedback, so it
measures to the display (plus however late the report arrives). Under XWayland the report completes when the X
server takes the frame, not when it is shown (0.9 ms windowed), so XWayland numbers are not comparable and are left
out below.

## Native Wayland by default

SDL3 picks its X11 driver (so XWayland) on compositors without the Wayland FIFO protocol, COSMIC among them. The
client now asks SDL for `wayland,x11` on Wayland desktops: native Wayland, with X11 as the fallback if Wayland fails
to start. **Window system** (Advanced, restart) forces either; `SDL_VIDEODRIVER` in the environment still wins.
The SDL app id is `alkaline`.

Checked on COSMIC: server-side window decorations (the compositor's title bar), relative mouse mode for the camera
and text input both granted (failures are now logged), the fullscreen toggle, and joining and playing on a server.
Not checked: mouse look and typing with real hardware input (only synthetic SDL events were available here), other
compositors, and HiDPI scale factors other than 1.

## Automatic present mode

The three-way **Frame sync** (V-Sync, Low latency, Uncapped) is replaced by one **V-Sync** switch, off by default
(settings schema 5; migration: V-Sync stays on, the other two become off). With V-Sync off the client picks the present
mode for the platform: mailbox where the display has a real one (it never tears), immediate where it has none (AMD
on Windows), and immediate under XWayland (decision 0009). Each mode falls back to the other before FIFO.

Mailbox now uses at least four swapchain images. With three, a fullscreen window on COSMIC, which the compositor
scans out directly, fell back to FIFO pacing: 170 fps and 17.6 ms input to screen. With four: 557 fps and 9.0 ms;
five and six were no better (587 fps, 8.9 ms). Windowed, the image count made no difference.

## Paced V-Sync

Plain FIFO lets the game run ahead until the swapchain is full, and each frame then waits in the queue: 17.6 ms input
to screen (three refreshes at 170 Hz). Where VK_KHR_present_id2 and VK_KHR_present_wait2 are available (enabled when
the device and surface support them, with VK_KHR_get_surface_capabilities2 on the instance), `VsyncPacer` waits until
a presented frame reaches the screen, then sleeps until a learned margin before the next refresh and only then
samples input.

On COSMIC a frame started the moment the previous one is reported shown always lands a refresh late (85 fps): the
compositor latches the next frame right after a refresh, before that report arrives. The pacer detects this (every
frame missing at the largest margin) and keeps one frame queued instead. The margin shrinks until a frame misses
its refresh, then settles 1 ms above that point.

Without present wait (XWayland's FIFO accepts two frames per refresh, decision 0009), V-Sync under XWayland is held to
the display's refresh rate by the frame limiter.

## Results

| native Wayland | fps | input to screen, mean (p95) |
|---|---:|---:|
| in game, V-Sync off (mailbox) | 2,710 | 3.7 ms (6.5) |
| in game, V-Sync on (paced FIFO) | 170 | 11.8 ms (11.9) |
| title screen, V-Sync off (mailbox) | 1,952 | 4.3 ms (7.1) |
| title screen, V-Sync on, plain FIFO (before) | 170 | 17.6 ms (17.8) |
| title screen, V-Sync on, paced | 170 | 11.8 ms (11.9) |
| fullscreen, V-Sync off, 3 images (before) | 170 | 17.6 ms |
| fullscreen, V-Sync off, 4 images | 557 | 9.0 ms (12.2) |
| fullscreen, V-Sync on, paced | 170 | 11.8 ms |

Paced V-Sync is one refresh (5.8 ms) closer to the input than plain FIFO, at the same 170 fps. On this compositor
windowed play has the lowest latency, 3.7 to 4.3 ms. Fullscreen's direct scanout adds about a refresh.

## Hidden windows

Presenting to a minimized Wayland window under FIFO blocked for about a second per frame, which would also have
starved the 20 Hz game tick (the loop runs at most ten ticks per frame). While the window is minimized, hidden or
occluded the client now draws nothing and keeps ticking; checked in game, where server chat kept arriving each second
while minimized. Wayland cannot report un-minimizing, so drawing resumes on focus, exposure or the pointer entering.
The resume was not checked here: a Wayland client cannot un-minimize itself, so it needs a real click.
