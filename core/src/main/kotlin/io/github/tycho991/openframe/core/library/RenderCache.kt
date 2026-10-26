package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Orientation
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

enum class RenderSize(
    val apiName: String,
) {
    THUMB("thumb"),

    /** The part of the photo the frame shows, see [Presentation]. */
    DISPLAY("display"),

    /** The uncropped photo for the section editor. */
    FULL("full"),
}

/** Who is waiting for an image. Higher priority is rendered first. */
enum class RenderPriority { PREFETCH, NEXT, DEMAND }

data class PixelSize(
    val width: Int,
    val height: Int,
)

/** Target sizes of the rendered images, testable as pure functions. */
object RenderSizes {
    const val THUMB_EDGE = 400
    const val FULL_EDGE = 2048

    /** Screen in the configured orientation, from the device's natural resolution. */
    fun screen(
        naturalWidth: Int,
        naturalHeight: Int,
        orientation: Orientation,
    ): PixelSize {
        val long = max(naturalWidth, naturalHeight)
        val short = minOf(naturalWidth, naturalHeight)
        return if (orientation == Orientation.LANDSCAPE) PixelSize(long, short) else PixelSize(short, long)
    }

    /** Thumbnail: longest edge 400 px, never larger than the original. */
    fun thumb(
        width: Int,
        height: Int,
    ): PixelSize = scaled(width, height, minOf(1.0, THUMB_EDGE.toDouble() / max(width, height)))

    /** Full size: the uncropped photo, longest edge 2048 px, never larger than the original. */
    fun full(
        width: Int,
        height: Int,
    ): PixelSize = scaled(width, height, minOf(1.0, FULL_EDGE.toDouble() / max(width, height)))

    private fun scaled(
        width: Int,
        height: Int,
        scale: Double,
    ) = PixelSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
}

/**
 * What one size of a photo is rendered as: [size] in pixels, the [region] of the EXIF-rotated photo
 * it shows, and the file [name], which changes with every change of size, region or image version.
 */
class RenderTarget(
    val size: PixelSize,
    val region: Region,
    val name: String,
    /** [Presentation.key] of a display size, `null` for the uncropped sizes. */
    val key: String? = null,
)

/**
 * Cache of the rendered image sizes. Rendering happens on exactly one worker thread
 * with low priority, requests from the display and the web UI before pre-rendering.
 * The cache stays within the budget by removing the files unused for the
 * longest time. It never touches originals.
 */
