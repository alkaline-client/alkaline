package alkaline.ui.menu

import alkaline.renderer.vulkan.ui.glass
import alkaline.ui.hud.stripFormatting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The server offers its resource pack (vanilla's "Use server resource pack?" GuiYesNo): one calm card over the
 * game, Use pack as the primary action and Decline beside it. The answer is remembered for a saved server and can
 * be changed in its edit card.
 */
@Composable
internal fun ServerPackPrompt(controller: MenuController) {
    val offer = controller.serverPackOffer ?: return
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceContainer.copy(alpha = 0.82f),
            modifier = Modifier.width(380.dp).glass(24.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Use this server's resource pack?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    "${offer.server.stripFormatting()} has its own textures and sounds. They apply while you play " +
                        "here and are removed when you leave.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = { controller.answerServerPack(false) }) { Text("Decline") }
                    Button(onClick = { controller.answerServerPack(true) }) { Text("Use pack") }
                }
            }
        }
    }
}

/** The accepted pack downloading and applying, out of the way at the top while the player keeps playing. */
@Composable
internal fun ServerPackProgress(controller: MenuController, modifier: Modifier) {
    val progress = controller.serverPackProgress ?: return
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainer.copy(alpha = 0.82f),
        modifier = modifier.padding(top = 16.dp).width(260.dp).glass(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                controller.serverPackStatus,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 8.dp))
            if (progress < 0f) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
            }
        }
    }
}
