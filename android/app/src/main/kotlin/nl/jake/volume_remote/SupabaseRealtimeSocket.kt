package nl.jake.volume_remote

import android.net.Uri
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A lightweight public Supabase Realtime channel.
 *
 * The topic is derived from the already-paired device UUID. Broadcasts contain
 * wake-up notices only; commands and Wi-Fi credentials are fetched separately
 * through the authenticated Edge Function.
 */
internal class SupabaseRealtimeSocket(
    private val onCommandAvailable: () -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var deviceId: String? = null
    @Volatile var isConnected: Boolean = false
        private set

    @Volatile private var started = false
    @Volatile private var opening = false
    private var reconnectDelayMs = 2000L
    private var reconnectScheduled = false
    private var reference = 1L

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val current = socket
            if (!started || !isConnected || current == null) return
            val heartbeat = JSONObject()
                .put("topic", "phoenix")
                .put("event", "heartbeat")
                .put("payload", JSONObject())
                .put("ref", nextReference().toString())
            current.send(heartbeat.toString())
            handler.postDelayed(this, 20_000L)
        }
    }

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        val id = deviceId
        if (started && id != null) openSocket(id)
    }

    fun connect(id: String) {
        if (id.isBlank()) return
        if (started && deviceId == id && (socket != null || opening)) return
        deviceId = id
        started = true
        isConnected = false
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        openSocket(id)
    }

    private fun nextReference(): Long {
        reference += 1
        return reference
    }

    private fun openSocket(id: String) {
        if (!started || opening) return
        opening = true

        val uri = Uri.Builder()
            .scheme("wss")
            .authority("nstpzmuimwjbvlvvbrsr.supabase.co")
            .appendPath("realtime")
            .appendPath("v1")
            .appendPath("websocket")
            .appendQueryParameter("apikey", PUBLISHABLE_KEY)
            .appendQueryParameter("vsn", "1.0.0")
            .build()

        val request = Request.Builder().url(uri.toString()).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opening = false
                socket = webSocket
                val topic = "realtime:volume:$id"
                val config = JSONObject()
                    .put("broadcast", JSONObject().put("ack", false).put("self", false))
                    .put("presence", JSONObject().put("enabled", false).put("key", ""))
                    .put("postgres_changes", JSONArray())
                    .put("private", false)
                val join = JSONObject()
                    .put("topic", topic)
                    .put("event", "phx_join")
                    .put("payload", JSONObject().put("config", config))
                    .put("ref", "1")
                    .put("join_ref", "1")
                webSocket.send(join.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("event")) {
                        "phx_reply" -> {
                            val payload = message.optJSONObject("payload")
                            if (payload?.optString("status") == "ok") {
                                isConnected = true
                                reconnectDelayMs = 2000L
                                handler.removeCallbacks(heartbeatRunnable)
                                handler.postDelayed(heartbeatRunnable, 20_000L)
                                onConnectionChanged(true)
                                // Catch commands queued while the socket was reconnecting.
                                onCommandAvailable()
                            } else {
                                markDisconnectedAndRetry(id)
                            }
                        }
                        "broadcast" -> {
                            val payload = message.optJSONObject("payload")
                            if (payload?.optString("event") == "command_available") {
                                onCommandAvailable()
                            }
                        }
                        "phx_error", "phx_close" -> markDisconnectedAndRetry(id)
                    }
                } catch (_: Exception) {
                    // Ignore malformed frames; the normal heartbeat/reconnect path recovers.
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                markDisconnectedAndRetry(id)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opening = false
                if (socket === webSocket) socket = null
                markDisconnectedAndRetry(id)
            }
        })
    }

    private fun markDisconnectedAndRetry(id: String) {
        opening = false
        isConnected = false
        handler.removeCallbacks(heartbeatRunnable)
        onConnectionChanged(false)
        val deadSocket = socket
        socket = null
        if (deadSocket != null) {
            try { deadSocket.close(1001, "Realtime reconnecting") } catch (_: Exception) { }
        }
        if (!started || deviceId != id || reconnectScheduled) return
        reconnectScheduled = true
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2L).coerceAtMost(30_000L)
        handler.postDelayed(reconnectRunnable, delay)
    }

    fun close() {
        started = false
        opening = false
        isConnected = false
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        val current = socket
        socket = null
        current?.close(1000, "Receiver service stopped")
        onConnectionChanged(false)
    }

    companion object {
        private const val PUBLISHABLE_KEY =
            "sb_publishable__jkGIXaG3xYh8123o9BusA_RYneYnex"
    }
}

[executed on device: PC-van-Jake (1997e53f-651c-4a6a-959d-b2acccb4b133)]