package com.synclab.airlens.ui

import android.app.Activity
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.synclab.airlens.R
import com.synclab.airlens.telemetry.HudFormatter
import com.synclab.airlens.telemetry.HudRow
import com.synclab.airlens.telemetry.HudSnapshot
import com.synclab.airlens.telemetry.HudStrings
import com.synclab.airlens.telemetry.HudTab
import com.synclab.airlens.telemetry.HudTone
import java.util.Locale

/**
 * Owns the three HUD depths of the camera screen: the HUD on/off toggle, the glance card
 * (five rows + debug footer chip) and the expandable telemetry bottom sheet (tabs, rows,
 * Debug toggle, footer buttons).
 *
 * Pure view binding — it never samples anything itself. MainActivity hands it one
 * [HudSnapshot] per second via [render]; state changes made by the user (toggle, expand,
 * tab, debug) are reported back through [onStateChanged] so the activity can mirror them
 * into its [CameraUiState]. `hud_on` / `hud_debug` persist in the "airlens_camera_ui"
 * preferences (writes go through `apply()`, i.e. off the UI thread).
 *
 * Row views are recycled: children of the two row containers are reused and only added or
 * removed when the row count changes, so the 1 Hz refresh never re-inflates.
 */
class CameraHudController(
    private val activity: Activity,
    private val root: View,
    strings: HudStrings,
    private val prefs: SharedPreferences,
    private val onStateChanged: (hudOn: Boolean, expanded: Boolean, debug: Boolean, tab: HudTab) -> Unit,
) {
    private val formatter = HudFormatter(strings)
    private val inflater = LayoutInflater.from(activity)

    private val hudToggle: TextView = root.findViewById(R.id.hudToggle)
    private val hudGlance: View = root.findViewById(R.id.hudGlance)
    private val hudGlanceRows: LinearLayout = root.findViewById(R.id.hudGlanceRows)
    private val streamInfoChip: TextView = root.findViewById(R.id.streamInfoChip)
    private val hudSheetScrim: View = root.findViewById(R.id.hudSheetScrim)
    private val hudSheet: View = root.findViewById(R.id.hudSheet)
    private val hudSheetSub: TextView = root.findViewById(R.id.hudSheetSub)
    private val btnHudDebug: TextView = root.findViewById(R.id.btnHudDebug)
    private val hudSheetScroll: ScrollView = root.findViewById(R.id.hudSheetScroll)
    private val hudSheetRows: LinearLayout = root.findViewById(R.id.hudSheetRows)
    private val hudFootnote: TextView = root.findViewById(R.id.hudFootnote)
    private val btnHudHide: TextView = root.findViewById(R.id.btnHudHide)
    private val btnHudClose: TextView = root.findViewById(R.id.btnHudClose)
    private val tabViews: Map<HudTab, TextView> = mapOf(
        HudTab.Stream to root.findViewById(R.id.hudTabStream),
        HudTab.Camera to root.findViewById(R.id.hudTabCamera),
        HudTab.Network to root.findViewById(R.id.hudTabNetwork),
        HudTab.Device to root.findViewById(R.id.hudTabDevice),
    )

    private val glanceKeys: IntArray = intArrayOf(
        R.string.hud_g_bitrate, R.string.hud_g_fps, R.string.hud_g_rtt, R.string.hud_g_wifi, R.string.hud_g_battery,
    )
    private val placeholderGlance: List<HudRow> =
        glanceKeys.map { HudRow(key = strings.get(it), value = strings.get(R.string.hud_v_na)) }

    var hudOn: Boolean = prefs.getBoolean(KEY_HUD_ON, true)
        private set

    var debug: Boolean = prefs.getBoolean(KEY_HUD_DEBUG, false)
        private set

    var isExpanded: Boolean = false
        private set

    var tab: HudTab = HudTab.Stream
        private set

    private var lastSnapshot: HudSnapshot? = null
    private var lastDegraded = false
    private var lastThermalAlert = false
    private var lastLive = false
    private var closing = false

    init {
        hudToggle.setOnClickListener { setHudOn(!hudOn) }
        hudGlance.setOnClickListener { expand() }
        hudSheetScrim.setOnClickListener { collapse() }
        btnHudClose.setOnClickListener { collapse() }
        btnHudHide.setOnClickListener {
            setHudOn(false)
        }
        btnHudDebug.setOnClickListener { setDebug(!debug) }
        tabViews.forEach { (hudTab, view) -> view.setOnClickListener { selectTab(hudTab) } }

        applyToggleStyle()
        applyDebugStyle()
        applyTabStyle()
        hudGlance.visibility = if (hudOn) View.VISIBLE else View.GONE
        bindRows(hudGlanceRows, R.layout.item_hud_glance_row, placeholderGlance, ::bindGlanceRow)
    }

    // ───────────────────────────── Public API ─────────────────────────────

    /**
     * Renders the newest telemetry frame. [snapshot] may be null before the first tick;
     * the glance rows then show "—". The debug chip text is ALWAYS refreshed while [live]
     * (instrumentation contract) and only its visibility depends on hudOn && debug.
     */
    fun render(snapshot: HudSnapshot?, degraded: Boolean, thermalAlert: Boolean, live: Boolean) {
        lastSnapshot = snapshot
        lastDegraded = degraded
        lastThermalAlert = thermalAlert
        lastLive = live

        if (snapshot != null && live) {
            streamInfoChip.text = String.format(
                Locale.US,
                STREAM_INFO_CHIP_FORMAT,
                snapshot.videoFrames,
                snapshot.keyframes,
                snapshot.sessionMegabits,
            )
        }
        streamInfoChip.visibility = if (hudOn && debug) View.VISIBLE else View.GONE

        if (hudOn) {
            val rows = snapshot?.let { formatter.glance(it, degraded, thermalAlert) } ?: placeholderGlance
            bindRows(hudGlanceRows, R.layout.item_hud_glance_row, rows, ::bindGlanceRow)
        }
        if (isExpanded) renderSheetRows()
    }

    fun setHudOn(on: Boolean) {
        if (hudOn == on) return
        hudOn = on
        prefs.edit().putBoolean(KEY_HUD_ON, on).apply()
        applyToggleStyle()
        hudGlance.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            hudGlance.alpha = 0f
            hudGlance.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        if (!on && isExpanded) {
            collapse()
        } else {
            render(lastSnapshot, lastDegraded, lastThermalAlert, lastLive)
            notifyState()
        }
    }

    fun expand(tab: HudTab? = null) {
        if (tab != null && tab != this.tab) {
            this.tab = tab
            applyTabStyle()
        }
        if (isExpanded) {
            renderSheetRows()
            notifyState()
            return
        }
        isExpanded = true
        closing = false
        hudSheet.animate().cancel()
        hudSheetScrim.animate().cancel()
        renderSheetRows()

        hudSheetScroll.layoutParams = hudSheetScroll.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        hudSheetScrim.visibility = View.VISIBLE
        hudSheetScrim.alpha = 0f
        hudSheetScrim.animate().alpha(1f).setDuration(SCRIM_MS).start()
        hudSheet.visibility = View.VISIBLE
        hudSheet.alpha = 1f
        hudSheet.post {
            if (!isExpanded) return@post
            capSheetHeight()
            hudSheet.post {
                if (!isExpanded) return@post
                hudSheet.translationY = hudSheet.height.toFloat()
                hudSheet.animate().translationY(0f).setDuration(RISE_MS).start()
            }
        }
        notifyState()
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        closing = true
        hudSheetScrim.animate().alpha(0f).setDuration(SCRIM_MS)
            .withEndAction { if (closing) hudSheetScrim.visibility = View.GONE }
            .start()
        hudSheet.animate().translationY(hudSheet.height.toFloat()).setDuration(SINK_MS)
            .withEndAction {
                if (closing) {
                    hudSheet.visibility = View.GONE
                    hudSheet.translationY = 0f
                }
            }
            .start()
        notifyState()
    }

    // ───────────────────────────── Internals ─────────────────────────────

    private fun setDebug(on: Boolean) {
        if (debug == on) return
        debug = on
        prefs.edit().putBoolean(KEY_HUD_DEBUG, on).apply()
        applyDebugStyle()
        render(lastSnapshot, lastDegraded, lastThermalAlert, lastLive)
        notifyState()
    }

    private fun selectTab(hudTab: HudTab) {
        if (tab == hudTab) return
        tab = hudTab
        applyTabStyle()
        renderSheetRows()
        hudSheetScroll.scrollTo(0, 0)
        notifyState()
    }

    private fun notifyState() {
        onStateChanged(hudOn, isExpanded, debug, tab)
    }

    private fun renderSheetRows() {
        val snapshot = lastSnapshot
        val rows = snapshot?.let { formatter.rows(tab, it, lastDegraded, debug) } ?: emptyList()
        val countBefore = hudSheetRows.childCount
        bindRows(hudSheetRows, R.layout.item_hud_row, rows, ::bindSheetRow)
        hudFootnote.setText(if (debug) R.string.cam_footnote_debug else R.string.cam_footnote)
        if (isExpanded && countBefore != rows.size) {
            hudSheetScroll.layoutParams = hudSheetScroll.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            hudSheet.post { if (isExpanded) capSheetHeight() }
        }
    }

    /**
     * Keeps the whole sheet at or under 82 % of the root height by shrinking the scroll
     * region (never the header, tabs or footer). Idempotent: layout params only change
     * when the target height differs from the current one.
     */
    private fun capSheetHeight() {
        val maxHeight = (root.height * SHEET_MAX_FRACTION).toInt()
        if (maxHeight <= 0 || hudSheet.height <= 0) return
        val overflow = hudSheet.height - maxHeight
        if (overflow <= 0) return
        val target = (hudSheetScroll.height - overflow).coerceAtLeast(dp(MIN_SCROLL_DP))
        if (hudSheetScroll.layoutParams.height == target) return
        hudSheetScroll.layoutParams = hudSheetScroll.layoutParams.apply { height = target }
    }

    private fun bindRows(
        container: LinearLayout,
        layoutRes: Int,
        rows: List<HudRow>,
        bind: (View, HudRow) -> Unit,
    ) {
        while (container.childCount > rows.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < rows.size) {
            container.addView(inflater.inflate(layoutRes, container, false))
        }
        rows.forEachIndexed { index, row -> bind(container.getChildAt(index), row) }
    }

    private fun bindGlanceRow(view: View, row: HudRow) {
        val key = view.findViewById<TextView>(R.id.glanceKey)
        val value = view.findViewById<TextView>(R.id.glanceValue)
        setTextIfChanged(key, row.key)
        setTextIfChanged(value, row.value)
        value.setTextColor(activity.getColor(toneColor(row.tone)))
    }

    private fun bindSheetRow(view: View, row: HudRow) {
        val key = view.findViewById<TextView>(R.id.hudRowKey)
        val note = view.findViewById<TextView>(R.id.hudRowNote)
        val value = view.findViewById<TextView>(R.id.hudRowValue)
        setTextIfChanged(key, row.key)
        setTextIfChanged(value, row.value)
        value.setTextColor(activity.getColor(toneColor(row.tone)))
        if (row.note.isNullOrBlank()) {
            note.visibility = View.GONE
        } else {
            setTextIfChanged(note, row.note)
            note.visibility = View.VISIBLE
        }
    }

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text?.toString() != text) view.text = text
    }

    private fun applyToggleStyle() {
        if (hudOn) {
            hudToggle.setBackgroundResource(R.drawable.bg_cam_light_circle)
            hudToggle.setTextColor(activity.getColor(R.color.cam_on_light))
        } else {
            hudToggle.setBackgroundResource(R.drawable.bg_cam_ghost_circle)
            hudToggle.setTextColor(activity.getColor(R.color.cam_text_80))
        }
    }

    private fun applyDebugStyle() {
        if (debug) {
            btnHudDebug.setBackgroundResource(R.drawable.bg_cam_pill_light)
            btnHudDebug.setTextColor(activity.getColor(R.color.cam_on_light))
        } else {
            btnHudDebug.setBackgroundResource(R.drawable.bg_cam_ghost_pill)
            btnHudDebug.setTextColor(activity.getColor(R.color.cam_text_80))
        }
        hudSheetSub.setText(if (debug) R.string.cam_sheet_sub_debug else R.string.cam_sheet_sub)
        hudFootnote.setText(if (debug) R.string.cam_footnote_debug else R.string.cam_footnote)
    }

    private fun applyTabStyle() {
        tabViews.forEach { (hudTab, view) ->
            if (hudTab == tab) {
                view.setBackgroundResource(R.drawable.bg_cam_tab_active)
                view.setTextColor(activity.getColor(R.color.cam_text))
            } else {
                view.setBackgroundResource(R.drawable.bg_cam_tab)
                view.setTextColor(activity.getColor(R.color.cam_text_72))
            }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "airlens_camera_ui"
        const val KEY_HUD_ON = "hud_on"
        const val KEY_HUD_DEBUG = "hud_debug"

        /** Instrumentation contract: `"%d f · %d kf · %.1f Mb"` (Locale.US). */
        const val STREAM_INFO_CHIP_FORMAT = "%d f · %d kf · %.1f Mb"

        private const val SHEET_MAX_FRACTION = 0.82f
        private const val MIN_SCROLL_DP = 120
        private const val RISE_MS = 260L
        private const val SINK_MS = 220L
        private const val SCRIM_MS = 200L
        private const val FADE_MS = 200L

        fun toneColor(tone: HudTone): Int = when (tone) {
            HudTone.Neutral -> R.color.cam_white_92
            HudTone.Ok -> R.color.cam_ok
            HudTone.Warn -> R.color.cam_warn
            HudTone.Err -> R.color.cam_err
        }
    }
}
