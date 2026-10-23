package alkaline.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlin.math.roundToInt

/**
 * The chat element while the chat is open: the whole scrollback (GuiNewChat keeps 100 messages), newest at the
 * bottom, and the player's line in one field under it. The messages look as they do closed; nothing else changes.
 */
@Composable
internal fun ChatBox(frame: HudFrame, chat: ChatInput) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium.copy(shadow = TextShadow)
    // One message row: the text's line, the pill's vertical padding, and the gap to the next row.
    val row = with(density) {
        (if (style.lineHeight.isSpecified) style.lineHeight.toDp() else style.fontSize.toDp() * 1.4f) + 5.dp
    }
    val scrollback = rememberLazyListState()
    DisposableEffect(chat, scrollback) {
        // The list is reversed: its later items are the older messages.
        chat.scroller = { lines -> scrollback.dispatchRawDelta(lines * with(density) { row.toPx() }) }
        onDispose { chat.scroller = null }
    }
    Column(Modifier.width(420.dp)) {
        LazyColumn(
            Modifier.heightIn(max = row * chat.visibleLines),
            state = scrollback,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(frame.chat) { line ->
                Text(
                    formatted(line.text, Color.White),
                    style = style,
                    color = Color.White,
                    modifier = Modifier.hudGlass(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.32f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ChatField(chat)
    }
}

@Composable
private fun ChatField(chat: ChatInput) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = MaterialTheme.typography.bodyLarge.copy(shadow = TextShadow)
    // Dressed like the messages above it, so the field reads as the next line to write.
    Box(
        Modifier.fillMaxWidth().hudGlass(12.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (chat.text.isEmpty()) {
            Text("Message or /command", style = style, color = Color.White.copy(alpha = 0.45f))
        }
        BasicTextField(
            value = TextFieldValue(chat.text, TextRange(chat.anchor, chat.caret)),
            onValueChange = { chat.edit(it.text, it.selection.start, it.selection.end) },
            singleLine = true,
            textStyle = style.copy(color = Color.White),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> chat.submit()
                    Key.Tab -> chat.tab()
                    Key.DirectionUp -> chat.older()
                    Key.DirectionDown -> chat.newer()
                    Key.PageUp -> chat.page(1)
                    Key.PageDown -> chat.page(-1)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        )
    }
}

/**
 * The wheel scrolls the scrollback from anywhere on screen while the chat is open, as GuiChat does. Nothing is
 * drawn; the field and the messages are part of the chat element.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatWheel(chat: ChatInput) {
    Box(
        Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll) { event ->
            // Compose scrolls down with positive deltas; up the scrollback is toward older messages.
            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (delta != 0f) chat.wheel(if (delta < 0f) 1 else -1, event.keyboardModifiers.isShiftPressed)
        },
    )
}
