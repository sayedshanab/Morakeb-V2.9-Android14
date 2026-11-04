package com.smartnet.monitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.sin

data class OutageRecord(
    val id: Long,
    val date: String,
    val startTime: String,
    var endTime: String,
    var durationSec: Long,
    var recoveryPing: String,
    var ongoing: Boolean
)

data class LogEntry(
    val date: String,
    val time: String,
    val msg: String,
    val type: String
)

class NetworkMonitorService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_MUTE = "ACTION_MUTE"
        const val ACTION_UPDATE_UI = "ACTION_UPDATE_UI"
        const val PREFS_NAME = "morakeb_v29_prefs"

        @Volatile var isRunning = false
        @Volatile var fails = 0
        @Volatile var threshold = 3
        @Volatile var totalTimeouts = 0
        @Volatile var isDisconnected = false
        @Volatile var disconnectCount = 0
        @Volatile var lastPing = "--"
        @Volatile var pingStatusSub = "حالة الخط: مستعد"
        @Volatile var downStartTime = 0L
        @Volatile var totalDowntimeSec = 0L
        @Volatile var longestDowntimeSec = 0L
        @Volatile var lastDisconnectDurSec = 0L
        @Volatile var lastDisconnectStartStr = ""
        @Volatile var lastDisconnectEndStr = ""
        @Volatile var alarmVolumePct = 150
        @Volatile var playingToneType: String? = null

        private var activeAudioTrack: AudioTrack? = null
        val outageHistory = mutableListOf<OutageRecord>()
        val logs = mutableListOf<LogEntry>()
        private var prefsInitialized = false

        fun toWesternDigits(input: Any?): String {
            if (input == null) return ""
            val str = input.toString()
            val sb = StringBuilder(str.length)
            for (ch in str) {
                when (ch.code) {
                    in 0x0660..0x0669 -> sb.append((ch.code - 0x0660).toString())
                    in 0x06F0..0x06F9 -> sb.append((ch.code - 0x06F0).toString())
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun formatDurationAr(secInput: Long): String {
            val sec = secInput.coerceAtLeast(0L)
            if (sec < 60L) return toWesternDigits("$sec ث")
            val hrs = sec / 3600L
            val mins = (sec % 3600L) / 60L
            val remSec = sec % 60L
            if (hrs > 0L) return toWesternDigits("$hrs س $mins د $remSec ث")
            return if (remSec > 0L) toWesternDigits("$mins د $remSec ث") else toWesternDigits("$mins د")
        }

        private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

        fun getNowTimeStr(ts: Long = System.currentTimeMillis()): String {
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = pad2(cal.get(Calendar.MINUTE))
            val s = pad2(cal.get(Calendar.SECOND))
            val period = if (h >= 12) "م" else "ص"
            var h12 = h % 12
            if (h12 == 0) h12 = 12
            return toWesternDigits("${pad2(h12)}:$m:$s $period")
        }

        fun getNowDateStr(ts: Long = System.currentTimeMillis()): String {
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return toWesternDigits("$y/$m/$d")
        }

        fun ensureLoaded(context: Context) {
            if (prefsInitialized) return
            prefsInitialized = true
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            disconnectCount = prefs.getInt("disconnectCount", 0)
            totalTimeouts = prefs.getInt("totalTimeouts", 0)
            totalDowntimeSec = prefs.getLong("totalDowntimeSec", 0L)
            longestDowntimeSec = prefs.getLong("longestDowntimeSec", 0L)
            lastDisconnectDurSec = prefs.getLong("lastDisconnectDurSec", 0L)
            lastDisconnectStartStr = toWesternDigits(prefs.getString("lastDisconnectStartStr", "") ?: "")
            lastDisconnectEndStr = toWesternDigits(prefs.getString("lastDisconnectEndStr", "") ?: "")
            alarmVolumePct = prefs.getInt("alarmVolumePct", 150)
            threshold = prefs.getInt("threshold", 3)

            synchronized(outageHistory) {
                outageHistory.clear()
                val rawOutages = prefs.getString("outages_json", null)
                if (!rawOutages.isNullOrEmpty()) {
                    try {
                        val arr = JSONArray(rawOutages)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val wasOngoing = o.optBoolean("ongoing", false)
                            outageHistory.add(
                                OutageRecord(
                                    id = o.optLong("id", System.currentTimeMillis() + i),
                                    date = toWesternDigits(o.optString("date", "")),
                                    startTime = toWesternDigits(o.optString("startTime", "")),
                                    endTime = if (wasOngoing) "أُغلق التطبيق" else toWesternDigits(o.optString("endTime", "")),
                                    durationSec = o.optLong("durationSec", 0L),
                                    recoveryPing = toWesternDigits(o.optString("recoveryPing", "--")),
                                    ongoing = false
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            synchronized(logs) {
                logs.clear()
                val rawLogs = prefs.getString("logs_json", null)
                if (!rawLogs.isNullOrEmpty()) {
                    try {
                        val arr = JSONArray(rawLogs)
                        for (i in 0 until arr.length()) {
                            val l = arr.getJSONObject(i)
                            logs.add(
                                LogEntry(
                                    date = toWesternDigits(l.optString("date", "")),
                                    time = toWesternDigits(l.optString("time", "")),
                                    msg = toWesternDigits(l.optString("msg", "")),
                                    type = l.optString("type", "ok")
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        fun savePersistentState(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val outagesArr = JSONArray()
                synchronized(outageHistory) {
                    for (o in outageHistory.take(200)) {
                        val obj = JSONObject()
                        obj.put("id", o.id)
                        obj.put("date", toWesternDigits(o.date))
                        obj.put("startTime", toWesternDigits(o.startTime))
                        obj.put("endTime", toWesternDigits(o.endTime))
                        obj.put("durationSec", o.durationSec)
                        obj.put("recoveryPing", toWesternDigits(o.recoveryPing))
                        obj.put("ongoing", o.ongoing)
                        outagesArr.put(obj)
                    }
                }

                val logsArr = JSONArray()
                synchronized(logs) {
                    for (l in logs.take(250)) {
                        val obj = JSONObject()
                        obj.put("date", toWesternDigits(l.date))
                        obj.put("time", toWesternDigits(l.time))
                        obj.put("msg", toWesternDigits(l.msg))
                        obj.put("type", l.type)
                        logsArr.put(obj)
                    }
                }

                prefs.edit()
                    .putInt("disconnectCount", disconnectCount)
                    .putInt("totalTimeouts", totalTimeouts)
                    .putLong("totalDowntimeSec", totalDowntimeSec)
                    .putLong("longestDowntimeSec", longestDowntimeSec)
                    .putLong("lastDisconnectDurSec", lastDisconnectDurSec)
                    .putString("lastDisconnectStartStr", toWesternDigits(lastDisconnectStartStr))
                    .putString("lastDisconnectEndStr", toWesternDigits(lastDisconnectEndStr))
                    .putInt("alarmVolumePct", alarmVolumePct)
                    .putInt("threshold", threshold)
                    .putString("outages_json", outagesArr.toString())
                    .putString("logs_json", logsArr.toString())
                    .apply()
            } catch (_: Exception) {}
        }

        fun clearAllSavedData(context: Context) {
            synchronized(outageHistory) { outageHistory.clear() }
            synchronized(logs) { logs.clear() }
            disconnectCount = 0
            totalTimeouts = 0
            totalDowntimeSec = 0L
            longestDowntimeSec = 0L
            lastDisconnectDurSec = 0L
            lastDisconnectStartStr = ""
            lastDisconnectEndStr = ""
            savePersistentState(context)
        }

        fun addLog(context: Context, msg: String, type: String = "ok") {
            val cleanMsg = toWesternDigits(msg)
            val t = getNowTimeStr()
            val d = getNowDateStr()
            synchronized(logs) {
                logs.add(0, LogEntry(d, t, cleanMsg, type))
                if (logs.size > 250) logs.removeAt(logs.size - 1)
            }
            savePersistentState(context)
        }

        fun stopAlarm(context: Context? = null) {
            playingToneType = null
            try {
                activeAudioTrack?.let { track ->
                    try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
                    try { track.release() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            activeAudioTrack = null
            if (context != null) {
                context.sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(context.packageName))
            }
        }

        fun playDisconnectTone(context: Context? = null) {
            stopAlarm(context)
            playingToneType = "disc"
            if (context != null) {
                context.sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(context.packageName))
            }
            Thread {
                playSequence(
                    listOf(
                        Triple(1200.0, 350, true),
                        Triple(800.0, 350, true),
                        Triple(400.0, 650, true)
                    ),
                    context
                )
            }.start()
        }

        fun playReturnTone(context: Context? = null) {
            stopAlarm(context)
            playingToneType = "ret"
            if (context != null) {
                context.sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(context.packageName))
            }
            Thread {
                playSequence(
                    listOf(
                        Triple(523.25, 250, false),
                        Triple(659.25, 250, false),
                        Triple(783.99, 250, false),
                        Triple(1046.50, 550, false)
                    ),
                    context
                )
            }.start()
        }

        private fun playSequence(notes: List<Triple<Double, Int, Boolean>>, context: Context?) {
            try {
                val volMultiplier = (alarmVolumePct / 100.0).coerceIn(0.0, 3.0)
                if (volMultiplier <= 0.001) {
                    playingToneType = null
                    if (context != null) context.sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(context.packageName))
                    return
                }
                val sampleRate = 22050
                val totalDurationMs = notes.sumOf { it.second }
                val totalSamples = (sampleRate * (totalDurationMs / 1000.0)).toInt()
                val buffer = ShortArray(totalSamples)
                var currentSample = 0

                for ((freq, durMs, isSquare) in notes) {
                    val noteSamples = (sampleRate * (durMs / 1000.0)).toInt()
                    for (i in 0 until noteSamples) {
                        if (currentSample >= totalSamples) break
                        val env = 1.0 - (i.toDouble() / noteSamples) * 0.85
                        val angle = 2.0 * Math.PI * i * freq / sampleRate
                        val wave = if (isSquare) (if (sin(angle) >= 0) 1.0 else -1.0) else sin(angle)
                        // Boost with hard-limiter clipping protection up to 300%
                        val sampleVal = (wave * env * 0.55 * volMultiplier).coerceIn(-0.98, 0.98)
                        buffer[currentSample++] = (sampleVal * Short.MAX_VALUE).toInt().toShort()
                    }
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                activeAudioTrack = track
                track.write(buffer, 0, buffer.size)
                track.play()
                Thread.sleep(totalDurationMs.toLong() + 100)
                if (activeAudioTrack === track) {
                    playingToneType = null
                    activeAudioTrack = null
                    if (context != null) {
                        context.sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(context.packageName))
                    }
                }
                track.release()
            } catch (_: Exception) {
                playingToneType = null
                if (context != null) {
                    context.sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(context.packageName))
                }
            }
        }
    }

    private var scheduler: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val STATUS_CHANNEL = "net_monitor_status"
    private val ALERT_CHANNEL = "net_monitor_alerts"

    override fun onCreate() {
        super.onCreate()
        ensureLoaded(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureLoaded(this)
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                stopAlarm(this)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(2002)
                return START_STICKY
            }
        }

        if (!isRunning) {
            isRunning = true
            fails = 0
            lastPing = "--"
            pingStatusSub = "جاري فحص الاتصال..."
            acquireWakeLock()
            addLog(this, "▶️ Started - المراقبة نشطة في الخلفية", "ok")
            val notif = buildStatusNotification("جاري الفحص...", "مراقب النت الذكي يعمل في الخلفية")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notif)
            }
            sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(packageName))
            startPingLoop()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartNetMonitor::PingWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startPingLoop() {
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({
            if (isRunning) checkSinglePing()
        }, 0, 1500, TimeUnit.MILLISECONDS)
    }

    private fun checkSinglePing() {
        if (!isRunning) return
        val start = SystemClock.elapsedRealtime()
        var success = false

        val endpoints = arrayOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )

        for (urlStr in endpoints) {
            if (!isRunning) return
            try {
                val conn = (URL("$urlStr?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 1200
                    readTimeout = 1200
                    useCaches = false
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) {
                    success = true
                    break
                }
            } catch (_: Exception) {}
        }

        if (!isRunning) return
        val latency = SystemClock.elapsedRealtime() - start

        if (success) {
            lastPing = toWesternDigits("$latency ms")
            if (isDisconnected) {
                val endTs = System.currentTimeMillis()
                val downSec = ((endTs - downStartTime) / 1000L).coerceAtLeast(1L)
                val endStr = getNowTimeStr(endTs)

                isDisconnected = false
                downStartTime = 0L
                fails = 0

                lastDisconnectDurSec = downSec
                lastDisconnectEndStr = endStr
                totalDowntimeSec += downSec
                if (downSec > longestDowntimeSec) longestDowntimeSec = downSec

                synchronized(outageHistory) {
                    if (outageHistory.isNotEmpty() && outageHistory[0].ongoing) {
                        outageHistory[0].ongoing = false
                        outageHistory[0].endTime = endStr
                        outageHistory[0].durationSec = downSec
                        outageHistory[0].recoveryPing = lastPing
                    }
                }

                pingStatusSub = toWesternDigits("عاد بعد ${formatDurationAr(downSec)}")
                addLog(this, "✅ BACK ONLINE - رجع النت بعد انقطاع دام (${formatDurationAr(downSec)}) - البنج: $lastPing", "ok")
                playReturnTone(this)
                sendAlertNotification("✅ الانترنت رجع!", toWesternDigits("استمر الانقطاع لمدة ${formatDurationAr(downSec)} ($lastPing)"))
                updateStatusNotification(toWesternDigits("متصل ($lastPing)"), toWesternDigits("فشل متتالي: 0/$threshold | مرات الفصل: $disconnectCount"))
            } else {
                if (fails > 0) {
                    addLog(this, "ℹ️ استقر الاتصال بعد فقد $fails باكت ($lastPing)", "ok")
                }
                fails = 0
                pingStatusSub = if (latency < 90) "حالة الخط: ممتاز" else "حالة الخط: مستقر"
                updateStatusNotification(toWesternDigits("متصل ($lastPing)"), toWesternDigits("مرات الفصل: $disconnectCount"))
            }
        } else {
            fails++
            totalTimeouts++
            lastPing = "Timeout"
            pingStatusSub = "لا توجد استجابة!"
            if (fails == 1 && downStartTime == 0L) downStartTime = System.currentTimeMillis()
            val capped = fails.coerceAtMost(threshold)
            addLog(this, "⚠️ Request timed out. ($fails/$threshold)", "warn")

            if (isDisconnected) {
                val curDur = ((System.currentTimeMillis() - downStartTime) / 1000L).coerceAtLeast(1L)
                lastDisconnectDurSec = curDur
                synchronized(outageHistory) {
                    if (outageHistory.isNotEmpty() && outageHistory[0].ongoing) {
                        outageHistory[0].durationSec = curDur
                    }
                }
                savePersistentState(this)
            }

            if (fails >= threshold && !isDisconnected) {
                isDisconnected = true
                disconnectCount++
                val initialDur = if (downStartTime > 0L) ((System.currentTimeMillis() - downStartTime) / 1000L).coerceAtLeast(1L) else threshold.toLong()
                lastDisconnectStartStr = getNowTimeStr(if (downStartTime > 0L) downStartTime else System.currentTimeMillis())
                lastDisconnectEndStr = ""
                lastDisconnectDurSec = initialDur

                synchronized(outageHistory) {
                    outageHistory.add(
                        0,
                        OutageRecord(
                            id = System.currentTimeMillis(),
                            date = getNowDateStr(if (downStartTime > 0L) downStartTime else System.currentTimeMillis()),
                            startTime = lastDisconnectStartStr,
                            endTime = "",
                            durationSec = initialDur,
                            recoveryPing = "--",
                            ongoing = true
                        )
                    )
                }

                addLog(this, "🚨 DISCONNECTED - الانترنت فصل رسمياً! (بداية الفصل: $lastDisconnectStartStr)", "fail")
                playDisconnectTone(this)
                sendAlertNotification("🚨 الانترنت فصل!", toWesternDigits("فشل الاتصال $threshold مرات متتالية"))
                updateStatusNotification(toWesternDigits("🚨 الانترنت فاصل! ($capped/$threshold)"), toWesternDigits("مرات الفصل: $disconnectCount"))
            }
        }

        sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(packageName))
    }

    private fun stopMonitoring() {
        isRunning = false
        scheduler?.shutdownNow()
        scheduler = null
        stopAlarm(this)
        releaseWakeLock()

        if (isDisconnected) {
            val wasDown = if (downStartTime > 0L) ((System.currentTimeMillis() - downStartTime) / 1000L).coerceAtLeast(1L) else 1L
            val endStr = getNowTimeStr()
            synchronized(outageHistory) {
                if (outageHistory.isNotEmpty() && outageHistory[0].ongoing) {
                    outageHistory[0].ongoing = false
                    outageHistory[0].endTime = endStr
                    outageHistory[0].durationSec = wasDown
                }
            }
            lastDisconnectEndStr = endStr
            totalDowntimeSec += wasDown
            if (wasDown > longestDowntimeSec) longestDowntimeSec = wasDown
        }

        isDisconnected = false
        downStartTime = 0L
        fails = 0
        lastPing = "--"
        pingStatusSub = "حالة الخط: متوقف مؤقتاً"

        addLog(this, "⏸️ Stopped - تم إيقاف المراقبة بنجاح", "warn")
        sendBroadcast(Intent(ACTION_UPDATE_UI).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val statusChan = NotificationChannel(
                STATUS_CHANNEL, "حالة المراقبة الدائمة", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }

            val alertChan = NotificationChannel(
                ALERT_CHANNEL, "تنبيهات فصل ورجوع النت", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setSound(null, null)
            }
            nm.createNotificationChannel(statusChan)
            nm.createNotificationChannel(alertChan)
        }
    }

    private fun buildStatusNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, NetworkMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, STATUS_CHANNEL)
            .setContentTitle(toWesternDigits("🌐 $title"))
            .setContentText(toWesternDigits(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف المراقبة", stopIntent).build())
            .build()
    }

    private fun updateStatusNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, buildStatusNotification(title, text))
    }

    private fun sendAlertNotification(title: String, body: String) {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteIntent = PendingIntent.getService(
            this, 2, Intent(this, NetworkMonitorService::class.java).apply { action = ACTION_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, ALERT_CHANNEL)
            .setContentTitle(toWesternDigits(title))
            .setContentText(toWesternDigits(body))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "إسكات التنبيه", muteIntent).build())
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2002, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
