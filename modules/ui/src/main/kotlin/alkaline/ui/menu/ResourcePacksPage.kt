package alkaline.ui.menu

import alkaline.client.settings.ClientSettings
import alkaline.config.ConfigSnapshot
import alkaline.config.ConfigStore
import alkaline.renderer.image.RgbaImage
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** The settings rail entry the pack list replaces the generic setting rows for. */
internal const val RESOURCE_PACKS_CATEGORY = "Resource packs"

/**
 * The resource pack list, replacing vanilla's two side-by-side columns with one ordered list: the active packs
 * first, highest priority on top, each moved or removed in place, then the vanilla assets they cover, then every
 * other pack found, added with one click. Edits save at once; the assets reload only on Apply, the screen's one
 * primary action, shown while the selection differs from what is loaded.
 */
@Composable
internal fun ResourcePacksContent(controller: MenuController, store: ConfigStore, snapshot: ConfigSnapshot, modifier: Modifier) {
    LaunchedEffect(Unit) { controller.actions.scanResourcePacks() }
    val selection = snapshot.get(ClientSettings.RESOURCE_PACKS)
    val selectedIds = selection.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val byId = controller.resourcePacks.associateBy { it.id }
    val active = selectedIds.mapNotNull { byId[it] }
    val available = controller.resourcePacks.filter { it.id !in selectedIds }
    fun save(ids: List<String>) = store.update(ClientSettings.RESOURCE_PACKS, ids.joinToString("\n"))
    val pending = selection != controller.appliedResourcePacks
    val status = controller.resourcePackStatus
    val reloading = status.endsWith("…")

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(RESOURCE_PACKS_CATEGORY, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Packs higher in the list cover those below. OptiFine custom item textures are supported.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { controller.actions.scanResourcePacks() }) {
                    Icon(AlkalineIcons.Refresh, "Look for packs again", Modifier.size(20.dp))
                }
                TextButton(onClick = { controller.actions.openResourcePacksFolder() }) {
                    Icon(AlkalineIcons.Folder, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Open folder")
                }
            }
        }
        item(key = "apply") {
            AnimatedVisibility(
                visible = pending || status.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            status.ifEmpty { "Apply to reload the textures." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { controller.actions.applyResourcePacks() }, enabled = !reloading && (pending || !status.isEmpty())) {
                            Text("Apply")
                        }
                    }
                }
            }
        }
        item(key = "active-title") { SectionTitle("Active") }
        itemsIndexed(active, key = { _, pack -> "active:" + pack.id }) { index, pack ->
            PackRow(pack, segmentShape(index, active.size + 1)) {
                PackAction(AlkalineIcons.ChevronUp, "Move ${pack.name} up", index > 0) {
                    save(active.map { it.id }.toMutableList().apply { add(index - 1, removeAt(index)) })
                }
                PackAction(AlkalineIcons.ChevronDown, "Move ${pack.name} down", index < active.size - 1) {
                    save(active.map { it.id }.toMutableList().apply { add(index + 1, removeAt(index)) })
                }
                PackAction(AlkalineIcons.Close, "Remove ${pack.name}", true) {
                    save(active.map { it.id } - pack.id)
                }
            }
        }
        item(key = "vanilla") {
            Surface(
                shape = segmentShape(active.size, active.size + 1),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Minecraft 1.8.9", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("Always underneath", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "available-title") { SectionTitle("Available") }
        if (available.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (controller.resourcePacks.isEmpty()) "No packs found. Put pack .zip files or folders in the pack folder, or in Minecraft's resourcepacks folder."
                    else "Every pack is active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                )
            }
        }
        itemsIndexed(available, key = { _, pack -> "available:" + pack.id }) { index, pack ->
            PackRow(pack, segmentShape(index, available.size)) {
                PackAction(AlkalineIcons.Add, "Use ${pack.name}", true) { save(listOf(pack.id) + active.map { it.id }) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun PackRow(
    pack: ResourcePackInfo,
    shape: androidx.compose.ui.graphics.Shape,
    actions: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PackIcon(pack)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = if (pack.compatible) pack.description else "Made for another version · " + pack.description
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pack.compatible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

@Composable
private fun PackAction(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, description, Modifier.size(20.dp))
    }
}

/** pack.png at 40dp with hard pixels, or a quiet placeholder. */
@Composable
private fun PackIcon(pack: ResourcePackInfo) {
    val bitmap = remember(pack.id, pack.icon) { pack.icon?.let(::bitmap) }
    val shape = RoundedCornerShape(10.dp)
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(40.dp).clip(shape), filterQuality = FilterQuality.None)
    } else {
        Box(Modifier.size(40.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(AlkalineIcons.Image, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun bitmap(image: RgbaImage): ImageBitmap {
    val info = ImageInfo(image.width(), image.height(), ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    return org.jetbrains.skia.Image.makeRaster(info, image.copyPixels(), image.width() * 4).toComposeImageBitmap()
}
