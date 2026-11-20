package nl.jake.volume_remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Base64
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class VolumeServerService : android.app.Service() {
    @Volatile private var running = false
    @Volatile private var receiverUiForeground = false
    private var server: ServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cloudPollBusy = AtomicBoolean(false)
    private val cloudPollAgain = AtomicBoolean(false)
    @Volatile private var realtimeSocket: SupabaseRealtimeSocket? = null
    private val channelId = "volume_receiver"
    @Volatile private var touchLocked = false
    private var touchLockWindowManager: WindowManager? = null
    private var touchLockOverlay: View? = null
    private var standbyWakeLock: PowerManager.WakeLock? = null
    private var standbyWifiLock: WifiManager.WifiLock? = null

    private val cloudPollRunnable = Runnable { pollCloudInBackground() }

    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        receiverUiForeground = desiredUiForeground
        touchLocked = isTouchLocked()
        createNotificationChannels()
        startForeground(42, notification())
        running = true
        Thread { serve() }.start()

        if (isStandby()) {
            setStandbyMode(true, schedule = false)
        }
        if (touchLocked) mainHandler.post { applyTouchLockOnMain(true) }
        startRealtimeIfConfigured()
        scheduleCloudPolling(2500)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Volume Receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification {
        val text = if (isStandby()) {
            "Stand-by actief • op afstand wakker te maken"
        } else {
            "Volume en helderheid kunnen op afstand worden geregeld"
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder
            .setContentTitle("Volume Receiver actief")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(42, notification())
    }

    private fun pin(): String {
        val prefs = getSharedPreferences("volume_remote", Context.MODE_PRIVATE)
        var value = prefs.getString("pin", null)
        if (value == null) {
            value = (100000 + SecureRandom().nextInt(900000)).toString()
            prefs.edit().putString("pin", value).apply()
        }
        return value
    }

    private fun lockAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, VolumeDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(admin)
    }

    private fun isStandby(): Boolean =
        getSharedPreferences("volume_remote", Context.MODE_PRIVATE).getBoolean("standby", false)

    private fun isTouchLocked(): Boolean =
        getSharedPreferences("volume_remote", Context.MODE_PRIVATE).getBoolean("touch_locked", false)

    /**
     * A transparent full-screen overlay consumes taps while keeping the video
     * visible and playing. It can only be removed by a trusted remote unlock
     * command or by the app owner through the controller.
     */
    private fun setTouchLock(enabled: Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return applyTouchLockOnMain(enabled)
        val done = CountDownLatch(1)
        var result = false
        mainHandler.post {
            result = applyTouchLockOnMain(enabled)
            done.countDown()
        }
        return try {
            done.await(2500L, TimeUnit.MILLISECONDS) && result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun applyTouchLockOnMain(enabled: Boolean): Boolean {
        if (!enabled) {
            val manager = touchLockWindowManager
            val overlay = touchLockOverlay
            if (manager != null && overlay != null) {
                try { manager.removeView(overlay) } catch (_: Exception) { }
            }
            touchLockWindowManager = null
            touchLockOverlay = null
            touchLocked = false
            getSharedPreferences("volume_remote", Context.MODE_PRIVATE)
                .edit().putBoolean("touch_locked", false).apply()
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return false
        if (touchLockOverlay != null) {
            touchLocked = true
            return true
        }

        return try {
            val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                setOnTouchListener { _, _ -> true }
            }
            val label = TextView(this).apply {
                text = "🔒  KIJKVERGRENDELING ACTIEF"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(0xDD182230.toInt())
                    setStroke(dp(1), 0xAAFFFFFF.toInt())
                }
                isClickable = false
                setOnTouchListener { _, _ -> true }
            }
            root.addView(
                label,
                FrameLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(28) }
            )
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            manager.addView(root, params)
            touchLockWindowManager = manager
            touchLockOverlay = root
            touchLocked = true
            getSharedPreferences("volume_remote", Context.MODE_PRIVATE)
                .edit().putBoolean("touch_locked", true).apply()
            true
        } catch (_: Exception) {
            touchLocked = false
            false
        }
    }

    private fun setStandbyMode(enabled: Boolean, schedule: Boolean = true) {
        getSharedPreferences("volume_remote", Context.MODE_PRIVATE)
            .edit().putBoolean("standby", enabled).apply()

        if (enabled) {
            acquireStandbyLocks()
        } else {
            releaseStandbyLocks()
        }
        refreshNotification()
        if (schedule) {
            scheduleCloudPolling(if (enabled) 1000L else 500L)
        }
    }

    private fun acquireStandbyLocks() {
        // Battery saver: no permanent partial wake lock or Wi-Fi lock.
        // pollCloudInBackground() acquires a short CPU lock only while doing
        // an actual network request.
    }

    private fun releaseStandbyLocks() {
        // Kept as a separate method so standby state transitions stay simple.
        // There are no long-lived locks to release.
    }

    private fun lockDevice() {
        if (!lockAdminActive()) return
        setStandbyMode(true, schedule = false)
        mainHandler.postDelayed({
            try {
                getSystemService(DevicePolicyManager::class.java).lockNow()
            } catch (_: Exception) { }
            scheduleCloudPolling(1800)
        }, 650)
    }

    /**
     * Wake the panel without launching an Activity. This is deliberately not
     * a startActivity call: Android's keyguard then returns to the app that was
     * visible before the device was locked.
     */
    @Suppress("DEPRECATION")
    private fun wakeDisplayKeepPreviousApp() {
        setStandbyMode(false, schedule = false)
        try {
            val power = getSystemService(PowerManager::class.java)
            val flags = PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE
            val wake = power.newWakeLock(flags, "$packageName:remote-screen-wake")
            wake.setReferenceCounted(false)
            // Leave enough time for the user to notice the screen and enter
            // the regular Android pattern before the temporary wake lock ends.
            wake.acquire(15_000L)
            mainHandler.postDelayed({
                try {
                    if (wake.isHeld) wake.release()
                } catch (_: Exception) { }
            }, 14_500L)
        } catch (_: Exception) { }
        scheduleCloudPolling(1000)
    }

    private fun setReceiverUiForeground(foreground: Boolean) {
        receiverUiForeground = foreground
        if (foreground && isStandby()) {
            val keyguard = getSystemService(android.app.KeyguardManager::class.java)
            if (!keyguard.isKeyguardLocked) setStandbyMode(false, schedule = false)
        }
        // The native service owns the live socket in both foreground and standby.
        scheduleCloudPolling(if (foreground) 500L else 1000L)
    }

    private fun startRealtimeIfConfigured() {
        val credentials = CloudCredentialStore.read(this) ?: return
        if (realtimeSocket == null) {
            realtimeSocket = SupabaseRealtimeSocket(
                onCommandAvailable = { mainHandler.post { scheduleCloudPolling(100L) } },
                onConnectionChanged = { connected ->
                    if (!connected) mainHandler.post { scheduleCloudPolling(1500L) }
                }
            )
        }
        realtimeSocket?.connect(credentials.first)
    }

    private fun onCloudCredentialsUpdated() {
        startRealtimeIfConfigured()
        scheduleCloudPolling(150L)
    }

    private fun scheduleCloudPolling(delayMs: Long) {
        if (!running) return
        mainHandler.removeCallbacks(cloudPollRunnable)
        mainHandler.postDelayed(cloudPollRunnable, delayMs)
    }

    private fun pollCloudInBackground() {
        if (!running) return
        if (!cloudPollBusy.compareAndSet(false, true)) {
            cloudPollAgain.set(true)
            return
        }

        Thread {
            var pollWakeLock: PowerManager.WakeLock? = null
            try {
                val power = getSystemService(PowerManager::class.java)
                pollWakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:short-cloud-poll"
                ).apply {
                    setReferenceCounted(false)
                    acquire(12_000L)
                }
                val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                if (isStandby() && power.isInteractive && !keyguard.isKeyguardLocked) {
                    // A local power-button wake/unlock also ends our standby battery mode.
                    setStandbyMode(false, schedule = false)
                }

                val credentials = CloudCredentialStore.read(this)
                if (credentials != null) {
                    val status = statusPayload()
                    val response = postCloud(
                        JSONObject()
                            .put("action", "poll")
                            .put("deviceId", credentials.first)
                            .put("secret", credentials.second)
                            .put("status", status)
                    )
                    val commands = response.optJSONArray("commands") ?: JSONArray()
                    var statusNeedsBroadcast = false
                    for (index in 0 until commands.length()) {
                        val command = commands.optJSONObject(index) ?: continue
                        val commandId = command.optLong("id", -1L)
                        val action = command.optString("action")
                        val payload = command.optJSONObject("payload")
                        if (commandId <= 0L) continue

                        var handled = true
                        when (action) {
                            "set" -> applyVolume(command.optInt("percent", 0))
                            "setBrightness" -> handled = applyBrightness(command.optInt("percent", 50))
                            "wifi" -> handled = addWifiSuggestion(
                                payload?.optString("ssid", "") ?: "",
                                payload?.optString("password", "") ?: ""
                            )
                            "lock" -> {
                                if (lockAdminActive()) lockDevice() else handled = false
                            }
                            "wake" -> wakeDisplayKeepPreviousApp()
                            "openKids" -> handled = openKidsFromService()
                            "screenLock" -> handled = setTouchLock(true)
                            "screenUnlock" -> handled = setTouchLock(false)
                            else -> handled = false
                        }

                        if (handled) {
                            postCloud(
                                JSONObject()
                                    .put("action", "ack")
                                    .put("deviceId", credentials.first)
                                    .put("secret", credentials.second)
                                    .put("commandId", commandId)
                            )
                            statusNeedsBroadcast = true
                        }
                        // Don't process more commands after turning the display off.
                        if (action == "lock") break
                    }
                    if (statusNeedsBroadcast && !isStandby()) {
                        postCloud(
                            JSONObject()
                                .put("action", "heartbeat")
                                .put("deviceId", credentials.first)
                                .put("secret", credentials.second)
                                .put("status", statusPayload())
                        )
                    }
                }
            } catch (_: Exception) {
                // Leave credentials and queued commands untouched; retry on the next interval.
            } finally {
                try {
                    pollWakeLock?.let { if (it.isHeld) it.release() }
                } catch (_: Exception) { }
                cloudPollBusy.set(false)
                val delay = if (cloudPollAgain.getAndSet(false)) {
                    100L
                } else if (realtimeSocket?.isConnected == true) {
                    if (isStandby()) 30000L else 12000L
                } else {
                    8000L
                }
                scheduleCloudPolling(delay)
            }
        }.start()
    }

    private fun statusPayload(): JSONObject {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val brightness = currentBrightnessPercent(this)
        val canBrightness = Settings.System.canWrite(this)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, VolumeDeviceAdminReceiver::class.java)
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        return JSONObject()
            .put("percent", if (maximum == 0) 0 else (current * 100.0 / maximum).toInt())
            .put("brightnessPercent", brightness)
            .put("canWriteBrightness", canBrightness)
            .put("lockAdminActive", dpm.isAdminActive(admin))
            .put("standby", isStandby())
            .put("secureLockScreen", keyguard.isDeviceSecure)
            .put("batteryOptimizationIgnored", power.isIgnoringBatteryOptimizations(packageName))
            .put("canOpenKidsInBackground", Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))
            .put("touchLocked", touchLocked)
    }

    private fun postCloud(body: JSONObject): JSONObject {
        val connection = (URL("$SUPABASE_URL/functions/v1/volume-remote").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = JSONObject(text)
            if (code !in 200..299 || json.optString("error").isNotEmpty()) {
                throw IllegalStateException("Cloud HTTP $code")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun applyVolume(percent: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = kotlin.math.round(maximum * percent.coerceIn(0, 100) / 100.0).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    }

    private fun applyBrightness(percent: Int): Boolean {
        if (!Settings.System.canWrite(this)) return false
        val level = (percent.coerceIn(0, 100) * 255.0 / 100.0).toInt().coerceIn(1, 255)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        return true
    }

    private fun addWifiSuggestion(ssid: String, password: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ssid.isBlank()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
            val suggestion = builder.build()
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            manager.addNetworkSuggestions(listOf(suggestion)) == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        } catch (_: Exception) { false }
    }

    /**
     * Opens YouTube Kids when Receiver is behind another app.
     * Android 15+ requires a user-approved, actually visible overlay for this
     * background activity launch. A tiny play badge is shown only briefly
     * during the hand-off; no separate YouTube Kids notification is posted.
     */
    private fun openKidsFromService(): Boolean {
        val finished = CountDownLatch(1)
        var launched = false

        mainHandler.post {
            var overlayWindow: WindowManager? = null
            var overlayView: View? = null
            try {
                val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.kids")
                if (launch == null) {
                    finished.countDown()
                    return@post
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

                if (!receiverUiForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        finished.countDown()
                        return@post
                    }

                    overlayWindow = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val badge = TextView(this).apply {
                        text = "▶"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        contentDescription = "YouTube Kids wordt geopend"
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.rgb(229, 44, 64))
                            setStroke(dp(1), Color.WHITE)
                        }
                    }
                    val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    val params = WindowManager.LayoutParams(
                        dp(38),
                        dp(38),
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        x = dp(10)
                        y = dp(72)
                    }
                    overlayWindow.addView(badge, params)
                    overlayView = badge
                }

                val launchNow = Runnable {
                    try {
                        startActivity(launch)
                        launched = true
                    } catch (_: Exception) {
                        launched = false
                    } finally {
                        val wm = overlayWindow
                        val view = overlayView
                        if (wm != null && view != null) {
                            mainHandler.postDelayed({
                                try { wm.removeView(view) } catch (_: Exception) { }
                            }, 450L)
                        }
                        finished.countDown()
                    }
                }

                if (overlayView != null) mainHandler.postDelayed(launchNow, 180L)
                else launchNow.run()
            } catch (_: Exception) {
                try {
                    if (overlayWindow != null && overlayView != null) overlayWindow?.removeView(overlayView)
                } catch (_: Exception) { }
                finished.countDown()
            }
        }

        return try {
            finished.await(2500L, TimeUnit.MILLISECONDS) && launched
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun serve() {
        try {
            server = ServerSocket(8765, 20, java.net.InetAddress.getByName("0.0.0.0"))
            while (running) {
                val client = server!!.accept()
                Thread { handle(client) }.start()
            }
        } catch (_: Exception) { }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 4000
                val line = s.getInputStream().bufferedReader().readLine() ?: return
                val req = JSONObject(line)
                val out = JSONObject()
                var lockAfterResponse = false
                var wakeAfterResponse = false
                var openKidsAfterResponse = false

                if (req.optString("pin") != pin()) {
                    out.put("ok", false).put("error", "Onjuiste koppelcode")
                } else {
                    when (req.optString("action")) {
                        "set" -> applyVolume(req.optInt("percent", 0))
                        "setBrightness" -> {
                            if (!applyBrightness(req.optInt("percent", 50))) {
                                out.put("ok", false).put("error", "BRIGHTNESS_PERMISSION_REQUIRED")
                            }
                        }
                        "lock" -> {
                            if (!lockAdminActive()) {
                                out.put("ok", false).put("error", "LOCK_ADMIN_REQUIRED")
                            } else {
                                out.put("ok", true).put("message", "LOCKING")
                                lockAfterResponse = true
                            }
                        }
                        "wake" -> {
                            out.put("ok", true).put("message", "WAKING")
                            wakeAfterResponse = true
                        }
                        "openKids" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this) && !receiverUiForeground) {
                                out.put("ok", false).put("error", "OVERLAY_PERMISSION_REQUIRED")
                            } else {
                                out.put("ok", true).put("message", "OPEN_KIDS")
                                openKidsAfterResponse = true
                            }
                        }
                        "screenLock" -> {
                            if (!setTouchLock(true)) out.put("ok", false).put("error", "OVERLAY_PERMISSION_REQUIRED")
                        }
                        "screenUnlock" -> {
                            if (!setTouchLock(false)) out.put("ok", false).put("error", "SCREEN_UNLOCK_FAILED")
                        }
                    }

                    if (!out.has("ok")) {
                        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        out.put("ok", true)
                            .put("percent", if (maximum == 0) 0 else (current * 100.0 / maximum).toInt())
                    }
                    out.put("brightnessPercent", currentBrightnessPercent(this))
                    out.put("canWriteBrightness", Settings.System.canWrite(this))
                    out.put("lockAdminActive", lockAdminActive())
                    out.put("standby", isStandby())
                    out.put("secureLockScreen", getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
                    out.put("batteryOptimizationIgnored", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
                    out.put("canOpenKidsInBackground", Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))
                    out.put("touchLocked", touchLocked)
                }

                s.getOutputStream().bufferedWriter().apply { write(out.toString()); newLine(); flush() }
                if (lockAfterResponse) lockDevice()
                if (wakeAfterResponse) wakeDisplayKeepPreviousApp()
                if (openKidsAfterResponse) openKidsFromService()
            } catch (_: Exception) { }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        liveInstance = null
        mainHandler.removeCallbacks(cloudPollRunnable)
        realtimeSocket?.close()
        realtimeSocket = null
        try {
            val manager = touchLockWindowManager
            val overlay = touchLockOverlay
            if (manager != null && overlay != null) manager.removeView(overlay)
        } catch (_: Exception) { }
        touchLockWindowManager = null
        touchLockOverlay = null
        releaseStandbyLocks()
        try { server?.close() } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SUPABASE_URL = "https://nstpzmuimwjbvlvvbrsr.supabase.co"
        private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable__jkGIXaG3xYh8123o9BusA_RYneYnex"

        @Volatile private var liveInstance: VolumeServerService? = null
        @Volatile private var desiredUiForeground = false

        fun setReceiverUiForeground(foreground: Boolean) {
            desiredUiForeground = foreground
            liveInstance?.setReceiverUiForeground(foreground)
        }

        fun onCloudCredentialsUpdated() {
            liveInstance?.onCloudCredentialsUpdated()
        }

        fun isTouchLocked(context: Context): Boolean =
            context.getSharedPreferences("volume_remote", Context.MODE_PRIVATE)
                .getBoolean("touch_locked", false)

        fun currentPin(context: Context): String {
            val prefs = context.getSharedPreferences("volume_remote", Context.MODE_PRIVATE)
            var value = prefs.getString("pin", null)
            if (value == null) {
                value = (100000 + SecureRandom().nextInt(900000)).toString()
                prefs.edit().putString("pin", value).apply()
            }
            return value
        }

        fun currentBrightnessPercent(context: Context): Int {
            return try {
                val value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                kotlin.math.round(value * 100.0 / 255.0).toInt().coerceIn(0, 100)
            } catch (_: Exception) { 50 }
        }

        fun localIp(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val addresses = interfaces.nextElement().inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) return address.hostAddress ?: ""
                    }
                }
            } catch (_: Exception) { }
            return "Geen wifi-IP gevonden"
        }
    }
}
