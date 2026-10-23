package alkaline.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import alkaline.client.modules.Modules
import alkaline.client.settings.ClientSettings
import alkaline.config.ConfigStore
import alkaline.renderer.asset.MinecraftInstallation
import alkaline.renderer.asset.ZipAssetSource
import alkaline.ui.hud.GameHud
import alkaline.ui.hud.HudEditor
import alkaline.ui.hud.HudFrame
import alkaline.ui.hud.HudSprites
import alkaline.ui.hud.LocalHudSprites
import alkaline.ui.theme.AlkalineTheme
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Previews the in-game HUD with sample values: the default profile, or a profile JSON file given as the argument
 * (a profile from ~/.alkaline/legacy/profiles). {@code --edit} opens the HUD editor, which writes moves back to that
 * file. {@code --menu} previews the title screen instead.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--menu") {
        MenuPreview.show()
        return
    }
    val edit = "--edit" in args
    val file = args.firstOrNull { !it.startsWith("--") }?.let(Path::of)
        ?: Files.createTempFile("alkaline-hud-preview", ".json").also { Files.delete(it) }
    val profile = ConfigStore(Modules.SCHEMA, file)
    profile.load().issues().forEach { System.err.println("Profile: $it") }
    val sprites = MinecraftInstallation.locateClientJar().map { HudSprites(ZipAssetSource(it)) }.orElse(null)
    application {
        Window(
            onCloseRequest = {
                profile.close()
                exitApplication()
            },
            title = "Alkaline HUD Preview · ${file.fileName}",
            state = rememberWindowState(width = 1280.dp, height = 720.dp),
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.F2) {
                    val window = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
                    if (window != null) saveScreenshot(window)
                    true
                } else {
                    false
                }
            },
        ) {
            HudPreview(profile, sprites, edit)
        }
    }
}

@Composable
private fun HudPreview(profile: ConfigStore, sprites: HudSprites?, edit: Boolean) {
    val editor = androidx.compose.runtime.remember { if (edit) HudEditor() else null }
    AlkalineTheme(ClientSettings.Theme.MONET_DARK) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF15202A))) {
            WorldBackdrop(Modifier.fillMaxSize())
            CompositionLocalProvider(LocalHudSprites provides sprites) {
                GameHud(HudFrame.SAMPLE, profile, editor, onDone = { println("Saved to ${profile.file()}") })
            }
        }
    }
}

internal fun writePng(image: java.awt.image.BufferedImage, destination: Path) {
    check(ImageIO.write(image, "png", destination.toFile())) {
        "No PNG image writer is available."
    }
}

private fun saveScreenshot(owner: Frame) {
    val dialog = FileDialog(owner, "Save HUD screenshot", FileDialog.SAVE).apply {
        file = "alkaline-hud-preview.png"
        isVisible = true
    }
    val destination = dialog.file?.let { selected ->
        val fileName = if (selected.endsWith(".png", ignoreCase = true)) selected else "$selected.png"
        Path.of(dialog.directory ?: ".", fileName)
    }
    dialog.dispose()
    if (destination == null) return

    Thread({
        val outcome = runCatching {
            val bounds = Rectangle(owner.locationOnScreen, owner.size)
            val image = Robot().createScreenCapture(bounds)
            writePng(image, destination)
        }
        SwingUtilities.invokeLater {
            outcome.onSuccess { println("Saved HUD preview screenshot: $destination") }
                .onFailure { failure -> System.err.println("Could not save HUD preview screenshot: ${failure.message}") }
        }
    }, "alkaline-preview-screenshot").apply { isDaemon = true }.start()
}

@Composable
private fun WorldBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val horizon = size.height * 0.63f
        drawRect(Color(0xFF243746), size = Size(size.width, horizon))
        drawRect(Color(0xFF17242C), topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
        val block = 40f
        var x = 0f
        while (x < size.width) {
            var y = horizon
            while (y < size.height) {
                val shade = if (((x / block).toInt() + (y / block).toInt()) % 2 == 0) 0xFF273B42 else 0xFF203239
                drawRect(Color(shade), topLeft = Offset(x, y), size = Size(block, block))
                y += block
            }
            x += block
        }
    }
}
