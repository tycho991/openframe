package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
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
    /** [RenderCache.displayKey] of a display size, `null` for the uncropped sizes. */
    val key: String? = null,
)

/**
 * Cache of the rendered image sizes. Rendering happens on exactly one worker thread
 * with low priority, requests from the display and the web UI before pre-rendering.
 * The cache stays within the budget by removing the files unused for the
 * longest time. It never touches originals.
 *
 * An original that is not on the frame, of a remote photo, is fetched on a download thread of its own (spec
 * `remote-sources`, "Rendering does not wait for downloads"): the worker hands the job over and renders the next one.
 * The download thread fetches each photo once, however many sizes wait for it, in the order of their priority, and
 * queues the jobs again with the fetched file.
 */
class RenderCache(
    private val dir: File,
    private val engine: MediaEngine,
    /** The original of a photo if it is on the frame, `null` if it has to be fetched first. */
    private val onFrame: (PhotoEntry) -> File?,
    private val screen: () -> PixelSize,
    private val fillMode: () -> FillMode,
    private val budgetBytes: () -> Long,
    private val log: AppLog,
    /** A rendering of this entry's version crashed the decoder: it is to be quarantined (spec "Quarantined photos"). */
    private val onCrashed: (PhotoEntry) -> Unit = {},
    /** Fetches the original that [onFrame] did not have, on the download thread; it stays until [release]. */
    private val fetch: (PhotoEntry) -> File = { onFrame(it) ?: throw RenderFailedException("The original of ${it.path} is missing") },
    /** The renderings that waited for a fetched original are done. */
    private val release: (PhotoEntry) -> Unit = {},
    /** Lists the folder; every listing goes through here, so that a test can count them. */
    private val listFolder: (File) -> Array<File>? = { it.listFiles() },
) {
    private class Job(
        val priority: RenderPriority,
        val seq: Long,
        val entry: PhotoEntry,
        val target: RenderTarget,
        val result: CompletableDeferred<File>,
        /** The original fetched for this job by the download thread. */
        val original: File? = null,
        /** Called once when the job is done, for a fetched original. */
        private val onFinished: (() -> Unit)? = null,
    ) {
        val key: String get() = target.name

        private val finished =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        fun finish() {
            if (finished.compareAndSet(false, true)) onFinished?.invoke()
        }

        fun withOriginal(
            seq: Long,
            file: File,
            onFinished: () -> Unit,
        ) = Job(priority, seq, entry, target, result, file, onFinished)
    }

    /** The original of [entry] to be fetched for the [jobs] that wait for it. */
    private class Download(
        val entry: PhotoEntry,
        var priority: RenderPriority,
        val seq: Long,
    ) {
        val jobs = ArrayList<Job>()
        var started = false
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

    private val downloads =
        PriorityBlockingQueue<Download>(4, compareByDescending<Download> { it.priority }.thenBy { it.seq })

    /** The downloads that are queued or running, by photo ID; guarded by itself. */
    private val pendingDownloads = HashMap<String, Download>()

    private val downloader =
        Thread({ runDownloads() }, "render-download").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }

    init {
        dir.mkdirs()
        listFolder(dir)
            .orEmpty()
            .filter { it.name.startsWith('.') }
            .forEach { it.delete() }
        listFolder(dir)
            .orEmpty()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .forEach { lastUse[it.name] = clock.incrementAndGet() }
        worker.start()
        downloader.start()
    }

    /** What the frame shows of [entry] with the current screen and fill mode. */
    fun presentation(entry: PhotoEntry): Presentation = Presentation.of(entry.width, entry.height, screen(), entry.crop, fillMode())

    /**
     * Thumbnail and full size are uncropped and named `id-v{version}-{w}x{h}.jpg`. The display size is the window of
     * [presentation], named `id-v{version}-p{key}.jpg`, so a new section, fill mode or format gives a new file.
     *
     * The renderings live in the folder [FOLDER], which carries the format [FORMAT]. Raise [FORMAT] with every change
     * to `MediaEngine.render`, to the presentation or to the JPEG quality: an update then starts with an empty folder,
     * and the display key, and with it the image URL that browsers keep for a year, changes as well.
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
                    displayKey(p),
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
        // Also a size rendered before the crash: a quarantined photo has no image at all.
        if (entry.quarantined) throw RenderFailedException("${entry.path} is quarantined")
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
            if (entry.quarantined) continue
            val target = target(entry, RenderSize.DISPLAY)
            if (!File(dir, target.name).exists()) enqueue(entry, target, priority)
        }
    }

    /** Removes all rendered sizes of a photo, for example after deleting. */
    fun remove(photoId: String) = removeAll(listOf(photoId))

    /**
     * Removes all rendered sizes of the photos with [photoIds], for example those of a removed source, with one listing of
     * the folder: one listing per photo made the removal of a source with tens of thousands of photos take minutes.
     */
    fun removeAll(photoIds: Collection<String>) {
        if (photoIds.isEmpty()) return
        val ids = photoIds.toHashSet()
        // A file belongs to a photo if its name starts with the ID and a '-' (see [target]). IDs contain '-' themselves,
        // so every prefix that ends before a '-' is looked up.
        deleteMatching { name ->
            var dash = name.indexOf('-')
            while (dash >= 0 && name.substring(0, dash) !in ids) dash = name.indexOf('-', dash + 1)
            dash >= 0
        }
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

    fun totalBytes(): Long = listFolder(dir).orEmpty().sumOf { it.length() }

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

    /** Stops the worker and the download thread. Waiting jobs fail, running ones are rendered to the end. */
    fun close() {
        closed = true
        worker.interrupt()
        downloader.interrupt()
        worker.join(WORKER_STOP_TIMEOUT_MS)
        // A download may hang in a read that an interrupt does not end; the thread is a daemon and its jobs fail now.
        downloader.join(DOWNLOAD_STOP_TIMEOUT_MS)
        queue.forEach { it.result.cancel() }
        queue.clear()
        val waiting = synchronized(pendingDownloads) { pendingDownloads.values.flatMap { it.jobs }.also { pendingDownloads.clear() } }
        waiting.forEach { it.result.cancel() }
        downloads.clear()
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
            if (job.result.isCompleted) {
                job.finish()
                continue
            }
            val target = File(dir, job.key)
            var handedOver = false
            try {
                if (!target.exists()) {
                    val original = job.original ?: onFrame(job.entry)
                    if (original == null) {
                        // The original has to be fetched first: on the download thread, while this one renders on.
                        handOver(job)
                        handedOver = true
                        continue
                    }
                    render(job, target, original)
                }
                touch(job.key)
                job.result.complete(target)
            } catch (e: Throwable) {
                log.w(TAG, "Rendering of ${job.entry.path} failed", e)
                if (e is ImageException && e.reason == SkipReason.CRASHED) {
                    log.e(TAG, "${job.entry.path} made the decoder crash and is quarantined")
                    onCrashed(job.entry)
                }
                // An Error such as OutOfMemoryError would end whoever awaits the result: the slideshow or a request.
                job.result.completeExceptionally(
                    if (e is Exception) e else RenderFailedException("Rendering of ${job.entry.path} failed", e),
                )
            } finally {
                // A job handed over stays in flight, so that another request for the same size joins it.
                if (!handedOver) {
                    synchronized(inFlight) { inFlight.remove(job.key) }
                    job.finish()
                }
            }
        }
    }

    /** Lets the download thread fetch the original of [job]; jobs for the same photo share one download. */
    private fun handOver(job: Job) {
        synchronized(pendingDownloads) {
            val known = pendingDownloads[job.entry.id]
            if (known == null) {
                val download = Download(job.entry, job.priority, seq.incrementAndGet())
                download.jobs += job
                pendingDownloads[job.entry.id] = download
                downloads.add(download)
            } else {
                known.jobs += job
                if (job.priority > known.priority && !known.started && downloads.remove(known)) {
                    known.priority = job.priority
                    downloads.add(known)
                }
            }
        }
    }

    private fun runDownloads() {
        while (!closed) {
            val download =
                try {
                    downloads.take()
                } catch (e: InterruptedException) {
                    return
                }
            synchronized(pendingDownloads) { download.started = true }
            val fetched =
                try {
                    Result.success(fetch(download.entry))
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            // Jobs that joined while it ran are in the list as well; one that comes after it finds the original there.
            val jobs =
                synchronized(pendingDownloads) {
                    pendingDownloads.remove(download.entry.id)
                    download.jobs.toList()
                }
            fetched
                .onSuccess { file ->
                    if (closed) {
                        release(download.entry)
                        jobs.forEach { it.result.cancel() }
                        return@onSuccess
                    }
                    val left =
                        java.util.concurrent.atomic
                            .AtomicInteger(jobs.size)
                    val done = { if (left.decrementAndGet() == 0) release(download.entry) }
                    if (jobs.isEmpty()) release(download.entry)
                    for (job in jobs) queue.add(job.withOriginal(seq.incrementAndGet(), file, done))
                }.onFailure { e ->
                    log.w(TAG, "The original of ${download.entry.path} could not be fetched: ${e.message}")
                    // Only the decoder crashing on a fetched original, in the check of its dimensions, quarantines it; a
                    // failed download does not.
                    if (e is ImageException && e.reason == SkipReason.CRASHED) {
                        log.e(TAG, "${download.entry.path} made the decoder crash and is quarantined")
                        onCrashed(download.entry)
                    }
                    val failure = if (e is Exception) e else RenderFailedException("Fetching ${download.entry.path} failed", e)
                    for (job in jobs) {
                        synchronized(inFlight) { inFlight.remove(job.key) }
                        job.result.completeExceptionally(failure)
                    }
                }
        }
    }

    private fun render(
        job: Job,
        target: File,
        original: File,
    ) {
        val tmp = File(dir, ".${job.key}.tmp")
        engine.render(original, tmp, job.target.size.width, job.target.size.height, job.target.region)
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
        listFolder(dir).orEmpty().filter { predicate(it.name) }.forEach { file ->
            file.delete()
            synchronized(lastUse) { lastUse.remove(file.name) }
        }
    }

    companion object {
        /** The format of the renderings; see [target]. */
        const val FORMAT = "r1"

        /** The folder of the renderings in the cache, `renders-r1`. */
        const val FOLDER = "renders-$FORMAT"

        /** The key `p` of a display URL: the format and [Presentation.key]. */
        fun displayKey(presentation: Presentation): String = "$FORMAT-${presentation.key}"

        /** Folders of renderings in another format than [FORMAT], or of the time before it, in [cache]. */
        fun staleFolders(cache: File): List<File> =
            cache.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("renders") && it.name != FOLDER }

        private const val TAG = "render"

        /** Starts the key in the name of a display size; thumbnail and full size start with a digit there. */
        private const val DISPLAY_MARK = "p"
        private const val WORKER_STOP_TIMEOUT_MS = 10_000L
        private const val DOWNLOAD_STOP_TIMEOUT_MS = 1_000L
    }
}

/**
 * A rendering that failed with an [Error], for example out of memory, passed on as an ordinary exception, or one that
 * was refused because the photo is quarantined.
 */
class RenderFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
