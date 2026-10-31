package alkaline.ui.music

import alkaline.music.Catalog
import alkaline.music.MusicPlayer
import alkaline.music.Origin
import alkaline.music.Playlist
import alkaline.music.Repeat
import alkaline.music.Track
import alkaline.music.YtDlp
import alkaline.music.formatTime
import alkaline.ui.menu.MenuController
import alkaline.ui.menu.PillField
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The ClickGUI's Music section: what plays now (cover, title, the controls, where it is, the volume) over four
 * tabs: the queue, saved playlists, the library of the music folders, and search (YouTube, SoundCloud, Apple
 * Music; a link or a path read in place).
 */
@Composable
internal fun MusicSection(music: MusicController, controller: MenuController) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NowPlaying(music)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            for ((index, tab) in MusicTab.entries.withIndex()) {
                SegmentedButton(
                    selected = music.tab == tab,
                    onClick = { music.tab = tab },
                    shape = SegmentedButtonDefaults.itemShape(index, MusicTab.entries.size),
                    icon = {},
                ) { Text(tab.label) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (music.tab) {
                MusicTab.QUEUE -> QueueTab(music)
                MusicTab.PLAYLISTS -> PlaylistsTab(music)
                MusicTab.LIBRARY -> LibraryTab(music)
                MusicTab.SEARCH -> SearchTab(music, controller)
            }
        }
    }
}

// ---- now playing ------------------------------------------------------------------------------------------------

@Composable
private fun NowPlaying(music: MusicController) {
    val state = music.state
    val player = music.player
    val track = state.track
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerHigh.copy(alpha = 0.62f)) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(state.cover, 60.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(track?.title ?: "Nothing playing", style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = when {
                        state.status == MusicPlayer.Status.LOADING -> "Loading…"
                        state.liveTitle != null -> state.liveTitle
                        track != null -> listOfNotNull(track.artist, track.album).joinToString(" · ").ifEmpty { track.byline }
                        else -> "Search, paste a link, or play from your library."
                    }
                    Text(detail ?: "", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    state.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Controls(music)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Progress(player, track, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                VolumeControl(music)
                Options(music)
            }
        }
    }
}

@Composable
private fun Controls(music: MusicController) {
    val state = music.state
    val player = music.player
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Toggle(AlkalineIcons.Shuffle, "Shuffle", state.shuffle) { player.shuffle(!state.shuffle) }
        IconButton(onClick = { player.previous() }) { Icon(AlkalineIcons.Previous, "Previous", Modifier.size(22.dp)) }
        FilledIconButton(
            onClick = { player.toggle() },
            modifier = Modifier.size(56.dp),
            shape = if (state.playing) RoundedCornerShape(16.dp) else CircleShape,
        ) {
            Icon(if (state.playing) AlkalineIcons.Pause else AlkalineIcons.Play, if (state.playing) "Pause" else "Play",
                Modifier.size(26.dp))
        }
        IconButton(onClick = { player.next() }) { Icon(AlkalineIcons.Next, "Next", Modifier.size(22.dp)) }
        val next = when (state.repeat) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            Repeat.ONE -> Repeat.OFF
        }
        Toggle(if (state.repeat == Repeat.ONE) AlkalineIcons.RepeatOne else AlkalineIcons.Repeat,
            when (state.repeat) {
                Repeat.OFF -> "Repeat"
                Repeat.ALL -> "Repeating the queue"
                Repeat.ONE -> "Repeating this track"
            }, state.repeat != Repeat.OFF) { player.repeat(next) }
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (on) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        ),
    ) { Icon(icon, label, Modifier.size(20.dp)) }
}

/** Where the track is; dragging previews the time and seeks on release. */
@Composable
private fun Progress(player: MusicPlayer, track: Track?, modifier: Modifier) {
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player.positionMillis()
            delay(250)
        }
    }
    val duration = track?.durationMillis
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val shown = dragging?.let { (it * (duration ?: 0)).toLong() } ?: position
        TimeLabel(formatTime(shown))
        if (duration != null && duration > 0) {
            Slider(
                value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seek((it * duration).toLong()) }
                    dragging = null
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TimeLabel(formatTime(duration))
        } else {
            Spacer(Modifier.weight(1f))
            if (track != null) TimeLabel("Live")
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(min = 40.dp))
}

