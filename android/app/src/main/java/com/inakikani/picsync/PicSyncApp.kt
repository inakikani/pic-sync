package com.inakikani.picsync

import android.app.Application
import android.content.pm.ApplicationInfo
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.data.MainThreadWatchdog
import com.inakikani.picsync.ui.environmentLine
import java.io.File

class PicSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(File(filesDir, "logs"))
        AppLog.i("==== app start: ${environmentLine(this)}")
        // Freeze detector: only in debuggable builds (Android Studio Run/Debug), not in release.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            MainThreadWatchdog.start()
            AppLog.i("startup: debuggable build, main-thread watchdog on")
        }
    }
}
