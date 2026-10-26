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

class MainActivity : Activity(), HudView.Listener {

    private val CYAN = Color.rgb(0, 229, 255)
    private lateinit var hud: HudView
    private lateinit var drawer: LinearLayout
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private var apps: List<AppEntry> = emptyList()
    private var filtered: List<AppEntry> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var lastWeather = 0L
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
        drawer = buildDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            hud.setTopInset(bars.top)
            drawer.setPadding(dp(16), bars.top + dp(12), dp(16), bars.bottom + dp(12))
            ins
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        hud.start()
        handler.post(statsLoop)
    }

    override fun onPause() {
        super.onPause()
        hud.stop()
        handler.removeCallbacks(statsLoop)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer()
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
            toast("ドックから外しました：${app.label}")
        } else {
            if (keys.size >= 5) { toast("ドックは5個までです"); return }
            keys += app.key; toast("ドックに登録：${app.label}")
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
            text = "タップで起動 ／ 長押しでドックに登録・解除"
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
    private val statsLoop = object : Runnable {
        override fun run() {
            val s = hud.stats.copy()
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                val lv = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                s.battery = lv * 100 / sc.coerceAtLeast(1)
                val st = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                s.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
            val mi = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            s.memory = (100 - mi.availMem * 100 / mi.totalMem.coerceAtLeast(1)).toInt()
            val fs = StatFs(Environment.getDataDirectory().path)
            s.storage = (100 - fs.availableBytes * 100 / fs.totalBytes.coerceAtLeast(1)).toInt()
            hud.stats = s
            if (System.currentTimeMillis() - lastWeather > 30 * 60 * 1000) fetchWeather()
            handler.postDelayed(this, 5000)
        }
    }

    private fun fetchWeather() {
        lastWeather = System.currentTimeMillis()
        thread {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                        "&current=temperature_2m,relative_humidity_2m,weather_code" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=Asia%2FTokyo&forecast_days=4")
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
    override fun onGaugeTap(index: Int) {
        when (index) {
            0 -> safeStart(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            1 -> { lastWeather = 0; fetchWeather(); toast("天気を更新中…") }
            2 -> safeStart(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            3 -> safeStart(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        }
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("開けませんでした") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
