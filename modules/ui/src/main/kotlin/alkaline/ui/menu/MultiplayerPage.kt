package alkaline.ui.menu

import alkaline.client.servers.ServerAddress
import alkaline.client.servers.ServerEntry
import alkaline.client.servers.ServerList
import alkaline.client.servers.ServerStatus
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/**
 * Multiplayer. One field does three jobs vanilla splits across screens: it searches saved servers, and when
 * it holds an address that is not saved yet, a live card pings it (direct connect) with Save beside it. Saved
 * servers are cards with favicon, formatted MOTD, players and latency; the selected card opens its actions in
 * place (Join, reorder, edit, remove) instead of a row of buttons under the list.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MultiplayerPage(controller: MenuController) {
    val services = controller.services
    val list = services.servers()
    var servers by remember { mutableStateOf(list.servers()) }
    DisposableEffect(list) {
        val listener = ServerList.Listener { servers = it }
        list.addListener(listener)
        servers = list.servers()
        onDispose { list.removeListener(listener) }
    }
    // Address -> status; a present key with a null value means a ping is in flight.
    val statuses = remember { mutableStateMapOf<String, ServerStatus?>() }
    val scope = rememberCoroutineScope()
    fun ping(address: String) {
        if (statuses.containsKey(address) && statuses[address] == null) return
        statuses[address] = null
        scope.launch { statuses[address] = services.pinger().ping(address).await() }
    }
    LaunchedEffect(Unit) { list.servers().forEach { ping(it.address()) } }

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ServerEntry?>(null) }
    var editing by remember { mutableStateOf<ServerEntry?>(null) }
    val trimmed = query.trim()
    val direct = trimmed.takeIf(::looksLikeAddress)
        ?.takeIf { address -> servers.none { it.address().equals(address, ignoreCase = true) } }
    LaunchedEffect(direct) {
        if (direct != null) {
            delay(450) // wait for typing to settle before touching the network
            ping(direct)
        }
    }
    val shown = remember(servers, trimmed) {
        if (trimmed.isEmpty()) servers else servers.filter {
            it.name().contains(trimmed, true) || it.address().contains(trimmed, true)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackHeader(controller, "Multiplayer")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { statuses.clear(); servers.forEach { ping(it.address()) }; direct?.let(::ping) }) {
                    Icon(AlkalineIcons.Refresh, "Refresh")
                }
            }
            controller.playError?.let { reason ->
                Text(
                    "Disconnected from ${controller.playAddress}: $reason",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            PillField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search servers or enter an address",
                icon = AlkalineIcons.Search,
                modifier = Modifier.fillMaxWidth(),
                onSubmit = { if (direct != null) { list.add(ServerEntry("", direct)); query = "" } },
            ) {
                if (direct != null) {
                    FilledTonalButton(onClick = { list.add(ServerEntry("", direct)); query = "" }) {
                        Icon(AlkalineIcons.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (direct != null) {
                    item(key = "direct") {
                        val entry = ServerEntry("", direct)
                        ServerCard(
                            entry = entry, status = statuses[direct], pinging = statuses.containsKey(direct) && statuses[direct] == null,
                            selected = true, unsaved = true,
                            onClick = {},
                            actions = { ServerActions(entry, unsaved = true, list = list, onJoin = controller::join, onEdit = {}, onSaved = { query = "" }) },
                        )
                    }
                }
                items(shown, key = { "${it.name()}\u0000${it.address()}" }) { entry ->
                    if (editing == entry) {
                        EditCard(entry, onDone = { updated ->
                            if (updated != null && updated != entry) {
                                list.replace(entry, updated)
                                if (updated.address() != entry.address()) ping(updated.address())
                                selected = updated
                            }
                            editing = null
                        })
                    } else {
                        ServerCard(
                            entry = entry,
                            status = statuses[entry.address()],
                            pinging = statuses.containsKey(entry.address()) && statuses[entry.address()] == null,
                            selected = selected == entry,
                            unsaved = false,
                            onClick = { selected = if (selected == entry) null else entry },
                            actions = {
                                ServerActions(entry, unsaved = false, list = list, onJoin = controller::join, onEdit = { editing = entry }, onSaved = {})
                            },
                        )
                    }
                }
                if (shown.isEmpty() && direct == null) {
                    item(key = "empty") { EmptyServers(trimmed.isNotEmpty()) }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    entry: ServerEntry,
    status: ServerStatus?,
    pinging: Boolean,
    selected: Boolean,
    unsaved: Boolean,
    onClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val (container, content) = hoverColors(hovered && !selected, selected)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = container.value,
        contentColor = content.value,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Favicon(status)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (unsaved) entry.address() else entry.name(),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (unsaved) {
                            Spacer(Modifier.width(8.dp))
                            Tag("Not saved", MaterialTheme.colorScheme.tertiary)
                        }
                        if (status != null && status.online && !status.compatible()) {
                            Spacer(Modifier.width(8.dp))
                            Tag(status.versionName().ifBlank { "Other version" }, MaterialTheme.colorScheme.error)
                        }
                    }
                    StatusLine(status, pinging, content.value, highlighted = hovered && !selected)
                }
                Spacer(Modifier.width(12.dp))
                Vitals(status, pinging, content.value)
            }
            AnimatedVisibility(selected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(Modifier.padding(top = 12.dp)) { actions() }
            }
        }
    }
}

@Composable
private fun StatusLine(status: ServerStatus?, pinging: Boolean, content: Color, highlighted: Boolean) {
    val scheme = MaterialTheme.colorScheme
    when {
        status == null -> Text(
            if (pinging) "Pinging…" else " ", style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.7f),
        )
        !status.online() -> Text(
            "Can't reach the server · ${status.error()}", style = MaterialTheme.typography.bodySmall,
            // Error red loses contrast on the bright hover container; the card's own text color keeps it legible.
            color = if (highlighted) content else scheme.error,
        )
        else -> {
            val dark = scheme.surface.luminance() < 0.5f
            val motd = remember(status.motd(), content, dark) {
                formatLegacy(status.motd(), content, content.copy(alpha = 0.72f), dark)
            }
            Text(motd, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Vitals(status: ServerStatus?, pinging: Boolean, content: Color) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pinging && status == null) {
            LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary)
            return@Column
        }
        if (status == null || !status.online()) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (status.latencyMillis() >= 0) "${status.latencyMillis()} ms" else "—",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = content.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(8.dp))
            SignalBars(status.latencyMillis())
        }
        Text(
            "${status.playersOnline()} / ${status.playersMax()}",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
        )
    }
}

/** Five bars like vanilla's thresholds: 150, 300, 600 and 1000 ms. */
@Composable
private fun SignalBars(latency: Long) {
    val bars = when {
        latency < 0 -> 0
        latency < 150 -> 5
        latency < 300 -> 4
        latency < 600 -> 3
        latency < 1000 -> 2
        else -> 1
    }
    val on = if (bars <= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
        val gap = 1.dp.toPx()
        val width = (size.width - gap * 4) / 5
        for (index in 0 until 5) {
            val height = size.height * (index + 1) / 5f
            drawRoundRect(
                color = if (index < bars) on else off,
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 3),
            )
        }
    }
}

