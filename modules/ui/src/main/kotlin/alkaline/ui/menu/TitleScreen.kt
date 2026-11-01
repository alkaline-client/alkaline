package alkaline.ui.menu

import alkaline.client.settings.ClientSettings
import alkaline.renderer.vulkan.ui.glass
import alkaline.ui.theme.AlkalineIcons
import alkaline.ui.theme.AlkalineLogo
import alkaline.ui.theme.AlkalineTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import alkaline.renderer.vulkan.ui.LocalGlassFade
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Corner radius of every frosted panel; glass and Compose surfaces must agree. */
internal val PanelRadius = 32.dp

/** Page panels stop growing here and center, so a tall window does not stretch them into a column. */
internal val PagePanelMaxHeight = 620.dp

/** How long the background plays alone between the loading screen fading and the menu arriving. */
private const val REVEAL_PAUSE_MILLIS = 450L

/** The menu's cross-fade into a joined world; the client's compositor fades its background over the same time. */
const val WORLD_FADE_MILLIS = 600

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
            LocalFrosted provides config.get(ClientSettings.MENU_GLASS),
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
                val morph = androidx.compose.runtime.remember { MorphState() }
                // The HUD and crosshair arrive with the world rather than before it.
                val world = androidx.compose.runtime.remember { Animatable(1f) }
                LaunchedEffect(controller.worldReveals) {
                    if (controller.worldReveals > 0) {
                        world.snapTo(0f)
                        world.animateTo(1f, tween(WORLD_FADE_MILLIS, easing = LinearOutSlowInEasing))
                    }
                }
                val worldFade = androidx.compose.runtime.remember(world) { derivedStateOf { world.value } }
                // Under the HUD, so the open chat's own messages still take the pointer; the wheel scrolls from anywhere else.
                if (controller.phase == MenuPhase.READY && overlay == Overlay.CHAT) {
                    alkaline.ui.hud.ChatWheel(controller.chat)
                }
                // The HUD shows while playing and under the pause card, as vanilla's does; the editor makes it movable.
                if (controller.phase == MenuPhase.READY && profiles != null
                    && (controller.play == PlayPhase.PLAYING || controller.play == PlayPhase.PAUSED)
                    && overlay != Overlay.CLICK_GUI && controller.page != MenuPage.SETTINGS
                ) {
                    CompositionLocalProvider(
                        alkaline.ui.hud.LocalHudSprites provides controller.hudSprites,
                        alkaline.ui.hud.LocalChatInput provides controller.chat,
                        alkaline.ui.hud.LocalNotifications provides controller.notifications,
                        alkaline.ui.hud.LocalMusic provides controller.music,
                        LocalGlassFade provides worldFade,
                    ) {
                        val plugins = controller.plugins
                        if (plugins != null && overlay != Overlay.HUD_EDITOR) {
                            alkaline.ui.plugins.PluginOverlays(plugins, Modifier.graphicsLayer { alpha = worldFade.value })
                        }
                        alkaline.ui.hud.GameHud(
                            controller.hud.frame, profiles.store(),
                            if (overlay == Overlay.HUD_EDITOR) controller.hudEditor else null,
                            modifier = Modifier.graphicsLayer { alpha = worldFade.value },
                            items = plugins?.hudItems.orEmpty(),
                            onDone = { controller.closeHudEditor() },
                        )
                    }
                }
                if (controller.phase == MenuPhase.READY && controller.play == PlayPhase.PLAYING && overlay == Overlay.NONE) {
                    Crosshair(Modifier.align(Alignment.Center).graphicsLayer { alpha = worldFade.value })
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.CLICK_GUI) {
                    ClickGui(controller)
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.INVENTORY) {
                    CompositionLocalProvider(alkaline.ui.hud.LocalHudSprites provides controller.hudSprites) {
                        ContainerScreen(controller.hud.frame, controller.inventoryInput) { controller.typing = it }
                    }
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.SIGN) {
                    SignEditor(controller)
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.SERVER_PACK) {
                    ServerPackPrompt(controller)
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.PLUGIN) {
                    controller.pluginScreen?.let { alkaline.ui.plugins.PluginScreenPanel(it) { controller.closeOverlay() } }
                }
                if (controller.phase == MenuPhase.READY && overlay == Overlay.DEATH) {
                    DeathScreen(controller)
                }
                if (controller.phase == MenuPhase.READY && controller.serverPackProgress != null
                    && (controller.play == PlayPhase.PLAYING || controller.play == PlayPhase.PAUSED)
                ) {
                    ServerPackProgress(controller, Modifier.align(Alignment.TopCenter))
                }
                AnimatedVisibility(
                    visible = controller.phase == MenuPhase.READY && controller.play == PlayPhase.CONNECTING,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(WORLD_FADE_MILLIS)),
                ) {
                    FadingGlass(200, 0, WORLD_FADE_MILLIS) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ConnectingCard(controller)
                        }
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
                    // The title action grows into the page it opens; see MorphState.
                    CompositionLocalProvider(LocalMorph provides morph) {
                        MorphDriver(morph, controller.page, ButtonDefaults.MediumContainerHeight)
                        MorphContainer(morph)
                        AnimatedContent(
                            targetState = controller.page,
                            transitionSpec = { pageTransition() },
                            modifier = Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp),
                            label = "menu-page",
                        ) { page ->
                            MorphPage(this) {
                                when (page) {
                                    MenuPage.TITLE -> TitlePage(controller)
                                    MenuPage.SINGLEPLAYER -> SingleplayerPage(controller)
                                    MenuPage.MULTIPLAYER -> MultiplayerPage(controller)
                                    MenuPage.SETTINGS -> SettingsPage(controller)
                                }
                            }
                        }
                    }
                    // Corner controls sit outside the page transition so they stay put while pages slide.
                    AnimatedVisibility(
                        visible = controller.page == MenuPage.TITLE && controller.play == PlayPhase.MENU,
                        enter = fadeIn(tween(320, delayMillis = 200)),
                        exit = fadeOut(tween(160)),
                        modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                    ) {
                        FadingGlass(320, 200, 160) { AccountChip(controller) }
                    }
                }
                // Outside the game the menus show notifications in their bottom corner, with the menu's glass.
                if (controller.phase == MenuPhase.READY && controller.play == PlayPhase.MENU) {
                    alkaline.ui.hud.NotificationStack(
                        controller.notifications, seconds = 5, newestAtBottom = true, alignment = Alignment.End,
                        blur = config.get(ClientSettings.MENU_GLASS), hud = false,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                    )
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

/**
 * Pages only cross-fade, and [MorphPage] does the fading (without offscreen layers); the motion is the
 * container transform ([MorphState]).
 */
private fun pageTransition(): ContentTransform = EnterTransition.None togetherWith ExitTransition.None

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

/**
 * The Alkaline mark and the four primary actions, centered and staggered in when the menu first appears. Coming
 * back from a page they simply fade in around the action the page shrinks into.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TitlePage(controller: MenuController) {
    val stagger = androidx.compose.runtime.remember { controller.firstTitleEntrance() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Staggered(0, stagger) {
                // A soft shadow keeps the light mark legible over bright skies without adding a surface. The blur
                // gets room to fall off around the mark: clipped to the mark's own bounds it ended in hard edges.
                val mark = DpSize(84.dp * AlkalineLogo.ASPECT, 84.dp)
                Box(Modifier.padding(bottom = 36.dp).size(mark), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.requiredSize(mark.width + 48.dp, mark.height + 48.dp).offset(y = 2.dp).blur(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AlkalineLogo.Mark, null, Modifier.size(mark), tint = Color.Black.copy(alpha = 0.3f))
                    }
                    Icon(AlkalineLogo.Mark, "Alkaline", Modifier.size(mark), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            val large = ButtonDefaults.MediumContainerHeight
            // Every action shares one calm style; the one under the pointer lights up.
            Staggered(1, stagger) {
                HoverAction(AlkalineIcons.Play, "Singleplayer", large, morph = MenuPage.SINGLEPLAYER) {
                    controller.open(MenuPage.SINGLEPLAYER)
                }
            }
            Staggered(2, stagger) {
                HoverAction(AlkalineIcons.Globe, "Multiplayer", large, morph = MenuPage.MULTIPLAYER) {
                    controller.open(MenuPage.MULTIPLAYER)
                }
            }
            Staggered(3, stagger) {
                HoverAction(AlkalineIcons.Tune, "Settings", large, morph = MenuPage.SETTINGS) {
                    controller.open(MenuPage.SETTINGS)
                }
            }
            Staggered(4, stagger) {
                HoverAction(AlkalineIcons.Power, "Quit", large) { controller.actions.quit() }
            }
        }
    }
}

@Composable
private fun Staggered(index: Int, animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) {
        content()
        return
    }
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

/**
 * Frosted surface used by every menu panel. A page's panel names its [morph] page: the title action that opened
 * it grows into it, and while that container flies the panel lets it stand in, then fades its content in.
 */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    morph: MenuPage? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = if (morph != null) LocalMorph.current else null
    val flying = morph != null && state?.flying(morph) == true
    Surface(
        shape = RoundedCornerShape(PanelRadius),
        color = if (flying) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = backdropAlpha(0.66f)),
        tonalElevation = 0.dp,
        modifier = (if (morph == null) modifier else modifier.morphTarget(state, morph))
            .glass(PanelRadius, enabled = !flying),
    ) {
        Column(
            (if (morph == null) Modifier else Modifier.morphContent(state, morph)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                Text(if (controller.singleplayer) "Opening ${controller.playAddress}" else "Joining",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (controller.singleplayer) controller.connectStatus ?: "Starting the server" else controller.playAddress,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = { controller.leave() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (controller.singleplayer) "Save and quit" else "Disconnect")
            }
        }
    }
}
