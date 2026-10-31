package alkaline.example.clickguard

import alkaline.plugin.AlkalinePlugin
import alkaline.plugin.Click
import alkaline.plugin.PluginContext
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Counts attack clicks and drops a press that comes sooner after the previous one than the "Minimum gap" setting
 * (a worn switch's double click). The counter is a HUD element; `/clickguard reset` and a bindable key reset it.
 */
class ClickGuard : AlkalinePlugin {
    private var clicks by mutableIntStateOf(0)
    private var dropped by mutableIntStateOf(0)
    private var lastPress = 0L

    override fun enable(context: PluginContext) {
        val drop = context.settings.boolean("drop")
        val gap = context.settings.integer("gap")
        val game = context.game

        game.onClick { click ->
            if (click.button != Click.Button.ATTACK) return@onClick
            val sincePrevious = click.timeMillis - lastPress
            lastPress = click.timeMillis
            if (drop.value && sincePrevious <= gap.value) {
                click.cancel()
                dropped++
            } else {
                clicks++
            }
        }
        game.onPress(context.settings.key("reset_key")) { reset() }
        game.command("clickguard", "reset · gap <ms>") { call ->
            when (call.args.firstOrNull()) {
                "reset" -> {
                    reset()
                    call.reply("Counter reset.")
                }
                "gap" -> {
                    val millis = call.args.getOrNull(1)?.toIntOrNull()
                    if (millis == null || millis !in 0..120) {
                        call.reply("§cUsage: /clickguard gap <0-120>")
                    } else {
                        gap.set(millis)
                        call.reply("Presses within $millis ms of the last are dropped.")
                    }
                }
                else -> call.reply("$clicks clicks, $dropped dropped. Gap ${gap.value} ms " +
                    (if (drop.value) "(dropping)." else "(counting only)."))
            }
        }
        context.hud("counter") {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
            ) {
                Text(
                    if (drop.value) "$clicks clicks · $dropped dropped" else "$clicks clicks",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        context.log.info("Dropping presses within ${gap.value} ms: ${drop.value}")
    }

    private fun reset() {
        clicks = 0
        dropped = 0
    }
}
