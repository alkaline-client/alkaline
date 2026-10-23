package alkaline.ui.menu

import alkaline.client.settings.ClientSettings
import alkaline.renderer.vulkan.ui.glass
import alkaline.ui.theme.AlkalineIcons
import alkaline.ui.theme.AlkalineLogo
import alkaline.ui.theme.AlkalineTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Corner radius of every frosted panel; glass and Compose surfaces must agree. */
internal val PanelRadius = 32.dp

/** How long the background plays alone between the loading screen fading and the menu arriving. */
private const val REVEAL_PAUSE_MILLIS = 450L

/**
 * Alkaline's menus. The title page is only the logo and the primary actions; everything else lives one level
 * down. Sub-pages slide in place of the buttons so the background stays put. At startup a loading screen
 * covers the window until the host calls [MenuController.finishLoading]; it then fades to the background, and
 * the menu follows a beat later.
 */
@Composable
fun AlkalineMenu(controller: MenuController) {
    val config by rememberConfig(controller.config)
    AlkalineTheme(config.get(ClientSettings.INTERFACE_THEME)) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            LocalDisplayRate provides controller.displayRate,
        ) {
            Box(Modifier.fillMaxSize()) {
                if (controller.phase == MenuPhase.REVEAL) {
                    LaunchedEffect(Unit) {
                        delay(REVEAL_PAUSE_MILLIS)
                        controller.revealed()
                    }
                }
                val inGame = controller.play == PlayPhase.PLAYING || controller.play == PlayPhase.CONNECTING
                        || (controller.play == PlayPhase.PAUSED && controller.page != MenuPage.SETTINGS)
                val overlay = controller.overlay
                val profiles = controller.profiles
                // The HUD shows while playing and under the pause card, as vanilla's does; the editor makes it movable.
                if (controller.phase == MenuPhase.READY && profiles != null
                    && (controller.play == PlayPhase.PLAYING || controller.play == PlayPhase.PAUSED)
                    && overlay != Overlay.CLICK_GUI && controller.page != MenuPage.SETTINGS
                ) {
                    CompositionLocalProvider(
                        alkaline.ui.hud.LocalHudSprites provides controller.hudSprites,
                        alkaline.ui.hud.LocalChatInput provides controller.chat,
                    ) {
                        alkaline.ui.hud.GameHud(
                            controller.hud.frame, profiles.store(),
                            if (overlay == Overlay.HUD_EDITOR) controller.hudEditor else null,
                            onDone = { controller.closeHudEditor() },
                        )
                    }
                }
                if (controller.phase == MenuPhase.READY && controller.play == PlayPhase.PLAYING && overlay == Overlay.NONE) {
                    Crosshair(Modifier.align(Alignment.Center))
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.CLICK_GUI) {
                    ClickGui(controller)
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.INVENTORY) {
                    CompositionLocalProvider(alkaline.ui.hud.LocalHudSprites provides controller.hudSprites) {
                        ContainerScreen(controller.hud.frame, controller.inventoryInput) { controller.typing = it }
                    }
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.CHAT) {
                    alkaline.ui.hud.ChatWheel(controller.chat)
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.SIGN) {
                    SignEditor(controller)
                }
                if (controller.phase == MenuPhase.READY && controller.play == PlayPhase.CONNECTING) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ConnectingCard(controller)
                    }
                }
                if (controller.phase == MenuPhase.READY && controller.play == PlayPhase.PAUSED
                    && controller.page != MenuPage.SETTINGS && overlay == Overlay.NONE
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PauseCard(controller)
                    }
                }
                if (controller.phase == MenuPhase.READY && !inGame) {
                    AnimatedContent(
                        targetState = controller.page,
                        transitionSpec = { pageTransition(initialState, targetState) },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp),
                        label = "menu-page",
                    ) { page ->
                        when (page) {
                            MenuPage.TITLE -> TitlePage(controller)
                            MenuPage.SINGLEPLAYER -> SingleplayerPage(controller)
                            MenuPage.MULTIPLAYER -> MultiplayerPage(controller)
                            MenuPage.SETTINGS -> SettingsPage(controller)
                        }
                    }
                    // Corner controls sit outside the page transition so they stay put while pages slide.
                    AnimatedVisibility(
                        visible = controller.page == MenuPage.TITLE && controller.play == PlayPhase.MENU,
                        enter = fadeIn(tween(320, delayMillis = 200)),
                        exit = fadeOut(tween(160)),
                        modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                    ) {
                        AccountChip(controller)
                    }
                }
                AnimatedVisibility(
                    visible = controller.phase == MenuPhase.LOADING,
                    enter = fadeIn(tween(0)),
                    exit = fadeOut(tween(650)),
                ) {
                    LoadingScreen(controller.loadingStatus)
                }
            }
        }
    }
}