class RenderCache(
    private val dir: File,
    private val engine: MediaEngine,
    private val originalOf: (PhotoEntry) -> File,
    private val screen: () -> PixelSize,
    private val fillMode: () -> FillMode,
    private val budgetBytes: () -> Long,
    private val log: AppLog,
) {
    private class Job(
        val priority: RenderPriority,
        val seq: Long,
        val entry: PhotoEntry,
        val target: RenderTarget,
        val result: CompletableDeferred<File>,
    ) {
        val key: String get() = target.name
    }

    /** A file waiting to be rendered, with the highest priority it was asked for. */
    private class Pending(
        val result: CompletableDeferred<File>,
        var priority: RenderPriority,
    )

    private val queue =
        PriorityBlockingQueue<Job>(16, compareByDescending<Job> { it.priority }.thenBy { it.seq })
    private val seq = AtomicLong()
    private val inFlight = HashMap<String, Pending>()

    /** Name → last use, for LRU eviction. */
    private val lastUse = LinkedHashMap<String, Long>()
    private val clock = AtomicLong()

    private val worker =
        Thread({ runWorker() }, "render").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }

    init {
        dir.mkdirs()
        dir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith('.') }
            .forEach { it.delete() }
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .forEach { lastUse[it.name] = clock.incrementAndGet() }
        worker.start()
    }

    /** What the frame shows of [entry] with the current screen and fill mode. */
    fun presentation(entry: PhotoEntry): Presentation = Presentation.of(entry.width, entry.height, screen(), entry.crop, fillMode())

    /**
     * Thumbnail and full size are uncropped and named `id-v{version}-{w}x{h}.jpg`. The display size is the window of
     * [presentation], named `id-v{version}-p{key}.jpg`, so a new section, fill mode or format gives a new file.
     */
    fun target(
        entry: PhotoEntry,
        size: RenderSize,
    ): RenderTarget {
        val prefix = "${entry.id}-v${entry.version}-"
        return when (size) {
            RenderSize.THUMB, RenderSize.FULL -> {
                val pixels =
                    if (size ==
                        RenderSize.THUMB
                    ) {
                        RenderSizes.thumb(entry.width, entry.height)
                    } else {
                        RenderSizes.full(entry.width, entry.height)
                    }
                RenderTarget(pixels, Region.FULL, "$prefix${pixels.width}x${pixels.height}.jpg")
            }

            RenderSize.DISPLAY -> {
                val p = presentation(entry)
                RenderTarget(
                    p.output,
                    Region(p.x, p.y, p.width, p.height),
                    "$prefix$DISPLAY_MARK${p.key}.jpg",
                    p.key,
                )
            }
        }
    }

    /** The rendered file, rendered now if needed. Throws if rendering fails. */
    suspend fun get(
        entry: PhotoEntry,
        size: RenderSize,
        priority: RenderPriority = RenderPriority.DEMAND,
    ): File = get(entry, target(entry, size), priority)

    /** The file of [target], worked out before, so a caller's headers and the body belong to the same window. */
    suspend fun get(
        entry: PhotoEntry,
        target: RenderTarget,
        priority: RenderPriority = RenderPriority.DEMAND,
    ): File {
        peek(target)?.let { return it }
        return enqueue(entry, target, priority).await()
    }

    /** The rendered file if it already exists, without rendering. */
    fun peek(
        entry: PhotoEntry,
        size: RenderSize,
    ): File? = peek(target(entry, size))

    private fun peek(target: RenderTarget): File? {
        val file = File(dir, target.name)
        if (!file.exists()) return null
        touch(file.name)
        return file
    }

    /**
     * Any finished display size of this image version, for example still the one of the old format or
     * section while the new one renders. Never the thumbnail or the full size.
     */
    fun peekAnyDisplay(entry: PhotoEntry): File? {
        val prefix = "${entry.id}-v${entry.version}-"
        val name =
            synchronized(lastUse) {
                lastUse.entries
                    .filter { it.key.startsWith(prefix + DISPLAY_MARK) }
                    .maxByOrNull { it.value }
                    ?.key
            }
                ?: return null
        return File(dir, name).takeIf { it.exists() }?.also { touch(name) }
    }

    /** Pre-renders the display sizes of [entries] if they are missing. */
    fun prefetch(
        entries: List<PhotoEntry>,
        priority: RenderPriority = RenderPriority.PREFETCH,
    ) {
        for (entry in entries) {
            val target = target(entry, RenderSize.DISPLAY)
            if (!File(dir, target.name).exists()) enqueue(entry, target, priority)
        }
    }

    /** Removes all rendered sizes of a photo, for example after deleting. */
    fun remove(photoId: String) {
        deleteMatching { it.startsWith("$photoId-") }
    }

    /** Stays within the budget. [keep] is kept in any case, for example the image just rendered. */
    fun trim(keep: String? = null) {
        val budget = budgetBytes()
        val files = synchronized(lastUse) { lastUse.entries.sortedBy { it.value }.map { it.key } }
        var total = files.sumOf { File(dir, it).length() }
        for (name in files) {
            if (total <= budget) break
            if (name == keep) continue
            val file = File(dir, name)
            total -= file.length()
            file.delete()
            synchronized(lastUse) { lastUse.remove(name) }
        }
    }

    fun totalBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    private fun enqueue(
        entry: PhotoEntry,
        target: RenderTarget,
        priority: RenderPriority,
    ): CompletableDeferred<File> {
        // A file already waiting is queued again only when it is asked for more urgently: the slideshow asks for the
        // photos ahead after every change of the library, which would otherwise pile up jobs for the same files.
        val (deferred, add) =
            synchronized(inFlight) {
                val pending = inFlight[target.name]
                when {
                    pending == null -> Pending(CompletableDeferred(), priority).also { inFlight[target.name] = it }.result to true
                    priority > pending.priority -> pending.also { it.priority = priority }.result to true
                    else -> pending.result to false
                }
            }
        if (add) queue.add(Job(priority, seq.incrementAndGet(), entry, target, deferred))
        return deferred
    }

    /** Jobs waiting for the worker, for tests. */
    internal val queued: Int get() = queue.size

    /** Stops the worker. Waiting jobs fail, running ones are rendered to the end. */
    fun close() {
        closed = true
        worker.interrupt()
        worker.join(WORKER_STOP_TIMEOUT_MS)
        queue.forEach { it.result.cancel() }
        queue.clear()
    }

    @Volatile
    private var closed = false

    private fun runWorker() {
        // A render can swallow the interrupt flag, hence [closed] in addition.
        while (!closed) {
            val job =
                try {
                    queue.take()
                } catch (e: InterruptedException) {
                    return
                }
            if (job.result.isCompleted) continue
            val target = File(dir, job.key)
            try {
                if (!target.exists()) render(job, target)
                touch(job.key)
                job.result.complete(target)
            } catch (e: Throwable) {
                log.w(TAG, "Rendering of ${job.entry.path} failed", e)
                job.result.completeExceptionally(e)
            } finally {
                synchronized(inFlight) { inFlight.remove(job.key) }
            }
        }
    }

    private fun render(
        job: Job,
        target: File,
    ) {
        val tmp = File(dir, ".${job.key}.tmp")
        engine.render(originalOf(job.entry), tmp, job.target.size.width, job.target.size.height, job.target.region)
        if (!tmp.renameTo(target)) throw IllegalStateException("Renaming to ${target.name} failed")
        // Older image versions of this photo are no longer needed.
        touch(target.name)
        val current = "${job.entry.id}-v${job.entry.version}-"
        deleteMatching { it.startsWith("${job.entry.id}-") && !it.startsWith(current) }
        trim(keep = target.name)
    }

    private fun touch(name: String) {
        synchronized(lastUse) { lastUse[name] = clock.incrementAndGet() }
    }

    private fun deleteMatching(predicate: (String) -> Boolean) {
        dir.listFiles().orEmpty().filter { predicate(it.name) }.forEach { file ->
            file.delete()
            synchronized(lastUse) { lastUse.remove(file.name) }
        }
    }

    private companion object {
        const val TAG = "render"

        /** Starts the key in the name of a display size; thumbnail and full size start with a digit there. */
        const val DISPLAY_MARK = "p"
        const val WORKER_STOP_TIMEOUT_MS = 10_000L
    }
}
