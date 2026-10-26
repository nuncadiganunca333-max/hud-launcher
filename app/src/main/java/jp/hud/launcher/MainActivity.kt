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

class MainActivity : Activity(), HudView.Listener, AlertOverlay.Listener, AssistantOverlay.Listener {

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
    private lateinit var assistant: AssistantOverlay
    private var recognizer: android.speech.SpeechRecognizer? = null
    private val agent = ClaudeAgent("")
    private val recentNotifs = ArrayDeque<NotifItem>()
    private val REQ_MIC = 51
    private lateinit var moduleFrame: FrameLayout
    private lateinit var widgetHost: android.appwidget.AppWidgetHost
    private var widgetView: android.appwidget.AppWidgetHostView? = null
    private var moduleRect = android.graphics.RectF()
    private var pendingWidgetId = -1
    private val REQ_BIND = 41
    private val REQ_CONFIG = 42
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
        moduleFrame = FrameLayout(this)
        root.addView(moduleFrame, FrameLayout.LayoutParams(-1, -1))
        widgetHost = android.appwidget.AppWidgetHost(this, 4711)
        restoreWidget()
        alert = AlertOverlay(this).also { it.listener = this }
        assistant = AssistantOverlay(this).also { it.listener = this }
        root.addView(alert, FrameLayout.LayoutParams(-1, -1))
        root.addView(assistant, FrameLayout.LayoutParams(-1, -1))
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
        startMedia()
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
        stopMedia()
        hud.stop()
        handler.removeCallbacks(statsLoop)
        NotifService.sink = null
        tts?.stop()
        recognizer?.destroy(); recognizer = null
        assistant.close()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (assistant.isShowing) { onAssistantClose(); return }
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
        recentNotifs.removeAll { it.key == n.key }; recentNotifs.addFirst(n)
        while (recentNotifs.size > 6) recentNotifs.removeLast()
        if (assistant.isShowing) return
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

    override fun onAlertOpen(item: NotifItem, src: android.graphics.Rect?) {
        tts?.stop()
        alertQueue.removeAll { it.key == item.key }
        alert.hide()
        var ok = false
        item.intent?.let {
            try {
                val ao = if (src != null) ActivityOptions.makeClipRevealAnimation(hud, src.left, src.top, src.width(), src.height()) else ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) ao.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                val opts = ao.toBundle()
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

    private fun launch(app: AppEntry, src: android.graphics.Rect? = null, delayMs: Long = 0) {
        val go = Runnable {
            try {
                val opts = src?.let {
                    android.app.ActivityOptions.makeClipRevealAnimation(hud, it.left, it.top, it.width(), it.height()).toBundle()
                }
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(app.pkg, app.cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), opts)
            } catch (e: Exception) { toast("起動できませんでした") }
        }
        if (delayMs > 0) handler.postDelayed(go, delayMs) else go.run()
    }

    // ---------- module (hosted app widget, e.g. Tesla) ----------
    override fun onStart() { super.onStart(); try { widgetHost.startListening() } catch (e: Exception) { } }
    override fun onStop() { super.onStop(); try { widgetHost.stopListening() } catch (e: Exception) { } }

    override fun onModuleRect(r: android.graphics.RectF) { moduleRect = android.graphics.RectF(r); placeWidget() }

    private fun placeWidget() {
        val v = widgetView ?: return
        if (moduleRect.isEmpty) return
        val d = resources.displayMetrics.density
        val info = v.appWidgetInfo
        // ウィジェット本来の高さで描画してから、枠に収まるよう縮小する（詰め込みによる文字の重なり防止）
        val wantH = maxOf(moduleRect.height(), (info?.minHeight ?: 0).toFloat(), 130 * d)
        val scale = (moduleRect.height() / wantH).coerceAtMost(1f)
        val natW = moduleRect.width() / scale
        val natH = moduleRect.height() / scale
        val lp = FrameLayout.LayoutParams(natW.toInt(), natH.toInt())
        lp.leftMargin = moduleRect.left.toInt(); lp.topMargin = moduleRect.top.toInt()
        v.layoutParams = lp
        v.pivotX = 0f; v.pivotY = 0f
        v.scaleX = scale; v.scaleY = scale
        val wdp = (natW / d).toInt(); val hdp = (natH / d).toInt()
        if (Build.VERSION.SDK_INT >= 31) {
            v.updateAppWidgetSize(Bundle(), listOf(android.util.SizeF(wdp.toFloat(), hdp.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            v.updateAppWidgetSize(null, wdp, hdp, wdp, hdp)
        }
    }

    private fun restoreWidget() {
        val id = prefs.getInt("widgetId", -1)
        if (id == -1) { hud.moduleLinked = false; return }
        val info = android.appwidget.AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (info == null) { prefs.edit().remove("widgetId").apply(); hud.moduleLinked = false; return }
        showWidget(id, info)
    }

    private fun showWidget(id: Int, info: android.appwidget.AppWidgetProviderInfo) {
        moduleFrame.removeAllViews()
        val v = widgetHost.createView(applicationContext, id, info)
        v.setAppWidget(id, info)
        moduleFrame.addView(v)
        widgetView = v
        prefs.edit().putInt("widgetId", id).apply()
        hud.moduleLinked = true
        placeWidget()
    }

    private fun removeWidget() {
        val id = prefs.getInt("widgetId", -1)
        if (id != -1) widgetHost.deleteAppWidgetId(id)
        prefs.edit().remove("widgetId").apply()
        moduleFrame.removeAllViews(); widgetView = null
        hud.moduleLinked = false
    }

    override fun onModuleEdit() {
        if (prefs.getInt("widgetId", -1) != -1) {
            AlertDialog.Builder(this).setTitle("MODULE")
                .setItems(arrayOf("ウィジェットを変更", "ウィジェットを外す")) { _, w -> if (w == 0) pickWidget() else removeWidget() }
                .show()
        } else pickWidget()
    }

    private fun pickWidget() {
        val awm = android.appwidget.AppWidgetManager.getInstance(this)
        val providers = awm.installedProviders.sortedWith(compareBy(
            { if (it.provider.packageName.contains("tesla", true)) 0 else 1 },
            { it.loadLabel(packageManager).lowercase() }))
        if (providers.isEmpty()) { toast("追加できるウィジェットがありません"); return }
        val names = providers.map {
            val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it.provider.packageName, 0)).toString() } catch (e: Exception) { "" }
            "$app ／ ${it.loadLabel(packageManager)}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("MODULEに入れるウィジェット")
            .setItems(names) { _, i -> bindWidget(providers[i]) }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun bindWidget(info: android.appwidget.AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val awm = android.appwidget.AppWidgetManager.getInstance(this)
        if (awm.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) configureWidget(id)
        else {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), REQ_BIND)
        }
    }

    private fun configureWidget(id: Int) {
        val info = android.appwidget.AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return
        if (info.configure != null) {
            try { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null); return }
            catch (e: Exception) { }
        }
        finishWidget(id)
    }

