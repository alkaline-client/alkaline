package alkaline.ui.plugins

import alkaline.config.ConfigSnapshot
import alkaline.ui.menu.MenuController
import alkaline.ui.menu.PagePanelMaxHeight
import alkaline.ui.menu.Panel
import alkaline.ui.menu.SettingRow
import alkaline.ui.menu.pngBitmap
import alkaline.ui.menu.segmentShape
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The ClickGUI's Plugins section: every jar in the plugin folder as a card with its switch. A card folds out to
 * what the manifest says (authors, license, links, what it needs), the plugin's declared settings and HUD
 * elements, its commands and page while it runs, and its latest log lines.
 */
@Composable
internal fun PluginsSection(host: PluginHost, controller: MenuController) {
    val plugins = host.installed
    val openFolder = { controller.actions.openUrl(host.folder.toUri().toString()) }
    if (plugins.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AlkalineIcons.Plugin, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("No plugins installed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Put plugin jars in ${host.folder}, then reload.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 380.dp))
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = openFolder) { Text("Open folder") }
                TextButton(onClick = { host.scan() }) { Text("Reload") }
            }
        }
        return
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "toolbar") {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val running = plugins.count { it.enabled }
                Text("${plugins.size} installed · $running on", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = openFolder) { Text("Open folder") }
                TextButton(onClick = { host.scan() }) { Text("Reload") }
            }
        }
        items(plugins, key = { it.jar.toString() }) { plugin ->
            PluginCard(host, plugin, controller, expanded == plugin.id) {
                expanded = if (expanded == plugin.id) null else plugin.id
            }
        }
        item(key = "trust") {
            Text(
                "Plugins run with your account's access to this computer and can do anything Alkaline can. " +
                    "Install only plugins you trust.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PluginCard(
    host: PluginHost,
    plugin: PluginHost.Installed,
    controller: MenuController,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val manifest = plugin.manifest
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "plugin-chevron")
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh.copy(alpha = 0.62f)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PluginIcon(plugin)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                        if (manifest != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(manifest.version.toString(), style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant)
                        }
                    }
                    val detail = manifest?.description?.ifEmpty { null } ?: plugin.jar.fileName.toString()
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(AlkalineIcons.ChevronDown, if (expanded) "Collapse" else "Details",
                    Modifier.size(20.dp).rotate(rotation), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = plugin.enabled,
                    enabled = manifest != null,
                    onCheckedChange = { on -> if (on) host.enable(plugin) else host.disable(plugin) },
                )
            }
            if (plugin.problems.isNotEmpty()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    for (problem in plugin.problems) {
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    }
                }
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (manifest != null) About(host, plugin, manifest, controller)
                    val snapshot = plugin.settings?.value
                    val store = plugin.store
                    if (manifest != null && store != null && snapshot != null) {
                        if (manifest.settings.isNotEmpty()) Settings(manifest, store, snapshot, controller)
                        if (manifest.hud.isNotEmpty()) HudElements(plugin, manifest, store, snapshot, controller)
                    }
                    val commands = if (plugin.enabled) host.commandsOf(plugin) else emptyList()
                    if (commands.isNotEmpty()) {
                        Label("Commands")
                        for ((name, description) in commands) {
                            Row(Modifier.padding(start = 4.dp)) {
                                Text("/$name", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                if (description.isNotEmpty()) {
                                    Text("  $description", style = MaterialTheme.typography.bodyMedium,
                                        color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val page = host.pages[plugin.id]
                    if (plugin.enabled && page != null) {
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) { page() }
                    }
                    if (plugin.log.isNotEmpty()) {
                        Label("Log")
                        Column(Modifier.padding(start = 4.dp)) {
                            for (line in plugin.log.takeLast(8)) {
                                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = if ("error:" in line) scheme.error else scheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginIcon(plugin: PluginHost.Installed) {
    val bitmap = remember(plugin) { plugin.icon?.let { pngBitmap(it) } }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize())
        } else {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AlkalineIcons.Plugin, null, Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp))
}

/** Who made it, under what license, its links, and the plugins it needs. */
@Composable
private fun About(host: PluginHost, plugin: PluginHost.Installed, manifest: Manifest, controller: MenuController) {
    val scheme = MaterialTheme.colorScheme
    val facts = listOfNotNull(
        manifest.authors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "By ") { it.name },
        manifest.license,
        "Plugin API ${manifest.api}",
        plugin.jar.fileName.toString(),
    )
    Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    if (plugin.enabled) {
        // Time in its hooks over the last second (all threads), and its slowest call since it started.
        val usage by produceState<String?>(null, plugin) {
            var previous = host.usage(plugin)
            while (true) {
                delay(1000)
                val current = host.usage(plugin) ?: break
                val spent = current.nanos - (previous?.nanos ?: 0L)
                value = "Busy %.2f ms a second  ·  slowest call %.1f ms".format(spent / 1e6, current.slowestNanos / 1e6)
                previous = current
            }
        }
        usage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
    }
    val links = listOfNotNull(
        manifest.homepage?.let { "Website" to it },
        manifest.source?.let { "Source" to it },
        manifest.issues?.let { "Report a problem" to it },
    )
    if (links.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, url) in links) {
                TextButton(onClick = { controller.actions.openUrl(url) }) { Text(label) }
            }
        }
    }
    val needs = manifest.dependencies.map { it to true } + manifest.optionalDependencies.map { it to false }
    if (needs.isNotEmpty()) {
        Label("Needs")
        for ((dependency, required) in needs) {
            val (id, range) = dependency
            val installed = host.installed.firstOrNull { it.id == id }
            val state = when {
                installed == null -> if (required) "not installed" else "not installed (optional)"
                installed.manifest?.version?.let { range.matches(it) } != true ->
                    "${installed.manifest?.version ?: "unreadable"} installed, needs $range"
                installed.enabled -> "on"
                else -> "off"
            }
            Text("${installed?.name ?: id} $range  —  $state${if (!required && installed != null) " · optional" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (required && (installed == null || state.contains("needs"))) scheme.error else scheme.onSurface,
                modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun Settings(manifest: Manifest, store: alkaline.config.ConfigStore, snapshot: ConfigSnapshot,
                     controller: MenuController) {
    var expandedColor by remember { mutableStateOf<String?>(null) }
    Label("Settings")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        manifest.settings.forEachIndexed { index, setting ->
            key(setting.id()) {
                SettingRow(store, snapshot, controller, setting, segmentShape(index, manifest.settings.size),
                    expanded = expandedColor == setting.id(),
                    onToggleExpanded = { expandedColor = if (expandedColor == setting.id()) null else setting.id() })
            }
        }
    }
}

/** Each declared HUD element: its switch, and a way to place it (in game, where the HUD editor is). */
@Composable
private fun HudElements(plugin: PluginHost.Installed, manifest: Manifest, store: alkaline.config.ConfigStore,
                        snapshot: ConfigSnapshot, controller: MenuController) {
    Label("HUD")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        manifest.hud.forEachIndexed { index, spec ->
            Surface(shape = segmentShape(index, manifest.hud.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(spec.title, style = MaterialTheme.typography.titleSmall)
                        if (spec.description.isNotEmpty()) {
                            Text(spec.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (plugin.enabled && snapshot.get(spec.enabled) && controller.play != alkaline.ui.menu.PlayPhase.MENU) {
                        TextButton(onClick = {
                            controller.openHudEditor()
                            controller.hudEditor.selected = "plugin:${plugin.id}:${spec.id}"
                        }) { Text("Move") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Switch(checked = snapshot.get(spec.enabled), onCheckedChange = {
                        runCatching { store.set(spec.enabled, it) }
                    })
                }
            }
        }
    }
}

/** Running plugins' overlays over the game view, under the HUD. */
@Composable
fun PluginOverlays(host: PluginHost, modifier: Modifier = Modifier) {
    val overlays = host.overlays
    if (overlays.isEmpty()) return
    Box(modifier.fillMaxSize()) {
        for (overlay in overlays) key(overlay) { overlay() }
    }
}

/** A plugin's screen in Alkaline's panel: its title, a close button, and the plugin's content. */
@Composable
internal fun PluginScreenPanel(screen: PluginHost.PluginScreen, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 880.dp).fillMaxWidth().heightIn(max = PagePanelMaxHeight)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(screen.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(AlkalineIcons.Close, "Close", Modifier.size(22.dp)) }
            }
            Box(Modifier.weight(1f, fill = false).fillMaxWidth()) { screen.content(onClose) }
        }
    }
}
