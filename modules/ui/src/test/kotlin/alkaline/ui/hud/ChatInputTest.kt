package alkaline.ui.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatInputTest {
    private class Recorder : ChatInput.Host {
        val sent = ArrayList<String>()
        val completions = ArrayList<String>()
        val listings = ArrayList<String>()
        var closed = 0
        override fun send(message: String) { sent += message }
        override fun complete(text: String) { completions += text }
        override fun listing(text: String) { listings += text }
        override fun close() { closed++ }
    }

    private val host = Recorder()
    private val chat = ChatInput(host)

    private fun type(text: String) = chat.edit(text, text.length, text.length)

    private fun send(line: String) {
        chat.open()
        type(line)
        chat.submit()
    }

    @Test
    fun `section signs, control characters and DEL are dropped as they are typed`() {
        chat.open()
        type("a§b\u0000\t\n\u007fc")
        assertEquals("abc", chat.text)
        assertEquals(3, chat.caret)
        assertEquals("héllo wörld ✓", ChatInput.filter("héllo wörld ✓"))
    }

    @Test
    fun `filtering keeps the caret and selection on the same characters`() {
        chat.open()
        chat.edit("a§b§c", 2, 5)
        assertEquals("abc", chat.text)
        assertEquals(1, chat.anchor)
        assertEquals(3, chat.caret)
    }

    @Test
    fun `a line is cut at 100 characters`() {
        chat.open()
        type("x".repeat(150))
        assertEquals(100, chat.text.length)
        chat.open("/" + "y".repeat(200))
        assertEquals(100, chat.text.length)
        assertEquals(100, chat.caret)
    }

    @Test
    fun `opening with a slash starts a command with the caret after it`() {
        chat.open("/")
        assertTrue(chat.open)
        assertEquals("/", chat.text)
        assertEquals(1, chat.caret)
    }

    @Test
    fun `enter sends the trimmed line and closes`() {
        send("  hello  ")
        assertEquals(listOf("hello"), host.sent)
        assertEquals(1, host.closed)
    }

    @Test
    fun `an empty line sends nothing but still closes`() {
        send("   ")
        assertTrue(host.sent.isEmpty())
        assertEquals(1, host.closed)
    }

    @Test
    fun `up walks back through sent lines and down returns to what was being typed`() {
        send("one")
        send("two")
        send("three")
        chat.open()
        type("draft")
        chat.older()
        assertEquals("three", chat.text)
        chat.older()
        assertEquals("two", chat.text)
        chat.older()
        chat.older()
        assertEquals("one", chat.text)
        chat.newer()
        chat.newer()
        assertEquals("three", chat.text)
        assertEquals(5, chat.caret)
        chat.newer()
        assertEquals("draft", chat.text)
        chat.newer()
        assertEquals("draft", chat.text)
    }

    @Test
    fun `a line equal to the last one is not stored twice, but an earlier one is`() {
        send("a")
        send("a")
        send("b")
        send("a")
        chat.open()
        val seen = ArrayList<String>()
        repeat(5) {
            chat.older()
            seen += chat.text
        }
        assertEquals(listOf("a", "b", "a", "a", "a"), seen)
    }

    @Test
    fun `history keeps the last 100 lines`() {
        repeat(130) { send("line $it") }
        chat.open()
        repeat(200) { chat.older() }
        assertEquals("line 30", chat.text)
        repeat(99) { chat.newer() }
        assertEquals("line 129", chat.text)
    }

    @Test
    fun `leaving the server forgets the history`() {
        send("secret")
        chat.open()
        chat.forget()
        assertFalse(chat.open)
        chat.open()
        chat.older()
        assertEquals("", chat.text)
    }

    @Test
    fun `tab asks for completions of the text before the caret`() {
        chat.open()
        chat.edit("/ti me", 3, 3)
        chat.tab()
        assertEquals(listOf("/ti"), host.completions)
        chat.open()
        chat.tab()
        assertEquals(1, host.completions.size)
    }

    @Test
    fun `a common prefix completes the word first, then tab cycles the candidates`() {
        chat.open()
        type("say S")
        chat.tab()
        chat.completed(listOf("Steve", "Stella"))
        assertEquals("say Ste", chat.text)
        assertTrue(host.listings.isEmpty())
        chat.tab()
        assertEquals(2, host.completions.size)
        chat.completed(listOf("Steve", "Stella"))
        assertEquals("say Steve", chat.text)
        assertEquals(listOf("Steve, Stella"), host.listings)
    }

    @Test
    fun `candidates sharing more than what is typed extend the word`() {
        chat.open()
        type("/gamem")
        chat.tab()
        chat.completed(listOf("/gamemode", "/gamemode"))
        assertEquals("/gamemode", chat.text)
        assertEquals(9, chat.caret)
        assertTrue(host.listings.isEmpty())
    }

    @Test
    fun `tab cycles through the candidates and wraps`() {
        chat.open()
        type("tp A")
        chat.tab()
        chat.completed(listOf("Alex", "Alice", "Amy"))
        // The common prefix "A" adds nothing, so the first candidate goes in.
        assertEquals("tp Alex", chat.text)
        chat.tab()
        assertEquals("tp Alice", chat.text)
        chat.tab()
        assertEquals("tp Amy", chat.text)
        chat.tab()
        assertEquals("tp Alex", chat.text)
        assertEquals(1, host.completions.size)
    }

    @Test
    fun `typing cancels a pending completion and its answer is ignored`() {
        chat.open()
        type("hi")
        chat.tab()
        type("hix")
        chat.completed(listOf("hint"))
        assertEquals("hix", chat.text)
    }

    @Test
    fun `formatting codes in candidates are not typed`() {
        chat.open()
        type("a")
        chat.tab()
        chat.completed(listOf("§aAnna", "§aAnnie"))
        assertEquals("Ann", chat.text)
    }

    @Test
    fun `page and wheel scroll the scrollback in lines`() {
        val moved = ArrayList<Float>()
        chat.scroller = { moved += it }
        chat.page(1)
        chat.page(-1)
        chat.wheel(1, shift = false)
        chat.wheel(-1, shift = true)
        assertEquals(listOf(19f, -19f, 7f, -1f), moved)
        chat.close()
        assertNull(chat.scroller)
    }
}
