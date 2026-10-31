# Architecture decision 0023: Hardened plugin hosting, and the music player

Status: accepted. Implemented 2026-10-31. Amends [decision 0022](0022-unrestricted-plugin-api-and-manifest.md)
(its first limit no longer holds) and fills the ClickGUI's Music section, which
[decision 0021](0021-page-morph-world-hand-off-notifications-and-plugins.md) left empty.

## Context

The user asked for two things:

1. Harden the plugin APIs.
2. Implement Music:
   - Play audio files: MP3, the audio of MP4, FLAC, AAC, Ogg, WAV and more.
   - Fetch audio from the Internet: Spotify, YouTube, SoundCloud and others.
   - Volume control, logarithmic.
   - Playlists.
   - Apple Music, if possible.

Decision 0022 stands: local plugins are unrestricted. "Hardening" here means containing faults and keeping the API's
contract stable, not limiting what plugins may do.

## Decision: plugin hosting

**A plugin's faults stay its own:**

- **Runs:** every hook belongs to one run of a plugin (one `PluginContext`), not to the plugin. This closed a bug
  where a late failure from a stopped run, such as a listener still in flight, could stop the restarted run.
- **Quarantine:** a run that throws is quarantined at once. Its other hooks stop firing before the UI thread gets
  round to stopping it, so a throwing tick listener reports one error, not twenty.
- **After stopping:** a stopped run's context is inactive (`PluginContext.active`). Registering throws, and actions do
  nothing.
- **Recursion:** endless recursion (`StackOverflowError`) stops only the plugin. Other `VirtualMachineError`s still
  end the client.
- **API mismatch:** linkage errors are reported as "built against a different plugin API or library?".

**The interface survives a plugin's composable.** Decision 0022's first limit said a throwing plugin composable broke
the whole interface. Now:

- `ComposeLayerHost` catches exceptions from composition, layout, drawing, input and effects. Effects are routed
  through a `CoroutineExceptionHandler` on the scene; before, they failed the recomposer silently.
- `PluginHost.recover` finds the plugin in the stack trace by its class loader's name (`plugin:<id>`), stops it, and
  the host rebuilds the scene.
- State kept outside the composition survives, such as the open page and the Music tab. `remember`ed state does not.
- An interface failure with no plugin in its trace is rebuilt too, with an "Interface restarted" notification. During
  testing a Compose-internal layout race ("LayoutNode not found in RectList", once while flinging a list) would have
  ended the game; it no longer can.
- After five rebuilds in a minute the host gives up as before.

**A watchdog for stuck plugins.** Java cannot stop a thread safely, so a plugin in an endless loop cannot be
killed, but it no longer freezes the game silently:

- Each hook call is timed. A call over 50 ms is logged as a late frame, at most once every 10 seconds per plugin.
- The card shows the plugin's time in hooks per second and its slowest call.
- A watchdog thread reports a call that has held a thread for 2 seconds: a notification, and the stuck thread's stack
  in the log. When the call returns, its duration is logged.

**Jars load from copies** in `plugins/.loaded/`. A jar can then be replaced and reloaded while the client runs.
Windows locks open jars, and replacing a jar in place under an open class loader corrupts lazily loaded classes on any
system. A jar that changed after its manifest was read asks for a Reload instead of starting with the old manifest.

**Flood control:**

- Logs: at most 40 lines a second per plugin reach the console and the card; the rest are counted.
- Notifications: a repeat within 4 seconds is merged into the one showing. At most 3 show per window; the rest go to
  the log.

**Arguments are checked at the call:**

- Chat: 1 to 100 characters, not blank, no `§` or control characters (the server kicks for them).
- Angles, positions and volumes must be finite. A NaN in movement input becomes 0 instead of reaching the server.
- Raw packets: serverbound ids 0x00 to 0x19, with the server's size limit.
- Byte arrays passed to `sendMessage` and `sendPacket` are copied when the call is made.
- An icon is measured by the bytes actually read, not by the zip directory's claim.

**The API's binary interface is checked on every build.** `:modules:plugin-api` enables the Kotlin Gradle plugin's
ABI validation (`abiValidation()`, Kotlin 2.4.21). `check` runs `checkKotlinAbi` against
`modules/plugin-api/api/plugin-api.api`, so a change that would break built plugins fails the build until the dump is
updated on purpose (`updateKotlinAbi`). The documented rules for plugin authors:

- Snapshots and enums may grow.
- Read by name, and give a `when` an `else` branch.
- Do not construct snapshots or call `copy` or `componentN`.
- Only `alkaline.plugin` is the API.

**Plugins can reach the music player:** `context.music`. It offers the current track, playing, position and volume,
`play` and `enqueue` of a path, a link or a search, the controls, and `onTrack`.

