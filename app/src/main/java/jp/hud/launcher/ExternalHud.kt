package jp.hud.launcher

import android.app.Presentation
import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.view.Display
import android.widget.FrameLayout

/** テレビ・モニターにつないだときに、横長のHUDを表示する */
class ExternalHud(outer: Context, display: Display) : Presentation(outer, display) {

    lateinit var hud: HudView
        private set
    private lateinit var gl: GLBackground

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context)
        gl = GLBackground(context)
        root.addView(gl, FrameLayout.LayoutParams(-1, -1))
        hud = HudView(context).also {
            it.interactive = false
            it.gl = gl
            it.idleTimeoutMs = Long.MAX_VALUE / 4   // 外部画面は待機モードにしない
            it.listener = object : HudView.Listener {
                override fun onOpenDrawer() {}
                override fun onLaunch(app: AppEntry, src: android.graphics.Rect?) {}
                override fun onMediaAction(action: String) {}
                override fun onDockLongPress(app: AppEntry) {}
                override fun onPanelTap(panel: String) {}
                override fun onClockTap() {}
                override fun onHomeSettings() {}
                override fun onTopStripTap() {}
                override fun onCoreLongPress() {}
                override fun onModuleRect(r: RectF) {}
                override fun onModuleEdit() {}
                override fun onPanelsAlpha(a: Float) {}
                override fun onUserActivity() {}
                override fun onScan() {}
                override fun onPay() {}
            }
        }
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onStart() { super.onStart(); hud.start(); gl.paused = false }
    override fun onStop() { hud.stop(); gl.paused = true; super.onStop() }
}
