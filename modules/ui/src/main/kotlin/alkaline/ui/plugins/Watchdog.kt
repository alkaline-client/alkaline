package alkaline.ui.plugins

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Notices plugin code that holds a thread for seconds: an endless loop in a tick listener freezes the game, one in
 * `enable` freezes the interface. Java cannot stop a thread safely, so the watchdog reports, with the stuck
 * thread's stack, and the player decides; when the call returns, how long it took is logged too.
 *
 * Entering and leaving plugin code costs a thread-local read and a few volatile writes; the checking thread wakes
 * twice a second and only while plugins run.
 */
internal class Watchdog(
    private val stalled: (owner: PluginHost.Context, where: String, thread: Thread, seconds: Long,
                          stack: List<StackTraceElement>) -> Unit,
    private val resumed: (owner: PluginHost.Context, where: String, millis: Long) -> Unit,
) : AutoCloseable {
    /** What one thread is doing in plugin code. */
    class Activity(val thread: Thread) {
        @Volatile var owner: PluginHost.Context? = null
        @Volatile var where: String = ""
        @Volatile var since = 0L
        @Volatile var reported = false
    }

    private val activities = CopyOnWriteArrayList<Activity>()
    private val local = ThreadLocal.withInitial { Activity(Thread.currentThread()).also { activities += it } }
    @Volatile private var thread: Thread? = null

    /** Marks this thread as running [owner]'s code; null when it already is (a nested call counts as the outer). */
    fun enter(owner: PluginHost.Context, where: String): Activity? {
        val activity = local.get()
        if (activity.owner != null) return null
        activity.where = where
        activity.since = System.nanoTime()
        activity.reported = false
        activity.owner = owner
        return activity
    }

    fun exit(activity: Activity?) {
        if (activity == null) return
        val owner = activity.owner
        activity.owner = null
        if (activity.reported && owner != null) {
            resumed(owner, activity.where, (System.nanoTime() - activity.since) / 1_000_000)
        }
    }

    inline fun <T> watch(owner: PluginHost.Context, where: String, block: () -> T): T {
        val activity = enter(owner, where)
        try {
            return block()
        } finally {
            exit(activity)
        }
    }

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread(::run, "alkaline-plugin-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(CHECK_MILLIS)
                val now = System.nanoTime()
                for (activity in activities) {
                    if (!activity.thread.isAlive) {
                        activities.remove(activity)
                        continue
                    }
                    val owner = activity.owner ?: continue
                    if (activity.reported) continue
                    val elapsed = now - activity.since
                    if (elapsed < STALL_NANOS) continue
                    val stack = activity.thread.stackTrace.toList()
                    // Still the same call (it may have returned while the stack was taken).
                    if (activity.owner !== owner) continue
                    activity.reported = true
                    stalled(owner, activity.where, activity.thread, elapsed / 1_000_000_000L, stack)
                }
            }
        } catch (interrupted: InterruptedException) {
            // Closed.
        }
    }

    @Synchronized
    override fun close() {
        thread?.interrupt()
        thread = null
    }

    private companion object {
        const val CHECK_MILLIS = 500L
        const val STALL_NANOS = 2_000_000_000L
    }
}
