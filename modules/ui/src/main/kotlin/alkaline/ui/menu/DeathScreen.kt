package alkaline.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** GuiGameOver's enableButtonsTimer and GuiYesNo.setButtonDelay: 20 ticks before a choice can be made. */
private const val BUTTON_DELAY_MILLIS = 1000L

/**
 * GuiGameOver: the red wash over the game, "You died!" and the score, with Respawn as the one primary action.
 * Leaving asks first, as vanilla's "Are you sure you want to quit?" does. In hardcore the only way on is out.
 * Vanilla's "Title screen" is "Disconnect" here, as on the pause card: leaving goes back to the server list.
 */
@Composable
internal fun DeathScreen(controller: MenuController) {
    val death = controller.death ?: return
    var confirming by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        ready = false
        delay(BUTTON_DELAY_MILLIS)
        ready = true
    }
    // drawGradientRect(0x60500000, 0xA0803030), top to bottom.
    val wash = Brush.verticalGradient(listOf(Color(0x60500000), Color(0xA0803030)))
    Box(Modifier.fillMaxSize().background(wash), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (confirming) {
                Text("Are you sure you want to quit?", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { controller.respawn() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Respawn")
                }
                TextButton(onClick = { controller.leave() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect", color = if (ready) Color.White else Color.White.copy(alpha = 0.38f))
                }
                return@Column
            }
            Text(
                if (death.hardcore) "Game over!" else "You died!",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Text(
                buildAnnotatedString {
                    append("Score: ")
                    withStyle(SpanStyle(color = Color(0xFFFFFF55))) { append(death.score.toString()) }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            if (death.hardcore) {
                Text(
                    "You cannot respawn in hardcore mode!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(24.dp))
            if (death.hardcore) {
                Button(onClick = { controller.leave() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Leave server")
                }
            } else {
                Button(onClick = { controller.respawn() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Respawn")
                }
                TextButton(onClick = { confirming = true }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect", color = if (ready) Color.White else Color.White.copy(alpha = 0.38f))
                }
            }
        }
    }
}
