package jp.hud.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.Build
import android.app.ActivityOptions
import android.app.AlertDialog
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.SystemClock
import android.net.TrafficStats
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

data class AppEntry(val label: String, val pkg: String, val cls: String) {
    val key get() = "$pkg/$cls"
}

class MainActivity : Activity(), HudView.Listener, AlertOverlay.Listener {

    private val CYAN = Color.rgb(0, 229, 255)
    private lateinit var hud: HudView
    private lateinit var drawer: LinearLayout
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private var apps: List<AppEntry> = emptyList()
    private var filtered: List<AppEntry> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var lastWeather = 0L
    private lateinit var alert: AlertOverlay
    private val alertQueue = ArrayDeque<NotifItem>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val prefs by lazy { getSharedPreferences("hud", Context.MODE_PRIVATE) }

    // 天気の地点（静岡市清水区付近）
    private val LAT = 35.02
    private val LON = 138.48

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this)
        hud = HudView(this).also { it.listener = this }
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        alert = AlertOverlay(this).also { it.listener = this }
        root.addView(alert, FrameLayout.LayoutParams(-1, -1))
        drawer = buildDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.JAPAN; tts?.setSpeechRate(1.05f); ttsReady = true }
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            hud.setInsets(bars.top, bars.bottom)
            drawer.setPadding(dp(16), bars.top + dp(12), dp(16), bars.bottom + dp(12))
            ins
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        hud.start()
        handler.post(statsLoop)
        val enabled = notifAccessEnabled()
        hud.notifLink = enabled
        hud.voiceOn = prefs.getBoolean("voice", true)
        if (!enabled && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            askNotifAccess()
        }
        NotifService.sink = { onNotif(it, live = true) }
        // ホーム以外にいた間に届いた通知（10分以内）
        val now = System.currentTimeMillis()
        NotifService.pending.filter { now - it.time < 10 * 60 * 1000 }.forEach { onNotif(it, live = false) }
        NotifService.pending.clear()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        hud.stop()
        handler.removeCallbacks(statsLoop)
        NotifService.sink = null
        tts?.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (alert.isShowing) alert.listener?.let { onAlertDismiss(alertQueue.firstOrNull() ?: return) }
        else if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    // ---------- notifications (JARVIS-style) ----------
    private fun notifAccessEnabled() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun askNotifAccess() {
        AlertDialog.Builder(this)
            .setTitle("通知アラートを有効にしますか？")
            .setMessage("通知が届いたときにHUDで知らせて、アプリを開くか確認するには「通知へのアクセス」の許可が必要です。\n\n次の画面で「HUD Launcher」をオンにしてください。")
            .setPositiveButton("設定を開く") { _, _ -> safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun onNotif(n: NotifItem, live: Boolean) {
        alertQueue.removeAll { it.key == n.key }
        alertQueue.addLast(n)
        while (alertQueue.size > 6) alertQueue.removeFirst()
        if (!alert.isShowing) showNext(speak = live)
        else alert.show(alertQueue.first(), alertQueue.size - 1)
    }

    private fun showNext(speak: Boolean) {
        val n = alertQueue.firstOrNull() ?: run { alert.hide(); return }
        closeDrawer()
        alert.show(n, alertQueue.size - 1)
        if (speak) say(n)
    }

    private fun say(n: NotifItem) {
        if (!ttsReady || !prefs.getBoolean("voice", true)) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val who = if (n.title.isNotEmpty() && n.title.length <= 20) "、${n.title}さんから" else ""
        tts?.speak("${n.appLabel}${who}の通知です。開きますか？", TextToSpeech.QUEUE_FLUSH, null, n.key)
    }

    override fun onAlertOpen(item: NotifItem) {
        tts?.stop()
        alertQueue.removeAll { it.key == item.key }
        alert.hide()
        var ok = false
        item.intent?.let {
            try {
                val opts = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
                it.send(this, 0, null, null, null, null, opts); ok = true
            } catch (e: Exception) { }
        }
        if (!ok) packageManager.getLaunchIntentForPackage(item.pkg)?.let { safeStart(it); ok = true }
        if (!ok) toast("開けませんでした")
        if (item.autoCancel) try { NotifService.instance?.cancelNotification(item.key) } catch (e: Exception) { }
        alertQueue.clear()
    }

    override fun onAlertDismiss(item: NotifItem) {
        tts?.stop()
        alertQueue.removeAll { it.key == item.key }
        showNext(speak = false)
    }

    override fun onTopStripTap() {
        if (!notifAccessEnabled()) askNotifAccess()
        else {
            val v = !prefs.getBoolean("voice", true)
            prefs.edit().putBoolean("voice", v).apply()
            hud.voiceOn = v
            toast(if (v) "音声読み上げ：ON" else "音声読み上げ：OFF")
        }
    }

    // ---------- apps ----------
    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.label.lowercase() }
        applyFilter()
        refreshDock()
    }

    private fun refreshDock() {
        val keys = prefs.getString("dock", "")!!.split("|").filter { it.isNotEmpty() }
        val byKey = apps.associateBy { it.key }
        hud.dock = keys.mapNotNull { byKey[it] }
    }

    private fun saveDock(keys: List<String>) = prefs.edit().putString("dock", keys.joinToString("|")).apply()

    private fun toggleDock(app: AppEntry) {
        val keys = prefs.getString("dock", "")!!.split("|").filter { it.isNotEmpty() }.toMutableList()
        if (keys.remove(app.key)) {
            toast("リングから外しました：${app.label}")
        } else {
            if (keys.size >= 7) { toast("リングに登録できるのは7個までです"); return }
            keys += app.key; toast("リングに登録：${app.label}")
        }
        saveDock(keys); refreshDock()
    }

    private fun launch(app: AppEntry) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(app.pkg, app.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) { toast("起動できませんでした") }
    }

    // ---------- drawer ----------
    private fun buildDrawer(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 1, 8, 16))
            visibility = View.GONE
            isClickable = true
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "APPLICATIONS"
            setTextColor(CYAN); textSize = 22f; letterSpacing = 0.2f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "✕"; setTextColor(CYAN); textSize = 24f
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener { closeDrawer() }
        })
        box.addView(header)
        box.addView(TextView(this).apply {
            text = "タップで起動 ／ 長押しでリングに登録・解除"
            setTextColor(Color.argb(160, 0, 229, 255)); textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        })
        search = EditText(this).apply {
            hint = "SEARCH"; setHintTextColor(Color.argb(110, 0, 229, 255))
            setTextColor(CYAN); textSize = 16f; isSingleLine = true
            background = frameBg(80)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(150)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(8); verticalSpacing = dp(8)
            adapter = object : BaseAdapter() {
                override fun getCount() = filtered.size
                override fun getItem(p: Int) = filtered[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                    val tv = (cv as? TextView) ?: TextView(this@MainActivity).apply {
                        setTextColor(CYAN); textSize = 14f; gravity = Gravity.CENTER
                        maxLines = 2; setPadding(dp(8), dp(14), dp(8), dp(14))
                        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                    }
                    val app = filtered[p]
                    val docked = hud.dock.any { it.key == app.key }
                    tv.text = if (docked) "◆ ${app.label}" else app.label
                    tv.background = frameBg(if (docked) 230 else 110)
                    return tv
                }
            }
            setOnItemClickListener { _, _, p, _ -> launch(filtered[p]); closeDrawer() }
            setOnItemLongClickListener { _, _, p, _ ->
                toggleDock(filtered[p]); (adapter as BaseAdapter).notifyDataSetChanged(); true
            }
        }
        box.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        return box
    }

    private fun frameBg(alpha: Int) = GradientDrawable().apply {
        setColor(Color.argb(60, 0, 40, 60)); setStroke(dp(1), Color.argb(alpha, 0, 229, 255))
    }

    private fun applyFilter() {
        if (!::grid.isInitialized) return
        val q = search.text.toString().trim().lowercase()
        filtered = if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun openDrawer() {
        drawer.alpha = 0f; drawer.translationY = dp(40).toFloat()
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        search.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        drawer.visibility = View.GONE
    }

    // ---------- stats ----------
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastSample = 0L
    private var tick = 0
    private val rxHist = ArrayDeque<Float>()
    private val txHist = ArrayDeque<Float>()
    private val memHist = ArrayDeque<Float>()
    private val battHist = ArrayDeque<Float>()

    private fun push(q: ArrayDeque<Float>, v: Float, max: Int) { q.addLast(v); while (q.size > max) q.removeFirst() }

    private val statsLoop = object : Runnable {
        override fun run() {
            val s = hud.stats.copy()
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                val lv = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                s.battery = lv * 100 / sc.coerceAtLeast(1)
                val st = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                s.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                s.battTemp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                s.voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            }
            val mi = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            s.memory = (100 - mi.availMem * 100 / mi.totalMem.coerceAtLeast(1)).toInt()
            if (tick % 10 == 0) {
                val fs = StatFs(Environment.getDataDirectory().path)
                s.storage = (100 - fs.availableBytes * 100 / fs.totalBytes.coerceAtLeast(1)).toInt()
            }
            s.uptimeMs = SystemClock.elapsedRealtime()

            val nowMs = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
            if (lastRx >= 0 && rx >= lastRx && tx >= lastTx && nowMs > lastSample) {
                val sec = (nowMs - lastSample) / 1000f
                s.rxKbps = (rx - lastRx) / 1024f / sec
                s.txKbps = (tx - lastTx) / 1024f / sec
            }
            lastRx = rx; lastTx = tx; lastSample = nowMs
            push(rxHist, s.rxKbps, 60); push(txHist, s.txKbps, 60)
            if (tick % 2 == 0) push(memHist, s.memory.toFloat(), 60)
            if (battHist.isEmpty()) { push(battHist, s.battery.toFloat(), 120); push(battHist, s.battery.toFloat(), 120) }
            if (tick % 60 == 0) push(battHist, s.battery.toFloat(), 120)
            s.rxHist = rxHist.toList(); s.txHist = txHist.toList()
            s.memHist = memHist.toList(); s.battHist = battHist.toList()
            hud.stats = s
            tick++
            if (System.currentTimeMillis() - lastWeather > 30 * 60 * 1000) fetchWeather()
            handler.postDelayed(this, 1000)
        }
    }

    private fun fetchWeather() {
        lastWeather = System.currentTimeMillis()
        thread {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&wind_speed_unit=ms" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=Asia%2FTokyo&forecast_days=4")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 8000
                val body = con.inputStream.bufferedReader().readText()
                val j = JSONObject(body)
                val cur = j.getJSONObject("current")
                val daily = j.getJSONObject("daily")
                val codes = daily.getJSONArray("weather_code")
                val hi = daily.getJSONArray("temperature_2m_max")
                val lo = daily.getJSONArray("temperature_2m_min")
                val names = arrayOf("今日", "明日", "明後日", "3日後")
                val lines = (0 until minOf(3, codes.length())).map {
                    String.format("%s  %s  %d° / %d°", names[it], wx(codes.getInt(it)),
                        Math.round(hi.getDouble(it)), Math.round(lo.getDouble(it)))
                }
                runOnUiThread {
                    hud.stats = hud.stats.copy(
                        temp = cur.getDouble("temperature_2m").toFloat(),
                        humidity = cur.getInt("relative_humidity_2m"),
                        weather = wx(cur.getInt("weather_code")),
                        feels = cur.getDouble("apparent_temperature").toFloat(),
                        wind = cur.getDouble("wind_speed_10m").toFloat(),
                        sunrise = daily.getJSONArray("sunrise").getString(0).takeLast(5),
                        sunset = daily.getJSONArray("sunset").getString(0).takeLast(5),
                        forecast = lines,
                    )
                }
            } catch (e: Exception) {
                lastWeather = System.currentTimeMillis() - 25 * 60 * 1000 // 5分後に再試行
            }
        }
    }

    private fun wx(c: Int) = when (c) {
        0 -> "快晴"; 1 -> "晴れ"; 2 -> "薄曇り"; 3 -> "曇り"
        45, 48 -> "霧"; in 51..57 -> "霧雨"; in 61..67 -> "雨"
        in 71..77 -> "雪"; in 80..82 -> "にわか雨"; 85, 86 -> "にわか雪"
        in 95..99 -> "雷雨"; else -> "---"
    }

    // ---------- HUD callbacks ----------
    override fun onOpenDrawer() = openDrawer()
    override fun onLaunch(app: AppEntry) = launch(app)
    override fun onDockLongPress(app: AppEntry) = toggleDock(app)
    override fun onClockTap() = safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    override fun onHomeSettings() = safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
    override fun onPanelTap(panel: String) {
        when (panel) {
            "POWER" -> safeStart(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            "WEATHER", "FORECAST" -> { lastWeather = 0; fetchWeather(); toast("天気を更新中…") }
            "SYSTEM" -> safeStart(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            "CALENDAR" -> safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            "NETWORK" -> safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("開けませんでした") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
