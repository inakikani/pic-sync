package com.inakikani.picsync.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Logs what the main (UI) thread is doing when it hasn't responded for [STALL_MS].
 * A frozen or blank screen then shows up in the log with the exact code that
 * blocks, without needing a debugger. Logs once per stall, then every 10 s while
 * it lasts. (A debugger paused on a breakpoint also triggers it; that's harmless.)
 * Started only in debuggable builds (see PicSyncApp).
 */
object MainThreadWatchdog {
    private const val STALL_MS = 2_000L

    @Volatile private var lastPong = SystemClock.uptimeMillis()

    fun start() {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        Thread({
            var reportedAt = 0L
            while (true) {
                main.post { lastPong = SystemClock.uptimeMillis() }
                Thread.sleep(1_000)
                val stalled = SystemClock.uptimeMillis() - lastPong
                if (stalled < STALL_MS) {
                    reportedAt = 0L
                    continue
                }
                val now = SystemClock.uptimeMillis()
                if (reportedAt != 0L && now - reportedAt < 10_000) continue
                reportedAt = now
                val stack = mainThread.stackTrace.take(40).joinToString("\n") { "    at $it" }
                AppLog.e("watchdog: main thread blocked for ${stalled / 1000.0} s, state ${mainThread.state}:\n$stack")
            }
        }, "pic-sync-watchdog").apply { isDaemon = true }.start()
    }
}