/** The volume on a cubic curve (the label shows the gain in dB); the speaker mutes and restores. */
@Composable
private fun VolumeControl(music: MusicController) {
    val volume = music.state.volume
    var dragging by remember { mutableStateOf<Float?>(null) }
    var beforeMute by remember { mutableFloatStateOf(0.6f) }
    val shown = dragging ?: volume
    IconButton(onClick = {
        if (volume > 0f) {
            beforeMute = volume
            music.player.volume(0f)
        } else music.player.volume(beforeMute.coerceAtLeast(0.05f))
    }) {
        Icon(if (shown <= 0f) AlkalineIcons.Muted else AlkalineIcons.Volume, if (shown <= 0f) "Unmute" else "Mute",
            Modifier.size(20.dp))
    }
    Slider(
        value = shown,
        onValueChange = {
            dragging = it
            music.player.volume(it)
        },
        onValueChangeFinished = { dragging = null },
        modifier = Modifier.width(140.dp),
    )
    val decibels = MusicPlayer.decibels(shown)
    Text(if (decibels == null) "Muted" else String.format(Locale.ROOT, "%.0f dB", decibels),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(min = 48.dp).padding(start = 8.dp))
}

@Composable
private fun Options(music: MusicController) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AlkalineIcons.More, "Options", Modifier.size(20.dp)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Even out loudness") },
                trailingIcon = { Switch(checked = music.state.normalize, onCheckedChange = null) },
                onClick = { music.player.normalize(!music.state.normalize) },
            )
            Text("Uses ReplayGain tags where files have them.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 240.dp))
        }
    }
}

