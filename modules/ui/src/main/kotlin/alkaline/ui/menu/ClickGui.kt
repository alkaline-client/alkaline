package alkaline.ui.menu

import alkaline.client.modules.Module
import alkaline.client.modules.Modules
import alkaline.config.ConfigSnapshot
import alkaline.config.ConfigStore
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Alkaline's in-game ClickGUI: one frosted panel with a sidebar (Modules, Plugins, Music, and the HUD editor at
 * its foot) and the selected section. Modules is a searchable gallery of the active profile's modules, under the
 * profile picker. Everything writes through to the profile store and applies at once.
 */
@Composable
internal fun ClickGui(controller: MenuController) {
    val profiles = controller.profiles ?: return
    val store = profiles.store()
    val snapshot by rememberConfig(store)
    Box(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(max = PagePanelMaxHeight).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (controller.clickGuiSection) {
                        ClickGuiSection.MODULES -> "Modules"
                        ClickGuiSection.PLUGINS -> "Plugins"
                        ClickGuiSection.MUSIC -> "Music"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                if (controller.clickGuiSection == ClickGuiSection.MODULES) {
                    ProfilePicker(controller)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { controller.closeOverlay() }) {
                    Icon(AlkalineIcons.Close, "Close", Modifier.size(22.dp))
                }
            }
            Row(Modifier.fillMaxSize().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(176.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((section, icon) in listOf(
                        ClickGuiSection.MODULES to AlkalineIcons.Grid,
                        ClickGuiSection.PLUGINS to AlkalineIcons.Plugin,
                        ClickGuiSection.MUSIC to AlkalineIcons.Music,
                    )) {
                        SidebarItem(
                            label = section.name.lowercase().replaceFirstChar { it.uppercase() },
                            icon = icon,
                            selected = controller.clickGuiSection == section,
                            onClick = { controller.clickGuiSection = section },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SidebarItem("HUD editor", AlkalineIcons.Layout, selected = false, onClick = { controller.openHudEditor() })
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (controller.clickGuiSection) {
                        ClickGuiSection.MODULES -> ModuleGallery(controller, store, snapshot)
                        ClickGuiSection.PLUGINS -> controller.plugins?.let { host ->
                            alkaline.ui.plugins.PluginsSection(host, controller)
                        } ?: EmptySection(AlkalineIcons.Plugin, "Plugins are unavailable", "This build does not load plugins.")
                        ClickGuiSection.MUSIC -> controller.music?.let { music ->
                            alkaline.ui.music.MusicSection(music, controller)
                        } ?: EmptySection(AlkalineIcons.Music, "Music is unavailable", "This build has no music player.")
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "section-bg",
    )
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(container)
            .clickable(onClick = onClick).semantics { role = Role.Tab }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = content)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun EmptySection(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 380.dp))
    }
}

/** The gallery's categories: the two module kinds, and the modules that are on. */
internal enum class ModuleFilter(val label: String) { ALL("All"), HUD("HUD"), GAMEPLAY("Gameplay"), ON("On") }

/**
 * Every module as a card in a grid: the card's switch (or a click anywhere on it) turns it on or off, and its
 * options button opens the module in place of the grid. Search and the category buttons narrow the grid.
 */
@Composable
private fun ModuleGallery(controller: MenuController, store: ConfigStore, snapshot: ConfigSnapshot) {
    val open = controller.openModule?.let(Modules::find)
    if (open != null) {
        ModuleOptions(controller, store, snapshot, open) { controller.openModule = null }
        return
    }
    val all = Modules.all()
    val query = controller.moduleQuery
    val filter = controller.moduleFilter
    val shown = all.filter { module ->
        val category = when (filter) {
            ModuleFilter.ALL -> true
            ModuleFilter.HUD -> module.kind() == Module.Kind.HUD
            ModuleFilter.GAMEPLAY -> module.kind() == Module.Kind.GAMEPLAY
            ModuleFilter.ON -> snapshot.get(module.enabled())
        }
        category && module.matches(query)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterButtons(filter) { controller.moduleFilter = it }
            Spacer(Modifier.width(16.dp))
            Text(
                "${all.count { snapshot.get(it.enabled()) }} of ${all.size} on",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            PillField(
                value = query,
                onValueChange = { controller.moduleQuery = it.take(40) },
                placeholder = "Search modules",
                icon = AlkalineIcons.Search,
                // While the field has the keyboard, bound keys type instead of acting.
                modifier = Modifier.width(260.dp).onFocusChanged { controller.typing = it.hasFocus },
                onSubmit = { shown.firstOrNull()?.let { controller.openModule = it.id() } },
                trailing = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { controller.moduleQuery = "" }) {
                            Icon(AlkalineIcons.Close, "Clear search", Modifier.size(18.dp))
                        }
                    }
                },
            )
        }
        if (shown.isEmpty()) {
            EmptySection(
                AlkalineIcons.Search,
                if (query.isBlank()) "Nothing here yet" else "No modules match \u201c${query.trim()}\u201d",
                if (filter == ModuleFilter.ALL) "Try another word." else "Try another word, or show all modules.",
            )
            return@Column
        }
        // Headings only while both kinds show; within a kind the grid keeps the modules' own order.
        val headed = shown.map { it.kind() }.distinct().size > 1
        LazyVerticalGrid(
            columns = GridCells.Adaptive(196.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            for ((kind, modules) in shown.groupBy { it.kind() }) {
                if (headed) {
                    item(key = "group:$kind", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (kind == Module.Kind.HUD) "HUD" else "Gameplay",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                        )
                    }
                }
                items(modules, key = { it.id() }) { module ->
                    ModuleCard(
                        module, snapshot.get(module.enabled()),
                        onToggle = { store.update(module.enabled(), it) },
                        onOpen = { controller.openModule = module.id() },
                    )
                }
            }
        }
    }
}

