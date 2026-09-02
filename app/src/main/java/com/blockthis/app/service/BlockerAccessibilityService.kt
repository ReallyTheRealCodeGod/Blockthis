package com.blockthis.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import com.blockthis.app.R
import com.blockthis.app.data.Store
import com.blockthis.app.data.UsageStore
import com.blockthis.app.engine.BlockDecision
import com.blockthis.app.engine.BlockEngine
import com.blockthis.app.model.BlockMode

/**
 * The enforcement core. Watches window changes; when a blocked app (or, during
 * a strict block, a screen that could disable the blocker) comes to the
 * foreground it covers the screen with an overlay and bounces the user home.
 *
 * A 5 second tick also re-evaluates state so blocks that begin while an app is
 * already open are enforced, and meters usage for daily-limit profiles.
 */
class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TICK_MS = 5000L
        private const val MIN_OVERLAY_MS = 3500L
        private const val HOME_DELAY_MS = 600L

        /** Whether the service is connected (shown as status in the main UI). */
        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayShownAt = 0L
    private var homePending = false

    private var foregroundPackage = ""
    private var foregroundClass = ""
    private var foregroundTitles: List<String> = emptyList()

    private var imePackages: Set<String> = emptySet()
    private var lastImeRefresh = 0L
    private var lastPrune = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        BlockEngine.warm(this)
        refreshImePackages()
        UsageStore.prune(this)
        lastPrune = System.currentTimeMillis()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Nothing to do.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Keyboards and system UI (shade, recents) pop windows over the real
        // foreground app; they must not be mistaken for an app switch.
        if (pkg == "com.android.systemui" || pkg in imePackages) return
        foregroundPackage = pkg
        foregroundClass = event.className?.toString() ?: ""
        foregroundTitles = event.text.mapNotNull { it?.toString() }
        evaluate()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastImeRefresh > 60 * 60 * 1000L) refreshImePackages()
        if (now - lastPrune > 60 * 60 * 1000L) {
            UsageStore.prune(this)
            lastPrune = now
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive && overlayView == null && foregroundPackage.isNotEmpty()) {
            for (p in Store.profiles(this)) {
                if (p.mode == BlockMode.LIMIT && p.enabled && foregroundPackage in p.packages) {
                    UsageStore.addSeconds(this, p.id, TICK_MS / 1000L)
                }
            }
        }
        evaluate()
    }

    private fun refreshImePackages() {
        lastImeRefresh = System.currentTimeMillis()
        imePackages = try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun evaluate() {
        val decision = BlockEngine.evaluate(this, foregroundPackage, foregroundClass, foregroundTitles)
        if (decision != null) showOverlay(decision) else maybeHideOverlay()
    }

    private fun showOverlay(decision: BlockDecision) {
        val existing = overlayView
        if (existing != null) {
            bindOverlay(existing, decision)
            return
        }
        val themed = ContextThemeWrapper(this, R.style.Theme_Blockthis)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_block, null)
        bindOverlay(view, decision)
        view.findViewById<View>(R.id.overlay_leave).setOnClickListener {
            removeOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager().addView(view, lp)
            overlayView = view
            overlayShownAt = System.currentTimeMillis()
            if (!homePending) {
                homePending = true
                handler.postDelayed({
                    homePending = false
                    if (overlayView != null) performGlobalAction(GLOBAL_ACTION_HOME)
                }, HOME_DELAY_MS)
            }
            handler.postDelayed({ maybeHideOverlay() }, MIN_OVERLAY_MS + 100)
        } catch (_: Throwable) {
            // If the overlay cannot be shown, at least leave the blocked app.
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun bindOverlay(view: View, decision: BlockDecision) {
        view.findViewById<TextView>(R.id.overlay_title).text = decision.title
        view.findViewById<TextView>(R.id.overlay_subtitle).text = decision.subtitle
        view.findViewById<TextView>(R.id.overlay_until).text = decision.untilText
    }

    /** Hide the overlay once the foreground is safe and it has been readable. */
    private fun maybeHideOverlay() {
        val view = overlayView ?: return
        val decision = BlockEngine.evaluate(this, foregroundPackage, foregroundClass, foregroundTitles)
        if (decision != null) {
            bindOverlay(view, decision)
            return
        }
        val elapsed = System.currentTimeMillis() - overlayShownAt
        if (elapsed >= MIN_OVERLAY_MS) {
            removeOverlay()
        } else {
            handler.postDelayed({ maybeHideOverlay() }, MIN_OVERLAY_MS - elapsed + 50)
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager().removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun windowManager(): WindowManager =
        getSystemService(WINDOW_SERVICE) as WindowManager
}
