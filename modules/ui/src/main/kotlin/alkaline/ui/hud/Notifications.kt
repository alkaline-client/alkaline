package alkaline.ui.hud

import alkaline.renderer.vulkan.ui.glass
import alkaline.ui.menu.FadingGlass
import alkaline.ui.theme.AlkalineIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/** A short message the Notifications element shows for a few seconds: what happened, and optionally why. */
class Notification @JvmOverloads constructor(
    val title: String,
    val text: String = "",
    val kind: Kind = Kind.INFO,
    /** Who posted it when it was not Alkaline itself, for example a plugin's name. */
    val source: String? = null,
) {
    enum class Kind { INFO, SUCCESS, WARNING }

    internal val id = ids.incrementAndGet()

    /** Visible from the moment it is posted (its enter animation plays), hidden when it expires. */
    internal val visibility = MutableTransitionState(false).apply { targetState = true }

    private companion object {
        val ids = AtomicLong()
    }
}

/** The notifications on screen, newest last. Post and read on the UI thread. */
class Notifications {
    internal val items = mutableStateListOf<Notification>()

    /** Shows [notification]; beyond [MAX_SHOWN] at once the oldest leaves early. */
    fun post(notification: Notification) {
        items.add(notification)
        val showing = items.filter { it.visibility.targetState }
        if (showing.size > MAX_SHOWN) showing.first().visibility.targetState = false
    }

    /** Hides everything at once (leaving a server). */
    fun clear() {
        items.forEach { it.visibility.targetState = false }
    }

    companion object {
        const val MAX_SHOWN = 4
    }
}

/** The client's notifications, for the HUD element; null where there are none (previews). */
val LocalNotifications = staticCompositionLocalOf<Notifications?> { null }

private val CardShape = RoundedCornerShape(16.dp)

/**
 * The notification stack: each card slides in from the side it is anchored to, stays [seconds], and folds away.
 * [newestAtBottom] puts the newest card at the bottom (a stack anchored low) rather than at the top. [blur]
 * frosts the scene behind each card; [hud] marks the glass as a HUD element's.
 */
@Composable
internal fun NotificationStack(
    notifications: Notifications,
    seconds: Int,
    newestAtBottom: Boolean,
    alignment: Alignment.Horizontal,
    blur: Boolean,
    hud: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = notifications.items
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = alignment) {
        val ordered = if (newestAtBottom) items.toList() else items.asReversed().toList()
        for (notification in ordered) {
            key(notification.id) {
                val visibility = notification.visibility
                LaunchedEffect(notification) {
                    delay(seconds * 1000L)
                    visibility.targetState = false
                }
                if (visibility.isIdle && !visibility.currentState && !visibility.targetState) {
                    LaunchedEffect(notification) { items.remove(notification) }
                }
                val fromRight = alignment != Alignment.Start
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = fadeIn(tween(220)) + expandVertically(tween(260)) +
                        slideInHorizontally(tween(320)) { if (fromRight) it / 3 else -it / 3 },
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(260, delayMillis = 120)),
                ) {
                    FadingGlass(220, 0, 200) { NotificationCard(notification, blur, hud) }
                }
            }
        }
    }
}

/** One frosted card: a tonal icon for its kind, the title, and the detail below it. */
@Composable
internal fun NotificationCard(notification: Notification, blur: Boolean, hud: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CardShape,
        color = scheme.surfaceContainer.copy(alpha = 0.72f),
        contentColor = scheme.onSurface,
        modifier = Modifier.width(300.dp).glass(16.dp, enabled = blur, hud = hud),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, container, content) = when (notification.kind) {
                Notification.Kind.INFO -> Triple(AlkalineIcons.Sparkle, scheme.secondaryContainer, scheme.onSecondaryContainer)
                Notification.Kind.SUCCESS -> Triple(AlkalineIcons.Check, scheme.primaryContainer, scheme.onPrimaryContainer)
                Notification.Kind.WARNING -> Triple(AlkalineIcons.Warning, scheme.errorContainer, scheme.onErrorContainer)
            }
            Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(notification.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(notification.text.ifEmpty { null }, notification.source).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
