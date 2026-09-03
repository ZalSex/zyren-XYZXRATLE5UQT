package com.whatsapp.bug.crash

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenCaptureActivity : Activity() {

    companion object {
        const val REQ_SCREEN = 201
    }

    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN && !resultSent) {
            resultSent = true
            val i = Intent(DeviceService.ACTION_SCREEN_CAPTURE_RESULT).apply {
                putExtra(DeviceService.EXTRA_SCREEN_RESULT_CODE, resultCode)
                putExtra(DeviceService.EXTRA_SCREEN_RESULT_DATA, data)
                setPackage(packageName)
            }
            sendBroadcast(i)
        }
        finish()
    }
}
