package com.smartnet.monitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private lateinit var statusCircle: TextView
    private lateinit var failText: TextView
    private lateinit var packetLossSub: TextView
    private lateinit var pingText: TextView
    private lateinit var pingAvgSub: TextView
    private lateinit var downDurText: TextView
    private lateinit var currentDownSub: TextView
    private lateinit var discCountText: TextView
    private lateinit var longestDownSub: TextView
    private lateinit var lastDownDurText: TextView
    private lateinit var lastDownRangeSub: TextView
    private lateinit var totalDownDurText: TextView
    private lateinit var volLabel: TextView
    private lateinit var volSlider: SeekBar
    private lateinit var threshSpinner: Spinner
    private lateinit var toggleBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var btnTestDisc: Button
    private lateinit var btnTestRet: Button
    private lateinit var outageCountBadge: TextView
    private lateinit var outageListContainer: LinearLayout
    private lateinit var logCountBadge: TextView
    private lateinit var logView: TextView

    private val uiTimerHandler = Handler(Looper.getMainLooper())
    private val uiTickRunnable = object : Runnable {
        override fun run() {
            if (NetworkMonitorService.isRunning && NetworkMonitorService.downStartTime > 0L) {
                refreshUI()
            }
            uiTimerHandler.postDelayed(this, 1000)
        }
    }

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkMonitorService.ensureLoaded(this)
        requestRequiredPermissions()
        buildUI()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUI() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0f0f0f"))
            isFillViewport = true
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(24))
        }

        // Card 1: Control & Stats Card
        val controlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = makeRoundedRect("#1a1a1a", "#2a2a2a", 20f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val title = TextView(this).apply {
            text = "🌐 مراقب النت الذكي v2.9"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "(By Mahmoud_Kamal)"
            textSize = 16f
            setTextColor(Color.parseColor("#00ff88"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }

        statusCircle = TextView(this).apply {
            text = "جاهز
للبدء"
            textSize = 16f
            setTextColor(Color.parseColor("#00ff88"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = makeCircle("#0a3d1f", "#00ff88")
            layoutParams = LinearLayout.LayoutParams(dp(125), dp(125)).apply {
                topMargin = dp(10)
                bottomMargin = dp(14)
            }
        }

        // Volume Boost Panel
        val volPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedRect("#222222", "#333333", 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val volHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val volTitle = TextView(this).apply {
            text = "📢 قوة صوت التنبيه (مع مضخم Boost):"
            textSize = 13f
            setTextColor(Color.parseColor("#cccccc"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        volLabel = TextView(this).apply {
            text = NetworkMonitorService.toWesternDigits("${NetworkMonitorService.alarmVolumePct}%")
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#ffaa00"))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = makeRoundedRect("#111111", "#444444", 6f)
        }

        volHeader.addView(volTitle)
        volHeader.addView(volLabel)

        volSlider = SeekBar(this).apply {
            max = 300
            progress = NetworkMonitorService.alarmVolumePct
            setPadding(0, dp(8), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    NetworkMonitorService.alarmVolumePct = progress
                    volLabel.text = NetworkMonitorService.toWesternDigits("$progress%")
                    volLabel.setTextColor(if (progress > 100) Color.parseColor("#ffaa00") else Color.parseColor("#00ff88"))
                    NetworkMonitorService.savePersistentState(this@MainActivity)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        volPanel.addView(volHeader)
        volPanel.addView(volSlider)

        // Threshold Selector Row
        val threshRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = makeRoundedRect("#222222", "#2d2d2d", 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val threshLabel = TextView(this).apply {
            text = "تنبيه الفصل بعد فشل متتالي:"
            textSize = 13f
            setTextColor(Color.parseColor("#cccccc"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val threshItems = arrayOf("2 ثواني (حساس)", "3 ثواني (موصى به)", "5 ثواني")
        val threshValues = intArrayOf(2, 3, 5)
        threshSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, threshItems)
            val idx = threshValues.indexOf(NetworkMonitorService.threshold).let { if (it >= 0) it else 1 }
            setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.setTextColor(Color.parseColor("#00ff88"))
                    NetworkMonitorService.threshold = threshValues[position]
                    NetworkMonitorService.savePersistentState(this@MainActivity)
                    refreshUI()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        threshRow.addView(threshLabel)
        threshRow.addView(threshSpinner)

        // 6-Box Stats Grid (3 rows x 2 columns)
        val row1 = makeTwoColRow()
        val boxFail = makeStatBox(row1, "فشل متتالي", false)
        failText = boxFail.first; packetLossSub = boxFail.second
        val boxPing = makeStatBox(row1, "البنج الحالي", false)
        pingText = boxPing.first; pingAvgSub = boxPing.second

        val row2 = makeTwoColRow()
        val boxDown = makeStatBox(row2, "مقطوع حالياً منذ", false)
        downDurText = boxDown.first; currentDownSub = boxDown.second
        val boxDisc = makeStatBox(row2, "مرات الفصل الكلية", false)
        discCountText = boxDisc.first; longestDownSub = boxDisc.second

        val row3 = makeTwoColRow()
        val boxLastDown = makeStatBox(row3, "⏱️ مدة آخر انقطاع", true, "#ffaa00")
        lastDownDurText = boxLastDown.first; lastDownRangeSub = boxLastDown.second
        val boxTotalDown = makeStatBox(row3, "📊 إجمالي وقت الانقطاع", true, "#00ff88")
        totalDownDurText = boxTotalDown.first
        boxTotalDown.second.text = "محفوظ تلقائياً بالهاتف"

        // Buttons
        toggleBtn = Button(this).apply {
            text = "▶️ ابدأ المراقبة"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = makeRoundedRect("#00ff88", "#00ff88", 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { toggleService() }
        }

        muteBtn = Button(this).apply {
            text = "🔇 إسكات التنبيه"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            visibility = View.GONE
            background = makeRoundedRect("#ffaa00", "#ffaa00", 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                NetworkMonitorService.stopAlarm(this@MainActivity)
                visibility = View.GONE
            }
        }

        val testRow = makeTwoColRow()
        btnTestDisc = Button(this).apply {
            text = "🔊 جرب نغمة الفصل"
            textSize = 13f
            setTextColor(Color.parseColor("#ff8888"))
            background = makeRoundedRect("#3d0a0a", "#ff3333", 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener {
                if (NetworkMonitorService.playingToneType == "disc") {
                    NetworkMonitorService.stopAlarm(this@MainActivity)
                } else {
                    NetworkMonitorService.addLog(this@MainActivity, "🚨 تجربة نغمة الفصل (القوة: ${NetworkMonitorService.alarmVolumePct}%)", "fail")
                    NetworkMonitorService.playDisconnectTone(this@MainActivity)
                }
                refreshUI()
            }
        }

        btnTestRet = Button(this).apply {
            text = "🔊 جرب نغمة الرجوع"
            textSize = 13f
            setTextColor(Color.parseColor("#88ffaa"))
            background = makeRoundedRect("#0a3d1f", "#00ff88", 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener {
                if (NetworkMonitorService.playingToneType == "ret") {
                    NetworkMonitorService.stopAlarm(this@MainActivity)
                } else {
                    NetworkMonitorService.addLog(this@MainActivity, "✅ تجربة نغمة الرجوع (القوة: ${NetworkMonitorService.alarmVolumePct}%)", "ok")
                    NetworkMonitorService.playReturnTone(this@MainActivity)
                }
                refreshUI()
            }
        }
        testRow.addView(btnTestDisc)
        testRow.addView(btnTestRet)

        val exportRow = makeTwoColRow()
        val btnExportCsv = Button(this).apply {
            text = "📊 تصدير CSV (Excel)"
            textSize = 13f
            setTextColor(Color.parseColor("#66b3ff"))
            background = makeRoundedRect("#102a45", "#1e4d7b", 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener { exportReportCSV() }
        }

        val btnExportTxt = Button(this).apply {
            text = "📄 تصدير تقرير TXT"
            textSize = 13f
            setTextColor(Color.parseColor("#e5e7eb"))
            background = makeRoundedRect("#1f2937", "#374151", 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener { exportReportTXT() }
        }
        exportRow.addView(btnExportCsv)
        exportRow.addView(btnExportTxt)

        val btnClear = Button(this).apply {
            text = "🗑️ مسح السجل والإحصائيات المحفوظة"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            background = makeRoundedRect("#1a1a1a", "#333333", 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { confirmClearHistory() }
        }

        controlCard.addView(title)
        controlCard.addView(subtitle)
        controlCard.addView(statusCircle)
        controlCard.addView(volPanel)
        controlCard.addView(threshRow)
        controlCard.addView(row1)
        controlCard.addView(row2)
        controlCard.addView(row3)
        controlCard.addView(toggleBtn)
        controlCard.addView(muteBtn)
        controlCard.addView(testRow)
        controlCard.addView(exportRow)
        controlCard.addView(btnClear)

        // Card 2: Dedicated History & Live Event Log Panel
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = makeRoundedRect("#1a1a1a", "#2a2a2a", 20f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val historyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = makeRoundedRect("#141414", "#2d2d2d", 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val historyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val historyTitle = TextView(this).apply {
            text = "📋 سجل فترات الانقطاع المحفوظ"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00ff88"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        outageCountBadge = TextView(this).apply {
            text = "0 انقطاع مسجل"
            textSize = 12f
            setTextColor(Color.parseColor("#aaaaaa"))
        }

        historyHeader.addView(historyTitle)
        historyHeader.addView(outageCountBadge)

        outageListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        historyBox.addView(historyHeader)
        historyBox.addView(outageListContainer)

        val liveLogBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = makeRoundedRect("#141414", "#2d2d2d", 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val logTitle = TextView(this).apply {
            text = "🖥️ السجل الزمني المباشر للأحداث"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00ff88"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        logCountBadge = TextView(this).apply {
            text = "0 حدث مسجل"
            textSize = 11f
            setTextColor(Color.parseColor("#aaaaaa"))
        }

        logHeader.addView(logTitle)
        logHeader.addView(logCountBadge)

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#00ff88"))
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = makeRoundedRect("#000000", "#333333", 10f)
            minLines = 10
        }

        liveLogBox.addView(logHeader)
        liveLogBox.addView(logView)

        logCard.addView(historyBox)
        logCard.addView(liveLogBox)

        mainColumn.addView(controlCard)
        mainColumn.addView(logCard)
        rootScroll.addView(mainColumn)
        setContentView(rootScroll)

        if (NetworkMonitorService.logs.isEmpty()) {
            NetworkMonitorService.addLog(this, "v2.9 جاهز (By Mahmoud_Kamal) - يعمل في الخلفية بأرقام غربية (0-9)", "ok")
        }
        refreshUI()
    }

    private fun makeTwoColRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun makeStatBox(parent: LinearLayout, titleStr: String, highlight: Boolean, accentHex: String = "#ffffff"): Pair<TextView, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = if (highlight) makeRoundedRect("#1f1608", "#553a00", 12f)
                         else makeRoundedRect("#222222", "#2a2a2a", 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }
        val lbl = TextView(this).apply {
            text = titleStr
            textSize = 11f
            setTextColor(if (highlight) Color.parseColor(accentHex) else Color.parseColor("#aaaaaa"))
            if (highlight) setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val valView = TextView(this).apply {
            text = "--"
            textSize = 18f
            setTextColor(Color.parseColor(accentHex))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(3))
        }
        val subView = TextView(this).apply {
            text = "--"
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
        }
        box.addView(lbl)
        box.addView(valView)
        box.addView(subView)
        parent.addView(box)
        return Pair(valView, subView)
    }

    private fun toggleService() {
        val intent = Intent(this, NetworkMonitorService::class.java)
        if (NetworkMonitorService.isRunning) {
            intent.action = NetworkMonitorService.ACTION_STOP
            startService(intent)
        } else {
            intent.action = NetworkMonitorService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
        uiTimerHandler.postDelayed({ refreshUI() }, 150)
    }

    private fun refreshUI() {
        val thresh = NetworkMonitorService.threshold
        val cappedFails = NetworkMonitorService.fails.coerceAtMost(thresh)

        failText.text = NetworkMonitorService.toWesternDigits("$cappedFails / $thresh")
        packetLossSub.text = NetworkMonitorService.toWesternDigits("إجمالي فقد الباكت: ${NetworkMonitorService.totalTimeouts}")
        pingText.text = NetworkMonitorService.toWesternDigits(NetworkMonitorService.lastPing)
        pingAvgSub.text = NetworkMonitorService.toWesternDigits(NetworkMonitorService.pingStatusSub)

        discCountText.text = NetworkMonitorService.toWesternDigits(NetworkMonitorService.disconnectCount)
        longestDownSub.text = if (NetworkMonitorService.longestDowntimeSec > 0L)
            NetworkMonitorService.toWesternDigits("أطول فصل: ${NetworkMonitorService.formatDurationAr(NetworkMonitorService.longestDowntimeSec)}")
        else "أطول فصل: --"

        totalDownDurText.text = NetworkMonitorService.formatDurationAr(NetworkMonitorService.totalDowntimeSec)

        if (NetworkMonitorService.isRunning && NetworkMonitorService.downStartTime > 0L) {
            val curDownSec = ((System.currentTimeMillis() - NetworkMonitorService.downStartTime) / 1000L).coerceAtLeast(1L)
            downDurText.text = NetworkMonitorService.formatDurationAr(curDownSec)
            currentDownSub.text = NetworkMonitorService.toWesternDigits("بدأ منذ ${NetworkMonitorService.getNowTimeStr(NetworkMonitorService.downStartTime)}")
            if (NetworkMonitorService.isDisconnected) {
                NetworkMonitorService.lastDisconnectDurSec = curDownSec
            }
        } else {
            downDurText.text = "--"
            currentDownSub.text = if (NetworkMonitorService.isRunning) "الخط متصل الآن ✓" else "المراقبة متوقفة"
        }

        if (NetworkMonitorService.lastDisconnectDurSec > 0L || NetworkMonitorService.lastDisconnectStartStr.isNotEmpty()) {
            lastDownDurText.text = NetworkMonitorService.formatDurationAr(NetworkMonitorService.lastDisconnectDurSec)
            lastDownRangeSub.text = if (NetworkMonitorService.lastDisconnectEndStr.isNotEmpty()) {
                NetworkMonitorService.toWesternDigits("${NetworkMonitorService.lastDisconnectStartStr} ← ${NetworkMonitorService.lastDisconnectEndStr}")
            } else {
                NetworkMonitorService.toWesternDigits("بدأ ${NetworkMonitorService.lastDisconnectStartStr} (جاري الآن...)")
            }
        } else {
            lastDownDurText.text = "--"
            lastDownRangeSub.text = "لم يسجل انقطاع بعد"
        }

        btnTestDisc.text = if (NetworkMonitorService.playingToneType == "disc") "⏹️ إيقاف النغمة" else "🔊 جرب نغمة الفصل"
        btnTestRet.text = if (NetworkMonitorService.playingToneType == "ret") "⏹️ إيقاف النغمة" else "🔊 جرب نغمة الرجوع"

        if (NetworkMonitorService.isRunning) {
            toggleBtn.text = "⏸️ وقف المراقبة"
            toggleBtn.setTextColor(Color.WHITE)
            toggleBtn.background = makeRoundedRect("#ff3333", "#ff3333", 12f)
            if (NetworkMonitorService.isDisconnected) {
                statusCircle.text = NetworkMonitorService.toWesternDigits("فاصل!
$thresh/$thresh")
                statusCircle.setTextColor(Color.parseColor("#ff3333"))
                statusCircle.background = makeCircle("#3d0a0a", "#ff3333")
                muteBtn.visibility = View.VISIBLE
            } else if (NetworkMonitorService.fails > 0) {
                statusCircle.text = NetworkMonitorService.toWesternDigits("فقد باكت
$cappedFails/$thresh")
                statusCircle.setTextColor(Color.parseColor("#ffaa00"))
                statusCircle.background = makeCircle("#2a1a00", "#ffaa00")
                muteBtn.visibility = View.GONE
            } else {
                statusCircle.text = NetworkMonitorService.toWesternDigits("متصل
${NetworkMonitorService.lastPing}")
                statusCircle.setTextColor(Color.parseColor("#00ff88"))
                statusCircle.background = makeCircle("#0a3d1f", "#00ff88")
                muteBtn.visibility = View.GONE
            }
        } else {
            toggleBtn.text = "▶️ ابدأ المراقبة"
            toggleBtn.setTextColor(Color.BLACK)
            toggleBtn.background = makeRoundedRect("#00ff88", "#00ff88", 12f)
            statusCircle.text = "متوقف
مؤقتاً"
            statusCircle.setTextColor(Color.parseColor("#bbbbbb"))
            statusCircle.background = makeCircle("#181818", "#666666")
            muteBtn.visibility = View.GONE
        }

        // Render Outage Table
        outageListContainer.removeAllViews()
        synchronized(NetworkMonitorService.outageHistory) {
            outageCountBadge.text = NetworkMonitorService.toWesternDigits("${NetworkMonitorService.outageHistory.size} انقطاع مسجل")
            if (NetworkMonitorService.outageHistory.isEmpty()) {
                val emptyView = TextView(this).apply {
                    text = "لا توجد انقطاعات مسجلة حتى الآن"
                    textSize = 12f
                    setTextColor(Color.parseColor("#666666"))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                }
                outageListContainer.addView(emptyView)
            } else {
                val total = NetworkMonitorService.outageHistory.size
                for ((idx, o) in NetworkMonitorService.outageHistory.take(15).withIndex()) {
                    val num = total - idx
                    val endStr = if (o.ongoing) "🔴 جاري الآن" else o.endTime
                    val rowView = TextView(this).apply {
                        text = NetworkMonitorService.toWesternDigits(
                            "#$num | ${o.date} | من: ${o.startTime} إلى: $endStr | المدة: ${NetworkMonitorService.formatDurationAr(o.durationSec)} | ${o.recoveryPing}"
                        )
                        textSize = 11.5f
                        typeface = Typeface.MONOSPACE
                        setTextColor(if (o.ongoing) Color.parseColor("#ff8888") else Color.parseColor("#eeeeee"))
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                    outageListContainer.addView(rowView)
                }
            }
        }

        // Render Logs
        synchronized(NetworkMonitorService.logs) {
            logCountBadge.text = NetworkMonitorService.toWesternDigits("${NetworkMonitorService.logs.size} حدث مسجل")
            val sb = StringBuilder()
            for (item in NetworkMonitorService.logs.take(40)) {
                sb.append("[${NetworkMonitorService.toWesternDigits(item.time)}] ${NetworkMonitorService.toWesternDigits(item.msg)}
")
            }
            logView.text = sb.toString().trimEnd()
        }
    }

    private fun exportReportCSV() {
        val sb = StringBuilder("﻿")
        sb.append(""رقم الانقطاع","التاريخ","وقت بداية الفصل","وقت عودة الاتصال","مدة الانقطاع (بالثواني)","مدة الانقطاع (مفصل)","البنج بعد العودة","الحالة"
")
        synchronized(NetworkMonitorService.outageHistory) {
            val total = NetworkMonitorService.outageHistory.size
            for ((idx, o) in NetworkMonitorService.outageHistory.withIndex()) {
                val num = total - idx
                val endCol = if (o.ongoing) "جاري الآن" else o.endTime
                val statusCol = if (o.ongoing) "مقطوع حالياً" else "تمت العودة"
                sb.append(
                    NetworkMonitorService.toWesternDigits(
                        ""$num","${o.date}","${o.startTime}","$endCol","${o.durationSec}","${NetworkMonitorService.formatDurationAr(o.durationSec)}","${o.recoveryPing}","$statusCol"
"
                    )
                )
            }
        }
        sb.append("
"ملخص التقرير (By Mahmoud_Kamal)","","","","","","",""
")
        sb.append(
            NetworkMonitorService.toWesternDigits(
                ""إجمالي مرات الفصل الكلية","${NetworkMonitorService.disconnectCount}","إجمالي فقد الباكت","${NetworkMonitorService.totalTimeouts}","إجمالي وقت الانقطاع","${NetworkMonitorService.formatDurationAr(NetworkMonitorService.totalDowntimeSec)}","أطول فترة انقطاع","${NetworkMonitorService.formatDurationAr(NetworkMonitorService.longestDowntimeSec)}"
"
            )
        )

        val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        saveAndShareFile("ISP_Outage_Report_Mahmoud_Kamal_$datePart.csv", "text/csv", sb.toString())
        NetworkMonitorService.addLog(this, "📊 تم حفظ ومشاركة تقرير CSV (Excel) بنجاح.", "ok")
        refreshUI()
    }

    private fun exportReportTXT() {
        val nowStr = NetworkMonitorService.toWesternDigits("${NetworkMonitorService.getNowDateStr()} - ${NetworkMonitorService.getNowTimeStr()}")
        val sb = StringBuilder("﻿")
        sb.append("====================================================================
")
        sb.append("   🌐 تقرير حالة وانقطاعات الإنترنت (ISP Technical Outage Report)
")
        sb.append("   مراقب النت الذكي v2.9 - (By Mahmoud_Kamal)
")
        sb.append("   تاريخ استخراج التقرير: $nowStr
")
        sb.append("====================================================================

")
        sb.append("[ أولاً: ملخص إحصائيات الخط ]
")
        sb.append(NetworkMonitorService.toWesternDigits("- إجمالي مرات الفصل الكامل    : ${NetworkMonitorService.disconnectCount} مرة
"))
        sb.append(NetworkMonitorService.toWesternDigits("- إجمالي فقد الباكت (Timeouts): ${NetworkMonitorService.totalTimeouts} باكت
"))
        sb.append(NetworkMonitorService.toWesternDigits("- مدة آخر انقطاع مسجل         : ${NetworkMonitorService.formatDurationAr(NetworkMonitorService.lastDisconnectDurSec)} (${NetworkMonitorService.lastDisconnectStartStr.ifEmpty { "--" }} -> ${NetworkMonitorService.lastDisconnectEndStr.ifEmpty { "--" }})
"))
        sb.append(NetworkMonitorService.toWesternDigits("- أطول فترة انقطاع متواصلة    : ${NetworkMonitorService.formatDurationAr(NetworkMonitorService.longestDowntimeSec)}
"))
        sb.append(NetworkMonitorService.toWesternDigits("- إجمالي وقت انقطاع الخدمة    : ${NetworkMonitorService.formatDurationAr(NetworkMonitorService.totalDowntimeSec)} (${NetworkMonitorService.totalDowntimeSec} ثانية)

"))

        sb.append("====================================================================
")
        synchronized(NetworkMonitorService.outageHistory) {
            sb.append(NetworkMonitorService.toWesternDigits("[ ثانياً: جدول سجل فترات الانقطاع التفصيلي (${NetworkMonitorService.outageHistory.size} سجل) ]
"))
            sb.append("====================================================================
")
            if (NetworkMonitorService.outageHistory.isEmpty()) {
                sb.append("لا توجد انقطاعات كاملة مسجلة في هذه الفترة.
")
            } else {
                val total = NetworkMonitorService.outageHistory.size
                for ((idx, o) in NetworkMonitorService.outageHistory.withIndex()) {
                    val num = total - idx
                    val endLabel = if (o.ongoing) "جاري حتى الآن" else o.endTime
                    sb.append(
                        NetworkMonitorService.toWesternDigits(
                            "#$num | التاريخ: ${o.date} | من: ${o.startTime} | إلى: $endLabel | المدة: ${NetworkMonitorService.formatDurationAr(o.durationSec)} (${o.durationSec}s) | البنج بعد العودة: ${o.recoveryPing}
"
                        )
                    )
                }
            }
        }

        sb.append("
====================================================================
")
        synchronized(NetworkMonitorService.logs) {
            sb.append(NetworkMonitorService.toWesternDigits("[ ثالثاً: السجل الزمني للأحداث وفقد الباكت (آخر ${NetworkMonitorService.logs.size} حدث) ]
"))
            sb.append("====================================================================
")
            for (l in NetworkMonitorService.logs) {
                sb.append(NetworkMonitorService.toWesternDigits("[${l.date} - ${l.time}] ${l.msg}
"))
            }
        }

        val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        saveAndShareFile("ISP_Detailed_Report_Mahmoud_Kamal_$datePart.txt", "text/plain", sb.toString())
        NetworkMonitorService.addLog(this, "📄 تم حفظ ومشاركة التقرير المفصل TXT بنجاح.", "ok")
        refreshUI()
    }

    private fun saveAndShareFile(fileName: String, mimeType: String, content: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                            writer.write(content)
                        }
                    }
                    Toast.makeText(this, "✅ تم حفظ الملف في التنزيلات (Downloads): $fileName", Toast.LENGTH_LONG).show()
                }
            }
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                putExtra(Intent.EXTRA_TEXT, content)
            }
            startActivity(Intent.createChooser(sendIntent, "مشاركة التقرير ($fileName)"))
        } catch (e: Exception) {
            Toast.makeText(this, "تم نسخ التقرير للمشاركة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("مسح السجل والإحصائيات")
            .setMessage("هل أنت متأكد من مسح كافة سجلات الانقطاعات والإحصائيات المحفوظة؟")
            .setPositiveButton("نعم، مسح") { _, _ ->
                NetworkMonitorService.clearAllSavedData(this)
                NetworkMonitorService.addLog(this, "🗑️ تم تصفير السجل المحفوظ وبدء جلسة إحصائيات جديدة.", "ok")
                refreshUI()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private fun makeCircle(bgHex: String, strokeHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(bgHex))
        setStroke(dp(4), Color.parseColor(strokeHex))
    }

    private fun makeRoundedRect(bgHex: String, strokeHex: String, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(Color.parseColor(bgHex))
        setStroke(dp(1), Color.parseColor(strokeHex))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(NetworkMonitorService.ACTION_UPDATE_UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiReceiver, filter)
        }
        uiTimerHandler.post(uiTickRunnable)
        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        uiTimerHandler.removeCallbacks(uiTickRunnable)
        try { unregisterReceiver(uiReceiver) } catch (_: Exception) {}
    }
}
