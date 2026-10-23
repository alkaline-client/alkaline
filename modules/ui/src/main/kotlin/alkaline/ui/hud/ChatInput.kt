package alkaline.ui.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** The chat being typed, for the HUD's chat element; null where there is no chat to type into (the HUD editor). */
val LocalChatInput = staticCompositionLocalOf<ChatInput?> { null }

/**
 * The chat line the player is typing and the rules around it, from GuiChat, GuiNewChat and ChatAllowedCharacters:
 * a line is cut at 100 characters, `§` and control characters are dropped as they are typed, Up and Down walk the
 * lines sent earlier, Tab completes through the server, and Enter sends the trimmed line and closes the chat.
 * State is Compose state so the field follows it; every method runs on the UI thread.
 */
class ChatInput(private val host: Host) {
    /** What the chat asks of the game. */
    interface Host {
        /** The player sent [message]: at most 100 characters, trimmed, never empty. */
        fun send(message: String)

        /** Asks the server to complete [text] (everything before the cursor); the answer arrives in [completed]. */
        fun complete(text: String)

        /** Shows [text] as a line in the chat itself (tab completion's candidates). */
        fun listing(text: String)

        /** Closes the chat. */
        fun close()
    }

    companion object {
        /** GuiChat sets the field's maximum length to this. */
        const val MAX_LENGTH = 100

        /** Sent lines kept for Up and Down. */
        const val HISTORY = 100

        /** Wheel notches move this many lines; Shift moves one (GuiChat.handleMouseInput). */
        const val WHEEL_LINES = 7

        /** ChatAllowedCharacters.isAllowedCharacter. */
        fun allowed(character: Char): Boolean = character != '§' && character >= ' ' && character != '\u007f'

        /** ChatAllowedCharacters.filterAllowedCharacters. */
        fun filter(text: String): String = text.filter(::allowed)

        private val FORMATTING = Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE)
    }

    /** Whether the chat is open. */
    var open by mutableStateOf(false)
        private set

    var text by mutableStateOf("")
        private set

    /** The selection's fixed end and the caret; equal when nothing is selected. */
    var anchor by mutableStateOf(0)
        private set
    var caret by mutableStateOf(0)
        private set

    /** Scrolls the scrollback by [Float] lines, positive toward older messages; set by the chat while it shows. */
    var scroller: ((Float) -> Unit)? = null

    /** Lines the scrollback shows at once (GuiNewChat.getLineCount at full height), and so what a page moves. */
    val visibleLines: Int get() = 20

    private val sent = ArrayList<String>()
    private var historyCursor = 0
    private var historyBuffer = ""
    private var waiting = false
    private var found = false
    private var matches = emptyList<String>()
    private var matchIndex = 0

    /** Opens the chat with [initial] typed ("/" for the command key). */
    fun open(initial: String = "") {
        text = filter(initial).take(MAX_LENGTH)
        anchor = text.length
        caret = text.length
        historyCursor = sent.size
        historyBuffer = ""
        resetCompletion()
        open = true
    }

    fun close() {
        open = false
        resetCompletion()
        scroller = null
    }

    /** A new session starts with no sent lines, as GuiNewChat.clearChatMessages does on leaving a world. */
    fun forget() {
        sent.clear()
        close()
    }

    /** The field changed to [next], with the selection [selectionStart] to [selectionEnd]. */
    fun edit(next: String, selectionStart: Int, selectionEnd: Int) {
        resetCompletion()
        val before = next.substring(0, selectionStart.coerceIn(0, next.length))
        val caretBefore = next.substring(0, selectionEnd.coerceIn(0, next.length))
        text = filter(next).take(MAX_LENGTH)
        anchor = filter(before).length.coerceAtMost(text.length)
        caret = filter(caretBefore).length.coerceAtMost(text.length)
    }

    /** Enter: sends the trimmed line, if it has anything, and closes the chat either way. */
    fun submit() {
        val message = text.trim()
        if (message.isNotEmpty()) {
            if (sent.lastOrNull() != message) {
                sent.add(message)
                if (sent.size > HISTORY) sent.removeAt(0)
            }
            host.send(message)
        }
        host.close()
    }

    /** Up: the line sent before the one shown (GuiChat.getSentHistory(-1)). */
    fun older() = walkHistory(-1)

    /** Down: the line sent after the one shown, then back to what was being typed. */
    fun newer() = walkHistory(1)

    private fun walkHistory(step: Int) {
        resetCompletion()
        val target = (historyCursor + step).coerceIn(0, sent.size)
        if (target == historyCursor) return
        if (target == sent.size) {
            show(historyBuffer)
        } else {
            if (historyCursor == sent.size) historyBuffer = text
            show(sent[target])
        }
        historyCursor = target
    }

    /** PageUp ([direction] 1, older) and PageDown (-1) move the scrollback a page less one line. */
    fun page(direction: Int) {
        scroller?.invoke(direction * (visibleLines - 1f))
    }

    /** The wheel: [notches] positive toward older messages. */
    fun wheel(notches: Int, shift: Boolean) {
        scroller?.invoke(notches * (if (shift) 1f else WHEEL_LINES.toFloat()))
    }

    /**
     * Tab (GuiChat.autocompletePlayerNames): cycles through the candidates the server already gave, or asks it for
     * some for the text before the caret.
     */
    fun tab() {
        waiting = false
        if (found) {
            cycle()
            return
        }
        val before = text.substring(0, caret)
        matches = emptyList()
        matchIndex = 0
        if (before.isNotEmpty()) {
            waiting = true
            host.complete(before)
        }
    }

    /**
     * The server's candidates (GuiChat.onAutocompleteResponse): their common prefix replaces the word being
     * typed when it adds something, and otherwise the first candidate does, and further Tabs go through the rest.
     */
    fun completed(candidates: List<String>) {
        if (!waiting) return
        found = false
        matches = candidates.filter { it.isNotEmpty() }
        val start = wordStart()
        val prefix = commonPrefix(candidates).replace(FORMATTING, "")
        if (prefix.isNotEmpty() && !text.substring(start).equals(prefix, ignoreCase = true)) {
            replaceWord(start, prefix)
        } else if (matches.isNotEmpty()) {
            found = true
            cycle()
        }
    }

    private fun cycle() {
        val start = wordStart()
        if (matchIndex >= matches.size) matchIndex = 0
        if (matches.size > 1) host.listing(matches.joinToString(", "))
        replaceWord(start, matches[matchIndex++].replace(FORMATTING, ""))
    }

    /** Where the word before the caret starts (GuiTextField.func_146197_a(-1, caret, false)). */
    private fun wordStart(): Int {
        var start = caret
        while (start > 0 && text[start - 1] != ' ') start--
        return start
    }

    private fun replaceWord(start: Int, replacement: String) {
        val tail = text.substring(caret)
        val head = text.substring(0, start)
        val room = (MAX_LENGTH - head.length - tail.length).coerceAtLeast(0)
        val inserted = filter(replacement).take(room)
        text = head + inserted + tail
        caret = head.length + inserted.length
        anchor = caret
    }

    private fun show(line: String) {
        text = line
        anchor = line.length
        caret = line.length
    }

    private fun resetCompletion() {
        waiting = false
        found = false
    }

    private fun commonPrefix(strings: List<String>): String {
        if (strings.isEmpty()) return ""
        var prefix = strings[0]
        for (other in strings) {
            var length = 0
            while (length < prefix.length && length < other.length && prefix[length] == other[length]) length++
            prefix = prefix.substring(0, length)
        }
        return prefix
    }
}
