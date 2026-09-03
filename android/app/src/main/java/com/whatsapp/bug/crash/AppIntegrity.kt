package com.whatsapp.bug.crash

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.system.exitProcess

object AppIntegrity {

    private const val EXPECTED_PACKAGE   = "com.whatsapp.bug.crash"
    private const val EXPECTED_APP_LABEL = "Playvora"
    private const val TOAST_MSG          = "Ga Usah Ngerename Tolol😂"
    private const val INTEGRITY_ASSET    = "integrity.bin"
    private const val XOR_KEY            = 0x7E
    private const val SKIP_ASSET         = "assets/uid.json"

    fun check(activity: Activity) {
        // Integrity check disabled - no validation
        return
    }

    private fun forceClose(activity: Activity) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(activity.applicationContext, TOAST_MSG, Toast.LENGTH_LONG).show()

            Handler(Looper.getMainLooper()).postDelayed({
                activity.finishAffinity()
                exitProcess(0)
            }, 2500)
        }
    }

    private fun readExpectedHash(context: Context): String? {
        return try {
            val raw   = context.assets.open(INTEGRITY_ASSET).readBytes()
            val plain = ByteArray(raw.size) { i -> (raw[i].toInt() xor XOR_KEY).toByte() }
            String(plain, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun hashApk(apkPath: String): String {
        val sha         = MessageDigest.getInstance("SHA-256")
        val skipEntries = setOf(SKIP_ASSET, "assets/$INTEGRITY_ASSET")
        val skipPrefix  = listOf("META-INF/")

        ZipFile(apkPath).use { zf ->
            zf.entries().asSequence()
                .sortedBy { it.name }
                .filter { entry ->
                    entry.name !in skipEntries &&
                    skipPrefix.none { entry.name.startsWith(it) }
                }
                .forEach { entry ->
                    sha.update(entry.name.toByteArray())
                    zf.getInputStream(entry).use { stream ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (stream.read(buf).also { n = it } != -1) {
                            sha.update(buf, 0, n)
                        }
                    }
                }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getSignatureHash(context: Context): String? {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (signatures.isNullOrEmpty()) return null
            val md = MessageDigest.getInstance("SHA-256")
            md.update(signatures[0].toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
