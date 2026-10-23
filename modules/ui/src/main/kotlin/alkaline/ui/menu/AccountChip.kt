package alkaline.ui.menu

import alkaline.client.account.AccountManager
import alkaline.renderer.vulkan.ui.glass
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val CHIP_HEIGHT = 44

@Composable
private fun rememberAccountState(accounts: AccountManager): State<AccountManager.State> {
    val state = remember(accounts) { mutableStateOf(accounts.state()) }
    DisposableEffect(accounts) {
        val listener = AccountManager.Listener { state.value = it }
        accounts.addListener(listener)
        state.value = accounts.state()
        onDispose { accounts.removeListener(listener) }
    }
    return state
}

/**
 * The Microsoft account, top left of the title screen: "Sign in" when signed out, progress while the browser
 * flow runs (click to reopen the page, x to cancel), and the player's face and name when signed in, which
 * reveals Sign out on click.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccountChip(controller: MenuController, modifier: Modifier = Modifier) {
    val accounts = controller.services.accounts()
    val state by rememberAccountState(accounts)
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is AccountManager.State.SignedIn) expanded = false
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val current = state) {
                is AccountManager.State.SignedOut -> {
                    Chip(onClick = { accounts.signIn(controller.actions::openUrl) }) {
                        Icon(AlkalineIcons.Person, null, Modifier.size(20.dp))
                        Text("Sign in", style = MaterialTheme.typography.labelLarge)
                    }
                }
                is AccountManager.State.SigningIn -> {
                    Chip(onClick = { current.url?.let(controller.actions::openUrl) }) {
                        LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                        Text(current.step, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    if (current.url != null) {
                        SmallChipButton(AlkalineIcons.Close, "Cancel sign-in") { accounts.cancel() }
                    }
                }
                is AccountManager.State.SignedIn -> {
                    Chip(onClick = { expanded = !expanded }) {
                        val face = current.face
                        val bitmap = remember(face) { face?.let { argbBitmap(it, AccountManager.FACE_SIZE, AccountManager.FACE_SIZE) } }
                        if (bitmap != null) {
                            PixelImage(bitmap, 26.dp, RoundedCornerShape(6.dp))
                        } else {
                            Icon(AlkalineIcons.Person, null, Modifier.size(22.dp))
                        }
                        Text(
                            current.profile.name, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
                        )
                    }
                    AnimatedVisibility(expanded, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        Chip(onClick = { expanded = false; accounts.signOut() }) {
                            Icon(AlkalineIcons.SignOut, null, Modifier.size(20.dp))
                            Text("Sign out", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        val notice = (state as? AccountManager.State.SignedOut)?.notice
        if (notice != null) {
            Text(
                notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.widthIn(max = 340.dp).padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Chip(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val (container, contentColor) = hoverColors(hovered)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container.value,
        contentColor = contentColor.value,
        interactionSource = interaction,
        modifier = Modifier.height(CHIP_HEIGHT.dp).glass((CHIP_HEIGHT / 2).dp),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.size(2.dp))
            content()
        }
    }
}

@Composable
private fun SmallChipButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val (container, contentColor) = hoverColors(hovered)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container.value,
        contentColor = contentColor.value,
        interactionSource = interaction,
        modifier = Modifier.size(CHIP_HEIGHT.dp).glass((CHIP_HEIGHT / 2).dp),
    ) {
        Icon(icon, description, Modifier.padding(12.dp))
    }
}
