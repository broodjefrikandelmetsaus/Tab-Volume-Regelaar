package nl.jake.volume_remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
    private val channelId = "volume_receiver"
    private val kidsChannelId = "volume_remote_actions"
    private var standbyWakeLock: PowerManager.WakeLock? = null
    private var standbyWifiLock: WifiManager.WifiLock? = null

    private val cloudPollRunnable = Runnable { pollCloudInBackground() }

    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        receiverUiForeground = desiredUiForeground
        createNotificationChannels()
        startForeground(42, notification())
        running = true
        Thread { serve() }.start()

        if (isStandby()) {
            setStandbyMode(true, schedule = false)
        }
        scheduleCloudPolling(2500)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Volume Receiver", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(kidsChannelId, "Volume Remote opdrachten", NotificationManager.IMPORTANCE_DEFAULT)
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
        try {
            val power = getSystemService(PowerManager::class.java)
            if (standbyWakeLock == null || standbyWakeLock?.isHeld != true) {
                standbyWakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:standby-cloud-listener"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) { }

        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (standbyWifiLock == null || standbyWifiLock?.isHeld != true) {
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                standbyWifiLock = wifi.createWifiLock(mode, "$packageName:standby-cloud-wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) { }
    }

    private fun releaseStandbyLocks() {
        try {
            standbyWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        standbyWakeLock = null
        try {
            standbyWifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        standbyWifiLock = null
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
        if (foreground) {
            if (isStandby()) {
                val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                if (!keyguard.isKeyguardLocked) setStandbyMode(false, schedule = false)
            }
            mainHandler.removeCallbacks(cloudPollRunnable)
        } else {
            scheduleCloudPolling(1000)
        }
    }

    private fun scheduleCloudPolling(delayMs: Long) {
        if (!running || receiverUiForeground) return
        mainHandler.removeCallbacks(cloudPollRunnable)
        mainHandler.postDelayed(cloudPollRunnable, delayMs)
    }

    private fun pollCloudInBackground() {
        if (!running || receiverUiForeground || !cloudPollBusy.compareAndSet(false, true)) return

        Thread {
            try {
                val power = getSystemService(PowerManager::class.java)
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
                            "openKids" -> postOpenKidsNotification()
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
                        }
                        // Don't process more commands after turning the display off.
                        if (action == "lock") break
                    }
                }
            } catch (_: Exception) {
                // Leave credentials and queued commands untouched; retry on the next interval.
            } finally {
                cloudPollBusy.set(false)
                val delay = if (isStandby()) 6000L else 12000L
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

    private fun postOpenKidsNotification() {
        try {
            val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.kids")
                ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.youtube.kids"))
            val pending = PendingIntent.getActivity(
                this,
                7731,
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, kidsChannelId) else Notification.Builder(this)
            val notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("YouTube Kids openen")
                .setContentText("Tik hier om YouTube Kids op deze tablet te openen.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build()
            getSystemService(NotificationManager::class.java).notify(7731, notification)
        } catch (_: Exception) { }
    }

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
                            out.put("ok", true).put("message", "OPEN_KIDS")
                            openKidsAfterResponse = true
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
                }

                s.getOutputStream().bufferedWriter().apply { write(out.toString()); newLine(); flush() }
                if (lockAfterResponse) lockDevice()
                if (wakeAfterResponse) wakeDisplayKeepPreviousApp()
                if (openKidsAfterResponse) {
                    if (receiverUiForeground) {
                        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.kids")
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        } else {
                            postOpenKidsNotification()
                        }
                    } else {
                        postOpenKidsNotification()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        liveInstance = null
        mainHandler.removeCallbacks(cloudPollRunnable)
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