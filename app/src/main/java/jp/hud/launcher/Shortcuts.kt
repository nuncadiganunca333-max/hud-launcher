package jp.hud.launcher

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Home-screen shortcuts that other apps ask us to pin (Chrome's "ホーム画面に追加", etc.).
 * They show up in the app list and can be put on the ring like any app.
 * A shortcut is kept as an AppEntry whose cls is "#sc:" + shortcut id.
 */
object Shortcuts {
    const val PREFIX = "#sc:"
    private const val KEY = "pins"

    fun isShortcut(app: AppEntry) = app.cls.startsWith(PREFIX)
    fun idOf(app: AppEntry) = app.cls.removePrefix(PREFIX)

    private fun la(ctx: Context) = ctx.getSystemService(LauncherApps::class.java)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hud", Context.MODE_PRIVATE)

    /** saved list: [{pkg,id,label}] */
    private fun saved(ctx: Context): MutableList<JSONObject> = try {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]")); MutableList(a.length()) { a.getJSONObject(it) }
    } catch (e: Exception) { mutableListOf() }

    private fun save(ctx: Context, list: List<JSONObject>) =
        prefs(ctx).edit().putString(KEY, JSONArray(list).toString()).apply()

    fun add(ctx: Context, info: ShortcutInfo) {
        val list = saved(ctx).filterNot { it.optString("pkg") == info.`package` && it.optString("id") == info.id }.toMutableList()
        list += JSONObject().put("pkg", info.`package`).put("id", info.id)
            .put("label", (info.shortLabel ?: info.longLabel ?: "ショートカット").toString())
        save(ctx, list)
    }

    /** live pinned shortcuts from the system (needs us to be the default home app), by "pkg/id" */
    private fun live(ctx: Context): Map<String, ShortcutInfo> = try {
        val l = la(ctx)
        if (!l.hasShortcutHostPermission()) emptyMap() else {
            val q = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            (l.getShortcuts(q, Process.myUserHandle()) ?: emptyList()).associateBy { it.`package` + "/" + it.id }
        }
    } catch (e: Exception) { emptyMap() }

    private var cache: Map<String, ShortcutInfo> = emptyMap()

    fun entries(ctx: Context): List<AppEntry> {
        cache = live(ctx)
        val list = saved(ctx)
        // drop the ones the system says are gone (app uninstalled, or the shortcut was removed by its app)
        val keep = if (cache.isEmpty()) list else list.filter { cache.containsKey(it.optString("pkg") + "/" + it.optString("id")) }
        if (keep.size != list.size) save(ctx, keep)
        return keep.map { o ->
            val info = cache[o.optString("pkg") + "/" + o.optString("id")]
            AppEntry((info?.shortLabel ?: o.optString("label")).toString(), o.optString("pkg"), PREFIX + o.optString("id"))
        }
    }

    fun icon(ctx: Context, app: AppEntry): Drawable? = try {
        cache[app.pkg + "/" + idOf(app)]?.let { la(ctx).getShortcutIconDrawable(it, ctx.resources.displayMetrics.densityDpi) }
            ?: ctx.packageManager.getApplicationIcon(app.pkg)
    } catch (e: Exception) { null }

    fun launch(ctx: Context, app: AppEntry, src: android.graphics.Rect?, opts: Bundle?): Boolean = try {
        la(ctx).startShortcut(app.pkg, idOf(app), src, opts, Process.myUserHandle()); true
    } catch (e: Exception) { false }

    fun remove(ctx: Context, app: AppEntry) {
        val list = saved(ctx).filterNot { it.optString("pkg") == app.pkg && it.optString("id") == idOf(app) }
        save(ctx, list)
        try {
            // tell the system we no longer keep it pinned (keep the other ones from the same app)
            val ids = list.filter { it.optString("pkg") == app.pkg }.map { it.optString("id") }
            la(ctx).pinShortcuts(app.pkg, ids, Process.myUserHandle())
        } catch (e: Exception) { }
    }
}

/** Receives "pin this shortcut" requests (Android 8+). Accepts right away and goes back. */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val req = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
            if (req != null && req.isValid && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
                val info = req.shortcutInfo
                if (info != null && req.accept()) {
                    Shortcuts.add(this, info)
                    Toast.makeText(this, "ホームに追加しました：${info.shortLabel ?: ""}（アプリ一覧にあります）", Toast.LENGTH_LONG).show()
                }
            } else if (req != null && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET) {
                Toast.makeText(this, "ウィジェットはMODULEの長押しから置いてください", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "追加できませんでした", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
