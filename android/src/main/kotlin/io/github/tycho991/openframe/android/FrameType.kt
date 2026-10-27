package io.github.tycho991.openframe.android

import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import android.widget.TextView

/**
 * Inter on the frame (spec `visual-design`, "Typeface Inter"; design-system, design.md, decision 5). XML
 * `fontFamily` cannot refer to a bundled font before API 26, so the views get their typeface here after inflating,
 * by role. Each weight is loaded once, because every `createFromAsset` creates a new native typeface. If a file
 * cannot be loaded, the view keeps the `fontFamily` of its style, the system's sans-serif.
 */
object FrameType {
    enum class Weight(
        val asset: String,
    ) {
        /** The clock's time. */
        LIGHT("fonts/Inter-Light.ttf"),

        /** Body text, labels, the date and the caption. */
        REGULAR("fonts/Inter-Regular.ttf"),

        /** The pairing code and the pause button. */
        MEDIUM("fonts/Inter-Medium.ttf"),

        /** Titles: the frame's name, the empty state's heading. */
        SEMIBOLD("fonts/Inter-SemiBold.ttf"),
    }

    private const val TAG = "FrameType"

    /** Per weight the typeface, or `null` once loading has failed, so that it is not tried again. */
    private val loaded = mutableMapOf<Weight, Typeface?>()

    fun typeface(
        assets: AssetManager,
        weight: Weight,
    ): Typeface? =
        synchronized(loaded) {
            if (weight !in loaded) loaded[weight] = load(assets, weight.asset)
            loaded[weight]
        }

    /** Sets [weight] on [views]; a view whose typeface failed to load stays as it is. */
    fun apply(
        weight: Weight,
        vararg views: TextView,
    ) {
        for (view in views) typeface(view.context.assets, weight)?.let { view.typeface = it }
    }

    /** Figures of equal width for numbers that change while they are visible (spec `visual-design`). */
    fun tabular(vararg views: TextView) {
        for (view in views) view.fontFeatureSettings = "tnum"
    }

    internal fun load(
        assets: AssetManager,
        path: String,
    ): Typeface? =
        try {
            Typeface.createFromAsset(assets, path)
        } catch (e: RuntimeException) {
            // Some Android 5 builds fail on custom fonts; the style's fontFamily remains.
            Log.w(TAG, "Cannot load $path, the system font stays", e)
            null
        }
}
