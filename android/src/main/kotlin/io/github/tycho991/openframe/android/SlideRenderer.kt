package io.github.tycho991.openframe.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Transition
import java.io.File
import java.util.concurrent.Executors

/**
 * Zeigt Fotos mit zwei übereinanderliegenden `ImageView`s. Decodiert wird auf
 * einem eigenen Thread, nur die vorab gerenderte Anzeigegröße, ohne weitere
 * Skalierung. Es gibt höchstens zwei Bitmaps: das aktuelle und das nächste.
 * Übergänge laufen über `ViewPropertyAnimator` mit Hardware-Layern.
 */
class SlideRenderer(
    private var front: ImageView,
    private var back: ImageView,
    /** Ein Foto ließ sich nicht decodieren. */
    private val onUndecodable: (PhotoEntry) -> Unit,
) {
    private class Decoded(
        val photo: PhotoEntry,
        val file: File,
        val bitmap: Bitmap,
    )

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "decode").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private var current: Decoded? = null
    private var preloaded: Decoded? = null
    private var preloading: Pair<String, File>? = null
    private var generation = 0L
    private var animating = false
    private var pendingOld: Decoded? = null

    var fillMode: FillMode = FillMode.FILL
        set(value) {
            field = value
            front.scaleType = scaleType()
            back.scaleType = scaleType()
        }

    /** Zeigt [photo] mit dem Übergang [transition]. [step] +1 kommt von rechts, -1 von links. */
    fun show(
        photo: PhotoEntry,
        file: File,
        step: Int,
        transition: Transition,
        durationMs: Long,
    ) {
        val gen = ++generation
        val ready = preloaded?.takeIf { it.photo.id == photo.id && it.photo.version == photo.version && it.file == file }
        if (ready != null) {
            preloaded = null
            swapTo(ready, step, transition, durationMs)
            return
        }
        // Nicht vorab decodiert, etwa nach einem Wischen zurück: jetzt decodieren.
        preloaded?.bitmap?.recycle()
        preloaded = null
        decoder.execute {
            val bitmap = decode(file)
            main.post {
                if (gen != generation) {
                    bitmap?.recycle()
                } else if (bitmap == null) {
                    onUndecodable(photo)
                } else {
                    swapTo(Decoded(photo, file, bitmap), step, transition, durationMs)
                }
            }
        }
    }

    /** Decodiert das nächste Foto vorab, damit der Übergang ohne Wartezeit beginnt. */
    fun preload(next: Pair<PhotoEntry, File>?) {
        if (next == null) return
        val (photo, file) = next
        if (preloaded?.photo?.id == photo.id && preloaded?.file == file) return
        if (preloading == photo.id to file) return
        // Höchstens zwei Bitmaps: während eines Übergangs sind beide belegt.
        if (animating) {
            main.postDelayed({ preload(next) }, 200)
            return
        }
        preloaded?.bitmap?.recycle()
        preloaded = null
        preloading = photo.id to file
        decoder.execute {
            val bitmap = decode(file)
            main.post {
                preloading = null
                if (bitmap == null) return@post
                if (current?.photo?.id == photo.id) {
                    bitmap.recycle()
                } else {
                    preloaded?.bitmap?.recycle()
                    preloaded = Decoded(photo, file, bitmap)
                }
            }
        }
    }

    /** Zeigt nichts mehr, etwa im Leerzustand. */
    fun clear() {
        generation++
        front.animate().cancel()
        back.animate().cancel()
        front.setImageDrawable(null)
        back.setImageDrawable(null)
        current?.bitmap?.recycle()
        preloaded?.bitmap?.recycle()
        current = null
        preloaded = null
    }

    fun release() {
        clear()
        decoder.shutdownNow()
    }

    private fun swapTo(
        next: Decoded,
        step: Int,
        transition: Transition,
        durationMs: Long,
    ) {
        // Ein laufender Übergang wird abgeschlossen, bevor der nächste beginnt. cancel() löst withEndAction nicht aus.
        if (animating) {
            front.animate().cancel()
            back.animate().cancel()
            done(pendingOld)
        }
        back.scaleType = scaleType()
        back.setImageBitmap(next.bitmap)
        back.translationX = 0f
        back.alpha = 1f
        val old = current
        current = next
        val width = front.width.toFloat().coerceAtLeast(1f)
        val instant = transition == Transition.NONE || durationMs <= 0 || old == null
        if (instant) {
            raiseBack()
            exchangeViews()
            finishSwap(old)
            return
        }
        animating = true
        pendingOld = old
        when (transition) {
            Transition.FADE -> {
                back.alpha = 0f
                raiseBack()
                back
                    .animate()
                    .alpha(1f)
                    .setDuration(durationMs)
                    .withLayer()
                    .withEndAction { done(old) }
                    .start()
            }

            Transition.SLIDE -> {
                val direction = if (step < 0) -1f else 1f
                back.translationX = width * direction
                raiseBack()
                back
                    .animate()
                    .translationX(0f)
                    .setDuration(durationMs)
                    .withLayer()
                    .withEndAction { done(old) }
                    .start()
                front
                    .animate()
                    .translationX(-width * direction)
                    .setDuration(durationMs)
                    .withLayer()
                    .start()
            }

            Transition.NONE -> {
                Unit
            }
        }
    }

    private fun done(old: Decoded?) {
        animating = false
        pendingOld = null
        exchangeViews()
        finishSwap(old)
    }

    /** Die hintere Ansicht nach vorn, aber unter den Overlays (deren translationZ ist höher). */
    private fun raiseBack() {
        back.translationZ = 1f
        front.translationZ = 0f
    }

    private fun exchangeViews() {
        val t = front
        front = back
        back = t
    }

    /** Räumt die hintere Ansicht ab und gibt das alte Bitmap frei. */
    private fun finishSwap(old: Decoded? = null) {
        back.setImageDrawable(null)
        back.alpha = 0f
        back.translationX = 0f
        front.alpha = 1f
        front.translationX = 0f
        if (old != null && old !== current) old.bitmap.recycle()
    }

    private fun scaleType() = if (fillMode == FillMode.FILL) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER

    private fun decode(file: File): Bitmap? =
        try {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } catch (e: OutOfMemoryError) {
            null
        }
}
