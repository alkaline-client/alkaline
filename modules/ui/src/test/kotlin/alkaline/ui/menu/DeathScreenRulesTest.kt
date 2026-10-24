package alkaline.ui.menu

import alkaline.client.ClientServices
import alkaline.client.settings.ClientSettings
import alkaline.config.ConfigStore
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When GuiGameOver shows, what closes it, and what the other screens do while the player is dead. */
class DeathScreenRulesTest {
    private class Recorder : MenuActions {
        var respawns = 0
        var resumes = 0
        var leaves = 0
        override fun quit() {}
        override val versionLabel = "test"
        override fun respawn() { respawns++ }
        override fun resumeGame() { resumes++ }
        override fun leaveGame() { leaves++ }
    }

    private val directory: Path = Files.createTempDirectory("alkaline-death-test")
    private val store = ConfigStore(ClientSettings.SCHEMA, directory.resolve("settings.json"))
    private val actions = Recorder()
    private val controller = MenuController(store, actions,
        ClientServices.open(directory, directory.resolve("minecraft")))

    private fun playing() {
        controller.join("localhost")
        controller.joined()
        assertEquals(PlayPhase.PLAYING, controller.play)
    }

    @AfterEach
    fun close() {
        store.close()
    }

    @Test
    fun `dying opens the screen, takes the mouse back and ignores Escape`() {
        playing()
        controller.died(7, false)
        assertEquals(Overlay.DEATH, controller.overlay)
        assertEquals(7, controller.death?.score)
        assertFalse(controller.gameHasInput())
        assertTrue(controller.back())
        assertEquals(Overlay.DEATH, controller.overlay)
        assertEquals(PlayPhase.PLAYING, controller.play)
        controller.toggleClickGui()
        controller.openChat()
        controller.openInventory()
        assertEquals(Overlay.DEATH, controller.overlay)
    }

    @Test
    fun `respawn asks the server once and returns to the game`() {
        playing()
        controller.died(0, false)
        controller.respawn()
        controller.respawn()
        assertEquals(1, actions.respawns)
        assertEquals(Overlay.NONE, controller.overlay)
        assertNull(controller.death)
        assertTrue(controller.gameHasInput())
        assertEquals(1, actions.resumes)
    }

    @Test
    fun `a screen open at death stays, and the death screen follows when it closes`() {
        playing()
        controller.openChat()
        controller.died(0, false)
        assertEquals(Overlay.CHAT, controller.overlay)
        controller.closeOverlay()
        assertEquals(Overlay.DEATH, controller.overlay)
        assertEquals(0, actions.resumes)
    }

    @Test
    fun `the pause card stays on top until resumed`() {
        playing()
        controller.pause()
        controller.died(0, false)
        assertEquals(Overlay.NONE, controller.overlay)
        controller.resume()
        assertEquals(Overlay.DEATH, controller.overlay)
    }

    @Test
    fun `health coming back closes it, and leaving forgets it`() {
        playing()
        controller.died(0, false)
        controller.alive()
        assertEquals(Overlay.NONE, controller.overlay)
        assertEquals(0, actions.respawns)
        controller.died(3, true)
        assertTrue(controller.death?.hardcore == true)
        controller.left(null)
        assertNull(controller.death)
        assertEquals(Overlay.NONE, controller.overlay)
    }

    @Test
    fun `nothing shows outside a game`() {
        controller.died(0, false)
        assertNull(controller.death)
        assertEquals(Overlay.NONE, controller.overlay)
    }
}
