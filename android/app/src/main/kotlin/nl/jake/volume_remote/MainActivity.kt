package nl.jake.volume_remote

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

class MainActivity : FlutterActivity() {
    private val channel = "nl.jake.volume_remote/receiver"
    private val adminComponent by lazy {
        ComponentName(this, VolumeDeviceAdminReceiver::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageName.endsWith(".receiver")) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
            startReceiver()
        }
    }

    private fun startReceiver() {
        val intent = Intent(this, VolumeServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun getKidsIcon(): ByteArray? {
        return try {
            val drawable = packageManager.getApplicationIcon("com.google.android.apps.youtube.kids")
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 128
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 128
            val bitmap = Bitmap.createBitmap(w.coerceAtMost(512), h.coerceAtMost(512), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openKids() {
        val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.kids")
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.youtube.kids"))
            startActivity(market)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            when (call.method) {
                "startReceiver" -> { startReceiver(); result.success(true) }
                "getReceiverInfo" -> result.success(
                    mapOf(
                        "ip" to VolumeServerService.localIp(),
                        "pin" to VolumeServerService.currentPin(this),
                        "brightnessPercent" to VolumeServerService.currentBrightnessPercent(this),
                        "canWriteBrightness" to Settings.System.canWrite(this),
                        "lockAdminActive" to getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent),
                        "youtubeKidsInstalled" to (packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.kids") != null)
                    )
                )
                "requestBrightnessPermission" -> {
                    if (!Settings.System.canWrite(this)) {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                    result.success(true)
                }
                "requestLockAdmin" -> {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Sta Volume Remote toe om de tablet op afstand te vergrendelen en het scherm uit te zetten.")
                    }
                    startActivity(intent)
                    result.success(true)
                }
                "getYouTubeKidsIcon" -> result.success(getKidsIcon())
                "openYouTubeKids" -> {
                    try { openKids(); result.success(true) }
                    catch (e: Exception) { result.error("OPEN_KIDS_FAILED", e.message, null) }
                }
                "openWifiSettings" -> {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }
}
