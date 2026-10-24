package alkaline.ui.menu

import alkaline.client.modules.Module
import alkaline.client.modules.Modules
import alkaline.config.ConfigSnapshot
import alkaline.config.ConfigStore
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Alkaline's in-game ClickGUI: one frosted panel with a sidebar (Modules, Plugins, Music, and the HUD editor at
 * its foot) and the selected section. Modules shows every module of the active profile with its switch and
 * options, under the profile picker. Everything writes through to the profile store and applies at once.
 */
@Composable
internal fun ClickGui(controller: MenuController) {
    val profiles = controller.profiles ?: return
    val store = profiles.store()
    val snapshot by rememberConfig(store)
    Box(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 1040.dp).fillMaxWidth().heightIn(max = PagePanelMaxHeight).fillMaxHeight()) {
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
                Column(Modifier.width(196.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        ClickGuiSection.MODULES -> ModuleList(controller, store, snapshot)
                        ClickGuiSection.PLUGINS -> EmptySection(AlkalineIcons.Plugin, "No plugins yet",
                            "Plugins will be listed here, each with its settings in your profiles, once Alkaline can load them.")
                        ClickGuiSection.MUSIC -> EmptySection(AlkalineIcons.Music, "Nothing to play yet",
                            "Music playback is not part of Alkaline yet.")
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

/** HUD modules, then gameplay ones; each row is the module's switch, with its options folded under it. */
@Composable
private fun ModuleList(controller: MenuController, store: ConfigStore, snapshot: ConfigSnapshot) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val groups = Modules.all().groupBy { it.kind() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        for ((kind, modules) in groups) {
            item(key = "group:$kind") {
                Text(
                    if (kind == Module.Kind.HUD) "HUD" else "Gameplay",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
                )
            }
            items(modules, key = { it.id() }) { module ->
                ModuleRow(
                    controller, store, snapshot, module, segmentShape(modules.indexOf(module), modules.size),
                    expanded = expanded == module.id(),
                    onToggleExpanded = { expanded = if (expanded == module.id()) null else module.id() },
                )
            }
        }
    }
}

@Composable
private fun ModuleRow(
    controller: MenuController,
    store: ConfigStore,
    snapshot: ConfigSnapshot,
    module: Module,
    shape: androidx.compose.ui.graphics.Shape,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val enabled = snapshot.get(module.enabled())
    val expandable = module.options().isNotEmpty() || module.placement() != null
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "module-chevron")
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = expandable, onClick = onToggleExpanded)
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(module.title(), style = MaterialTheme.typography.titleSmall)
                    Text(module.description(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expandable) {
                    Icon(AlkalineIcons.ChevronDown, if (expanded) "Collapse" else "Options",
                        Modifier.size(20.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                }
                Switch(checked = enabled, onCheckedChange = { store.update(module.enabled(), it) })
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    module.options().forEachIndexed { index, option ->
                        SettingRow(store, snapshot, controller, option, segmentShape(index, module.options().size),
                            expanded = false, onToggleExpanded = {})
                    }
                    val placement = module.placement()
                    if (placement != null) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
