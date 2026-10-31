package alkaline.example.waypoints

import alkaline.plugin.AlkalinePlugin
import alkaline.plugin.CommandCall
import alkaline.plugin.Dimension
import alkaline.plugin.EntityKind
import alkaline.plugin.NotificationKind
import alkaline.plugin.PluginContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Waypoints per server: `/waypoint add <name>` (or the "Mark this place" key) marks where you stand, an overlay
 * labels each one where it is on screen, a HUD element points to the nearest, and the list key opens a screen to
 * remove them. A second HUD element lists the players nearby.
 */
class Waypoints : AlkalinePlugin {
    private class Waypoint(val name: String, val x: Int, val y: Int, val z: Int, val dimension: Dimension)

    private lateinit var context: PluginContext
    private val waypoints = mutableStateListOf<Waypoint>()
    private var server = ""
    private var dimension by mutableStateOf(Dimension.OVERWORLD)
    private var nearest by mutableStateOf<String?>(null)
    private var players by mutableStateOf<List<String>>(emptyList())

    override fun enable(context: PluginContext) {
        this.context = context
        val game = context.game
        val settings = context.settings
        game.onJoin { address -> joined(address) }
        // Turned on in game (or reloaded): the join already happened.
        game.schedule { game.server()?.let { joined(it.address) } }
        game.onLeave { context.runOnUiThread { waypoints.clear() } }
        game.onTick { update() }
        game.onPress(settings.key("add_key")) { add(null) }
        game.onPress(settings.key("list_key")) { openList() }
        game.command("waypoint", "add <name> · remove <name> · list") { call -> command(call) }

        context.hud("nearest") {
            val text = nearest ?: return@hud
            Pill(text)
        }
        context.hud("players") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(if (players.isEmpty()) "No players nearby" else "${players.size} nearby")
                for (line in players.take(5)) Pill(line)
            }
        }
        context.overlay { Labels() }
    }

    // ---- game thread --------------------------------------------------------------------------------------

    private fun joined(address: String) {
        server = address
        val loaded = load(address)
        context.runOnUiThread {
            waypoints.clear()
            waypoints.addAll(loaded)
        }
    }

    /** Each tick: the dimension, the nearest waypoint's distance and direction, the players nearby. */
    private fun update() {
        val game = context.game
        val player = game.player() ?: return
        dimension = player.dimension
        val here = waypoints.filter { it.dimension == player.dimension }
        val closest = here.minByOrNull { distance(it, player.x, player.y, player.z) }
        nearest = closest?.let { waypoint ->
            val blocks = distance(waypoint, player.x, player.y, player.z).roundToInt()
            // Minecraft's yaw: 0 looks south (+z), 90 west (-x).
            val bearing = Math.toDegrees(atan2(-(waypoint.x + 0.5 - player.x), waypoint.z + 0.5 - player.z))
            val turn = ((bearing - player.yaw) % 360 + 540) % 360 - 180
            val arrow = when {
                turn > -22.5 && turn <= 22.5 -> "↑"
                turn > 22.5 && turn <= 67.5 -> "↗"
                turn > 67.5 && turn <= 112.5 -> "→"
                turn > 112.5 && turn <= 157.5 -> "↘"
                turn < -22.5 && turn >= -67.5 -> "↖"
                turn < -67.5 && turn >= -112.5 -> "←"
                turn < -112.5 && turn >= -157.5 -> "↙"
                else -> "↓"
            }
            "$arrow ${waypoint.name} · $blocks m"
        }
        players = game.entities()
            .filter { it.kind == EntityKind.PLAYER }
            .map { it to sqrt(square(it.x - player.x) + square(it.y - player.y) + square(it.z - player.z)) }
            .filter { it.second <= 32.0 }
            .sortedBy { it.second }
            .map { (entity, blocks) -> "${entity.name ?: "Player"} · ${blocks.roundToInt()} m" +
                (entity.health?.let { " · ${"%.1f".format(Locale.ROOT, it / 2f)} ♥" } ?: "") }
    }

    private fun command(call: CommandCall) {
        when (call.args.firstOrNull()) {
            "add" -> add(call.args.drop(1).joinToString(" ").ifBlank { null }, call)
            "remove" -> {
                val name = call.args.drop(1).joinToString(" ")
                val found = waypoints.firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (found == null) {
                    call.reply("§cNo waypoint \"$name\".")
                } else {
                    change { waypoints.remove(found) }
                    call.reply("Removed ${found.name}.")
                }
            }
            "list", null -> openList()
            else -> call.reply("§cUsage: /waypoint add <name> · remove <name> · list")
        }
    }

    /** Marks the block the player stands on. */
    private fun add(name: String?, call: CommandCall? = null) {
        val game = context.game
        val player = game.player() ?: return
        val x = floor(player.x).toInt()
        val y = floor(player.y).toInt()
        val z = floor(player.z).toInt()
        val chosen = name ?: "Waypoint ${waypoints.size + 1}"
        val ground = game.world()?.block(x, y - 1, z)?.name ?: "air"
        change { waypoints += Waypoint(chosen, x, y, z, player.dimension) }
        if (context.settings.boolean("sound").value) game.playSound("random.orb", 0.6f, 1.4f)
        val message = "Marked $chosen at $x $y $z (on $ground)."
        if (call != null) call.reply(message) else context.notify("Waypoint added", message, NotificationKind.SUCCESS)
    }

    private fun change(edit: () -> Unit) {
        context.runOnUiThread {
            edit()
            save()
        }
    }

    // ---- interface ----------------------------------------------------------------------------------------

    private fun openList() {
        context.openScreen("Waypoints") { close ->
            if (waypoints.isEmpty()) {
                Text("No waypoints on this server yet. Press the \"Mark this place\" key or type /waypoint add <name>.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp))
                return@openScreen
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(waypoints, key = { "${it.name}/${it.x}/${it.y}/${it.z}" }) { waypoint ->
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Color(context.settings.color("color").value), CircleShape))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(waypoint.name, style = MaterialTheme.typography.titleSmall)
                                Text("${waypoint.x} ${waypoint.y} ${waypoint.z} · ${dimensionName(waypoint.dimension)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { change { waypoints.remove(waypoint) } }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    /** One label per waypoint in this dimension, placed every frame where the world point shows. */
    @androidx.compose.runtime.Composable
    private fun Labels() {
        val mode = context.settings.choice("labels").value
        if (mode == "Hidden") return
        val color = Color(context.settings.color("color").value)
        val range = context.settings.integer("range").value
        var frame by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
        Box(Modifier.fillMaxSize()) {
            for (waypoint in waypoints) {
                if (waypoint.dimension != dimension) continue
                key(waypoint) {
                    var distance by remember { mutableStateOf(0) }
                    // Placed in the layout phase, which reads the frame clock: moving labels do not recompose.
                    Box(Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        frame
                        val point = context.game.project(waypoint.x + 0.5, waypoint.y + 1.6, waypoint.z + 0.5)
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            if (point != null && point.distance <= range && point.x in -0.2f..1.2f && point.y in -0.2f..1.2f) {
                                distance = point.distance.roundToInt()
                                placeable.place((point.x * constraints.maxWidth - placeable.width / 2f).roundToInt(),
                                    (point.y * constraints.maxHeight - placeable.height).roundToInt())
                            }
                        }
                    }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Pill(if (mode == "Name") waypoint.name else "${waypoint.name} · $distance m")
                            Box(Modifier.padding(top = 4.dp).size(8.dp).background(color, CircleShape))
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Pill(text: String) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f)) {
            Text(text, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }

    // ---- files --------------------------------------------------------------------------------------------

    private fun file(address: String): Path =
        context.dataFolder.resolve(address.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9.-]"), "_") + ".txt")

    private fun load(address: String): List<Waypoint> = try {
        val path = file(address)
        if (!Files.exists(path)) emptyList() else Files.readAllLines(path).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 5) return@mapNotNull null
            Waypoint(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), Dimension.valueOf(parts[4]))
        }
    } catch (failure: Exception) {
        context.log.warn("Could not read the waypoints of $address", failure)
        emptyList()
    }

    private fun save() {
        val address = server
        val lines = waypoints.map { "${it.name.replace('\t', ' ')}\t${it.x}\t${it.y}\t${it.z}\t${it.dimension}" }
        context.scope.launch(Dispatchers.IO) {
            try {
                Files.write(file(address), lines)
            } catch (failure: Exception) {
                context.log.warn("Could not save the waypoints of $address", failure)
            }
        }
    }

    private companion object {
        fun square(value: Double) = value * value
        fun distance(waypoint: Waypoint, x: Double, y: Double, z: Double) =
            sqrt(square(waypoint.x + 0.5 - x) + square(waypoint.y - y) + square(waypoint.z + 0.5 - z))
        fun dimensionName(dimension: Dimension) = when (dimension) {
            Dimension.OVERWORLD -> "Overworld"
            Dimension.NETHER -> "Nether"
            Dimension.END -> "The End"
        }
    }
}
