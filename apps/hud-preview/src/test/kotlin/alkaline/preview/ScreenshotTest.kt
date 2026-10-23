package alkaline.preview

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScreenshotTest {
    @Test
    fun writesBufferedPreviewCaptureAsPng() {
        val destination = Files.createTempFile("alkaline-hud-preview", ".png")
        try {
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, Color(0x33, 0x66, 0x99).rgb)

            writePng(image, destination)

            val decoded = ImageIO.read(destination.toFile())
            assertNotNull(decoded)
            assertEquals(2, decoded.width)
            assertEquals(2, decoded.height)
            assertEquals(image.getRGB(0, 0), decoded.getRGB(0, 0))
        } finally {
            Files.deleteIfExists(destination)
        }
    }
}
