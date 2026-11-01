package alkaline.preview

import alkaline.client.ClientServices
import alkaline.client.settings.ClientSettings
import alkaline.renderer.asset.MinecraftInstallation
import alkaline.config.ConfigStore
import alkaline.ui.menu.AlkalineMenu
import alkaline.ui.menu.MenuActions
import alkaline.ui.menu.MenuController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Files

/**
 * Desktop design preview of the title screen. Frosted glass and the 3D preview are drawn by the in-game
 * Vulkan host, so here panels sit over a flat gradient; settings are kept in a temporary file.
 */
object MenuPreview {
    fun show() {
        val directory = Files.createTempDirectory("alkaline-menu-preview")
        val store = ConfigStore(ClientSettings.SCHEMA, directory.resolve("settings.json"))
        store.load()
        application {
            val services = ClientServices.open(directory, MinecraftInstallation.gameDirectory())
            val controller = MenuController(store, object : MenuActions {
                override fun quit() = exitApplication()
                override val versionLabel = "Alkaline dev · design preview"
            }, services)
            Window(
                onCloseRequest = { store.close(); exitApplication() },
                title = "Alkaline Menu Preview",
                state = rememberWindowState(width = 1280.dp, height = 760.dp),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Brush.linearGradient(
                            listOf(Color(0xFF8DB4E8), Color(0xFF3B6A4C), Color(0xFF1C2B22)),
                            start = Offset.Zero, end = Offset(0f, size.height),
                        ))
                    }
                    AlkalineMenu(controller)
                }
            }
        }
    }
}