@Composable
private fun Favicon(status: ServerStatus?) {
    val shape = RoundedCornerShape(14.dp)
    val bytes = status?.favicon()
    val bitmap = remember(bytes) { bytes?.let(::pngBitmap) }
    if (bitmap != null) {
        PixelImage(bitmap, 56.dp, shape)
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(AlkalineIcons.Globe, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.16f), contentColor = color) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Join is the one primary action; saved servers add reorder, edit and remove, unsaved ones Save. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerActions(
    entry: ServerEntry,
    unsaved: Boolean,
    list: ServerList,
    onJoin: (String) -> Unit,
    onEdit: () -> Unit,
    onSaved: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onJoin(entry.address()) }) {
            Icon(AlkalineIcons.Play, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Join")
        }
        Spacer(Modifier.weight(1f))
        if (unsaved) {
            FilledTonalButton(onClick = { list.add(entry); onSaved() }) {
                Icon(AlkalineIcons.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        } else {
            IconButton(onClick = { list.move(entry, -1) }) { Icon(AlkalineIcons.ChevronUp, "Move up") }
            IconButton(onClick = { list.move(entry, 1) }) { Icon(AlkalineIcons.ChevronDown, "Move down") }
            IconButton(onClick = onEdit) { Icon(AlkalineIcons.Edit, "Edit") }
            IconButton(onClick = { list.remove(entry) }) {
                Icon(AlkalineIcons.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EditCard(entry: ServerEntry, onDone: (ServerEntry?) -> Unit) {
    var name by remember { mutableStateOf(entry.name()) }
    var address by remember { mutableStateOf(entry.address()) }
    var packs by remember { mutableStateOf(entry.packs()) }
    val valid = ServerAddress.isValid(address)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillField(name, { name = it }, "Name", AlkalineIcons.Edit, Modifier.fillMaxWidth())
            PillField(address, { address = it }, "Address", AlkalineIcons.Globe, Modifier.fillMaxWidth(),
                onSubmit = { if (valid) onDone(ServerEntry(name, address, packs)) })
            PackChoice(packs) { packs = it }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onDone(null) }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onDone(ServerEntry(name, address, packs)) }, enabled = valid) { Text("Save") }
            }
        }
    }
}

@Composable
private fun EmptyServers(searching: Boolean) {
    Column(
        Modifier.fillMaxWidth().height(260.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AlkalineIcons.Globe, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(if (searching) "No saved server matches" else "No servers yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Type an address above, like play.example.net, to ping it and save it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A search term that is also a plausible address: a dotted host, a port, an IPv6 literal, or localhost. */
private fun looksLikeAddress(text: String): Boolean =
    ServerAddress.isValid(text) && (text.contains('.') || text.contains(':') || text.equals("localhost", true))

/** What the server's resource pack offers do: ask each time, use them, or decline them (vanilla's edit screen). */
@Composable
private fun PackChoice(packs: ServerEntry.Packs, onChange: (ServerEntry.Packs) -> Unit) {
    val choices = listOf(ServerEntry.Packs.PROMPT to "Ask", ServerEntry.Packs.ENABLED to "Use", ServerEntry.Packs.DISABLED to "Never")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
        Text("Server resource packs", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            for ((index, choice) in choices.withIndex()) {
                SegmentedButton(
                    selected = packs == choice.first,
                    onClick = { onChange(choice.first) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    label = { Text(choice.second) },
                )
            }
        }
    }
}
