package io.github.tycho991.openframe.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import io.github.tycho991.openframe.R
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.settings.FrameLanguage
import io.github.tycho991.openframe.core.settings.Orientation
import io.github.tycho991.openframe.core.settings.Settings
import io.github.tycho991.openframe.core.slideshow.SlideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * The display on the frame. Gets the core through the binder of the
 * [FrameService] and shows its state, without HTTP. Starts only once
 * the core has loaded settings and library, independent of server and network.
 */
class DisplayActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var core: FrameCore? = null
    private var bound = false

    private lateinit var renderer: SlideRenderer
    private lateinit var clock: ClockOverlay
    private lateinit var loading: TextView
    private lateinit var emptyState: View
    private lateinit var emptyBody: TextView
    private lateinit var emptyCode: TextView
    private lateinit var emptyCodeLabel: TextView
    private lateinit var hint: View
    private lateinit var hintName: TextView
    private lateinit var hintAddress: TextView
    private lateinit var hintCode: TextView
    private lateinit var hintCodeBox: View
    private lateinit var hintPause: Button
    private lateinit var gestures: GestureDetector

    private var settings: Settings? = null
    private var localized: Resources? = null
    private var hintVisible = false
    private var showingEmpty = false
    private var menuOpen = false

    private val hideHint = Runnable { setHintVisible(false) }
    private val reimmerse = Runnable { enterImmersive() }
    private val longPress = Runnable { openDeviceMenu() }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val c = (binder as FrameService.LocalBinder).core
                core = c
                attach(c)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                core = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_display)
        renderer = SlideRenderer(findViewById(R.id.photo_a), findViewById(R.id.photo_b)) { core?.slideshow?.reportUndecodable(it) }
        clock = ClockOverlay(this, findViewById(R.id.clock), findViewById(R.id.clock_time), findViewById(R.id.clock_date))
        loading = findViewById(R.id.loading)
        emptyState = findViewById(R.id.empty_state)
        emptyBody = findViewById(R.id.empty_body)
        emptyCode = findViewById(R.id.empty_code)
        emptyCodeLabel = findViewById(R.id.empty_code_label)
        hint = findViewById(R.id.hint)
        hintName = findViewById(R.id.hint_name)
        hintAddress = findViewById(R.id.hint_address)
        hintCode = findViewById(R.id.hint_code)
        hintCodeBox = findViewById(R.id.hint_code_box)
        hintPause = findViewById(R.id.hint_pause)
        hintPause.setOnClickListener { core?.slideshow?.togglePause() }
        // Overlays are always above the photos, even if the renderer raises the image views.
        for (id in intArrayOf(R.id.loading, R.id.empty_state, R.id.clock, R.id.hint)) findViewById<View>(id).translationZ = OVERLAY_Z
        gestures = GestureDetector(this, GestureListener())

        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // System bars shown: hide them again after a short time.
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                main.removeCallbacks(reimmerse)
                main.postDelayed(reimmerse, REIMMERSE_MS)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { setHintVisible(false) }
        }
        FrameService.start(this)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, FrameService::class.java), connection, Context.BIND_AUTO_CREATE)
        clock.start()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onStop() {
        clock.stop()
        scope?.cancel()
        scope = null
        core?.pairing?.setShownOnFrame(false)
        if (bound) unbindService(connection)
        bound = false
        renderer.clear()
        super.onStop()
    }

    override fun onDestroy() {
        renderer.release()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * As the HOME launcher the Back button leads nowhere, it only closes the hint.
     * From API 33 through the OnBackInvokedCallback in onCreate; below that there is only this way without AndroidX.
     */
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            setHintVisible(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // A 3-second press in a corner opens the device menu.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                if (inCorner(event.x, event.y)) main.postDelayed(longPress, CORNER_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) main.removeCallbacks(longPress)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
            }
        }
        if (!menuOpen) gestures.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private var downX = 0f
    private var downY = 0f

    private fun inCorner(
        x: Float,
        y: Float,
    ): Boolean {
        val w = window.decorView.width
        val h = window.decorView.height
        val cx = w * CORNER_FRACTION
        val cy = h * CORNER_FRACTION
        return (x < cx || x > w - cx) && (y < cy || y > h - cy)
    }

    private fun attach(core: FrameCore) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope?.cancel()
        scope = s
        s.launch {
            // Wait only for the core, not for server or network.
            core.ready.filter { it }.first()
            launch { core.settings.settings.collect(::applySettings) }
            launch { core.slideshow.state.collect(::render) }
            launch { core.slideshow.nextUp.collect { renderer.preload(it) } }
            launch { core.slideshow.paused.collect { updateHint() } }
            launch {
                core.settings.localOnly.collect {
                    updateHint()
                    updateEmpty()
                }
            }
            launch {
                combine(core.pairing.display, core.clients.clients) { display, clients -> display to clients.isEmpty() }
                    .collect { (display, noClients) ->
                        // On request from the web UI the hint with the code stays up.
                        if (display.requestedUntil != null && !hintVisible) setHintVisible(true, autoHide = false)
                        if (display.requestedUntil == null && hintVisible && !hintAutoHides) setHintVisible(false)
                        core.pairing.setShownOnFrame(hintVisible || (showingEmpty && noClients))
                        updateHint()
                        updateEmpty()
                    }
            }
        }
    }

    private fun applySettings(s: Settings) {
        val previous = settings
        settings = s
        if (previous?.language != s.language || localized == null) {
            localized = localizedResources(s.language)
            hintPause.text = text(R.string.hint_pause)
            loading.text = text(R.string.loading)
            findViewById<TextView>(R.id.empty_title).text = text(R.string.empty_title)
            emptyCodeLabel.text = text(R.string.pairing_code)
            findViewById<TextView>(R.id.hint_code_label).text = text(R.string.pairing_code)
        }
        requestedOrientation =
            if (s.orientation == Orientation.LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        renderer.fillMode = s.fillMode
        clock.apply(s.clock, locale(s.language))
        updateHint()
        updateEmpty()
    }

    private fun render(state: SlideState) {
        val s = settings ?: return
        showingEmpty = state == SlideState.Empty
        loading.visibility = if (state == SlideState.Loading) View.VISIBLE else View.GONE
        emptyState.visibility = if (showingEmpty) View.VISIBLE else View.GONE
        when (state) {
            is SlideState.Showing -> renderer.show(state.photo, state.file, state.step, s.transition, s.transitionDurationMs.toLong())
            SlideState.Empty -> renderer.clear()
            SlideState.Loading -> Unit
        }
        core?.let { c ->
            c.pairing.setShownOnFrame(
                hintVisible || (
                    showingEmpty &&
                        c.clients.clients.value
                            .isEmpty()
                ),
            )
        }
        updateEmpty()
    }

    private var hintAutoHides = true

    private fun setHintVisible(
        visible: Boolean,
        autoHide: Boolean = true,
    ) {
        hintVisible = visible
        hintAutoHides = autoHide
        hint.visibility = if (visible) View.VISIBLE else View.GONE
        main.removeCallbacks(hideHint)
        if (visible && autoHide) main.postDelayed(hideHint, HINT_MS)
        core?.let { c ->
            c.pairing.setShownOnFrame(
                visible || (
                    showingEmpty &&
                        c.clients.clients.value
                            .isEmpty()
                ),
            )
        }
        updateHint()
    }

    private fun updateHint() {
        val c = core ?: return
        val s = settings ?: return
        hintName.text = s.name
        hintAddress.text =
            when {
                c.settings.localOnly.value -> text(R.string.hint_local_only)
                else -> address(c)?.let { "${text(R.string.hint_address)}: $it" } ?: text(R.string.hint_no_network)
            }
        val code = c.pairing.display.value.code
        hintCodeBox.visibility = if (code != null && !c.settings.localOnly.value) View.VISIBLE else View.GONE
        hintCode.text = code?.chunked(3)?.joinToString(" ") ?: ""
        hintPause.text = if (c.slideshow.paused.value) text(R.string.hint_resume) else text(R.string.hint_pause)
    }

    private fun updateEmpty() {
        val c = core ?: return
        val folder = "Android/data/$packageName/files/photos"
        val address = address(c)
        emptyBody.text =
            if (address != null && !c.settings.localOnly.value) {
                text(R.string.empty_body, address, folder)
            } else {
                text(R.string.empty_no_network, folder)
            }
        val code = c.pairing.display.value.code
        val showCode =
            code != null &&
                c.clients.clients.value
                    .isEmpty() && !c.settings.localOnly.value
        emptyCode.text = code?.chunked(3)?.joinToString(" ") ?: ""
        emptyCode.visibility = if (showCode) View.VISIBLE else View.GONE
        emptyCodeLabel.visibility = emptyCode.visibility
    }

    private fun address(core: FrameCore): String? {
        val ip = core.platform.ipAddresses().firstOrNull() ?: return null
        val port = core.server.boundPort ?: FrameCore.DEFAULT_PORT
        return if (port == 80) "http://$ip/" else "http://$ip:$port/"
    }

    private fun openDeviceMenu() {
        val c = core ?: return
        if (menuOpen) return
        menuOpen = true
        val localOnly = c.settings.localOnly.value
        val items =
            arrayOf(
                text(if (localOnly) R.string.menu_local_only_on else R.string.menu_local_only_off),
                text(R.string.menu_reset),
                text(R.string.menu_info),
            )
        AlertDialog
            .Builder(this, R.style.Theme_OpenFrame_Dialog)
            .setTitle(text(R.string.menu_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> c.setLocalOnly(!localOnly).also { updateHint() }
                    1 -> confirmReset(c)
                    2 -> showInfo(c)
                }
            }.setNegativeButton(text(R.string.menu_close), null)
            .setOnDismissListener {
                menuOpen = false
                enterImmersive()
            }.show()
    }

    private fun confirmReset(c: FrameCore) {
        AlertDialog
            .Builder(this, R.style.Theme_OpenFrame_Dialog)
            .setMessage(text(R.string.confirm_reset))
            .setPositiveButton(text(R.string.confirm_reset_yes)) { _, _ ->
                c.resetAllPairings()
                setHintVisible(true, autoHide = false)
            }.setNegativeButton(text(R.string.cancel), null)
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun showInfo(c: FrameCore) {
        val message =
            text(
                R.string.info_text,
                c.platform.appVersion,
                c.platform.model,
                c.platform.androidVersion,
                address(c) ?: text(R.string.hint_no_network),
                c.identity.id,
            )
        AlertDialog
            .Builder(this, R.style.Theme_OpenFrame_Dialog)
            .setTitle(text(R.string.menu_info))
            .setMessage(message)
            .setPositiveButton(text(R.string.menu_close), null)
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    /** Language on the frame: `auto` follows the system, otherwise the chosen one applies, without restart. */
    private fun locale(language: FrameLanguage): Locale =
        when (language) {
            FrameLanguage.AUTO -> {
                Resources.getSystem().configuration.let {
                    @Suppress("DEPRECATION")
                    it.locale
                }
            }

            FrameLanguage.DE -> {
                Locale.GERMAN
            }

            FrameLanguage.EN -> {
                Locale.ENGLISH
            }
        }

    private fun localizedResources(language: FrameLanguage): Resources {
        val config = Configuration(resources.configuration)
        config.setLocale(locale(language))
        return createConfigurationContext(config).resources
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = (localized ?: resources).getString(id, *args)

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            setHintVisible(!hintVisible)
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (abs(velocityX) < abs(velocityY)) return false
            val slideshow = core?.slideshow ?: return false
            if (velocityX < 0) slideshow.next() else slideshow.previous()
            return true
        }
    }

    private companion object {
        const val OVERLAY_Z = 10f
        const val HINT_MS = 8_000L
        const val REIMMERSE_MS = 3_000L
        const val CORNER_PRESS_MS = 3_000L
        const val CORNER_FRACTION = 0.15f
    }
}
