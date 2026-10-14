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
import java.util.concurrent.atomic.AtomicBoolean

class TwitchService : Service() {

    companion object {
        const val TAG = "TwitchService"
        const val ACTION_START = "start"
        const val CH_LIVE = "live"
        const val CH_FG = "fg"
        const val FG_ID = 1
        const val POLL_INTERVAL_MS = 30_000L

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FG_ID, buildFgNotification("Запуск…"))
        if (running.getAndSet(true)) return START_STICKY
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (running.get()) {
                try {
                    val channels = Storage.getChannels(applicationContext)
                    withContext(Dispatchers.Main) {
                        updateFg("Отслеживается: ${channels.size}")
                    }
                    for (ch in channels) {
                        if (!running.get()) break
                        val live = TwitchApi.isStreamLive(ch.login)
                        val wasLive = Storage.isLive(applicationContext, ch.login)
                        if (live && !wasLive) {
                            Storage.setLive(applicationContext, ch.login, true)
                            withContext(Dispatchers.Main) { onStreamOnline(ch) }
                        } else if (!live && wasLive) {
                            Storage.setLive(applicationContext, ch.login, false)
                        }
                        delay(500)
                    }
                    delay(POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "poll error", e)
                    delay(10_000)
                }
            }
        }
    }

    private fun onStreamOnline(ch: Channel) {
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitch.tv/${ch.login}"))
        val pi = PendingIntent.getActivity(
            this, ch.login.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("${ch.displayName} в эфире!")
            .setContentText("Нажми, чтобы открыть Twitch")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(ch.login.hashCode(), n)
        } catch (e: SecurityException) {
            Log.e(TAG, "no notification permission", e)
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
