package jp.hud.launcher

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * tier: 1=急ぎ 2=今日中 3=今週中 4=来週やること 0=自分で追加
 */
data class TaskItem(val id: String, val text: String, val tier: Int, val meta: String, val done: Boolean)

/** TASKSパネルの中身：Claudeの定期タスクから届く分（暗号化）＋自分で足した分 */
object TaskStore {
    private const val BASE = "https://raw.githubusercontent.com/nuncadiganunca333-max/hud-launcher/data/"
    private val FILES = listOf("morning" to "tasks-morning.enc", "weekly" to "tasks-weekly.enc")

    class SyncError(msg: String) : Exception(msg)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hud", Context.MODE_PRIVATE)

    fun key(ctx: Context) = prefs(ctx).getString("tasks.key", "") ?: ""
    fun setKey(ctx: Context, k: String) = prefs(ctx).edit().putString("tasks.key", k.trim()).apply()
    fun lastSync(ctx: Context) = prefs(ctx).getLong("tasks.syncAt", 0L)

    private fun doneSet(ctx: Context): MutableSet<String> = HashSet(prefs(ctx).getStringSet("tasks.done", emptySet()) ?: emptySet())

    private fun idOf(text: String) = "s" + Integer.toHexString(text.hashCode())

    private fun payload(ctx: Context, kind: String): JSONObject? =
        try { prefs(ctx).getString("tasks.$kind", null)?.let { JSONObject(it) } } catch (e: Exception) { null }

    private fun localArr(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString("tasks.local", "[]")) } catch (e: Exception) { JSONArray() }

    /** 朝のまとめのひとこと */
    fun headline(ctx: Context): String = payload(ctx, "morning")?.optString("headline", "") ?: ""

    /** 「11/10 06:52」のような更新時刻（朝のまとめ） */
    fun stamp(ctx: Context): String {
        val u = payload(ctx, "morning")?.optString("updated", "") ?: ""
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(u) ?: return ""
        val g = m.groupValues
        return "${g[2].toInt()}/${g[3].toInt()} ${g[4]}:${g[5]}"
    }

    fun all(ctx: Context): List<TaskItem> {
        val done = doneSet(ctx)
        val out = ArrayList<TaskItem>()
        val local = localArr(ctx)
        for (i in 0 until local.length()) {
            val o = local.optJSONObject(i) ?: continue
            out += TaskItem(o.optString("id"), o.optString("t"), 0, "", o.optBoolean("done"))
        }
        for ((kind, _) in FILES) {
            val arr = payload(ctx, kind)?.optJSONArray("tasks") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("t").trim()
                if (text.isEmpty()) continue
                val id = idOf("$kind:$text")
                if (out.any { it.id == id }) continue
                val tier = if (kind == "weekly") 4 else o.optInt("p", 2).coerceIn(1, 3)
                val meta = listOf(o.optString("from"), o.optString("due")).filter { it.isNotBlank() }.joinToString(" ・ ")
                out += TaskItem(id, text, tier, meta, id in done)
            }
        }
        // 未完了を上に。その中では 急ぎ → 自分で追加 → 今日中 → 今週中 → 来週
        val rank = mapOf(1 to 0, 0 to 1, 2 to 2, 3 to 3, 4 to 4)
        return out.sortedWith(compareBy({ it.done }, { rank[it.tier] ?: 9 }))
    }

    fun openCount(ctx: Context) = all(ctx).count { !it.done }

    fun addLocal(ctx: Context, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val arr = localArr(ctx)
        arr.put(JSONObject().put("id", "l" + System.currentTimeMillis()).put("t", t).put("done", false))
        prefs(ctx).edit().putString("tasks.local", arr.toString()).apply()
    }

    fun toggle(ctx: Context, id: String) {
        if (id.startsWith("l")) {
            val arr = localArr(ctx)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("id") == id) it.put("done", !it.optBoolean("done")) }
            prefs(ctx).edit().putString("tasks.local", arr.toString()).apply()
        } else {
            val d = doneSet(ctx)
            if (!d.remove(id)) d.add(id)
            prefs(ctx).edit().putStringSet("tasks.done", d).apply()
        }
    }

    /** キーワードに合うタスクを完了にする。完了にしたタスクの文面を返す */
    fun completeMatching(ctx: Context, keyword: String): String? {
        val k = keyword.trim().lowercase(); if (k.isEmpty()) return null
        val hit = all(ctx).firstOrNull { !it.done && it.text.lowercase().contains(k) } ?: return null
        toggle(ctx, hit.id)
        return hit.text
    }

    /** 完了にした「自分で追加」のタスクを消す。届いた分の完了印は、次のまとめで文面が変われば自然に消える */
    fun clearDone(ctx: Context): Int {
        val arr = localArr(ctx); val keep = JSONArray(); var n = 0
        for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; if (o.optBoolean("done")) n++ else keep.put(o) }
        prefs(ctx).edit().putString("tasks.local", keep.toString()).apply()
        return n
    }

    fun decrypt(keyText: String, body: String): String {
        val key = try { Base64.decode(keyText.trim(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
            catch (e: Exception) { throw SyncError("合言葉の形式が正しくありません") }
        if (key.size != 32) throw SyncError("合言葉の長さが正しくありません")
        val raw = try { Base64.decode(body.trim(), Base64.DEFAULT) } catch (e: Exception) { throw SyncError("届いたデータが壊れています") }
        if (raw.size < 12 + 16) throw SyncError("届いたデータが短すぎます")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) { throw SyncError("合言葉が合いません") }
    }

    /**
     * まとめを取りに行く（通信するので、メイン以外のスレッドから呼ぶ）。
     * 新しい内容が届いたら true。
     */
    fun sync(ctx: Context): Boolean {
        val k = key(ctx)
        if (k.isEmpty()) throw SyncError("合言葉が未設定です")
        var changed = false; var got = 0
        for ((kind, file) in FILES) {
            val con = try { URL(BASE + file + "?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection }
                catch (e: Exception) { throw SyncError("通信できませんでした") }
            con.connectTimeout = 12000; con.readTimeout = 15000
            val code = try { con.responseCode } catch (e: Exception) { throw SyncError("通信できませんでした") }
            if (code == 404) continue
            if (code !in 200..299) throw SyncError("取得に失敗しました（$code）")
            val body = con.inputStream.bufferedReader().readText()
            val json = decrypt(k, body)
            try { JSONObject(json) } catch (e: Exception) { throw SyncError("届いたデータを読めませんでした") }
            got++
            if (prefs(ctx).getString("tasks.$kind", null) != json) { prefs(ctx).edit().putString("tasks.$kind", json).apply(); changed = true }
        }
        if (got == 0) throw SyncError("まだ届いていません")
        prefs(ctx).edit().putLong("tasks.syncAt", System.currentTimeMillis()).apply()
        return changed
    }

    fun syncAge(ctx: Context): String {
        val at = lastSync(ctx); if (at == 0L) return ""
        return SimpleDateFormat("M/d H:mm", Locale.JAPAN).format(Date(at))
    }
}