@Composable
private fun Cover(bytes: ByteArray?, size: Dp) {
    val bitmap: ImageBitmap? = remember(bytes) {
        bytes?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
    }
    val shape = RoundedCornerShape(size / 6)
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) {
            Icon(AlkalineIcons.Music, null, Modifier.size(size / 2.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- lists ------------------------------------------------------------------------------------------------------

/** One track: title and byline, its length, and a menu of what can be done with it. */
@Composable
private fun TrackRow(
    track: Track,
    current: Boolean = false,
    onClick: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (current) scheme.secondaryContainer.copy(alpha = 0.8f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) scheme.onSecondaryContainer else scheme.onSurface)
            Text(listOfNotNull(track.artist, track.album, track.origin.takeIf { it != Origin.FILE }?.label).joinToString(" · ")
                .ifEmpty { track.byline },
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        track.durationMillis?.let { TimeLabel(formatTime(it)) }
        Box {
            IconButton(onClick = { open = true }) { Icon(AlkalineIcons.More, "More", Modifier.size(18.dp)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

/** The menu items every track offers: play next, add to the queue, add to a playlist (or a new one). */
@Composable
private fun TrackActions(music: MusicController, tracks: List<Track>, close: () -> Unit) {
    DropdownMenuItem(text = { Text("Play next") }, onClick = { music.player.enqueue(tracks, next = true); close() })
    DropdownMenuItem(text = { Text("Add to queue") }, onClick = { music.player.enqueue(tracks); close() })
    HorizontalDivider()
    for (playlist in music.playlists) {
        DropdownMenuItem(text = { Text("Add to ${playlist.name}") }, onClick = { music.addToPlaylist(playlist, tracks); close() })
    }
    DropdownMenuItem(text = { Text("New playlist…") }, onClick = {
        music.createPlaylist(tracks.singleOrNull()?.title ?: "Playlist", tracks)
        close()
    })
}

@Composable
private fun Header(text: String, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
private fun Empty(title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AlkalineIcons.Music, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp))
        action()
    }
}

private fun summary(tracks: List<Track>): String {
    val known = tracks.mapNotNull { it.durationMillis }
    val total = if (known.isEmpty()) "" else " · ${formatTime(known.sum())}"
    return "${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}$total"
}

// ---- tabs -------------------------------------------------------------------------------------------------------

@Composable
private fun QueueTab(music: MusicController) {
    val state = music.state
    if (state.queue.isEmpty()) {
        Empty("The queue is empty", "Play something from Search, Library or Playlists.") {
            TextButton(onClick = { music.tab = MusicTab.SEARCH }) { Text("Search") }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Header(summary(state.queue.map { it.track })) {
                TextButton(onClick = { music.createPlaylist("Queue", state.queue.map { it.track }) }) { Text("Save as playlist") }
                TextButton(onClick = { music.player.clearQueue() }) { Text("Clear") }
            }
        }
        itemsIndexed(state.queue, key = { _, entry -> entry.id }) { index, entry ->
            TrackRow(entry.track, current = index == state.current, onClick = { music.player.playEntry(entry.id) }) { close ->
                if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { music.player.move(entry.id, index - 1); close() })
                if (index < state.queue.lastIndex) {
                    DropdownMenuItem(text = { Text("Move down") }, onClick = { music.player.move(entry.id, index + 1); close() })
                }
                DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { music.player.remove(entry.id); close() })
                HorizontalDivider()
                for (playlist in music.playlists) {
                    DropdownMenuItem(text = { Text("Add to ${playlist.name}") },
                        onClick = { music.addToPlaylist(playlist, listOf(entry.track)); close() })
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(music: MusicController) {
    var openId by remember { mutableStateOf<String?>(null) }
    val open = music.playlists.firstOrNull { it.id == openId }
    if (open != null) {
        PlaylistView(music, open) { openId = null }
        return
    }
    if (music.playlists.isEmpty()) {
        Empty("No playlists yet", "Save the queue as one, or add tracks to a new playlist from any track's menu. " +
            "An M3U or PLS file pasted into Search plays too.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        itemsIndexed(music.playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f)) {
                Row(Modifier.fillMaxWidth().clickable { openId = playlist.id }.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(summary(playlist.tracks), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { music.player.play(playlist.tracks) }, enabled = playlist.tracks.isNotEmpty()) {
                        Icon(AlkalineIcons.Play, "Play ${playlist.name}", Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistView(music: MusicController, playlist: Playlist, back: () -> Unit) {
    var renaming by remember(playlist.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(playlist.id) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(AlkalineIcons.Back, "Back", Modifier.size(20.dp)) }
                val name = renaming
                if (name != null) {
                    PillField(name, { renaming = it }, "Name", AlkalineIcons.Edit, Modifier.weight(1f),
                        onSubmit = {
                            music.renamePlaylist(playlist, name)
                            renaming = null
                        })
                } else {
                    Text(playlist.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { music.player.play(playlist.tracks) }, enabled = playlist.tracks.isNotEmpty()) { Text("Play") }
                TextButton(onClick = {
                    music.player.shuffle(true)
                    music.player.play(playlist.tracks, (playlist.tracks.indices).randomOrNull() ?: 0)
                }, enabled = playlist.tracks.isNotEmpty()) { Text("Shuffle") }
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(AlkalineIcons.More, "More", Modifier.size(20.dp)) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false; confirmDelete = false }) {
                        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { music.player.enqueue(playlist.tracks); open = false })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { renaming = playlist.name; open = false })
                        DropdownMenuItem(text = { Text("Export as M3U") }, onClick = { music.exportPlaylist(playlist); open = false })
                        DropdownMenuItem(
                            text = { Text(if (confirmDelete) "Delete for good?" else "Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                if (confirmDelete) {
                                    music.deletePlaylist(playlist)
                                    open = false
                                    back()
                                } else confirmDelete = true
                            },
                        )
                    }
                }
            }
            Header(summary(playlist.tracks))
        }
        itemsIndexed(playlist.tracks, key = { index, track -> "$index ${track.location}" }) { index, track ->
            TrackRow(track, onClick = { music.player.play(playlist.tracks, index) }) { close ->
                if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { music.movePlaylistTrack(playlist, index, index - 1); close() })
                if (index < playlist.tracks.lastIndex) {
                    DropdownMenuItem(text = { Text("Move down") }, onClick = { music.movePlaylistTrack(playlist, index, index + 1); close() })
                }
                DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { music.removeFromPlaylist(playlist, index); close() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Play next") }, onClick = { music.player.enqueue(listOf(track), next = true); close() })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { music.player.enqueue(listOf(track)); close() })
            }
        }
    }
}

@Composable
private fun LibraryTab(music: MusicController) {
    var filter by remember { mutableStateOf("") }
    var folderInput by remember { mutableStateOf<String?>(null) }
    val needle = filter.trim().lowercase(Locale.ROOT)
    val shown = if (needle.isEmpty()) music.library else music.library.filter { track ->
        listOfNotNull(track.title, track.artist, track.album).any { needle in it.lowercase(Locale.ROOT) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillField(filter, { filter = it }, "Filter the library", AlkalineIcons.Search, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { music.player.play(shown) }, enabled = shown.isNotEmpty()) { Text("Play all") }
            TextButton(onClick = {
                music.player.shuffle(true)
                music.player.play(shown, shown.indices.randomOrNull() ?: 0)
            }, enabled = shown.isNotEmpty()) { Text("Shuffle") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (folder in music.state.folders) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AlkalineIcons.Folder, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(folder.toString().replace(System.getProperty("user.home"), "~"), style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                        IconButton(onClick = { music.removeFolder(folder) }, modifier = Modifier.size(32.dp)) {
                            Icon(AlkalineIcons.Close, "Stop reading $folder", Modifier.size(14.dp))
                        }
                    }
                }
            }
            val input = folderInput
            if (input == null) {
                TextButton(onClick = { folderInput = "" }) { Text("Add folder") }
            } else {
                PillField(input, { folderInput = it }, "Folder path, then Enter", AlkalineIcons.Folder, Modifier.weight(1f),
                    onSubmit = { if (music.addFolder(input)) folderInput = null })
            }
            if (input == null) Spacer(Modifier.weight(1f))
            val progress = music.scanProgress
            Text(if (progress != null) "Reading ${progress.first} of ${progress.second}…" else summary(music.library),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { music.scan() }, enabled = progress == null) { Text("Rescan") }
        }
        if (music.library.isEmpty()) {
            Empty(if (music.scanProgress != null) "Reading your music…" else "No music found",
                "Alkaline plays the audio files in these folders: MP3, AAC and M4A, FLAC, ALAC, Ogg, Opus, WAV, AIFF and more.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(shown, key = { _, track -> track.location }) { index, track ->
                    TrackRow(track, current = track.location == music.state.track?.location, onClick = { music.player.play(shown, index) }) { close ->
                        TrackActions(music, listOf(track), close)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTab(music: MusicController, controller: MenuController) {
    val results = music.results
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillField(music.query, { music.query = it }, "Search, or paste a link or a file path",
                if (MusicController.looksLikeLink(music.query)) AlkalineIcons.Link else AlkalineIcons.Search,
                Modifier.weight(1f), onSubmit = { music.submit() }) {
                if (music.query.isNotEmpty()) {
                    IconButton(onClick = {
                        music.query = ""
                        music.clearResults()
                    }) { Icon(AlkalineIcons.Close, "Clear", Modifier.size(18.dp)) }
                }
            }
        }
        if (!MusicController.looksLikeLink(music.query)) {
            SingleChoiceSegmentedButtonRow {
                for ((index, catalog) in Catalog.entries.withIndex()) {
                    SegmentedButton(
                        selected = music.catalog == catalog,
                        onClick = {
                            music.catalog = catalog
                            if (music.query.isNotBlank()) music.submit()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, Catalog.entries.size),
                        icon = {},
                    ) { Text(catalog.label) }
                }
            }
        }
        val missing = music.ytDlpVersion == ""
        when {
            music.busy != null -> Header(music.busy!!)
            results != null -> Header(
                (results.title?.let { "$it · " } ?: "") + summary(results.tracks),
            ) {
                TextButton(onClick = { music.player.play(results.tracks) }, enabled = results.tracks.isNotEmpty()) { Text("Play all") }
                TextButton(onClick = { music.player.enqueue(results.tracks) }, enabled = results.tracks.isNotEmpty()) { Text("Add all") }
                if (results.link && results.tracks.size > 1) {
                    TextButton(onClick = { music.createPlaylist(results.title ?: "Playlist", results.tracks) }) { Text("Save as playlist") }
                }
            }
        }
        if (results != null && music.busy == null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (missing && results.tracks.any { it.origin != Origin.FILE && it.origin != Origin.STREAM }) {
                    item(key = "yt-dlp") { YtDlpNotice(controller) }
                }
                itemsIndexed(results.tracks, key = { index, track -> "$index ${track.location}" }) { index, track ->
                    TrackRow(track, current = track.location == music.state.track?.location,
                        onClick = { if (results.link) music.player.play(results.tracks, index) else music.player.play(listOf(track)) }) { close ->
                        TrackActions(music, listOf(track), close)
                    }
                }
            }
        } else if (music.busy == null) {
            Box(Modifier.weight(1f)) {
                Empty("Find something to play",
                    "Search YouTube, SoundCloud or Apple Music, or paste a link: YouTube, SoundCloud, Bandcamp, Spotify and Apple " +
                        "Music tracks, albums and playlists, a radio stream, an audio file's URL, or a file or folder on this computer.") {
                    if (missing) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.widthIn(max = 520.dp)) { YtDlpNotice(controller) }
                    }
                }
            }
        }
    }
}

/** yt-dlp is missing: what needs it, and where to get it. */
@Composable
private fun YtDlpNotice(controller: MenuController) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
        modifier = Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("YouTube, SoundCloud, Spotify and Apple Music play through yt-dlp, which is not installed. Files, " +
                "streams and Apple Music search work without it.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.actions.openUrl(YtDlp.HOMEPAGE) }) { Text("Get yt-dlp") }
        }
    }
}
