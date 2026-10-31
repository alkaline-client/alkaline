package alkaline.ui.menu

import alkaline.client.worlds.SavedWorld
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.future.await
import java.util.Locale

/**
 * Singleplayer: the player's own worlds as cards, newest first, with a search field once there are enough to
 * need one. Selecting a card fills the footer; Play is the page's one primary action. Worlds come from the
 * vanilla saves folder; opening them needs the game adapter, which the footer says plainly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SingleplayerPage(controller: MenuController) {
    val library = controller.services.worlds()
    val worlds by produceState<List<SavedWorld>?>(null, library) {
        value = runCatching { library.scan().await() }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 1040.dp).fillMaxWidth().heightIn(max = PagePanelMaxHeight).fillMaxHeight(), morph = MenuPage.SINGLEPLAYER) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackHeader(controller, "Singleplayer")
                Spacer(Modifier.weight(1f))
                if ((worlds?.size ?: 0) > 6) {
                    PillField(query, { query = it }, "Search worlds", AlkalineIcons.Search, Modifier.width(300.dp))
                }
            }
            val all = worlds
            val shown = remember(all, query) {
                all?.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = shown == null,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "worlds",
                ) { loading ->
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary)
                        }
                        shown.isNullOrEmpty() -> EmptyWorlds(
                            if (all.isNullOrEmpty()) "No worlds yet" else "No worlds match “${query.trim()}”",
                            if (all.isNullOrEmpty()) "Worlds in ${library.savesFolder()} appear here." else "Try another name.",
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(280.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(shown, key = { it.id() }) { world ->
                                WorldCard(world, world.id() == selectedId) {
                                    selectedId = if (selectedId == world.id()) null else world.id()
                                }
                            }
                        }
                    }
                }
            }
            PlayBar(all?.firstOrNull { it.id() == selectedId })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayBar(selected: SavedWorld?) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                selected?.name ?: "Choose a world",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Opening worlds needs the 1.8.9 game adapter, which is not in this build yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        val height = ButtonDefaults.MediumContainerHeight
        Button(
            onClick = {},
            enabled = false,
            shapes = ButtonDefaults.shapesFor(height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.height(height),
        ) {
            Icon(AlkalineIcons.Play, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
            Text("Play", style = ButtonDefaults.textStyleFor(height))
        }
    }
}

@Composable
private fun WorldCard(world: SavedWorld, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val (container, content) = hoverColors(hovered, selected)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = container.value,
        contentColor = content.value,
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WorldIcon(world)
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(world.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    describe(world), style = MaterialTheme.typography.bodySmall,
                    color = if (world.hardcore) MaterialTheme.colorScheme.error else content.value.copy(alpha = 0.8f),
                    maxLines = 1,
                )
                Text(relativeTime(world.lastPlayedMillis), style = MaterialTheme.typography.labelSmall, color = content.value.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun WorldIcon(world: SavedWorld) {
    val shape = RoundedCornerShape(14.dp)
    val icon = world.icon()
    val bitmap = remember(world.folder(), icon) { icon?.let { argbBitmap(it, SavedWorld.ICON_SIZE, SavedWorld.ICON_SIZE) } }
    if (bitmap != null) {
        PixelImage(bitmap, 64.dp, shape)
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(AlkalineIcons.Terrain, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun EmptyWorlds(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AlkalineIcons.Folder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun describe(world: SavedWorld): String {
    val mode = if (world.hardcore) "Hardcore" else world.mode().name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    return if (world.cheats) "$mode · Cheats" else mode
}
