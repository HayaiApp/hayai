package dev.ahmedmohamed.hayai.process

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

object HayaiProcessPolicy {
    fun isMainProcess(context: Context): Boolean {
        val processName =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
        return processName == context.packageName
    }
}
