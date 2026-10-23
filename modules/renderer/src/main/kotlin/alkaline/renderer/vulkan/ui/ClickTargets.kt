package alkaline.renderer.vulkan.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties

/**
 * Whether a press lands on a control that vanilla would answer with GuiButton.playPressSound: an enabled
 * button, toggle or slider (GuiOptionSlider is a button too). Text fields stay silent, as vanilla's do. Only the
 * topmost layer under the point counts, so a dialog's scrim does not click for the page beneath it.
 */
internal object ClickTargets {
    fun pressable(owners: Collection<SemanticsOwner>, x: Float, y: Float): Boolean {
        val point = Offset(x, y)
        for (owner in owners.reversed()) {
            val root = owner.rootSemanticsNode
            if (root.boundsInRoot.contains(point)) {
                return pressable(root, point)
            }
        }
        return false
    }

    private fun pressable(node: SemanticsNode, point: Offset): Boolean {
        if (!node.boundsInRoot.contains(point)) return false
        val config = node.config
        if (SemanticsProperties.Disabled !in config && SemanticsActions.SetText !in config
            && (SemanticsActions.OnClick in config || SemanticsActions.SetProgress in config)
        ) {
            return true
        }
        return node.children.any { pressable(it, point) }
    }
}
