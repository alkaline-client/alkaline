package alkaline.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The handful of 24dp glyphs the menu needs, drawn as original vector paths. */
object AlkalineIcons {
    private fun icon(name: String, stroke: Boolean, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (stroke) {
                path(
                    stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
                )
            } else {
                path(fill = SolidColor(Color.Black), pathBuilder = block)
            }
        }.build()

    val Play: ImageVector = icon("Play", false) {
        moveTo(8f, 5.5f); lineTo(19f, 12f); lineTo(8f, 18.5f); close()
    }

    val Globe: ImageVector = icon("Globe", true) {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        moveTo(3.5f, 9f); lineTo(20.5f, 9f)
        moveTo(3.5f, 15f); lineTo(20.5f, 15f)
        moveTo(12f, 3f); curveTo(8f, 7.5f, 8f, 16.5f, 12f, 21f)
        moveTo(12f, 3f); curveTo(16f, 7.5f, 16f, 16.5f, 12f, 21f)
    }

    val Tune: ImageVector = icon("Tune", true) {
        moveTo(4f, 7f); lineTo(14f, 7f); moveTo(18f, 7f); lineTo(20f, 7f)
        moveTo(16f, 5f); lineTo(16f, 9f)
        moveTo(4f, 17f); lineTo(6f, 17f); moveTo(10f, 17f); lineTo(20f, 17f)
        moveTo(8f, 15f); lineTo(8f, 19f)
    }

    val Keyboard: ImageVector = icon("Keyboard", true) {
        moveTo(4f, 6f); lineTo(20f, 6f); arcTo(1.5f, 1.5f, 0f, false, true, 21.5f, 7.5f); lineTo(21.5f, 16.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 20f, 18f); lineTo(4f, 18f); arcTo(1.5f, 1.5f, 0f, false, true, 2.5f, 16.5f)
        lineTo(2.5f, 7.5f); arcTo(1.5f, 1.5f, 0f, false, true, 4f, 6f); close()
        moveTo(6f, 10f); lineTo(6.01f, 10f); moveTo(10f, 10f); lineTo(10.01f, 10f)
        moveTo(14f, 10f); lineTo(14.01f, 10f); moveTo(18f, 10f); lineTo(18.01f, 10f)
        moveTo(8f, 14.5f); lineTo(16f, 14.5f)
    }

    val Power: ImageVector = icon("Power", true) {
        moveTo(12f, 3.5f); lineTo(12f, 11f)
        moveTo(7.05f, 6.05f); arcTo(7f, 7f, 0f, true, false, 16.95f, 6.05f)
    }

    val Back: ImageVector = icon("Back", true) {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }

    val Reset: ImageVector = icon("Reset", true) {
        moveTo(5f, 12f); arcTo(7f, 7f, 0f, true, false, 7.05f, 7.05f)
        moveTo(4f, 4f); lineTo(4f, 9f); lineTo(9f, 9f)
    }

    val Person: ImageVector = icon("Person", false) {
        moveTo(16f, 8f); arcTo(4f, 4f, 0f, true, true, 8f, 8f); arcTo(4f, 4f, 0f, true, true, 16f, 8f); close()
        moveTo(4f, 20f); curveTo(4f, 15.5f, 8f, 14f, 12f, 14f); curveTo(16f, 14f, 20f, 15.5f, 20f, 20f); close()
    }

    val Layout: ImageVector = icon("Layout", true) {
        moveTo(5f, 4f); lineTo(19f, 4f); arcTo(1f, 1f, 0f, false, true, 20f, 5f); lineTo(20f, 19f)
        arcTo(1f, 1f, 0f, false, true, 19f, 20f); lineTo(5f, 20f); arcTo(1f, 1f, 0f, false, true, 4f, 19f)
        lineTo(4f, 5f); arcTo(1f, 1f, 0f, false, true, 5f, 4f); close()
        moveTo(10f, 4f); lineTo(10f, 20f)
        moveTo(10f, 12f); lineTo(20f, 12f)
    }

