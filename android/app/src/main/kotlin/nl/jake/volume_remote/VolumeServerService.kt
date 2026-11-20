package nl.jake.volume_remote

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import org.json.JSONObject

class VolumeServerService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val channelId = "volume_receiver"

    override fun onCreate() {
        super.onCreate()
        createNotification()
        startForeground(42, notification())
        running = true
        Thread { serve() }.start()
    }

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channelId, "Volume Receiver", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setContentTitle("Volume Receiver actief")
            .setContentText("Volume en helderheid kunnen op afstand worden geregeld.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true).build()
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

                if (req.optString("pin") != pin()) {
                    out.put("ok", false).put("error", "Onjuiste koppelcode")
                } else {
                    when (req.optString("action")) {
                        "set" -> {
                            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val p = req.optInt("percent", 0).coerceIn(0, 100)
                            val level = kotlin.math.round(max * p / 100.0).toInt()
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                        }
                        "setBrightness" -> {
                            if (!Settings.System.canWrite(this)) {
                                out.put("ok", false).put("error", "BRIGHTNESS_PERMISSION_REQUIRED")
                            } else {
                                val p = req.optInt("percent", 50).coerceIn(0, 100)
                                val level = (p * 255.0 / 100.0).toInt().coerceIn(1, 255)
                                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
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
                    }

                    if (!out.has("ok")) {
                        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        out.put("ok", true)
                            .put("percent", if (max == 0) 0 else kotlin.math.round(current * 100.0 / max).toInt())
                    }
                    out.put("brightnessPercent", currentBrightnessPercent(this))
                    out.put("canWriteBrightness", Settings.System.canWrite(this))
                    out.put("lockAdminActive", lockAdminActive())
                }

                s.getOutputStream().bufferedWriter().apply { write(out.toString()); newLine(); flush() }
                if (lockAfterResponse) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val dpm = getSystemService(DevicePolicyManager::class.java)
                            val admin = ComponentName(this, VolumeDeviceAdminReceiver::class.java)
                            dpm.lockNow()
                        } catch (_: Exception) { }
                    }, 700)
                }
            } catch (_: Exception) { }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { running = false; try { server?.close() } catch (_: Exception) {}; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
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
                        val a = addresses.nextElement()
                        if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: ""
                    }
                }
            } catch (_: Exception) { }
            return "Geen wifi-IP gevonden"
        }
    }
}