    private fun finishWidget(id: Int) {
        val old = prefs.getInt("widgetId", -1)
        if (old != -1 && old != id) widgetHost.deleteAppWidgetId(old)
        val info = android.appwidget.AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return
        showWidget(id, info)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        if (id == -1) return
        if (resultCode != RESULT_OK) { widgetHost.deleteAppWidgetId(id); pendingWidgetId = -1; return }
        when (requestCode) {
            REQ_BIND -> configureWidget(id)
            REQ_CONFIG -> { finishWidget(id); pendingWidgetId = -1 }
        }
    }

    // ---------- Claude assistant ----------
    private fun apiKey() = prefs.getString("apiKey", "") ?: ""

    override fun onCoreLongPress() {
        AlertDialog.Builder(this).setTitle("HUD 設定")
            .setItems(arrayOf("Claude APIキーを設定", "呼び名を変更（いま：${callName()}）", "会話をリセット", "アラーム一覧を開く", "ホームアプリを切り替え")) { _, w ->
                when (w) {
                    0 -> askApiKey(); 1 -> askName(); 2 -> { agent.reset(); toast("会話をリセットしました") }
                    3 -> safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)); 4 -> onHomeSettings()
                }
            }.show()
    }

    private fun callName() = prefs.getString("callName", "Yudaiさん") ?: "Yudaiさん"

    private fun askName() {
        val et = EditText(this).apply { setText(callName()); setSingleLine() }
        AlertDialog.Builder(this).setTitle("呼び名").setView(et)
            .setPositiveButton("保存") { _, _ -> prefs.edit().putString("callName", et.text.toString().trim()).apply() }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun askApiKey() {
        val et = EditText(this).apply {
            hint = "sk-ant-..."; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey())
        }
        AlertDialog.Builder(this).setTitle("Claude APIキー")
            .setMessage("console.anthropic.com で発行したAPIキーを貼り付けてください。キーはこの端末の中にだけ保存されます。")
            .setView(et)
            .setPositiveButton("保存") { _, _ -> prefs.edit().putString("apiKey", et.text.toString().trim()).apply(); agent.reset(); toast("保存しました。コアをタップして話しかけてください") }
            .setNeutralButton("発行ページを開く") { _, _ -> safeStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://console.anthropic.com/settings/keys"))) }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun startAssistant() {
        if (apiKey().isEmpty()) { askApiKey(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        closeDrawer()
        assistant.open()
        listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startAssistant()
            else toast("マイクの許可がないと話しかけられません")
        }
    }

    private fun listen() {
        tts?.stop()
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            assistant.state = AssistantOverlay.State.ERROR; assistant.reply = "この端末では音声認識が使えないようです"; return
        }
        recognizer?.destroy()
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        assistant.heard = ""; assistant.reply = ""; assistant.level = 0f
        assistant.state = AssistantOverlay.State.LISTENING
        playBeep()
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { assistant.level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { assistant.level = 0f }
            override fun onError(error: Int) {
                assistant.level = 0f
                if (assistant.state == AssistantOverlay.State.LISTENING) {
                    assistant.state = AssistantOverlay.State.IDLE
                    assistant.reply = if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        "聞き取れませんでした。マイクをタップしてもう一度どうぞ。" else "音声認識エラー（$error）"
                }
            }
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) { assistant.state = AssistantOverlay.State.IDLE; assistant.reply = "聞き取れませんでした。"; return }
                assistant.heard = text
                think(text)
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) assistant.heard = it }
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    private fun playBeep() {
        try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 35).startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 90) } catch (e: Exception) { }
    }

    private fun systemPrompt(): String {
        val s = hud.stats
        val now = java.text.SimpleDateFormat("yyyy年M月d日(E) H:mm", Locale.JAPAN).format(java.util.Date())
        val m = hud.media
        val media = if (m == null) "なし" else "${m.title} / ${m.artist}（${if (m.playing) "再生中" else "一時停止"}）"
        val notifs = recentNotifs.mapIndexed { i, n -> "${i + 1}. ${n.appLabel}：${n.title}" }.joinToString("\n").ifEmpty { "なし" }
        val appNames = apps.joinToString("、") { it.label }
        return """あなたは${callName()}のスマートフォン（Galaxy Z Fold7）のホーム画面に組み込まれたAIアシスタント、Claudeです。
映画に出てくる有能なAI執事のように、落ち着いて丁寧、ときどき軽いウィットを交えて話してください。相手は「${callName()}」と呼びます。
返答は音声で読み上げられます。話し言葉で1〜3文に短くまとめ、記号・箇条書き・絵文字・URLは使わないでください。
端末の操作が必要なときはツールを使い、実行後は結果をひとことで伝えてください。わからないことや最新情報は推測で断言せず、必要ならweb_searchを使ってください。
テスラ車の直接操作はできません。頼まれたら、ホームのMODULE欄のテスラウィジェットかテスラアプリを案内してください。

【現在の状況】
日時：$now
バッテリー：${s.battery}%（${if (s.charging) "充電中" else "放電中"}）、端末温度 ${s.battTemp}℃
天気（静岡市清水区）：${s.weather}、気温 ${s.temp ?: "不明"}℃、湿度 ${s.humidity}%、風速 ${s.wind}m/s
予報：${s.forecast.joinToString("／")}
再生中：$media
最近の通知：
$notifs
インストール済みアプリ：$appNames"""
    }

    private fun think(text: String) {
        assistant.state = AssistantOverlay.State.THINKING
        agent.apiKey = apiKey()
        val sys = systemPrompt()
        thread {
            val out = try {
                agent.ask(text, sys, ClaudeAgent.tools()) { name, input -> runTool(name, input) }
            } catch (e: ClaudeAgent.ApiError) { "ERR:" + e.message }
            runOnUiThread {
                if (!assistant.isShowing) return@runOnUiThread
                if (out.startsWith("ERR:")) {
                    assistant.state = AssistantOverlay.State.ERROR; assistant.reply = out.removePrefix("ERR:")
                } else {
                    assistant.state = AssistantOverlay.State.SPEAKING; assistant.reply = out
                    speakReply(out)
                }
            }
        }
    }

    private fun speakReply(text: String) {
        if (!ttsReady) { assistant.state = AssistantOverlay.State.IDLE; return }
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { runOnUiThread { if (assistant.isShowing && assistant.state == AssistantOverlay.State.SPEAKING) assistant.state = AssistantOverlay.State.IDLE } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {}
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant")
    }

    /** Claude が呼ぶ端末操作（バックグラウンドスレッドから呼ばれる） */
    private fun runTool(name: String, input: JSONObject): String {
        fun ui(block: () -> Unit) = runOnUiThread(block)
        return when (name) {
            "open_app" -> {
                val q = input.optString("name").lowercase().replace(" ", "")
                val hit = apps.firstOrNull { it.label.lowercase().replace(" ", "") == q }
                    ?: apps.firstOrNull { it.label.lowercase().replace(" ", "").contains(q) }
                    ?: apps.firstOrNull { q.contains(it.label.lowercase().replace(" ", "")) && it.label.length >= 2 }
                if (hit == null) "見つかりませんでした。候補: " + apps.filter { a -> q.take(2).let { a.label.lowercase().contains(it) } }.take(5).joinToString { it.label }
                else {
                    ui {
                        handler.postDelayed({
                            assistant.close()
                            val r = android.graphics.Rect(hud.width / 2 - 100, hud.height / 2 - 100, hud.width / 2 + 100, hud.height / 2 + 100)
                            hud.flashLaunch(r, hit.label); launch(hit, r, 320)
                        }, 1600)
                    }
                    "${hit.label}を起動します"
                }
            }
            "media_control" -> {
                val c = controller ?: return "再生中のメディアがありません"
                when (input.optString("action")) {
                    "play" -> c.transportControls.play(); "pause" -> c.transportControls.pause()
                    "next" -> c.transportControls.skipToNext(); "previous" -> c.transportControls.skipToPrevious()
                }
                "OK"
            }
            "set_timer" -> {
                val sec = input.optInt("seconds")
                safeStart(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label", "HUD")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "${sec}秒のタイマーをセットしました"
            }
            "set_alarm" -> {
                safeStart(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, input.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, input.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label", "HUD")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "アラームをセットしました"
            }
            "flashlight" -> try {
                val cm = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                cm.setTorchMode(id, input.optBoolean("on")); if (input.optBoolean("on")) "点灯しました" else "消灯しました"
            } catch (e: Exception) { "ライトを操作できませんでした" }
            "web_search" -> {
                ui { handler.postDelayed({ assistant.close(); safeStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, input.optString("query"))) }, 1600) }
                "検索を開きます"
            }
            "open_notification" -> {
                val n = recentNotifs.toList().getOrNull(input.optInt("index") - 1) ?: return "その番号の通知はありません"
                ui { handler.postDelayed({ assistant.close(); onAlertOpen(n, null) }, 1400) }
                "${n.appLabel}の通知を開きます"
            }
            else -> "unknown tool"
        }
    }

    override fun onAssistantMic() { if (assistant.state != AssistantOverlay.State.LISTENING) listen() }

    override fun onAssistantClose() {
        recognizer?.cancel(); tts?.stop()
        assistant.close()
    }

    // ---------- media ----------
    private var mediaMgr: android.media.session.MediaSessionManager? = null
    private var controller: android.media.session.MediaController? = null
    private val mediaCb = object : android.media.session.MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = pushMedia()
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) { pickController(); pushMedia() }
        override fun onSessionDestroyed() { pickController(); pushMedia() }
    }
    private val sessionsListener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { pickController(); pushMedia() }

    private fun startMedia() {
        if (!notifAccessEnabled()) return
        try {
            val mm = getSystemService(MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            mediaMgr = mm
            mm.addOnActiveSessionsChangedListener(sessionsListener, ComponentName(this, NotifService::class.java))
            pickController(); pushMedia()
        } catch (e: Exception) { }
    }

    private fun stopMedia() {
        try { mediaMgr?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        controller?.unregisterCallback(mediaCb); controller = null
    }

    private fun pickController() {
        val list = try { mediaMgr?.getActiveSessions(ComponentName(this, NotifService::class.java)) } catch (e: Exception) { null } ?: emptyList()
        val best = list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(mediaCb)
            controller = best
            best?.registerCallback(mediaCb, handler)
        }
    }

    private fun pushMedia() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) { hud.media = null; return }
        val st = c.playbackState
        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (title.isEmpty() && artist.isEmpty()) { hud.media = null; return }
        hud.media = MediaInfo(title, artist, art,
            st?.state == android.media.session.PlaybackState.STATE_PLAYING,
            md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION),
            st?.position ?: 0L, st?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime(), st?.playbackSpeed ?: 1f)
    }

    override fun onMediaAction(action: String) {
        val c = controller ?: return
        val tc = c.transportControls
        when (action) {
            "prev" -> tc.skipToPrevious()
            "next" -> tc.skipToNext()
            "toggle" -> if (c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            "open" -> try { c.sessionActivity?.send() } catch (e: Exception) {
                packageManager.getLaunchIntentForPackage(c.packageName)?.let { safeStart(it) }
            }
        }
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
            setOnItemClickListener { _, v, p, _ ->
                val loc = IntArray(2); v.getLocationInWindow(loc)
                launch(filtered[p], android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)); closeDrawer()
            }
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
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=Asia%2FTokyo&forecast_days=4&hourly=temperature_2m&forecast_hours=24")
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
                        hourly = j.optJSONObject("hourly")?.optJSONArray("temperature_2m")?.let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } } ?: emptyList(),
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
    override fun onLaunch(app: AppEntry, src: android.graphics.Rect?) = launch(app, src, delayMs = 320)
    override fun onDockLongPress(app: AppEntry) = toggleDock(app)
    override fun onClockTap() = startAssistant()
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
