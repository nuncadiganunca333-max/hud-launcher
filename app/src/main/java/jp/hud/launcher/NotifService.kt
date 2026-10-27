package jp.hud.launcher

import android.app.Notification
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class NotifItem(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
    val time: Long,
    val reply: Notification.Action? = null,
)

class NotifService : NotificationListenerService() {

    companion object {
        var instance: NotifService? = null
        /** ホーム画面が前面にあるときだけセットされる受け口 */
        var sink: ((NotifItem) -> Unit)? = null
        /** ホーム以外にいる間に届いた通知（最新5件まで） */
        val pending = ArrayDeque<NotifItem>()

        private val IGNORE = setOf(
            "android", "com.android.systemui", "com.android.providers.downloads",
            "com.samsung.android.app.smartcapture", "com.sec.android.app.camera",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val seen = HashMap<String, Long>()

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.packageName in IGNORE) return
        val n = sbn.notification
        if (sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_SERVICE || n.category == Notification.CATEGORY_PROGRESS ||
            n.category == Notification.CATEGORY_TRANSPORT) return
        val ex = n.extras
        if (ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return

        val now = System.currentTimeMillis()
        val prev = seen[sbn.key]
        seen[sbn.key] = now
        if (seen.size > 200) seen.entries.removeIf { now - it.value > 10 * 60 * 1000 }
        // 同じ通知の更新（音を鳴らさない更新）は無視
        if (prev != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return
        if (prev != null && now - prev < 3000) return

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }

        val replyAction = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val item = NotifItem(sbn.key, sbn.packageName, label, title, text, n.contentIntent,
            n.flags and Notification.FLAG_AUTO_CANCEL != 0, now, replyAction)

        main.post {
            val s = sink
            if (s != null) s(item) else {
                pending.removeAll { it.key == item.key }
                pending.addLast(item)
                while (pending.size > 5) pending.removeFirst()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        main.post { pending.removeAll { it.key == sbn.key } }
    }
}
