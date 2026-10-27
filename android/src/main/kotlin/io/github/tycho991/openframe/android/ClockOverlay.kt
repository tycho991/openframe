package io.github.tycho991.openframe.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tycho991.openframe.core.settings.ClockFormat
import io.github.tycho991.openframe.core.settings.ClockSettings
import io.github.tycho991.openframe.core.settings.Corner
import java.util.Date
import java.util.Locale

/**
 * Clock with date over the photo. Legible on bright and dark photos alike thanks to
 * a shadow on the text. Updates on every minute change.
 */
class ClockOverlay(
    private val context: Context,
    private val container: LinearLayout,
    private val time: TextView,
    private val date: TextView,
) {
    private var settings = ClockSettings()
    private var locale: Locale = Locale.getDefault()
    private var registered = false

    /** True while the hint is shown: it covers the same screen edge, so the clock steps aside. */
    var hidden = false
        set(value) {
            field = value
            refreshVisibility()
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = update()
        }

    fun apply(
        clock: ClockSettings,
        locale: Locale,
    ) {
        settings = clock
        this.locale = locale
        refreshVisibility()
        val scale = clock.sizePercent / 100f
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TIME_SP * scale)
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_DATE_SP * scale)
        val (vertical, horizontal) =
            when (clock.position) {
                Corner.TOP_LEFT -> Gravity.TOP to Gravity.LEFT
                Corner.TOP_RIGHT -> Gravity.TOP to Gravity.RIGHT
                Corner.BOTTOM_LEFT -> Gravity.BOTTOM to Gravity.LEFT
                Corner.BOTTOM_RIGHT -> Gravity.BOTTOM to Gravity.RIGHT
            }
        (container.layoutParams as FrameLayout.LayoutParams).gravity = vertical or horizontal
        container.gravity = horizontal
        container.requestLayout()
        update()
    }

    fun start() {
        if (registered) return
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        context.registerReceiver(receiver, filter)
        registered = true
        update()
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun refreshVisibility() {
        container.visibility = if (settings.enabled && !hidden) View.VISIBLE else View.GONE
    }

    private fun update() {
        if (!settings.enabled) return
        val now = Date()
        val timePattern = if (settings.format == ClockFormat.H24) "HH:mm" else DateFormat.getBestDateTimePattern(locale, "hmm")
        time.text = java.text.SimpleDateFormat(timePattern, locale).format(now)
        date.text = java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now)
    }

    private companion object {
        const val BASE_TIME_SP = 64f
        const val BASE_DATE_SP = 22f
    }
}