## Decision: the music player

**Decoding: FFmpeg, bundled.** `:modules:music` decodes with FFmpeg 8.1.2 through JavaCPP 1.5.14
(`org.bytedeco:ffmpeg`, the LGPL build, released August 2026, natives for linux-x86_64 and windows-x86_64).

- **Why bundled:** one decoder covers every listed format and network streams on both first-class platforms with
  nothing to install. Pure-Java decoders would need several libraries, and none decodes AAC well. A system ffmpeg is
  rare on Windows.
- **Formats verified:** MP3, AAC (ADTS, and MP4/M4A), ALAC, FLAC, Ogg Vorbis, Opus (Ogg and WebM), WAV and AIFF all
  decode to their exact lengths (`DecoderTest.probeFolder`).
- **Network:** HTTP(S), HLS and Icecast streams, including the radio's current title (ICY).
- **Tags:** title, artist and album, plus embedded cover art.
- **Normalization:** ReplayGain, or R128 for Opus, is applied with peak protection, on by default and switchable.
- **Cancellation:** FFmpeg's interrupt callback lets a skip or a closing client stop a slow network read.

**Output: a music feed in the audio engine.** `AudioEngine.music()`:

- One OpenAL source beside the game's voices, outside their pools and untouched by resource pack reloads.
- The player writes 48 kHz stereo PCM; the audio thread moves it into eight 50 ms buffers.
- Every track is resampled to that one format, so tracks written back to back play without a gap.
- `flush` drops what has not been heard, for seeks and skips, and restarts the feed's frame clock.
- The position shown is that clock: frames OpenAL has played.
- The master volume applies as to every sound; the player's own volume is the source's gain.

**The player** (`MusicPlayer`) runs on its own thread:

