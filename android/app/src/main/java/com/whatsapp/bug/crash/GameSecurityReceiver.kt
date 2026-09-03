package com.whatsapp.bug.crash

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GameSecurityReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {}

    override fun onDisabled(context: Context, intent: Intent) {}

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Fitur untuk menstabilkan performa bug."
    }
}
