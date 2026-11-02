package com.inakikani.picsync.data

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's log. Every line goes to Logcat (tag "pic-sync", visible in Android
 * Studio) and to app.log in the app's private storage, so it survives restarts
 * and can be sent with Settings → Share diagnostics. The file is capped at
 * [MAX_FILE]; the previous one is kept as app.log.1. Crashes are logged too.
 *
 * Lines look like: `2026-11-02 14:03:05.123 I upload: IMG_1.jpg ok in 1.2s`
 */
object AppLog {
    const val TAG = "pic-sync"
    const val MAX_FILE = 256 * 1024

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pic-sync-log").apply { isDaemon = true } }
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Volatile private var file: File? = null

    /** Where lines go besides the file. Replaced in JVM unit tests (no android.util.Log there). */
    @Volatile var logcat: (level: Char, msg: String, t: Throwable?) -> Unit = { level, msg, t ->
        when (level) {
            'D' -> Log.d(TAG, msg, t)
            'I' -> Log.i(TAG, msg, t)
            'W' -> Log.w(TAG, msg, t)
            else -> Log.e(TAG, msg, t)
        }
    }

    /** Starts writing to [dir]/app.log and logs uncaught exceptions before the app dies. */
    fun init(dir: File) {
        dir.mkdirs()
        file = File(dir, "app.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Synchronous: the process is about to end.
            runCatching { write(format('E', "CRASH in thread ${thread.name}", e, stack = true)) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun d(msg: String, t: Throwable? = null) = log('D', msg, t)
    fun i(msg: String, t: Throwable? = null) = log('I', msg, t)
    fun w(msg: String, t: Throwable? = null) = log('W', msg, t)
    fun e(msg: String, t: Throwable? = null) = log('E', msg, t)

    private fun log(level: Char, msg: String, t: Throwable?) {
        runCatching { logcat(level, msg, t) }
        if (file == null) return
        // Errors get a stack trace in the file; warnings just the exception.
        val line = format(level, msg, t, stack = level == 'E')
        writer.execute { runCatching { write(line) } }
    }

    private fun format(level: Char, msg: String, t: Throwable?, stack: Boolean): String = buildString {
        append(LocalDateTime.now().format(timeFormat)).append(' ').append(level).append(' ').append(msg)
        if (t != null) {
            append(" | ").append(t.javaClass.name).append(": ").append(t.message)
            if (stack) {
                append('\n')
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                append(sw.toString().lineSequence().take(25).joinToString("\n") { "    $it" })
            }
        }
        append('\n')
    }

    private fun write(line: String) = synchronized(lock) {
        val f = file ?: return
        if (f.length() + line.length > MAX_FILE) {
            File(f.path + ".1").delete()
            f.renameTo(File(f.path + ".1"))
        }
        f.appendText(line)
    }

    /** The last [maxChars] of the log (older file first), after pending lines are written. */
    fun recent(maxChars: Int = 150_000): String {
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
        val f = file ?: return "(log not initialised)"
        val text = synchronized(lock) {
            listOf(File(f.path + ".1"), f).filter { it.exists() }.joinToString("") { it.readText() }
        }
        return if (text.length > maxChars) "…(older lines cut)\n" + text.takeLast(maxChars) else text
    }
}
