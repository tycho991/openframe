package io.github.tycho991.openframe.android

import android.view.Choreographer

/**
 * Counts the frames of one transition and how many of them arrived late, that is more than half a frame
 * after they were due. It runs only while a transition does, so it costs nothing in between.
 */
class FrameMonitor(
    private val refreshIntervalNanos: () -> Long,
    private val onFinished: (frames: Int, late: Int) -> Unit,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var last = 0L
    private var frames = 0
    private var late = 0

    fun start() {
        stop()
        running = true
        last = 0L
        frames = 0
        late = 0
        choreographer.postFrameCallback(this)
    }

    /** The transition ended as planned: reports it. */
    fun finish() {
        if (!running) return
        stop()
        onFinished(frames, late)
    }

    /** The transition was cut short: nothing is reported. */
    fun stop() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (last != 0L) {
            frames++
            if (frameTimeNanos - last > refreshIntervalNanos() * 3 / 2) late++
        }
        last = frameTimeNanos
        choreographer.postFrameCallback(this)
    }
}
