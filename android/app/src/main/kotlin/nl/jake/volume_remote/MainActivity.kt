package nl.jake.volume_remote

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import android.util.Base64
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

class MainActivity : FlutterActivity() {
    private val channel = "nl.jake.volume_remote/receiver"
    private var pendingWifiScanResult: MethodChannel.Result? = null
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

    private fun scanWifiNetworks(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingWifiScanResult = result
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }
        performWifiScan(result)
    }

    private fun performWifiScan(result: MethodChannel.Result) {
        try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!manager.isWifiEnabled) {
                result.error("WIFI_DISABLED", "Schakel wifi op je telefoon in en probeer opnieuw.", null)
                return
            }
            manager.startScan()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val found = manager.scanResults
                        .map { it.SSID.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                        .map { mapOf("ssid" to it) }
                    result.success(found)
                } catch (e: Exception) {
                    result.error("WIFI_SCAN_FAILED", e.message, null)
                }
            }, 2200)
        } catch (e: Exception) {
            result.error("WIFI_SCAN_FAILED", e.message, null)
        }
    }

    private fun requestWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 102)
        } else {
            Toast.makeText(this, "Wifi op afstand is toegestaan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addWifiSuggestion(ssid: String, password: String, result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            result.error("WIFI_API_UNSUPPORTED", "Wifi op afstand toevoegen vereist Android 10 of nieuwer.", null)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            result.error("WIFI_PERMISSION_REQUIRED", "Geef eerst op de tablet toestemming voor wifi instellen op afstand.", null)
            return
        }
        if (ssid.isBlank() || ssid.length > 32 || password.length > 63 ||
            (password.isNotEmpty() && password.length < 8)
        ) {
            result.error("INVALID_WIFI_DETAILS", "Controleer de wifi-naam en het wachtwoord.", null)
            return
        }
        try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
            val suggestion = builder.build()
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val status = manager.addNetworkSuggestions(listOf(suggestion))
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                result.error("WIFI_SUGGESTION_FAILED", "Android kon het wifi-netwerk niet toevoegen (code $status).", null)
                return
            }
            Toast.makeText(this, "Wifi-netwerk toegevoegd. Android kan nog om toestemming vragen.", Toast.LENGTH_LONG).show()
            result.success(mapOf("ok" to true, "status" to "suggestion_added"))
        } catch (e: Exception) {
            result.error("WIFI_SUGGESTION_FAILED", e.message, null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val pending = pendingWifiScanResult
            pendingWifiScanResult = null
            if (pending != null) {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    performWifiScan(pending)
                } else {
                    pending.error("WIFI_SCAN_PERMISSION_DENIED", "Geef locatietoegang om wifi-netwerken te kunnen scannen.", null)
                }
            }
        }
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
                        "wifiPermissionGranted" to (Build.VERSION.SDK_INT < 33 ||
                            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED),
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
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Sta Volume Remote toe om de tablet op afstand in stand-by te zetten.")
                    }
                    startActivity(intent)
                    result.success(true)
                }
                "requestWifiPermission" -> {
                    requestWifiPermission()
                    result.success(true)
                }
                "scanWifiNetworks" -> scanWifiNetworks(result)
                "addWifiSuggestion" -> {
                    val ssid = call.argument<String>("ssid") ?: ""
                    val password = call.argument<String>("password") ?: ""
                    addWifiSuggestion(ssid, password, result)
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