package alkaline.clientapp

import alkaline.renderer.vulkan.ui.ComposeLayerHost
import alkaline.ui.menu.AlkalineMenu
import alkaline.ui.menu.MenuController

/** Builds the in-game Compose host running Alkaline's title screen; a Java-friendly entry point. */
object MenuUi {
    @JvmStatic
    fun createHost(width: Int, height: Int, density: Float, controller: MenuController): ComposeLayerHost =
        ComposeLayerHost(width, height, density) { AlkalineMenu(controller) }
}
