package com.yzj.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Keeps the stored authtoken warm for 12 hours by touching a lightweight
 * authenticated endpoint every 10 minutes. It does not drive WebView timers.
 */
class SessionKeeper : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startedAt = System.currentTimeMillis()
            startForeground(NOTIFICATION_ID, notification("12 小时会话保活已启动"))
            thread = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun loop() {
        val twelveHours = 12L * 60 * 60 * 1000
        while (running) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= twelveHours) {
                notify("12 小时保活窗口结束，请重新登录")
                break
            }
            var ok = 0
            var expired = 0
            val count = InstanceStore.count(this)
            for (slot in 0 until count) {
                val session = SecureStore.getJson(this, SecureStore.sessionName(slot))
                    ?: continue
                val token = session.optString("reslibAuthtoken")
                if (token.isEmpty()) continue
                val result = touch(session, token)
                val status = StatusStore.read(this, slot)
                status.put("sessionOk", result == 200)
                status.put("sessionExpired", result == 401 || result == 203)
                status.put("lastSessionCheck", System.currentTimeMillis())
                status.put("sessionWindowStartedAt", startedAt)
                StatusStore.write(this, slot, status)
                if (result == 200) ok++ else if (result == 401 || result == 203) expired++
            }
            notify("会话保活：正常 $ok，需重登 $expired（每 10 分钟检查）")
            var slept = 0L
            while (running && slept < 10 * 60 * 1000) {
                Thread.sleep(1000)
                slept += 1000
            }
        }
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun touch(session: JSONObject, token: String): Int {
        return runCatching {
            val stu = session.optString("stuId")
            val gid = session.optString("indexpage:gid")
            val url = "https://www.csgmooc.com/uni-gateway/jxapi2/student/task/getteachtask" +
                "?stuid=${enc(stu)}&gid=${enc(gid)}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("authtoken", token)
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", UA)
            val httpCode = conn.responseCode
            if (httpCode == 401 || httpCode == 203) return httpCode
            val stream = if (httpCode >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                ?: return httpCode
            val json = JSONObject(text)
            val code = json.optString("errcode", json.optString("errCode", "0"))
            if (code == "200") 200
            else if (code == "401" || code == "203") code.toInt()
            else httpCode
        }.getOrDefault(-1)
    }

    private fun notify(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("易智教多开助手 · 会话保活")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "会话保活", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        thread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.yzj.client.action.STOP_KEEPALIVE"
        private const val CHANNEL = "yzj_session"
        private const val NOTIFICATION_ID = 4102
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, SessionKeeper::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isRunning = true
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionKeeper::class.java)
                .setAction(ACTION_STOP))
            isRunning = false
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
