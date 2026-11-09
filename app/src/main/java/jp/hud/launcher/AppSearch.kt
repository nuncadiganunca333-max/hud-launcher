package jp.hud.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.text.Normalizer

/** アプリ一覧の「探しやすさ」まわり：読みの正規化・ローマ字検索・索引・種類分け */
object AppText {

    /** 全角/半角・大文字/小文字・カタカナ/ひらがなの違いをなくす */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (ch in n) {
            when {
                ch in 'ァ'..'ヶ' -> sb.append((ch.code - 0x60).toChar())
                ch == ' ' || ch == '　' || ch == '・' || ch == '_' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val ROMA: Map<String, String> = HashMap<String, String>().apply {
        val rows = listOf(
            "" to "あいうえお", "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの",
            "h" to "はひふへほ", "m" to "まみむめも", "r" to "らりるれろ", "g" to "がぎぐげご", "z" to "ざじずぜぞ",
            "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ")
        val v = "aiueo"
        for ((c, kana) in rows) for (i in 0 until 5) put(c + v[i], kana[i].toString())
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ"); put("wa", "わ"); put("wo", "を")
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("fu", "ふ"); put("ji", "じ")
        put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ")
        put("la", "ら"); put("li", "り"); put("lu", "る"); put("le", "れ"); put("lo", "ろ")
        put("va", "ば"); put("vi", "び"); put("vu", "ぶ"); put("ve", "べ"); put("vo", "ぼ")
        put("ti", "てぃ"); put("di", "でぃ"); put("wi", "うぃ"); put("we", "うぇ")
        for ((c, k) in listOf("ky" to "き", "sy" to "し", "sh" to "し", "ty" to "ち", "ch" to "ち", "ny" to "に", "hy" to "ひ",
            "my" to "み", "ry" to "り", "gy" to "ぎ", "zy" to "じ", "j" to "じ", "jy" to "じ", "by" to "び", "py" to "ぴ")) {
            put(c + "a", k + "ゃ"); put(c + "u", k + "ゅ"); put(c + "o", k + "ょ")
            put(c + "e", k + "ぇ")
        }
    }

    /** ローマ字で打った検索語をひらがなに（変換できない文字が残るなら null） */
    fun romajiToHira(q: String): String? {
        if (q.isEmpty() || !q.all { it in 'a'..'z' || it == '-' }) return null
        val sb = StringBuilder(); var i = 0
        while (i < q.length) {
            val ch = q[i]
            if (ch == '-') { sb.append('ー'); i++; continue }
            // っ：子音の連続（nn は「ん」）
            if (i + 1 < q.length && ch == q[i + 1] && ch !in "aiueon") { sb.append('っ'); i++; continue }
            var hit = false
            for (len in 4 downTo 1) {
                if (i + len > q.length) continue
                val k = ROMA[q.substring(i, i + len)] ?: continue
                sb.append(k); i += len; hit = true; break
            }
            if (hit) continue
            if (ch == 'n') { sb.append('ん'); i += if (i + 1 < q.length && q[i + 1] == 'n') 2 else 1; continue }
            if (i == q.length - 1) { i++; continue }   // 打ちかけの子音は無視
            return null
        }
        return sb.toString().ifEmpty { null }
    }

    /** 英語名のアプリを、かなでも見つけられるようにする読み */
    private val ALIAS = listOf(
        "youtube" to "ゆーちゅーぶ ようつべ", "naver.line" to "らいん", "chrome" to "くろーむ", "android.gm" to "じーめーる めーる",
        "slack" to "すらっく", "tesla" to "てすら", "instagram" to "いんすたぐらむ いんすた", "twitter" to "ついったー えっくす",
        "amazon" to "あまぞん", "paypay" to "ぺいぺい", "maps" to "まっぷ ちず", "spotify" to "すぽてぃふぁい", "netflix" to "ねっとふりっくす",
        "anthropic" to "くろーど", "openai" to "ちゃっとじーぴーてぃー", "camera" to "かめら", "calendar" to "かれんだー", "calculator" to "でんたく",
        "clock" to "とけい", "settings" to "せってい", "gallery" to "ぎゃらりー しゃしん", "photos" to "ふぉと しゃしん", "docs" to "どきゅめんと",
        "apps.docs" to "どらいぶ", "teams" to "ちーむず", "zoom" to "ずーむ", "mercari" to "めるかり", "rakuten" to "らくてん",
        "facebook" to "ふぇいすぶっく", "discord" to "でぃすこーど", "notion" to "のーしょん", "vending" to "ぷれいすとあ すとあ",
        "contacts" to "れんらくさき", "dialer" to "でんわ", "messaging" to "めっせーじ", "myfiles" to "ふぁいる", "abema" to "あべま",
        "avod" to "ぷらいむびでお", "yahoo" to "やふー", "whatsapp" to "わっつあっぷ", "tiktok" to "てぃっくとっく", "musically" to "てぃっくとっく",
        "outlook" to "あうとるっく", "excel" to "えくせる", "word" to "わーど", "powerpoint" to "ぱわーぽいんと", "github" to "ぎっとはぶ",
        "uber" to "うーばー", "sbrowser" to "ぶらうざ いんたーねっと", "samsung.android.app.notes" to "のーと めも", "keep" to "きーぷ めも")

    fun searchText(label: String, pkg: String): String {
        val p = pkg.lowercase()
        val al = ALIAS.filter { p.contains(it.first) }.joinToString(" ") { it.second }
        return norm(label) + " " + p + " " + al
    }

    val GROUPS = listOf("#", "A", "D", "G", "J", "M", "P", "S", "V", "Y", "あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ", "漢")
    private val LATIN = listOf("A", "D", "G", "J", "M", "P", "S", "V", "Y")

    /** 索引のどこに入るか */
    fun groupOf(label: String): String {
        val ch = norm(label).firstOrNull() ?: return "#"
        return when (ch) {
            in 'a'..'z' -> LATIN[((ch - 'a') / 3).coerceAtMost(8)]
            in 'ぁ'..'お' -> "あ"; in 'か'..'ご' -> "か"; in 'さ'..'ぞ' -> "さ"
            in 'た'..'ど' -> "た"; in 'な'..'の' -> "な"; in 'は'..'ぽ' -> "は"
            in 'ま'..'も' -> "ま"; in 'ゃ'..'よ' -> "や"; in 'ら'..'ろ' -> "ら"
            in 'ゎ'..'ゖ' -> "わ"
            else -> if (ch.code >= 0x2E80) "漢" else "#"
        }
    }

    // ---- 種類 ----
    val CATS = listOf("すべて", "仕事", "連絡・SNS", "動画・音楽", "写真", "ゲーム", "地図・移動", "お金・買い物", "その他")
    private val K_WORK = listOf("docs", "sheets", "slides", "slack", "teams", "zoom", "office", "excel", "word", "powerpoint", "outlook", "notion",
        "calendar", "android.gm", "mail", "keep", "notes", "drive", "dropbox", "adobe", "scan", "chatwork", "anthropic", "openai", "github", "meet", "sansan", "eight")
    private val K_SOCIAL = listOf("naver.line", "messag", "dialer", "contacts", "twitter", "instagram", "facebook", "discord", "whatsapp", "telegram", "threads", "linkedin", "tiktok", "musically", "phone")
    private val K_MEDIA = listOf("youtube", "netflix", "spotify", "music", "tver", "abema", "avod", "video", "radiko", "podcast", "audible", "kindle", "dazn", "hulu", "unext", "nhk", "player")
    private val K_PHOTO = listOf("camera", "gallery", "photo", "snapseed", "lightroom", "canva", "editor")
    private val K_MOVE = listOf("maps", "navi", "tesla", "transit", "jorudan", "ekispert", "taxi", "uber", "suica", "norikae", "weather", "tenki", "ana.", "jal.", "smartex", "go.dena", "travel")
    private val K_MONEY = listOf("pay", "bank", "wallet", "amazon", "rakuten", "mercari", "shop", "card", "money", "sbi", "moneyforward", "zaim", "store", "yodobashi", "monotaro", "askul", "vending", "securities")

    /** 0=すべて用の予約。1..8 が CATS の順 */
    fun categoryOf(label: String, pkg: String, sys: Int): Int {
        val s = pkg.lowercase() + " " + label.lowercase()
        fun has(l: List<String>) = l.any { s.contains(it) }
        return when {
            sys == ApplicationInfo.CATEGORY_GAME -> 5
            has(K_WORK) -> 1
            has(K_SOCIAL) -> 2
            has(K_MEDIA) -> 3
            has(K_PHOTO) -> 4
            has(K_MOVE) -> 6
            has(K_MONEY) -> 7
            sys == ApplicationInfo.CATEGORY_PRODUCTIVITY -> 1
            sys == ApplicationInfo.CATEGORY_SOCIAL -> 2
            sys == ApplicationInfo.CATEGORY_AUDIO || sys == ApplicationInfo.CATEGORY_VIDEO -> 3
            sys == ApplicationInfo.CATEGORY_IMAGE -> 4
            sys == ApplicationInfo.CATEGORY_MAPS -> 6
            else -> 8
        }
    }
}

/** アプリ一覧の右端に出る索引。なぞるとその行へ飛ぶ */
class IndexBar(context: Context) : View(context) {
    var entries: List<String> = emptyList()
        set(v) { field = v; active = -1; invalidate() }
    var onPick: ((String) -> Unit)? = null
    private var active = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val n = entries.size; if (n == 0) return
        val rowH = height.toFloat() / n
        val fs = minOf(rowH * 0.72f, width * 0.55f)
        p.textSize = fs
        for (i in 0 until n) {
            val cy = rowH * (i + 0.5f)
            if (i == active) {
                bg.color = Color.argb(200, 120, 222, 255)
                c.drawRoundRect(width * 0.08f, cy - rowH * 0.48f, width * 0.92f, cy + rowH * 0.48f, 8f, 8f, bg)
                p.color = Color.rgb(1, 10, 20)
            } else p.color = Color.argb(210, 120, 222, 255)
            c.drawText(entries[i], width / 2f, cy + fs * 0.36f, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val n = entries.size; if (n == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val i = (e.y / height * n).toInt().coerceIn(0, n - 1)
                if (i != active) {
                    active = i; invalidate()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPick?.invoke(entries[i])
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { postDelayed({ active = -1; invalidate() }, 600) }
        }
        return true
    }
}
