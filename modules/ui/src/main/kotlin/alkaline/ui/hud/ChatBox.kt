package alkaline.ui.hud

import alkaline.ui.menu.Tooltip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlin.math.roundToInt

private val ChatWidth = 420.dp
private val ChatShape = RoundedCornerShape(16.dp)

/** Remaining characters are shown once a line gets this close to [ChatInput.MAX_LENGTH], where vanilla cuts silently. */
private const val COUNT_FROM = 20

/** The part of a chat line under the pointer, and where the pointer is (root coordinates). */
private class ChatHover {
    var serial by mutableStateOf(-1L)
    var action by mutableStateOf<HudChatAction?>(null)
    var at by mutableStateOf(Offset.Zero)

    fun clear(owner: Long) {
        if (serial == owner) {
            serial = -1L
            action = null
        }
    }
}

/**
 * The chat element while the chat is open: one frosted surface holding the whole scrollback (GuiNewChat keeps 100
 * messages), newest at the bottom, and under it the line being typed. Parts of messages the server made clickable
 * underline under the pointer and show their hover text; Tab's candidates sit above the field, and a clicked link
 * asks before it opens.
 */
@Composable
internal fun ChatBox(frame: HudFrame, chat: ChatInput) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium.copy(shadow = TextShadow, color = Color.White)
    // One message row: the text's line and the gap to the next row.
    val row = with(density) {
        (if (style.lineHeight.isSpecified) style.lineHeight.toDp() else style.fontSize.toDp() * 1.4f) + 2.dp
    }
    val scrollback = rememberLazyListState()
    DisposableEffect(chat, scrollback) {
        // The list is reversed: its later items are the older messages.
        chat.scroller = { lines -> scrollback.dispatchRawDelta(lines * with(density) { row.toPx() }) }
        onDispose { chat.scroller = null }
    }
    // Items are keyed by arrival, so a reader scrolled up stays on what they read; at the bottom, follow new lines.
    LaunchedEffect(frame.chat.firstOrNull()?.serial) {
        if (scrollback.firstVisibleItemIndex <= 1) scrollback.scrollToItem(0)
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160, easing = FastOutSlowInEasing)) }
    val hover = remember { ChatHover() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.width(ChatWidth).onGloballyPositioned { origin = it.positionInRoot() }.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 8.dp.toPx()
        },
    ) {
        Column(
            Modifier.fillMaxWidth().hudGlass(16.dp).clip(ChatShape).background(Color.Black.copy(alpha = 0.42f))
                .padding(vertical = 8.dp),
        ) {
            if (frame.chat.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = row * chat.visibleLines),
                    state = scrollback,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    items(frame.chat, key = { it.serial }) { line -> ChatMessage(line, style, chat, hover) }
                }
            }
            if (chat.suggestions.isNotEmpty()) Suggestions(chat)
            if (chat.pendingUrl != null) LinkPrompt(chat) else ChatField(chat, style)
        }
        val shown = hover.action?.hover
        if (shown != null) {
            val at = hover.at - origin
            val gap = with(density) { 12.dp.roundToPx() }
            Tooltip(
                shown.split('\n'),
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Int.MAX_VALUE))
                    layout(0, 0) {
                        // Above and right of the pointer, like GuiScreen.drawHoveringText, so the line stays readable.
                        placeable.place(at.x.roundToInt() + gap, at.y.roundToInt() - placeable.height - gap / 2)
                    }
                },
            )
        }
    }
}

/** One received line; the parts that do something underline while hovered and act on a click. */
@Composable
private fun ChatMessage(line: HudChat, style: TextStyle, chat: ChatInput, hover: ChatHover) {
    val hovered = if (hover.serial == line.serial) hover.action else null
    val text = remember(line.text, hovered) { underlined(formatted(line.text, Color.White), hovered) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pointer = if (line.actions.isEmpty()) Modifier else Modifier.pointerInput(line) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val action = layout?.let { actionAt(line.actions, it, change.position) }
                when (event.type) {
                    PointerEventType.Exit -> hover.clear(line.serial)
                    PointerEventType.Press -> if (action != null) {
                        chat.click(action.click, action.value, action.insertion, event.keyboardModifiers.isShiftPressed)
                        change.consume()
                    }
                    else -> if (action == null) {
                        hover.clear(line.serial)
                    } else {
                        hover.serial = line.serial
                        hover.action = action
                        hover.at = (coordinates?.positionInRoot() ?: Offset.Zero) + change.position
                    }
                }
            }
        }
    }
    DisposableEffect(line.serial) { onDispose { hover.clear(line.serial) } }
    Text(
        text,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).onGloballyPositioned { coordinates = it }
            .then(pointer),
    )
}

/** The action under [position], when it is over a glyph of a line rather than past the line's end. */
private fun actionAt(actions: List<HudChatAction>, layout: TextLayoutResult, position: Offset): HudChatAction? {
    if (position.y < 0f || position.y > layout.size.height) return null
    val lineIndex = layout.getLineForVerticalPosition(position.y)
    if (position.x < layout.getLineLeft(lineIndex) || position.x > layout.getLineRight(lineIndex)) return null
    val offset = layout.getOffsetForPosition(position)
    return actions.lastOrNull { offset >= it.start && offset < it.end }
}

/** Underlines the hovered part when a click does something there. */
private fun underlined(text: AnnotatedString, action: HudChatAction?): AnnotatedString {
    if (action == null || (action.click == null && action.insertion == null)) return text
    val start = action.start.coerceIn(0, text.length)
    val end = action.end.coerceIn(start, text.length)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
    }
}

/** Tab's candidates, the one in the field highlighted and kept in view; the next Tab moves along them. */
@Composable
private fun Suggestions(chat: ChatInput) {
    val scheme = MaterialTheme.colorScheme
    val row = rememberLazyListState()
    LaunchedEffect(chat.suggestion) {
        if (chat.suggestion >= 0) row.animateScrollToItem(chat.suggestion)
    }
    LazyRow(
        Modifier.fillMaxWidth(),
        state = row,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(chat.suggestions) { index, candidate ->
            val selected = index == chat.suggestion
            Text(
                candidate,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) scheme.onPrimaryContainer else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (selected) scheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ChatField(chat: ChatInput, style: TextStyle) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (chat.text.isEmpty()) {
                Text("Message or /command", style = style, color = Color.White.copy(alpha = 0.45f))
            }
            BasicTextField(
                value = TextFieldValue(chat.text, TextRange(chat.anchor, chat.caret)),
                onValueChange = { chat.edit(it.text, it.selection.start, it.selection.end) },
                singleLine = true,
                textStyle = style,
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
        val left = ChatInput.MAX_LENGTH - chat.text.length
        if (left <= COUNT_FROM) {
            Text(
                left.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (left == 0) scheme.error else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * GuiConfirmOpenLink in the field's place: the address, Open as the one primary action, and Copy. Enter opens and
 * Escape goes back to typing.
 */
@Composable
private fun LinkPrompt(chat: ChatInput) {
    val url = chat.pendingUrl ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(url) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp).focusRequester(focus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    chat.openPending()
                    true
                } else {
                    false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Open link from chat?", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        TextButton(onClick = chat::copyPending) { Text("Copy") }
        Button(onClick = chat::openPending) { Text("Open") }
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
