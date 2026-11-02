package com.inakikani.picsync

import android.app.Application
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.ui.environmentLine
import java.io.File

class PicSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(File(filesDir, "logs"))
        AppLog.i("==== app start: ${environmentLine(this)}")
    }
}