    val Palette: ImageVector = icon("Palette", true) {
        moveTo(12f, 3f); curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f)
        curveTo(13.5f, 21f, 14f, 20f, 13.4f, 18.8f); curveTo(12.8f, 17.5f, 13.6f, 16f, 15f, 16f)
        lineTo(17f, 16f); curveTo(19.2f, 16f, 21f, 14.2f, 21f, 12f); curveTo(21f, 7f, 17f, 3f, 12f, 3f); close()
        moveTo(7.5f, 11.5f); lineTo(7.51f, 11.5f)
        moveTo(10f, 7.5f); lineTo(10.01f, 7.5f)
        moveTo(14.5f, 7.5f); lineTo(14.51f, 7.5f)
    }

    val Monitor: ImageVector = icon("Monitor", true) {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(4f, 16f); close()
        moveTo(9f, 20f); lineTo(15f, 20f)
        moveTo(12f, 16f); lineTo(12f, 20f)
    }

    val Terrain: ImageVector = icon("Terrain", true) {
        moveTo(3f, 19f); lineTo(9f, 9f); lineTo(13f, 15f); lineTo(16f, 11f); lineTo(21f, 19f); close()
        moveTo(17f, 6f); lineTo(17.01f, 6f)
    }

    val Image: ImageVector = icon("Image", true) {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 16f); lineTo(9f, 11f); lineTo(14f, 16f)
        moveTo(13f, 14f); lineTo(15.5f, 11.5f); lineTo(20f, 16f)
        moveTo(15f, 8.5f); lineTo(15.01f, 8.5f)
    }

    val Sparkle: ImageVector = icon("Sparkle", false) {
        moveTo(12f, 2.5f); curveTo(12.8f, 8f, 16f, 11.2f, 21.5f, 12f)
        curveTo(16f, 12.8f, 12.8f, 16f, 12f, 21.5f); curveTo(11.2f, 16f, 8f, 12.8f, 2.5f, 12f)
        curveTo(8f, 11.2f, 11.2f, 8f, 12f, 2.5f); close()
    }

    val Code: ImageVector = icon("Code", true) {
        moveTo(8f, 7f); lineTo(3f, 12f); lineTo(8f, 17f)
        moveTo(16f, 7f); lineTo(21f, 12f); lineTo(16f, 17f)
        moveTo(13.5f, 5f); lineTo(10.5f, 19f)
    }

    val Check: ImageVector = icon("Check", true) {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }

    val Warning: ImageVector = icon("Warning", true) {
        moveTo(12f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
        moveTo(12f, 10f); lineTo(12f, 14f)
        moveTo(12f, 17f); lineTo(12.01f, 17f)
    }

    val Restart: ImageVector = icon("Restart", true) {
        moveTo(20f, 12f); arcTo(8f, 8f, 0f, true, true, 17.66f, 6.34f)
        moveTo(20f, 4f); lineTo(20f, 9f); lineTo(15f, 9f)
    }

    val Sync: ImageVector = icon("Sync", true) {
        moveTo(4f, 12f); arcTo(8f, 8f, 0f, false, true, 18f, 6.7f)
        moveTo(18f, 3f); lineTo(18f, 7f); lineTo(14f, 7f)
        moveTo(20f, 12f); arcTo(8f, 8f, 0f, false, true, 6f, 17.3f)
        moveTo(6f, 21f); lineTo(6f, 17f); lineTo(10f, 17f)
    }

    val ChevronDown: ImageVector = icon("ChevronDown", true) {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }

    val Search: ImageVector = icon("Search", true) {
        moveTo(17f, 10.5f); arcTo(6.5f, 6.5f, 0f, true, true, 4f, 10.5f); arcTo(6.5f, 6.5f, 0f, true, true, 17f, 10.5f)
        moveTo(15.2f, 15.2f); lineTo(20f, 20f)
    }

    val Add: ImageVector = icon("Add", true) {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Edit: ImageVector = icon("Edit", true) {
        moveTo(4f, 20f); lineTo(4.5f, 16f); lineTo(15.5f, 5f); lineTo(19f, 8.5f); lineTo(8f, 19.5f); close()
        moveTo(13f, 7.5f); lineTo(16.5f, 11f)
    }

    val Delete: ImageVector = icon("Delete", true) {
        moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f)
        moveTo(9.5f, 6.5f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(14.5f, 6.5f)
        moveTo(6.5f, 6.5f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 6.5f)
    }

    val Refresh: ImageVector = icon("Refresh", true) {
        moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 16.95f, 7.05f)
        moveTo(20f, 4f); lineTo(20f, 9f); lineTo(15f, 9f)
    }

    val ChevronUp: ImageVector = icon("ChevronUp", true) {
        moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f)
    }

    val SignOut: ImageVector = icon("SignOut", true) {
        moveTo(10f, 4f); lineTo(5f, 4f); lineTo(5f, 20f); lineTo(10f, 20f)
        moveTo(14f, 8f); lineTo(18f, 12f); lineTo(14f, 16f)
        moveTo(9f, 12f); lineTo(18f, 12f)
    }

    val Close: ImageVector = icon("Close", true) {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val Folder: ImageVector = icon("Folder", true) {
        moveTo(3.5f, 6f); lineTo(9.5f, 6f); lineTo(11.5f, 8f); lineTo(20.5f, 8f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
    }

    val Grid: ImageVector = icon("Grid", true) {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }

    val Plugin: ImageVector = icon("Plugin", true) {
        moveTo(9f, 3.5f); lineTo(9f, 8f); moveTo(15f, 3.5f); lineTo(15f, 8f)
        moveTo(6f, 8f); lineTo(18f, 8f); lineTo(18f, 12f); arcTo(6f, 6f, 0f, false, true, 6f, 12f); close()
        moveTo(12f, 18f); lineTo(12f, 21f)
    }

    val Music: ImageVector = icon("Music", true) {
        moveTo(9f, 18f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 16f)
        moveTo(9f, 18f); arcTo(3f, 3f, 0f, true, true, 3f, 18f); arcTo(3f, 3f, 0f, true, true, 9f, 18f)
        moveTo(20f, 16f); arcTo(3f, 3f, 0f, true, true, 14f, 16f); arcTo(3f, 3f, 0f, true, true, 20f, 16f)
    }
}