private fun pageTransition(from: MenuPage, to: MenuPage): ContentTransform {
    val forward = to != MenuPage.TITLE
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(tween(320)) { it / 6 * direction } + fadeIn(tween(220)))
        .togetherWith(slideOutHorizontally(tween(240)) { -it / 8 * direction } + fadeOut(tween(160)))
}

/** Opaque brand surface with the mark and an Expressive loading indicator. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingScreen(status: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    AlkalineLogo.Mark, "Alkaline",
                    Modifier.size(width = 72.dp * AlkalineLogo.ASPECT, height = 72.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(40.dp))
                LoadingIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(20.dp),
                )
            }
        }
    }
}

/** The Alkaline mark and the four primary actions, centered and staggered in on first appearance. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TitlePage(controller: MenuController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Staggered(0) {
                // A soft shadow keeps the light mark legible over bright skies without adding a surface.
                Box(Modifier.padding(bottom = 36.dp)) {
                    val size = Modifier.size(width = 84.dp * AlkalineLogo.ASPECT, height = 84.dp)
                    Icon(AlkalineLogo.Mark, null, size.offset(y = 3.dp).blur(12.dp), tint = Color.Black.copy(alpha = 0.45f))
                    Icon(AlkalineLogo.Mark, "Alkaline", size, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            val large = ButtonDefaults.MediumContainerHeight
            // Every action shares one calm style; the one under the pointer lights up.
            Staggered(1) {
                HoverAction(AlkalineIcons.Play, "Singleplayer", large) { controller.open(MenuPage.SINGLEPLAYER) }
            }
            Staggered(2) {
                HoverAction(AlkalineIcons.Globe, "Multiplayer", large) { controller.open(MenuPage.MULTIPLAYER) }
            }
            Staggered(3) {
                HoverAction(AlkalineIcons.Tune, "Settings", large) { controller.open(MenuPage.SETTINGS) }
            }
            Staggered(4) {
                HoverAction(AlkalineIcons.Power, "Quit", large) { controller.actions.quit() }
            }
        }
    }
}

@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visibleState = rememberEntrance(index),
        enter = fadeIn(tween(380, delayMillis = index * 70)) +
            slideInVertically(tween(480, delayMillis = index * 70)) { it / 2 },
    ) {
        content()
    }
}

/** First-appearance state: starts hidden and turns visible immediately, so the enter animation plays once. */
@Composable
private fun rememberEntrance(index: Int): androidx.compose.animation.core.MutableTransitionState<Boolean> =
    androidx.compose.runtime.remember(index) { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }

@Composable
internal fun BackHeader(controller: MenuController, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { controller.back() }, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
            Icon(AlkalineIcons.Back, "Back", Modifier.size(ButtonDefaults.IconSize))
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Frosted surface used by every menu panel. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(PanelRadius),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f),
        tonalElevation = 0.dp,
        modifier = modifier.glass(PanelRadius),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

/** Vanilla's crosshair size at GUI scale 2: two 2-pixel bars, 18 px across. */
@Composable
private fun Crosshair(modifier: Modifier) {
    Box(modifier.size(18.dp)) {
        Surface(Modifier.size(width = 18.dp, height = 2.dp).align(Alignment.Center), color = Color.White.copy(alpha = 0.85f)) {}
        Surface(Modifier.size(width = 2.dp, height = 18.dp).align(Alignment.Center), color = Color.White.copy(alpha = 0.85f)) {}
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectingCard(controller: MenuController) {
    Panel(Modifier.width(420.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Joining", style = MaterialTheme.typography.titleMedium)
                Text(controller.playAddress, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { controller.leave() }) { Text("Cancel") }
        }
    }
}

/** Resume is the one primary action; settings and leaving are secondary. */
@Composable
private fun PauseCard(controller: MenuController) {
    Panel(Modifier.width(380.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Paused", style = MaterialTheme.typography.titleLarge)
            Text(controller.playAddress, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(onClick = { controller.resume() }, modifier = Modifier.fillMaxWidth()) {
                Text("Resume")
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.FilledTonalButton(onClick = { controller.open(MenuPage.SETTINGS) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
            if (controller.profiles != null) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.FilledTonalButton(onClick = { controller.toggleClickGui() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Modules")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { controller.leave() }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }
}
