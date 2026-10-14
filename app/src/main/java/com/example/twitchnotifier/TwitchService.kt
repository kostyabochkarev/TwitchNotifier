package com.example.twitchnotifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class TwitchService : Service() {

    companion object {
        const val TAG = "TwitchService"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CH_LIVE = "live"
        const val CH_FG = "fg"
        const val FG_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, TwitchService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TwitchService::class.java))
        }

        fun reload(ctx: Context) {
            ctx.stopService(Intent(ctx, TwitchService::class.java))
            start(ctx)
        }
    }

    private var ws: WebSocket? = null
    private var sessionId: String? = null
    private var reconnectUrl: String? = null
    private val subscribed = HashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var clientId: String? = null
    private var token: String? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                clientId = applicationContext.clientId
                token = applicationContext.accessToken
                if (clientId.isNullOrEmpty() || token.isNullOrEmpty()) {
                    Log.w(TAG, "Нет client_id/токена — стоп")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(FG_ID, buildFgNotification("Запуск…"))
                running = true
                connect(null)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        reconnectJob?.cancel()
        ws?.close(1000, "service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private fun connect(url: String?) {
        reconnectJob?.cancel()
        val target = url ?: reconnectUrl ?: "wss://eventsub.wss.twitch.tv/ws"
        reconnectUrl = null
        Log.i(TAG, "WS connect: $target")
        updateFg("Подключение…")

        val req = Request.Builder().url(target).build()
        ws = TwitchApi.http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(text) } catch (e: Exception) { Log.e(TAG, "handle", e) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                if (running) scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed $code $reason")
                if (running) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        sessionId = null
        subscribed.clear()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            withContext(Dispatchers.Main) { if (running) connect(null) }
        }
    }

    private fun handle(text: String) {
        val json = JSONObject(text)
        val type = json.getJSONObject("metadata").getString("message_type")
        when (type) {
            "session_welcome" -> {
                sessionId = json.getJSONObject("payload").getJSONObject("session").getString("id")
                Log.i(TAG, "Session: $sessionId")
                subscribeAll()
            }
            "session_keepalive" -> { }
            "session_reconnect" -> {
                reconnectUrl = json.getJSONObject("payload").getJSONObject("session")
                    .getString("reconnect_url")
                ws?.close(1000, "reconnect")
            }
            "notification" -> {
                val payload = json.getJSONObject("payload")
                if (payload.getJSONObject("subscription").getString("type") == "stream.online") {
                    onStreamOnline(payload.getJSONObject("event"))
                }
            }
        }
    }

    private fun subscribeAll() {
        val cid = clientId ?: return
        val tok = token ?: return
        val sid = sessionId ?: return
        val channels = Storage.getChannels(applicationContext)
        updateFg("Отслеживается: ${channels.size}")

        scope.launch {
            for (ch in channels) {
                if (subscribed.containsKey(ch.id)) continue
                try {
                    val id = TwitchApi.subscribeStreamOnline(cid, tok, sid, ch.id)
                    subscribed[ch.id] = id
                    Log.i(TAG, "Subscribed ${ch.displayName} -> $id")
                } catch (e: Exception) {
                    Log.e(TAG, "Subscribe ${ch.displayName}", e)
                }
                delay(120)
            }
            withContext(Dispatchers.Main) {
                updateFg("Активно · ${subscribed.size}/${channels.size}")
            }
        }
    }

    private fun onStreamOnline(ev: JSONObject) {
        val name = ev.getString("broadcaster_user_name")
        val login = ev.getString("broadcaster_user_login")
        Log.i(TAG, "LIVE: $name / $login")

        val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitch.tv/$login"))
        val pi = PendingIntent.getActivity(
            this, login.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("$name в эфире!")
            .setContentText("Нажми, чтобы открыть Twitch")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(login.hashCode(), n)
        } catch (e: SecurityException) {
            Log.e(TAG, "Нет разрешения на уведомления", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "Стримы", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Уведомления о начале стримов"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FG, "Служба", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление о работе сервиса"
                setShowBadge(false)
            }
        )
    }

    private fun buildFgNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_FG)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Twitch Notifier")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateFg(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(FG_ID, buildFgNotification(text))
        } catch (_: SecurityException) { }
    }
}
