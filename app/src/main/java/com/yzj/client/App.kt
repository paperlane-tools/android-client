package com.yzj.client

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.webkit.WebView

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = currentProcessName()
        val suffix = process.substringAfter(":", "main").ifEmpty { "main" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("yzj_$suffix") }
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName() ?: packageName
        }
        val pid = Process.myPid()
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.let { return it.processName }
        return packageName
    }
}