/** A word-by-word match against the module's name, description and option names (so "blur" finds them all). */
private fun Module.matches(query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val text = buildString {
        append(title()).append(' ').append(description()).append(' ').append(id())
        for (option in options()) append(' ').append(option.title())
    }.lowercase()
    return words.all { it in text }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterButtons(selected: ModuleFilter, onSelect: (ModuleFilter) -> Unit) {
    val filters = ModuleFilter.entries
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        filters.forEachIndexed { index, filter ->
            ToggleButton(
                checked = filter == selected,
                onCheckedChange = { onSelect(filter) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    filters.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) { Text(filter.label) }
        }
    }
}

/** One module: its initial, switch and options button over its name and a two-line description. */
@Composable
private fun ModuleCard(module: Module, enabled: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (enabled) scheme.secondaryContainer.copy(alpha = 0.9f) else scheme.surfaceContainerHigh.copy(alpha = 0.62f),
        label = "module-card",
    )
    val content = if (enabled) scheme.onSecondaryContainer else scheme.onSurface
    Surface(
        onClick = { onToggle(!enabled) },
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth().height(136.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(if (enabled) scheme.primary else scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        module.title().take(1).uppercase(), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                    Icon(AlkalineIcons.Tune, "${module.title()} options", Modifier.size(20.dp))
                }
                Spacer(Modifier.width(4.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            Text(module.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                module.description(), style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.72f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One module opened from the gallery: its switch and description, then its options and HUD placement. */
@Composable
private fun ModuleOptions(
    controller: MenuController,
    store: ConfigStore,
    snapshot: ConfigSnapshot,
    module: Module,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AlkalineIcons.Back, "All modules", Modifier.size(22.dp)) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(module.title(), style = MaterialTheme.typography.titleLarge)
                Text(module.description(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = snapshot.get(module.enabled()), onCheckedChange = { store.update(module.enabled(), it) })
        }
        val options = module.options()
        val placement = module.placement()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(options, key = { _, option -> option.id() }) { index, option ->
                SettingRow(store, snapshot, controller, option, segmentShape(index, options.size),
                    expanded = false, onToggleExpanded = {})
            }
            if (placement != null) {
                item(key = "placement") {
                    Row(Modifier.padding(start = 8.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            controller.openHudEditor()
                            controller.hudEditor.selected = module.id()
                        }) { Text("Move in HUD editor") }
                        TextButton(onClick = {
                            resetSetting(store, placement.anchor())
                            resetSetting(store, placement.x())
                            resetSetting(store, placement.y())
                            resetSetting(store, placement.scale())
                        }) { Text("Reset position") }
                    }
                }
            }
            if (options.isEmpty() && placement == null) {
                item(key = "none") {
                    Text("No options; the switch is all there is.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * The active profile as a pill; its menu switches profiles and creates, renames or deletes them. Names are
 * typed in place; a failure (a taken name) is shown in the menu.
 */
@Composable
private fun ProfilePicker(controller: MenuController) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) } // "new" or "rename"
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Box {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
            modifier = Modifier.clip(CircleShape).clickable { open = true; editing = null; error = null },
        ) {
            Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Profile", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(controller.activeProfile, style = MaterialTheme.typography.labelLarge)
                Icon(AlkalineIcons.ChevronDown, null, Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (profile in controller.profileNames) {
                DropdownMenuItem(
                    text = { Text(profile, fontWeight = if (profile == controller.activeProfile) FontWeight.Bold else null) },
                    leadingIcon = if (profile == controller.activeProfile) {
                        { Icon(AlkalineIcons.Check, null, Modifier.size(18.dp)) }
                    } else null,
                    onClick = {
                        error = controller.profileAction { it.activate(profile) }
                        if (error == null) open = false
                    },
                )
            }
            androidx.compose.material3.HorizontalDivider()
            if (editing != null) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).width(260.dp)) {
                    PillField(
                        value = name,
                        onValueChange = { name = it.take(24) },
                        placeholder = if (editing == "new") "New profile name" else "New name",
                        icon = AlkalineIcons.Edit,
                        onSubmit = {
                            error = controller.profileAction {
                                if (editing == "new") it.create(name, true) else it.rename(controller.activeProfile, name)
                            }
                            if (error == null) {
                                editing = null
                                open = false
                            }
                        },
                    )
                }
            } else {
                DropdownMenuItem(text = { Text("New profile from this one") },
                    leadingIcon = { Icon(AlkalineIcons.Add, null, Modifier.size(18.dp)) },
                    onClick = { editing = "new"; name = ""; error = null })
                DropdownMenuItem(text = { Text("Rename") },
                    leadingIcon = { Icon(AlkalineIcons.Edit, null, Modifier.size(18.dp)) },
                    onClick = { editing = "rename"; name = controller.activeProfile; error = null })
                DropdownMenuItem(text = { Text("Delete") },
                    leadingIcon = { Icon(AlkalineIcons.Delete, null, Modifier.size(18.dp)) },
                    enabled = controller.profileNames.size > 1,
                    onClick = {
                        error = controller.profileAction { it.delete(controller.activeProfile) }
                        if (error == null) open = false
                    })
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 260.dp))
            }
        }
    }
}
