package alkaline.renderer.vulkan.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.ColorType
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a Compose UI in-game. A dedicated thread owns an offscreen [ImageComposeScene] (Skia CPU raster),
 * renders only when the composition is invalidated or input arrives, and publishes premultiplied BGRA
 * frames that the render thread uploads with [alkaline.renderer.vulkan.resource.VulkanFrame.upload].
 *
 * Nothing here touches Vulkan, and the render thread never waits on Compose: it takes the newest finished
 * frame if there is one and keeps showing the previous texture otherwise.
 */
class ComposeLayerHost(
    width: Int,
    height: Int,
    density: Float,
    maxFramesPerSecond: Int = 144,
    private val content: @Composable () -> Unit,
) : AutoCloseable {
    /** A finished UI frame. Return it with [release] after uploading. */
    class Frame internal constructor(
        val width: Int,
        val height: Int,
        val pixels: ByteBuffer,
        internal val generation: Int,
    ) {
        /** Glass regions laid out in this frame, in pixels. */
        var glass: List<GlassRegion> = emptyList()
            internal set

        /** Named anchors laid out in this frame, in pixels. */
        var anchors: Map<String, SceneAnchor> = emptyMap()
            internal set

        /**
         * Rows that differ from the last frame the render thread took, as {@code start, end} pairs (end
         * exclusive), or null when the whole frame must be uploaded.
         */
        var rowBands: IntArray? = null
            internal set

        internal var sequence = 0L
    }

    private companion object {
        const val MIN_RATE = 10
        const val MAX_RATE = 1000
    }

    private sealed interface Command {
        data class Resize(val width: Int, val height: Int, val density: Float) : Command
        data class Pointer(
            val type: PointerEventType,
            val x: Float,
            val y: Float,
            val primary: Boolean,
            val secondary: Boolean,
            val button: PointerButton?,
            val scrollX: Float,
            val scrollY: Float,
        ) : Command
        data object Leave : Command
        data class Run(val action: () -> Unit) : Command
        /** An AWT key event id (pressed, released or typed), key code, character and modifier mask. */
        data class Key(val id: Int, val code: Int, val character: Char, val modifiers: Int) : Command
    }

    private val commands = ArrayBlockingQueue<Command>(1024)
    private val latest = AtomicReference<Frame?>(null)
    private val free = ConcurrentLinkedQueue<Frame>()
    private val glassRegistry = GlassRegistry()
    private val thread = Thread(::runLoop, "alkaline-ui").apply { isDaemon = true }

    /**
     * The most frames per second the UI renders, however often it is invalidated: the HUD cache rate. The render
     * thread keeps compositing the last frame in between, so a HUD that changes every frame still costs one Skia
     * raster and one upload per period, not per game frame.
     */
    @Volatile var maxFramesPerSecond: Int = maxFramesPerSecond.coerceIn(MIN_RATE, MAX_RATE)
        set(value) {
            val clamped = value.coerceIn(MIN_RATE, MAX_RATE)
            if (clamped != field) {
                field = clamped
                // Wake the loop so a raised rate applies to the frame it is waiting on.
                commands.offer(Command.Run {})
            }
        }

    @Volatile private var running = true
    @Volatile private var failure: Throwable? = null

    // UI-thread state.
    private var scene: ImageComposeScene? = null
    private var sceneWidth = width
    private var sceneHeight = height
    private var sceneDensity = density
    private var generation = 0
    private var primaryDown = false
    private var secondaryDown = false
    private var dirty = true

    init {
        require(width > 0 && height > 0) { "UI size must be positive." }
        thread.start()
    }

    /** Throws the UI thread's failure, if any, on the caller (render) thread. */
    fun checkHealthy() {
        failure?.let { throw IllegalStateException("Compose UI thread failed.", it) }
    }

    fun resize(width: Int, height: Int, density: Float) {
        if (width > 0 && height > 0) commands.offer(Command.Resize(width, height, density))
    }

    fun pointerMoved(x: Float, y: Float) {
        // Coalesce: a full queue only drops motion, never presses.
        commands.offer(Command.Pointer(PointerEventType.Move, x, y, primaryDownExternal, secondaryDownExternal, null, 0f, 0f))
    }

    @Volatile private var primaryDownExternal = false
    @Volatile private var secondaryDownExternal = false

    /** [button] 0 = primary, 1 = secondary, 2 = tertiary. */
    fun pointerButton(x: Float, y: Float, button: Int, pressed: Boolean) {
        if (button == 0) primaryDownExternal = pressed
        if (button == 1) secondaryDownExternal = pressed
        val pointerButton = when (button) {
            0 -> PointerButton.Primary
            1 -> PointerButton.Secondary
            else -> PointerButton.Tertiary
        }
        val type = if (pressed) PointerEventType.Press else PointerEventType.Release
        commands.put(Command.Pointer(type, x, y, primaryDownExternal, secondaryDownExternal, pointerButton, 0f, 0f))
    }

    fun scroll(x: Float, y: Float, deltaX: Float, deltaY: Float) {
        commands.offer(Command.Pointer(PointerEventType.Scroll, x, y, primaryDownExternal, secondaryDownExternal, null, deltaX, deltaY))
    }

    fun pointerLeft() {
        commands.offer(Command.Leave)
    }

    /**
     * Text typed on the keyboard (from the window system's text input, so layouts and dead keys are resolved),
     * delivered to the focused text field.
     */
    fun typed(text: String) {
        text.forEach { commands.offer(Command.Key(java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.VK_UNDEFINED, it, 0)) }
    }

    /**
     * An editing key pressed or released, as an AWT key code (Backspace, Delete, Enter, the arrows, Home, End),
     * with AWT modifier masks; letters arrive through [typed].
     */
    fun key(awtKeyCode: Int, pressed: Boolean, modifiers: Int) {
        val id = if (pressed) java.awt.event.KeyEvent.KEY_PRESSED else java.awt.event.KeyEvent.KEY_RELEASED
        commands.offer(Command.Key(id, awtKeyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED, modifiers))
    }

    /** The source AWT key events need; never shown (no peer is created). */
    private val keySource by lazy { java.awt.Canvas() }

    /** Runs [action] on the UI thread, for state changes that must happen there. */
    fun post(action: () -> Unit) {
        commands.put(Command.Run(action))
    }

    /** Newest unconsumed frame, or null. The caller must [release] it. */
    fun poll(): Frame? = latest.getAndSet(null)?.also { consumedSequence = it.sequence }

    /** The sequence of the last frame the render thread took; rows changed since then are re-sent. */
    @Volatile private var consumedSequence = 0L
    private var publishedSequence = 0L
    /** Rows changed since the last consumed frame (a superset is safe), and the previous frame's pixels. */
    private var pendingRows: BooleanArray? = null
    private var lastPixels: ByteBuffer? = null

    fun release(frame: Frame) {
        if (frame.generation == generationSnapshot) free.offer(frame) else MemoryUtil.memFree(frame.pixels)
    }

    @Volatile private var generationSnapshot = 0

    override fun close() {
        running = false
        thread.interrupt()
        thread.join(2_000)
        latest.getAndSet(null)?.let { MemoryUtil.memFree(it.pixels) }
        lastPixels?.let(MemoryUtil::memFree)
        lastPixels = null
        while (true) {
            val frame = free.poll() ?: break
            MemoryUtil.memFree(frame.pixels)
        }
    }

    private fun runLoop() {
        try {
            createScene()
            var lastRender = 0L
            while (running) {
                val minFrameNanos = 1_000_000_000L / maxFramesPerSecond
                val animating = scene!!.hasInvalidations()
                val waitNanos = if (animating || dirty) {
                    (lastRender + minFrameNanos - System.nanoTime()).coerceAtLeast(0L)
                } else {
                    100_000_000L
                }
                var command = if (waitNanos > 0) commands.poll(waitNanos, TimeUnit.NANOSECONDS) else commands.poll()
                while (command != null) {
                    apply(command)
                    command = commands.poll()
                }
                Snapshot.sendApplyNotifications()
                val now = System.nanoTime()
                if ((dirty || scene!!.hasInvalidations()) && now - lastRender >= minFrameNanos) {
                    render(now)
                    lastRender = now
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            scene?.close()
            scene = null
        }
    }

    /**
     * Marks the rows that differ from the previous frame, adds them to the rows not yet taken by the render
     * thread, and returns those as bands; null (upload everything) after a resize or when most rows changed.
     * A HUD update usually touches a few elements, so this turns a full-screen copy per change into a few bands.
     */
    private fun changedRows(pixels: ByteBuffer): IntArray? {
        val rowBytes = sceneWidth * 4
        val previous = lastPixels
        if (previous == null || previous.capacity() != pixels.capacity()) {
            lastPixels?.let(MemoryUtil::memFree)
            lastPixels = MemoryUtil.memAlloc(pixels.capacity()).also {
                MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), MemoryUtil.memAddress(it), pixels.capacity().toLong())
            }
            pendingRows = BooleanArray(sceneHeight) { true }
            return null
        }
        val pending = pendingRows ?: BooleanArray(sceneHeight).also { pendingRows = it }
        if (consumedSequence == publishedSequence) pending.fill(false)
        val source = MemoryUtil.memAddress(pixels)
        val copy = MemoryUtil.memAddress(previous)
        val longs = rowBytes / 8
        for (row in 0 until sceneHeight) {
            val base = row.toLong() * rowBytes
            var differs = false
            var index = 0
            while (index < longs) {
                if (MemoryUtil.memGetLong(source + base + index * 8L) != MemoryUtil.memGetLong(copy + base + index * 8L)) {
                    differs = true
                    break
                }
                index++
            }
            if (!differs && rowBytes % 8 != 0) {
                differs = MemoryUtil.memGetInt(source + base + rowBytes - 4) != MemoryUtil.memGetInt(copy + base + rowBytes - 4)
            }
            if (differs) {
                pending[row] = true
                MemoryUtil.memCopy(source + base, copy + base, rowBytes.toLong())
            }
        }
        var bands = IntArray(8)
        var count = 0
        var changed = 0
        var row = 0
        while (row < sceneHeight) {
            if (!pending[row]) {
                row++
                continue
            }
            val start = row
            while (row < sceneHeight && pending[row]) row++
            changed += row - start
            if (count + 2 > bands.size) bands = bands.copyOf(bands.size * 2)
            bands[count++] = start
            bands[count++] = row
        }
        return if (changed * 4 > sceneHeight * 3) null else bands.copyOf(count)
    }

    private fun createScene() {
        lastPixels?.let(MemoryUtil::memFree)
        lastPixels = null
        scene?.close()
        generation++
        generationSnapshot = generation
        while (true) {
            val stale = free.poll() ?: break
            MemoryUtil.memFree(stale.pixels)
        }
        scene = ImageComposeScene(sceneWidth, sceneHeight, Density(sceneDensity)) {
            CompositionLocalProvider(LocalGlassRegistry provides glassRegistry) {
                content()
            }
        }
        dirty = true
    }

    @OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    private fun apply(command: Command) {
        when (command) {
            is Command.Resize -> {
                if (command.width != sceneWidth || command.height != sceneHeight || command.density != sceneDensity) {
                    sceneWidth = command.width
                    sceneHeight = command.height
                    sceneDensity = command.density
                    createScene()
                }
            }
            is Command.Pointer -> {
                primaryDown = command.primary
                secondaryDown = command.secondary
                scene!!.sendPointerEvent(
                    eventType = command.type,
                    position = Offset(command.x, command.y),
                    scrollDelta = Offset(command.scrollX, command.scrollY),
                    timeMillis = System.currentTimeMillis(),
                    type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = primaryDown, isSecondaryPressed = secondaryDown),
                    button = command.button,
                )
                dirty = true
            }
            Command.Leave -> {
                scene!!.sendPointerEvent(eventType = PointerEventType.Exit, position = Offset(-1f, -1f))
                dirty = true
            }
            is Command.Run -> {
                command.action()
                dirty = true
            }
            is Command.Key -> {
                val event = java.awt.event.KeyEvent(keySource, command.id, System.currentTimeMillis(), command.modifiers,
                    command.code, command.character)
                // Compose 1.12 reads its own key event fields (the AWT event rides along as the native one).
                scene!!.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(
                    key = androidx.compose.ui.input.key.Key(command.code),
                    type = when (command.id) {
                        java.awt.event.KeyEvent.KEY_PRESSED -> androidx.compose.ui.input.key.KeyEventType.KeyDown
                        java.awt.event.KeyEvent.KEY_TYPED -> androidx.compose.ui.input.key.KeyEventType.Unknown
                        else -> androidx.compose.ui.input.key.KeyEventType.KeyUp
                    },
                    codePoint = if (command.id == java.awt.event.KeyEvent.KEY_TYPED) command.character.code else 0,
                    isCtrlPressed = command.modifiers and java.awt.event.InputEvent.CTRL_DOWN_MASK != 0,
                    isMetaPressed = command.modifiers and java.awt.event.InputEvent.META_DOWN_MASK != 0,
                    isAltPressed = command.modifiers and java.awt.event.InputEvent.ALT_DOWN_MASK != 0,
                    isShiftPressed = command.modifiers and java.awt.event.InputEvent.SHIFT_DOWN_MASK != 0,
                    nativeEvent = event,
                ))
                dirty = true
            }
        }
    }

    private fun render(nanoTime: Long) {
        val current = scene!!
        val image = current.render(nanoTime)
        dirty = false
        try {
            val pixmap = image.peekPixels() ?: error("Compose scene image is not CPU-backed.")
            check(pixmap.info.colorType == ColorType.BGRA_8888) {
                "Compose scene uses ${pixmap.info.colorType}; expected BGRA_8888."
            }
            val bytes = sceneWidth * sceneHeight * 4
            val frame = free.poll()?.takeIf { it.width == sceneWidth && it.height == sceneHeight }
                ?: Frame(sceneWidth, sceneHeight, MemoryUtil.memAlloc(bytes), generation)
            val rowBytes = pixmap.rowBytes
            val target = MemoryUtil.memAddress(frame.pixels)
            if (rowBytes == sceneWidth * 4) {
                MemoryUtil.memCopy(pixmap.addr, target, bytes.toLong())
            } else {
                for (row in 0 until sceneHeight) {
                    MemoryUtil.memCopy(pixmap.addr + row.toLong() * rowBytes, target + row.toLong() * sceneWidth * 4, sceneWidth * 4L)
                }
            }
            pixmap.close()
            frame.rowBands = changedRows(frame.pixels)
            frame.sequence = ++publishedSequence
            frame.glass = glassRegistry.snapshot()
            frame.anchors = glassRegistry.anchorSnapshot()
            latest.getAndSet(frame)?.let { release(it) }
        } finally {
            image.close()
        }
    }
}
