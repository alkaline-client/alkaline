package alkaline.ui.menu

import alkaline.client.settings.ClientSettings
import alkaline.client.settings.GuiScale
import alkaline.config.ConfigSnapshot
import alkaline.config.ConfigStore
import alkaline.config.Setting
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings generated from the config schema. Layout: a header with the save status, a restart banner that
 * appears only while a restart-only change is pending, a category rail with modified-count badges, and the
 * selected category as an Expressive segmented list. Every control writes through to the [ConfigStore]: the
 * client applies the value on its next frame and the store persists it in the background.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsPage(controller: MenuController) {
    val store = controller.config
    val snapshot by rememberConfig(store)
    val saveState by rememberSaveState(store)
    val categories = remember(store) { store.schema().categories() }
    val pending = controller.pendingRestart(snapshot)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 1040.dp).fillMaxWidth().heightIn(max = PagePanelMaxHeight).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackHeader(controller, "Settings")
                Spacer(Modifier.weight(1f))
                SaveStatus(saveState)
            }
            AnimatedVisibility(
                visible = pending.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                RestartBanner(controller, pending)
            }
            Row(Modifier.fillMaxSize().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(196.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((category, settings) in categories) {
                        CategoryItem(
                            label = category,
                            icon = categoryIcon(category),
                            modified = settings.count { snapshot.value(it) != it.defaultValue() },
                            selected = controller.settingsCategory == category,
                            onClick = { controller.settingsCategory = category },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    About(controller.actions.versionLabel)
                }
                val category = controller.settingsCategory
                if (category == RESOURCE_PACKS_CATEGORY) {
                    ResourcePacksContent(controller, store, snapshot, Modifier.weight(1f).fillMaxHeight())
                } else {
                    CategoryContent(
                        store, snapshot, controller, category, categories[category].orEmpty(),
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Build identity and the required non-affiliation notice, kept off the title screen. */
@Composable
private fun About(version: String) {
    Column(Modifier.padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SaveStatus(state: ConfigStore.SaveState) {
    val (icon, label, color) = when (state) {
        ConfigStore.SaveState.SAVED -> Triple(AlkalineIcons.Check, "Saved", MaterialTheme.colorScheme.primary)
        ConfigStore.SaveState.PENDING -> Triple(AlkalineIcons.Sync, "Saving…", MaterialTheme.colorScheme.onSurfaceVariant)
        ConfigStore.SaveState.FAILED -> Triple(AlkalineIcons.Warning, "Not saved — retrying on next change", MaterialTheme.colorScheme.error)
        ConfigStore.SaveState.READ_ONLY -> Triple(AlkalineIcons.Warning, "Changes not saved: settings file is read-only", MaterialTheme.colorScheme.error)
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RestartBanner(controller: MenuController, pending: List<Setting<*>>) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AlkalineIcons.Restart, null, Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (pending.size == 1) "Restart to apply 1 change" else "Restart to apply ${pending.size} changes",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    pending.joinToString(" · ") { it.title() } + " — saved, takes effect after a restart.",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (controller.actions.canRestart) {
                Button(
                    onClick = { controller.actions.restart() },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) {
                    Text("Restart now")
                }
            } else {
                OutlinedButton(onClick = { controller.actions.quit() }, shapes = ButtonDefaults.shapes()) {
                    Text("Quit game")
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(label: String, icon: ImageVector, modified: Int, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "category-bg",
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
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
        if (modified > 0) {
            Box(
                Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(modified.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun CategoryContent(
    store: ConfigStore,
    snapshot: ConfigSnapshot,
    controller: MenuController,
    category: String,
    settings: List<Setting<*>>,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(category) { listState.scrollToItem(0) }
    var expandedColor by remember(category) { mutableStateOf<String?>(null) }
    val anyModified = settings.any { snapshot.value(it) != it.defaultValue() }
    // Controls are shown in vanilla's groups, named by the second segment of their ids.
    val groups = settings.groupBy { if (category == "Controls") it.id().split('.')[1] else "" }
    val bindings = settings.filterIsInstance<Setting.KeySetting>().map { snapshot.get(it) }
        .filter { it != Setting.KeySetting.NONE }.groupingBy { it }.eachCount()
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header:$category") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(category, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        categoryDescription(category),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { settings.forEach { resetSetting(store, it) } }, enabled = anyModified) {
                    Icon(AlkalineIcons.Reset, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Reset section")
                }
            }
        }
        for ((group, members) in groups) {
            if (group.isNotEmpty()) {
                item(key = "group:$group") {
                    Text(
                        groupTitle(group),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
            }
            itemsIndexed(members, key = { _, setting -> setting.id() }) { index, setting ->
                SettingRow(
                    store, snapshot, controller, setting, segmentShape(index, members.size),
                    expanded = expandedColor == setting.id(),
                    onToggleExpanded = { expandedColor = if (expandedColor == setting.id()) null else setting.id() },
                    conflicted = setting is Setting.KeySetting && (bindings[snapshot.get(setting)] ?: 0) > 1,
                )
            }
        }
    }
}

/** Expressive segmented list: a large outer radius on the group, small radii between its rows. */
internal fun segmentShape(index: Int, count: Int): Shape {
    val outer = 22.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
internal fun SettingRow(
    store: ConfigStore,
    snapshot: ConfigSnapshot,
    controller: MenuController,
    setting: Setting<*>,
    shape: Shape,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    conflicted: Boolean = false,
) {
    val modified = snapshot.value(setting) != setting.defaultValue()
    val awaitingRestart = setting.restartRequired() && snapshot.value(setting) != controller.launchSettings.value(setting)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(setting.title(), style = MaterialTheme.typography.titleSmall)
                        if (setting.restartRequired()) {
                            Spacer(Modifier.width(8.dp))
                            RestartChip(awaitingRestart)
                        }
                    }
                    if (setting.description().isNotEmpty()) {
                        Text(
                            setting.description(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (setting is Setting.KeySetting) {
                    KeyBindingControl(controller, store, snapshot.get(setting), setting, conflicted)
                } else {
                    SettingControl(store, snapshot, setting, expanded, onToggleExpanded)
                }
                IconButton(onClick = { resetSetting(store, setting) }, enabled = modified) {
                    Icon(
                        AlkalineIcons.Reset,
                        "Reset ${setting.title()}",
                        Modifier.size(20.dp),
                        tint = if (modified) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                }
            }
            if (setting is Setting.ColorSetting) {
                AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    ColorEditor(snapshot.get(setting)) { store.update(setting, it) }
                }
            }
        }
    }
}

@Composable
private fun RestartChip(awaiting: Boolean) {
    val color = if (awaiting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Row(
        Modifier.border(1.dp, color, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AlkalineIcons.Restart, null, Modifier.size(12.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(if (awaiting) "Restart pending" else "Needs restart", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

internal fun <T> resetSetting(store: ConfigStore, setting: Setting<T>) = store.update(setting, setting.defaultValue())

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingControl(
    store: ConfigStore,
    snapshot: ConfigSnapshot,
    setting: Setting<*>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    when (setting) {
        is Setting.BooleanSetting -> Switch(
            checked = snapshot.get(setting),
            onCheckedChange = { store.update(setting, it) },
        )
        is Setting.IntSetting -> {
            val stored = snapshot.get(setting)
            val step = intStep(setting)
            val span = (setting.max() - setting.min()) / step
            // GUI scale re-lays out the whole interface, so it applies on release instead of under the pointer.
            val deferred = setting == ClientSettings.GUI_SCALE
            var dragging by remember(setting) { mutableStateOf<Int?>(null) }
            val value = dragging ?: stored
            NumericSlider(
                value = value.toFloat(),
                range = setting.min().toFloat()..setting.max().toFloat(),
                steps = if (span in 2..32) span - 1 else 0,
                label = when {
                    deferred -> guiScaleLabel(value)
                    setting == ClientSettings.HUD_RATE && value == ClientSettings.HUD_RATE_DISPLAY -> {
                        val hertz = LocalDisplayRate.current
                        if (hertz > 0) "Refresh rate · $hertz Hz" else "Refresh rate"
                    }
                    else -> valueLabel(setting, value)
                },
                onChange = {
                    val next = (setting.min() + ((it - setting.min()) / step).roundToInt() * step)
                        .coerceIn(setting.min(), setting.max())
                    if (deferred) dragging = next else store.update(setting, next)
                },
                onChangeFinished = {
                    dragging?.let { store.update(setting, it) }
                    dragging = null
                },
            )
        }
        is Setting.FloatSetting -> {
            val value = snapshot.get(setting)
            val step = floatStep(setting)
            NumericSlider(
                value = value,
                range = setting.min()..setting.max(),
                steps = 0,
                label = valueLabel(setting, value),
                onChange = { store.update(setting, ((it / step).roundToInt() * step).coerceIn(setting.min(), setting.max())) },
            )
        }
        is Setting.EnumSetting<*> -> EnumControl(store, snapshot, setting)
        is Setting.ColorSetting -> {
            val argb = snapshot.get(setting)
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            Row(
                Modifier.clip(CircleShape).clickable(onClick = onToggleExpanded).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(argb, 28)
                Spacer(Modifier.width(10.dp))
                Text(String.format(Locale.ROOT, "#%08X", argb), style = MaterialTheme.typography.labelLarge)
                Icon(AlkalineIcons.ChevronDown, if (expanded) "Collapse" else "Edit color", Modifier.size(20.dp).rotate(rotation))
            }
        }
        is Setting.StringSetting -> Text(snapshot.get(setting), style = MaterialTheme.typography.labelLarge)
        is Setting.KeySetting -> Text(bindingLabel(snapshot.get(setting)), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A binding's current input; selecting it listens for the next key or mouse button (the host routes that press
 * here instead of to the interface). Bindings that share an input are shown in the error color.
 */
@Composable
private fun KeyBindingControl(
    controller: MenuController,
    store: ConfigStore,
    binding: String,
    setting: Setting.KeySetting,
    conflicted: Boolean,
) {
    val listening = controller.capturingBinding == setting.id()
    val container by animateColorAsState(
        if (listening) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "binding-bg",
    )
    OutlinedButton(
        onClick = { if (listening) controller.finishCapture(null) else controller.startCapture(store, setting) },
        colors = ButtonDefaults.outlinedButtonColors(containerColor = container),
        modifier = Modifier.widthIn(min = 152.dp),
    ) {
        Text(
            if (listening) "Press a key…" else bindingLabel(binding),
            style = MaterialTheme.typography.labelLarge,
            color = when {
                listening -> MaterialTheme.colorScheme.onPrimaryContainer
                conflicted -> MaterialTheme.colorScheme.error
                binding == Setting.KeySetting.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "W", "Left Shift", "Right button", or "Not bound". */
internal fun bindingLabel(binding: String): String = when {
    binding == Setting.KeySetting.NONE -> "Not bound"
    binding.startsWith("key:") -> binding.substring(4)
    else -> when (binding.substring(6)) {
        "left" -> "Left button"
        "right" -> "Right button"
        "middle" -> "Middle button"
        "x1" -> "Mouse 4"
        "x2" -> "Mouse 5"
        else -> binding
    }
}

private fun groupTitle(group: String): String = when (group) {
    "mouse" -> "Mouse"
    "movement" -> "Movement"
    "gameplay" -> "Gameplay"
    "inventory" -> "Inventory"
    "multiplayer" -> "Multiplayer"
    else -> "Miscellaneous"
}

/** A color chip drawn over a checkerboard so translucent values read as translucent. */
@Composable
private fun Swatch(argb: Int, sizeDp: Int) {
    val shape = RoundedCornerShape((sizeDp / 3.5f).dp)
    Box(
        Modifier.size(sizeDp.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF9A9A9A), Color(0xFFE0E0E0), Color(0xFF9A9A9A), Color(0xFFE0E0E0))))
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    )
}

private val ColorPresets = intArrayOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF6FDACF.toInt(), 0xFFFFB86B.toInt(),
    0xFF7AA7FF.toInt(), 0xFFFF7A90.toInt(), 0xFFB98CFF.toInt(), 0xFF9BE37A.toInt(),
)

/** Hue/saturation/brightness/opacity editor with presets; presets keep the current opacity. */
@Composable
private fun ColorEditor(argb: Int, onChange: (Int) -> Unit) {
    var hsv by remember { mutableStateOf(toHsv(argb)) }
    LaunchedEffect(argb) {
        if (fromHsv(hsv, argb ushr 24) != argb) hsv = toHsv(argb)
    }
    val alpha = argb ushr 24
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            for (preset in ColorPresets) {
                val withAlpha = (preset and 0x00FFFFFF) or (alpha shl 24)
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { hsv = toHsv(withAlpha); onChange(withAlpha) }) {
                    Swatch(withAlpha, 26)
                }
            }
        }
        ColorSlider("Hue", hsv[0], 0f..360f, "${hsv[0].roundToInt()}°") { hsv = floatArrayOf(it, hsv[1], hsv[2]); onChange(fromHsv(hsv, alpha)) }
        ColorSlider("Saturation", hsv[1], 0f..1f, percent(hsv[1])) { hsv = floatArrayOf(hsv[0], it, hsv[2]); onChange(fromHsv(hsv, alpha)) }
        ColorSlider("Brightness", hsv[2], 0f..1f, percent(hsv[2])) { hsv = floatArrayOf(hsv[0], hsv[1], it); onChange(fromHsv(hsv, alpha)) }
        ColorSlider("Opacity", alpha / 255f, 0f..1f, percent(alpha / 255f)) {
            onChange((argb and 0x00FFFFFF) or ((it * 255f).roundToInt().coerceIn(0, 255) shl 24))
        }
    }
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(display, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp).padding(start = 12.dp))
    }
}

private fun percent(value: Float) = "${(value * 100f).roundToInt()}%"

private fun toHsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta).mod(6f))
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return floatArrayOf(hue, if (max == 0f) 0f else delta / max, max)
}

private fun fromHsv(hsv: FloatArray, alpha: Int): Int {
    val (hue, saturation, value) = Triple(hsv[0], hsv[1], hsv[2])
    val chroma = value * saturation
    val x = chroma * (1f - abs((hue / 60f).mod(2f) - 1f))
    val m = value - chroma
    val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    fun channel(c: Float) = ((c + m) * 255f).roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

@Composable
private fun NumericSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = onChangeFinished, modifier = Modifier.width(220.dp),
        )
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(118.dp).padding(start = 12.dp), maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <E : Enum<E>> EnumControl(store: ConfigStore, snapshot: ConfigSnapshot, setting: Setting.EnumSetting<E>) {
    val selected = snapshot.get(setting)
    val values = setting.values()
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        values.forEachIndexed { index, value ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { store.update(setting, value) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(displayName(value))
            }
        }
    }
}

/** "Auto · 125%", "150%", or "150% → 125%" when the window caps the request; reads the live density. */
@Composable
private fun guiScaleLabel(step: Int): String {
    val effective = (LocalDensity.current.density * 100f).roundToInt()
    if (step == GuiScale.AUTO) return "Auto · $effective%"
    val requested = (GuiScale.requested(step) * 100f).roundToInt()
    return if (requested > effective) "$requested% → $effective%" else "$requested%"
}

/** The refresh rate of the display the game is on, in whole Hz (0 when unknown), for the HUD rate's label. */
internal val LocalDisplayRate = androidx.compose.runtime.compositionLocalOf { 0 }

/** Slider increments: vanilla's 10 fps steps for the frame limit and the HUD rate, 1 elsewhere. */
private fun intStep(setting: Setting.IntSetting): Int =
    if (setting == ClientSettings.VIDEO_FRAME_LIMIT || setting == ClientSettings.HUD_RATE) 10 else 1

/** Slider increments for decimals: whole degrees of FOV, single percents for vanilla's percentage sliders. */
private fun floatStep(setting: Setting.FloatSetting): Float = when (setting) {
    ClientSettings.WORLD_FOV -> 1f
    ClientSettings.MOUSE_SENSITIVITY -> 0.005f
    alkaline.client.modules.Modules.ZOOM_FACTOR -> 0.5f
    ClientSettings.WORLD_BRIGHTNESS -> 0.01f
    else -> 0.05f
}

/** Human-readable value for sliders; vanilla 1.8.9's labels and sentinel values where it has them. */
private fun valueLabel(setting: Setting<*>, value: Number): String = when (setting) {
    ClientSettings.VIDEO_FRAME_LIMIT ->
        if (value.toInt() >= ClientSettings.FRAME_LIMIT_UNLIMITED) "Unlimited" else "${value.toInt()} fps"
    ClientSettings.WORLD_RENDER_DISTANCE -> "${value.toInt()} chunks"
    ClientSettings.HUD_RATE -> "${value.toInt()} fps"
    ClientSettings.WORLD_FOV -> when (value.toFloat().roundToInt()) {
        70 -> "Normal"
        110 -> "Quake Pro"
        else -> value.toFloat().roundToInt().toString()
    }
    ClientSettings.WORLD_BRIGHTNESS -> when (value.toFloat()) {
        0f -> "Moody"
        1f -> "Bright"
        else -> "+${(value.toFloat() * 100f).toInt()}%"
    }
    ClientSettings.PANORAMA_SPEED -> String.format(Locale.ROOT, "%.2f×", value.toFloat())
    ClientSettings.WORLD_CLOUD_HEIGHT -> "y = ${128 + (value.toFloat() * 128f).roundToInt()}"
    alkaline.client.modules.Modules.ZOOM_FACTOR -> String.format(Locale.ROOT, "%.1f×", value.toFloat())
    alkaline.client.modules.Modules.CHAT_FADE_SECONDS -> "${value.toInt()} s"
    alkaline.client.modules.Modules.CHAT_LINES -> "${value.toInt()} lines"
    // Vanilla's scale: 0.5 reads as 100%.
    ClientSettings.MOUSE_SENSITIVITY -> when (value.toFloat()) {
        0f -> "*yawn*"
        1f -> "HYPERSPEED!!!"
        else -> "${(value.toFloat() * 200f).toInt()}%"
    }
    in ClientSettings.AUDIO_VOLUMES -> if (value.toFloat() <= 0f) "Off" else percent(value.toFloat())
    else -> if (value is Float) String.format(Locale.ROOT, "%.2f", value) else value.toString()
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "General" -> AlkalineIcons.Layout
    "Video" -> AlkalineIcons.Monitor
    "Interface" -> AlkalineIcons.Palette
    "Advanced" -> AlkalineIcons.Code
    "Controls" -> AlkalineIcons.Keyboard
    RESOURCE_PACKS_CATEGORY -> AlkalineIcons.Image
    "Audio" -> AlkalineIcons.Music
    else -> AlkalineIcons.Tune
}

private fun categoryDescription(category: String): String = when (category) {
    "Video" -> "Presentation, pacing, and world rendering. Applied on the next frame."
    "Interface" -> "Color scheme, density, and what plays behind the menus."
    "Advanced" -> "Fine tuning and diagnostics. Defaults suit most players."
    "Controls" -> "Bindings follow key positions. Select one and press the new key or button; Esc cancels, Backspace clears."
    "Audio" -> "Volume by kind of sound, as vanilla groups them. Applied at once, to sounds already playing too."
    else -> ""
}

private fun displayName(value: Enum<*>): String = if (value == ClientSettings.WindowSystem.X11) "X11" else
    value.name.lowercase(Locale.ROOT).split('_').joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