- **Responsiveness:** commands are queued, so a key press or the interface never waits. Opening a track (a disk read,
  a yt-dlp call, a server's first bytes) runs on helper threads.
- **Gapless:** the next track is opened 15 seconds before the current one ends.
- **Seeking:** in place, sample-accurate.
- **Queue:** shuffle (of the tracks after the current one, reversible) and repeat (off, all, one).
- **Failures:** a track that cannot open is skipped, five times in a row at most. A missing program (yt-dlp) stops
  at once with one message instead of failing every track the same way.
- **Persistence:** the queue, volume, shuffle, repeat and folders are kept in `music/state.json`.

**Volume is the slider's cube**, the curve PulseAudio uses for its software volume:

- Half way is about -18 dB and a tenth is -60 dB, so the slider's travel matches how loudness is heard.
- The label shows the gain in dB.
- The speaker button mutes and restores.

**Sources** (`Sources.find`, `Sources.search`):

- **Files and folders:** recursive, sorted. M3U, M3U8 and PLS playlists play too, and playlists export as M3U8.
- **The library:** the music folders (the platform's Music folder by default, more can be added). Tags are read with
  FFmpeg and cached in `music/library.tsv` by path, size and modification time, so a rescan only reads new and
  changed files.
- **Streams:** a URL with an audio or playlist extension, or any URL when yt-dlp is missing.
- **Sites:** YouTube, SoundCloud, Bandcamp and the other sites yt-dlp supports, including playlists and channels.
  Searching covers YouTube and SoundCloud. yt-dlp is an external program the player installs. It is not bundled: it
  changes with the sites weekly and is better updated by its own means.
  - Alkaline finds it on the `PATH` or in `tools/`.
  - The interface is its documented output template, `-O "%(.{field,...})j"`. It prints only the needed fields, one
    JSON object per entry, and runs after format selection, so the stream URL and headers come with it. That keeps
    every answer far under the JSON reader's 1 MiB limit, long playlists included.
  - Formats FFmpeg streams are preferred (progressive HTTP, then HLS).
  - YouTube needs a JavaScript runtime beside yt-dlp: Deno by default
    ([yt-dlp's EJS guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS)). When only Node.js is installed, Alkaline passes
    `--js-runtimes node`.
- **Spotify and Apple Music:** their audio is DRM-protected, and playing it outside their own
  apps is not possible or permitted. Alkaline reads their links for what the music is and plays the same recording
  found through yt-dlp.
  - Spotify tracks, albums and playlists come from the page Spotify serves for embedding a link (its
    `__NEXT_DATA__`), which needs no account or developer key.
  - Apple Music songs and albums come from Apple's public iTunes lookup API. Playlists come from the schema.org
    description on their page, with artists filled in by one batched lookup.
  - The recording is chosen among the first six YouTube results for "artist - title audio". Its title must contain
    the track's, and the closest duration wins.
  - Apple Music search uses the iTunes search API and works without yt-dlp. Playing its results needs yt-dlp.

**Terms of service.** Streaming YouTube's or SoundCloud's audio outside their players is against those sites' terms,
as with every yt-dlp-based player. The feature only runs yt-dlp when the player has installed it, and the docs say so.
Spotify's and Apple's pages and APIs are read for metadata only.

**Interface:**

- **The ClickGUI's Music section:**
  - A now-playing card: cover, title, artist (or the radio's title), shuffle, previous, a primary play/pause, next,
    repeat, a seek bar, volume, and an option to even out loudness.
  - Four tabs:
    - Queue: reorder, remove, save as a playlist.
    - Playlists: play, shuffle, rename, export, delete, reorder.
    - Library: filter, play all, shuffle, folders, rescan.
    - Search: catalog choice, or a pasted link or path.
  - Every track's menu offers play next, add to queue and add to a playlist.
  - Without yt-dlp, a notice says what needs it and links to its installation guide.
- **A "Now playing" HUD module** (cover option, title, artist, progress), placed in the HUD editor like the others.
- **Key bindings:** Controls → Music has play/pause, next and previous, unbound by default. The keyboard's media keys
  always work.

**Apple Music, beyond its links:** an Apple developer token (a paid program) and DRM rule out MusicKit playback in
the client. Controlling the Apple Music or Spotify desktop apps through the OS (MPRIS on Linux, SMTC on Windows) would
be legitimate; it is not built, since Windows' WinRT media API needs native interop that could not be tested here.

## Verification

Unit tests:

- **Decoder:** rate and channels, sample-accurate seeks, failures, and probing. Every format above decodes from
  files made by a local ffmpeg.
- **Player**, through a test output with a hand-moved clock:
  - Tracks are written back to back with one flush.
  - The heard track follows the clock.
  - Repeat one and repeat all.
  - A missing file is skipped with its error kept.
  - In-place seek, pause and resume.
  - Queue edits while playing.
  - The cubic volume and its persistence.
- **Sources:**
  - Catalog link recognition.
  - Recording choice by title and duration.
  - Stream detection.
  - M3U and PLS round trips.
  - Folders.
  - yt-dlp's interface through a stand-in script (field-selected JSON lines, errors from standard error).
- **Library:** playlist round trips and scan caching.
- **Online** (`-Dalkaline.music.online=true`):
  - A Spotify track, and a 50-track Spotify playlist with artists and durations.
  - An Apple Music song, a 31-song album, and a 50-song playlist with artists filled in.
  - Apple Music search.
- **Plugin host:**
  - Quarantine before stopping.
  - A stale run cannot stop a restarted one.
  - Recursion is contained.
  - Interface failures are blamed through the class loader name.
  - Jars load from copies, and a changed jar asks for a Reload.
  - Log and notification flood control.
  - Argument checks.
  - The watchdog reports a 2.6 s listener, which returns and is logged.
  - A plugin follows the player through `context.music`.
- **The plugin API's ABI** is checked by `check`.

In game, against a fresh local 1.8.9 server world, at 1280x760:

- **Library:** the user's `~/Music` and a folder of test tones were scanned: 18 tracks, 11:20.
- **Playing a file:** "This One Here.m4a" played; the clock advanced with the device.
- **Unicode path:** a FLAC pasted by a path with CJK characters played with its embedded cover art and tags.
- **Seeking:** a click on the seek bar jumped to 3:22.
- **HUD:** "Now playing" showed the cover and title, with no change in frame rate (about 3,500 FPS).
- **Media keys:** play/pause started and paused the restored queue.
- **Spotify without yt-dlp:** a Spotify playlist link listed its 50 tracks. Playing one explained that yt-dlp is
  needed.
- **Crash recovery:** a test plugin whose overlay threw after `/crash` was stopped by its class loader name. The game
  went on, a "Plugin stopped" notification showed, the interface was rebuilt, and the plugin's card read "Stopped
  after an error: overlay test crash (in its interface)".

Not verified here:

- yt-dlp playback: YouTube, SoundCloud, and Spotify or Apple Music matching. The sandbox did not allow running a
  downloaded yt-dlp binary, so that path is tested only against the stand-in script.
- Windows.

## Limits

- yt-dlp is not bundled and must be installed (with Deno or Node for YouTube).
- Spotify playlists list the tracks Spotify's embed page includes.
- The library is one flat list (filterable). There are no artist or album views, and no drag-to-reorder: menus
  move tracks.
- No equalizer and no crossfade.
- The OS's media controls do not see Alkaline's player, and Alkaline does not control other players.
- A plugin's code that loops forever still holds its thread; the watchdog reports it but cannot stop it.
