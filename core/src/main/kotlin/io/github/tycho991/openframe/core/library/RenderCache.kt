package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.Clock
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
import io.github.tycho991.openframe.core.settings.FillMode
import io.github.tycho991.openframe.core.settings.Orientation
import io.github.tycho991.openframe.core.sources.SourceException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.Executors
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
 * How the download thread asks for a missing original: [onDemand] when a rendering somebody awaits needs it (it may go
 * below the storage reserve, down to a floor), and [cancelled] once nobody wants it any more (the download ends at its
 * next read).
 */
class OriginalRequest(
    val onDemand: Boolean,
    val cancelled: () -> Boolean,
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
 *
 * It keeps an index of its files in memory (design decision 3 of `library-performance`): name → last use, size and the
 * file time it last set, and photo ID → names, with a running total. The folder is listed once at the start and again
 * only when a file it knows has gone behind its back, at most once an hour.
 *
 * The slideshow sets a window of renderings ahead ([prefetchAhead]); a waiting job that left it and that nobody awaits is
 * dropped before it starts, and so is a download for it. A job of a photo that left the library ([removeAll]) is
 * dropped as well, and a rendering of it that finishes afterwards is discarded.
 *
 * Below the storage reserve the cache removes its own renderings unused longest, also below its budget, and renders
 * only what somebody awaits (design decision 2).
 *
 * Locks: one cache lock guards every map of the cache. It comes after the index lock and before the lock of an
 * `OriginalsStore`; under it the cache never calls [onFrame], [fetch], [release], [onCrashed], renders or deletes files
 * (design decision 6a).
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
    private val fetch: (PhotoEntry, OriginalRequest) -> File = { entry, _ ->
        onFrame(entry) ?: throw RenderFailedException("The original of ${entry.path} is missing")
    },
    /** The renderings that waited for a fetched original are done. */
    private val release: (PhotoEntry) -> Unit = {},
    /** Lists the folder; every listing goes through here, so that a test can count them. */
    private val listFolder: (File) -> Array<File>? = { it.listFiles() },
    /** Reads the size of a rendering; every size read goes through here, so that a test can count them. */
    private val sizeOf: (File) -> Long = { it.length() },
    /** Free storage on the volume of [dir] (`PlatformInfo.freeBytes`). */
    private val freeBytes: (File) -> Long = { it.usableSpace },
    /** The storage reserve ([StorageReserve]); 0 checks nothing. */
    private val reserveBytes: Long = 0,
    /** For the file times of the renderings and the hourly limits. */
    private val clock: Clock = Clock.System,
) {
    private class Job(
        val priority: RenderPriority,
        val seq: Long,
        val entry: PhotoEntry,
        val target: RenderTarget,
        /** What the job was queued for; after a drop the same name may have a new one. */
        val pending: Pending,
        /** The original fetched for this job by the download thread. */
        val original: File? = null,
        /** Called once when the job is done, for a fetched original. */
        private val onFinished: (() -> Unit)? = null,
    ) {
        val key: String get() = target.name
        val result: CompletableDeferred<File> get() = pending.result

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
        ) = Job(priority, seq, entry, target, pending, file, onFinished)

        /** The same job queued once more, with the original it has. */
        fun again(seq: Long) = Job(RenderPriority.DEMAND, seq, entry, target, pending, original, onFinished)
    }

    /** The original of [entry] to be fetched for the [jobs] that wait for it. */
    private class Download(
        val entry: PhotoEntry,
        var priority: RenderPriority,
        val seq: Long,
    ) {
        val jobs = ArrayList<Job>()
        var started = false

        /** Nobody wants it any more: the fetch ends at its next read. A job that joins does not take this back. */
        @Volatile var cancelled = false

        /** A job of it was awaited when it started: it may go below the storage reserve. */
        var onDemand = false
    }

    /**
     * A file waiting to be rendered, with the highest priority it was asked for, and whether a [get] awaits it. One
     * only in the window of renderings ahead can be dropped; one awaited never is.
     */
    private class Pending(
        val result: CompletableDeferred<File>,
        var priority: RenderPriority,
        /** How many [get] calls await it now; one that is cancelled stops counting. */
        var awaiters: Int,
    ) {
        val awaited: Boolean get() = awaiters > 0
    }

    /** A rendered file in the index: its photo, version, last use, size and the file time last set. */
    private class Item(
        val photoId: String,
        val version: Int,
        var lastUse: Long,
        val size: Long,
        var fileTime: Long,
        /** [uses] when it entered the index, so a rebuild keeps what was rendered while it listed. */
        val since: Long,
    )

    /** Guards every map below; see the class comment for what must not happen under it. */
    private val lock = Any()

    private val queue =
        PriorityBlockingQueue<Job>(16, compareByDescending<Job> { it.priority }.thenBy { it.seq })
    private val seq = AtomicLong()
    private val inFlight = HashMap<String, Pending>()

    private val items = HashMap<String, Item>()
    private val byPhoto = HashMap<String, MutableSet<String>>()
    private var total = 0L
    private var uses = 0L

    /** The names of the window of renderings ahead, and the files on screen and next up; see [prefetchAhead]. */
    private var wanted: Set<String> = emptySet()
    private var keep: Set<String> = emptySet()

    /** Names out of the index whose files are still to be deleted; see [discardLocked]. */
    private val deleting = HashSet<String>()

    /** Photo ID → the highest version removed from the library (design decision 5); a few bytes per removed photo. */
    private val tombstones = HashMap<String, Int>()

    /** When the index was last rebuilt from a listing, on [Clock.monotonicMillis]; `null` before the first rebuild. */
    private var rebuiltAt: Long? = null
    private var reserveWarnedAt: Long? = null

    private val worker =
        Thread({ runWorker() }, "render").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }

    private val downloads =
        PriorityBlockingQueue<Download>(4, compareByDescending<Download> { it.priority }.thenBy { it.seq })

    /** The downloads that are queued or running, by photo ID. */
    private val pendingDownloads = HashMap<String, Download>()

    private val downloader =
        Thread({ runDownloads() }, "render-download").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }

    /** Deletes the files of removed photos with low priority, so that a large removal holds up no rendering. */
    private val deleter =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "render-delete").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    init {
        dir.mkdirs()
        val startNs = System.nanoTime()
        val junk = ArrayList<File>()
        val found = ArrayList<Triple<String, Long, Long>>()
        for (file in listFolder(dir).orEmpty()) {
            // A `.tmp` of a run that was cut off, and anything whose name is not that of a rendering.
            if (file.name.startsWith('.') || parse(file.name) == null) {
                junk += file
            } else {
                // Each file is asked for its time and size once.
                found += Triple(file.name, file.lastModified(), sizeOf(file))
            }
        }
        for ((name, time, size) in found.sortedBy { it.second }) add(name, size, time)
        junk.forEach { it.delete() }
        val ms = (System.nanoTime() - startNs) / 1_000_000
        log.i(TAG, "Render cache: ${items.size} files, ${total / MIB} MiB, listed in $ms ms")
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

    /**
     * The file of [target], worked out before, so a caller's headers and the body belong to the same window. The caller
     * awaits it: the job is never dropped and may render below the storage reserve.
     */
    suspend fun get(
        entry: PhotoEntry,
        target: RenderTarget,
        priority: RenderPriority = RenderPriority.DEMAND,
    ): File {
        // Also a size rendered before the crash: a quarantined photo has no image at all.
        if (entry.quarantined) throw RenderFailedException("${entry.path} is quarantined")
        peek(target)?.let { return it }
        val (pending, job) = synchronized(lock) { enqueueLocked(entry, target, priority, awaited = true) }
        job?.let(queue::add)
        try {
            return pending.result.await()
        } finally {
            // An awaiter that gave up, the slideshow resting or a request that ended: once nobody awaits the job any more
            // it can be dropped like one of the window, and so can its download.
            if (!pending.result.isCompleted) stopAwaiting(pending)
        }
    }

    private fun stopAwaiting(pending: Pending) {
        val dropped =
            synchronized(lock) {
                pending.awaiters = (pending.awaiters - 1).coerceAtLeast(0)
                if (pending.awaited) emptyList() else dropUnwantedDownloadsLocked()
            }
        complete(dropped)
    }

    /**
     * Under [lock]: a queued download whose jobs nobody wants any more leaves the queue with its jobs, which are returned
     * to be completed outside the lock; a running one is cancelled.
     */
    private fun dropUnwantedDownloadsLocked(): List<Job> {
        val dropped = ArrayList<Job>()
        val downloadsLeft = pendingDownloads.values.iterator()
        while (downloadsLeft.hasNext()) {
            val download = downloadsLeft.next()
            if (download.jobs.any { !droppable(it) }) continue
            if (download.started) {
                download.cancelled = true
            } else {
                downloads.remove(download)
                downloadsLeft.remove()
                for (job in download.jobs) {
                    removeInFlight(job)
                    dropped += job
                }
            }
        }
        return dropped
    }

    private fun complete(dropped: List<Job>) {
        for (job in dropped) {
            job.result.completeExceptionally(RenderDroppedException("${job.entry.path} is no longer wanted"))
            job.finish()
        }
    }

    /** The rendered file if it already exists, without rendering. */
    fun peek(
        entry: PhotoEntry,
        size: RenderSize,
    ): File? = peek(target(entry, size))

    /** Answered from the index; the disk is asked only for a hit. */
    private fun peek(target: RenderTarget): File? {
        if (synchronized(lock) { target.name !in items }) return null
        return existing(target.name)
    }

    /**
     * The file of [name], which the index knows, marked as used; `null` if it is gone from the disk: Android or someone
     * else deleted files in the folder, and the index is rebuilt from one listing, at most once an hour.
     */
    private fun existing(name: String): File? {
        val file = File(dir, name)
        if (file.exists()) {
            touch(name)
            return file
        }
        val rebuild =
            synchronized(lock) {
                forget(name)
                val now = clock.monotonicMillis()
                val due = rebuiltAt.let { it == null || now - it >= HOUR_MS }
                if (due) rebuiltAt = now
                due
            }
        if (rebuild) rebuild()
        return null
    }

    /**
     * Any finished display size of this image version, for example still the one of the old format or
     * section while the new one renders. Never the thumbnail or the full size.
     */
    fun peekAnyDisplay(entry: PhotoEntry): File? {
        val prefix = "${entry.id}-v${entry.version}-$DISPLAY_MARK"
        val name =
            synchronized(lock) {
                byPhoto[entry.id]
                    ?.filter { it.startsWith(prefix) }
                    ?.maxByOrNull { items[it]?.lastUse ?: 0 }
            } ?: return null
        return existing(name)
    }

    /**
     * The window of renderings ahead (design decision 6 of `library-performance`): the display sizes of [next] are
     * rendered with [RenderPriority.NEXT], those of [later] with [RenderPriority.PREFETCH], if they are missing. A
     * waiting job that is in neither and that nobody awaits is dropped before it starts, and so is a queued download
     * for it; a running one is cancelled. [keep] are the files on screen and next up, which no trim removes.
     */
    fun prefetchAhead(
        next: List<PhotoEntry>,
        later: List<PhotoEntry>,
        keep: Collection<File>,
    ) {
        val targets =
            next.filter { !it.quarantined }.map { Triple(it, target(it, RenderSize.DISPLAY), RenderPriority.NEXT) } +
                later.filter { !it.quarantined }.map { Triple(it, target(it, RenderSize.DISPLAY), RenderPriority.PREFETCH) }
        val queued = ArrayList<Job>()
        val dropped =
            synchronized(lock) {
                wanted = targets.mapTo(HashSet()) { it.second.name }
                this.keep = keep.mapTo(HashSet()) { it.name }
                for ((entry, target, priority) in targets) {
                    if (target.name in items) continue
                    enqueueLocked(entry, target, priority, awaited = false).second?.let(queued::add)
                }
                dropUnwantedDownloadsLocked()
            }
        queued.forEach(queue::add)
        complete(dropped)
    }

    /**
     * Removes all rendered sizes of [entries], which left the library: their names leave the index at once, their files
     * go on a thread of low priority, and a job of such an entry's version or an older one is dropped, also one that is
     * rendering now (design decisions 3 and 5 of `library-performance`). Takes the cache lock, so it may be called
     * inside an edit of the index.
     */
    fun removeAll(entries: Collection<PhotoEntry>) {
        if (entries.isEmpty()) return
        val files = ArrayList<File>()
        synchronized(lock) {
            for (entry in entries) {
                val tomb = tombstones[entry.id]
                if (tomb == null || entry.version > tomb) tombstones[entry.id] = entry.version
                val names = byPhoto[entry.id]?.toList() ?: continue
                for (name in names) files += discardLocked(name)
            }
        }
        if (files.isNotEmpty()) deleteLater(files)
    }

    /**
     * Removes the renderings of photos that are not in the index and of versions above the index's ([version] gives
     * the version of a photo ID, `null` for one that is not there), at the start before the slideshow: files from
     * before an update and a deletion that the end of the process cut off. Returns how many it removes.
     */
    fun sweep(version: (String) -> Int?): Int {
        val files = ArrayList<File>()
        synchronized(lock) {
            val stale = items.filter { (_, item) -> version(item.photoId).let { it == null || item.version > it } }.keys
            for (name in stale) files += discardLocked(name)
        }
        if (files.isNotEmpty()) deleteLater(files)
        return files.size
    }

    /** Stays within the budget. [keep] is kept in any case, for example the image just rendered. */
    fun trim(keep: String? = null) {
        deleteNow(synchronized(lock) { overBudgetLocked(keep) })
    }

    fun totalBytes(): Long = synchronized(lock) { total }

    /** Waits until the files handed to the delete thread are gone, for tests. */
    internal fun awaitDeletions() {
        deleter.submit {}.get()
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
        val waiting = synchronized(lock) { pendingDownloads.values.flatMap { it.jobs }.also { pendingDownloads.clear() } }
        waiting.forEach { it.result.cancel() }
        downloads.clear()
        deleter.shutdown()
    }

    @Volatile
    private var closed = false

    /** Under [lock]: what waits for [target], and the job to queue, `null` if one is waiting already. */
    private fun enqueueLocked(
        entry: PhotoEntry,
        target: RenderTarget,
        priority: RenderPriority,
        awaited: Boolean,
    ): Pair<Pending, Job?> {
        // A file already waiting is queued again only when it is asked for more urgently: the slideshow asks for the
        // photos ahead after every change of the library, which would otherwise pile up jobs for the same files.
        val pending = inFlight[target.name]
        if (pending == null) {
            val created = Pending(CompletableDeferred(), priority, if (awaited) 1 else 0)
            inFlight[target.name] = created
            return created to Job(priority, seq.incrementAndGet(), entry, target, created)
        }
        if (awaited) pending.awaiters++
        if (priority <= pending.priority) return pending to null
        pending.priority = priority
        return pending to Job(priority, seq.incrementAndGet(), entry, target, pending)
    }

    /** Under [lock]: neither awaited nor in the window. */
    private fun droppable(job: Job) = !job.pending.awaited && job.key !in wanted

    /** Under [lock]: the photo of [entry] left the library at this version or a later one. */
    private fun removed(entry: PhotoEntry) = tombstones[entry.id]?.let { entry.version <= it } ?: false

    /** Under [lock]: ends the job's own entry, never a newer one queued for the same name after a drop. */
    private fun removeInFlight(job: Job) {
        if (inFlight[job.key] === job.pending) inFlight.remove(job.key)
    }

    private sealed interface Step {
        data object Skip : Step

        class Drop(
            val why: String,
        ) : Step

        data object Render : Step

        data object Existing : Step
    }

    private fun runWorker() {
        // A render can swallow the interrupt flag, hence [closed] in addition.
        while (!closed) {
            val job =
                try {
                    queue.take()
                } catch (e: InterruptedException) {
                    return
                }
            work(job)
        }
    }

    private fun work(job: Job) {
        val step =
            synchronized(lock) {
                when {
                    job.result.isCompleted -> Step.Skip
                    removed(job.entry) -> Step.Drop("${job.entry.path} left the library")
                    droppable(job) -> Step.Drop("${job.entry.path} is no longer wanted")
                    job.key in items -> Step.Existing
                    else -> Step.Render
                }.also { if (it is Step.Drop) removeInFlight(job) }
            }
        when (step) {
            Step.Skip -> {
                job.finish()
                return
            }

            is Step.Drop -> {
                log.d(TAG, "Rendering dropped: ${step.why}")
                job.result.completeExceptionally(RenderDroppedException(step.why))
                job.finish()
                return
            }

            Step.Existing -> {
                existing(job.key)?.let { file ->
                    synchronized(lock) { removeInFlight(job) }
                    job.result.complete(file)
                    job.finish()
                    return
                }
            }

            Step.Render -> {}
        }
        var handedOver = false
        try {
            if (!roomForRendering(job)) {
                // A get() that joined after the check awaits it now: it renders after all. Decided under the lock, so a
                // later get() finds no entry in flight and queues a job of its own.
                val joined = synchronized(lock) { job.pending.awaited.also { if (!it) removeInFlight(job) } }
                if (joined) {
                    queue.add(job.again(seq.incrementAndGet()))
                    handedOver = true
                    return
                }
                throw StorageShortException("Less free storage than the reserve; ${job.entry.path} is rendered when it is due")
            }
            val original = job.original ?: onFrame(job.entry)
            if (original == null) {
                // The original has to be fetched first: on the download thread, while this one renders on.
                if (!handOver(job)) throw RenderDroppedException("${job.entry.path} left the library")
                handedOver = true
                return
            }
            job.result.complete(render(job, original))
        } catch (e: Throwable) {
            logFailure(job.entry, e)
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
                synchronized(lock) { removeInFlight(job) }
                job.finish()
            }
        }
    }

    /** A failure the cache expects is logged without its stack trace; a dropped job only for debugging. */
    private fun logFailure(
        entry: PhotoEntry,
        e: Throwable,
    ) {
        when (e) {
            is RenderDroppedException -> {
                log.d(TAG, "Rendering dropped: ${e.message}")
            }

            is StorageShortException -> {}

            is SourceException -> {
                log.w(TAG, "Rendering of ${entry.path} failed: ${e.message}")
            }

            else -> {
                log.w(TAG, "Rendering of ${entry.path} failed", e)
            }
        }
    }

    /**
     * Whether [job] may render now (design decision 2): below the storage reserve the cache first removes its own
     * renderings unused longest, also below its budget, never a protected one; then only an awaited job renders.
     */
    private fun roomForRendering(job: Job): Boolean {
        if (reserveBytes <= 0) return true
        val free = freeBytes(dir)
        if (free >= reserveBytes) return true
        val victims = synchronized(lock) { victimsLocked(reserveBytes - free, protectedLocked(job.key)) }
        deleteNow(victims)
        if (freeBytes(dir) >= reserveBytes) return true
        val awaited = synchronized(lock) { job.pending.awaited }
        val now = clock.monotonicMillis()
        val warn = synchronized(lock) { reserveWarnedAt.let { it == null || now - it >= HOUR_MS }.also { if (it) reserveWarnedAt = now } }
        if (warn) {
            log.w(
                TAG,
                "Less than the storage reserve of ${reserveBytes / MIB} MiB is free: only images that are waited for are rendered",
            )
        }
        return awaited
    }

    /** Under [lock]: what no trim removes: the window, the files on screen and next up, what a request awaits, and [also]. */
    private fun protectedLocked(also: String?): Set<String> {
        val set = HashSet<String>(wanted)
        set += keep
        for ((name, pending) in inFlight) if (pending.awaited) set += name
        if (also != null) set += also
        return set
    }

    /** Under [lock]: takes renderings unused longest out of the index until [bytes] are freed; returns their files. */
    private fun victimsLocked(
        bytes: Long,
        protected: Set<String>,
    ): List<File> {
        var freed = 0L
        val files = ArrayList<File>()
        for (name in items.entries.sortedBy { it.value.lastUse }.map { it.key }) {
            if (freed >= bytes) break
            if (name in protected) continue
            freed += items.getValue(name).size
            files += discardLocked(name)
        }
        return files
    }

    /** Under [lock]: the files to delete to stay within the budget, already out of the index. */
    private fun overBudgetLocked(keep: String?): List<File> {
        val budget = budgetBytes()
        if (total <= budget) return emptyList()
        return victimsLocked(total - budget, protectedLocked(keep))
    }

    /**
     * Lets the download thread fetch the original of [job]; jobs for the same photo share one download. `false` if the
     * photo left the library.
     */
    private fun handOver(job: Job): Boolean = synchronized(lock) { handOverLocked(job) }

    private fun handOverLocked(job: Job): Boolean {
        if (removed(job.entry)) {
            removeInFlight(job)
            return false
        }
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
        return true
    }

    private fun runDownloads() {
        while (!closed) {
            val download =
                try {
                    downloads.take()
                } catch (e: InterruptedException) {
                    return
                }
            val gone =
                synchronized(lock) {
                    when {
                        // Dropped from the window after the thread took it.
                        pendingDownloads[download.entry.id] !== download -> {
                            emptyList()
                        }

                        removed(download.entry) -> {
                            pendingDownloads.remove(download.entry.id)
                            download.jobs.onEach(::removeInFlight).toList()
                        }

                        else -> {
                            download.started = true
                            download.onDemand = download.jobs.any { it.pending.awaited }
                            null
                        }
                    }
                }
            if (gone != null) {
                for (job in gone) {
                    job.result.completeExceptionally(RenderDroppedException("${job.entry.path} left the library"))
                    job.finish()
                }
                continue
            }
            val fetched =
                try {
                    Result.success(fetch(download.entry, OriginalRequest(download.onDemand) { download.cancelled || closed }))
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            fetched
                .onSuccess { file -> fetchedFor(download, file) }
                .onFailure { e -> failedFor(download, e) }
        }
    }

    /** Queues the jobs that waited for [download] again with the fetched [file]; one that came after finds it there. */
    private fun fetchedFor(
        download: Download,
        file: File,
    ) {
        val jobs =
            synchronized(lock) {
                if (pendingDownloads[download.entry.id] === download) pendingDownloads.remove(download.entry.id)
                download.jobs.toList()
            }
        if (closed) {
            release(download.entry)
            jobs.forEach { it.result.cancel() }
            return
        }
        val left =
            java.util.concurrent.atomic
                .AtomicInteger(jobs.size)
        val done = { if (left.decrementAndGet() == 0) release(download.entry) }
        if (jobs.isEmpty()) release(download.entry)
        for (job in jobs) queue.add(job.withOriginal(seq.incrementAndGet(), file, done))
    }

    /**
     * The jobs of a failed [download]: a cancelled one hands those that are wanted again over as a new download, a
     * window download that found too little storage hands awaited ones over as a new download on demand, and the others
     * fail with the error or, if nobody wants them, are dropped.
     */
    private fun failedFor(
        download: Download,
        e: Throwable,
    ) {
        val storage = e is SourceException && e.kind == SourceException.Kind.STORAGE
        val failed = ArrayList<Job>()
        val dropped = ArrayList<Job>()
        var handedOver = 0
        synchronized(lock) {
            if (pendingDownloads[download.entry.id] === download) pendingDownloads.remove(download.entry.id)
            for (job in download.jobs) {
                val again =
                    when {
                        removed(job.entry) -> false
                        download.cancelled -> !droppable(job)
                        storage && !download.onDemand -> job.pending.awaited
                        else -> false
                    }
                when {
                    again && handOverLocked(job) -> {
                        handedOver++
                    }

                    download.cancelled || removed(job.entry) -> {
                        removeInFlight(job)
                        dropped += job
                    }

                    else -> {
                        removeInFlight(job)
                        failed += job
                    }
                }
            }
        }
        if (failed.isNotEmpty()) {
            log.w(TAG, "The original of ${download.entry.path} could not be fetched: ${e.message}")
        } else if (handedOver > 0 && !download.cancelled) {
            log.i(TAG, "The original of ${download.entry.path} is fetched again on demand: ${e.message}")
        }
        // Only the decoder crashing on a fetched original, in the check of its dimensions, quarantines it; a failed
        // download does not.
        if (e is ImageException && e.reason == SkipReason.CRASHED) {
            log.e(TAG, "${download.entry.path} made the decoder crash and is quarantined")
            onCrashed(download.entry)
        }
        val failure = if (e is Exception) e else RenderFailedException("Fetching ${download.entry.path} failed", e)
        for (job in failed) {
            job.result.completeExceptionally(failure)
            job.finish()
        }
        for (job in dropped) {
            job.result.completeExceptionally(RenderDroppedException("${job.entry.path} is no longer wanted"))
            job.finish()
        }
    }

    /**
     * Renders [job] into a `.tmp` file and renames it into place. The check for a removal, the rename and the entry in
     * the index are one critical section, so a [removeAll] cannot fall between them. Older versions of the photo and
     * what exceeds the budget go only after the new file is there: the display may be decoding one of them.
     */
    private fun render(
        job: Job,
        original: File,
    ): File {
        val target = File(dir, job.key)
        val tmp = File(dir, ".${job.key}.tmp")
        engine.render(original, tmp, job.target.size.width, job.target.size.height, job.target.region)
        val obsolete = ArrayList<File>()
        val outcome =
            synchronized(lock) {
                val names = byPhoto[job.entry.id].orEmpty()
                when {
                    removed(job.entry) -> {
                        "${job.entry.path} left the library while it was rendered"
                    }

                    // A late job of an older version: the newer one is on screen or about to be.
                    names.any { (items[it]?.version ?: 0) > job.entry.version } -> {
                        "${job.entry.path} has a newer version"
                    }

                    !tmp.renameTo(target) -> {
                        null
                    }

                    else -> {
                        add(job.key, sizeOf(target), clock.millis())
                        // Only older versions, and not the file on screen or next up: the display may decode it now.
                        names
                            .filter { (items[it]?.version ?: Int.MAX_VALUE) < job.entry.version && it !in keep }
                            .forEach { obsolete += discardLocked(it) }
                        obsolete += overBudgetLocked(job.key)
                        ""
                    }
                }
            }
        deleteNow(obsolete)
        when (outcome) {
            "" -> {
                return target
            }

            null -> {
                tmp.delete()
                throw IllegalStateException("Renaming to ${target.name} failed")
            }

            else -> {
                tmp.delete()
                throw RenderDroppedException(outcome)
            }
        }
    }

    /** Marks [name] as used; sets its file time when the last one set is more than an hour old (design decision 2). */
    private fun touch(name: String) {
        val now = clock.millis()
        val setTime =
            synchronized(lock) {
                val item = items[name] ?: return
                item.lastUse = ++uses
                val due = now - item.fileTime >= HOUR_MS || now < item.fileTime
                if (due) item.fileTime = now
                due
            }
        if (setTime) File(dir, name).setLastModified(now)
    }

    /** Under [lock] or in the constructor: [name] enters the index. */
    private fun add(
        name: String,
        size: Long,
        fileTime: Long,
    ) {
        val (photoId, version) = parse(name) ?: return
        forget(name)
        uses++
        items[name] = Item(photoId, version, uses, size, fileTime, uses)
        byPhoto.getOrPut(photoId) { HashSet() } += name
        total += size
    }

    /**
     * Under [lock]: [name] leaves the index to be deleted; until [deleted] it counts as being deleted, so that a
     * [rebuild] that lists the folder meanwhile does not take it back.
     */
    private fun discardLocked(name: String): File {
        forget(name)
        deleting += name
        return File(dir, name)
    }

    /** Deletes [files] that [discardLocked] took out of the index. */
    private fun deleteNow(files: List<File>) {
        if (files.isEmpty()) return
        files.forEach { it.delete() }
        synchronized(lock) {
            files.forEach { deleting -= it.name }
            deletedDuringRebuild?.let { set -> files.forEach { set += it.name } }
        }
    }

    /** Names deleted while a [rebuild] lists the folder, which it must not take back; `null` outside a rebuild. */
    private var deletedDuringRebuild: HashSet<String>? = null

    /** Under [lock]: [name] leaves the index. */
    private fun forget(name: String) {
        val item = items.remove(name) ?: return
        total -= item.size
        byPhoto[item.photoId]?.let { names ->
            names -= name
            if (names.isEmpty()) byPhoto.remove(item.photoId)
        }
    }

    /**
     * Reads the index from one listing again, after files went behind the cache's back. A name rendered while it lists
     * stays; the order of use of known names stays.
     */
    private fun rebuild() {
        val (since, known) =
            synchronized(lock) {
                deletedDuringRebuild = HashSet()
                uses to (items.keys + deleting)
            }
        val listed = listFolder(dir).orEmpty().filter { !it.name.startsWith('.') }
        val names = listed.mapTo(HashSet()) { it.name }
        // A file the index does not know is asked for its time and size outside the lock, once.
        val found = listed.filter { it.name !in known }.map { Triple(it.name, sizeOf(it), it.lastModified()) }
        synchronized(lock) {
            for (name in items.keys.toList()) {
                if (name !in names && items.getValue(name).since <= since) forget(name)
            }
            val deletedMeanwhile = deletedDuringRebuild.orEmpty()
            deletedDuringRebuild = null
            for ((name, size, time) in found) {
                // Not a file being deleted or deleted while the folder was listed, nor one of a photo that left the
                // library meanwhile.
                if (name in items || name in deleting || name in deletedMeanwhile) continue
                val (photoId, version) = parse(name) ?: continue
                if (tombstones[photoId]?.let { version <= it } == true) continue
                add(name, size, time)
            }
        }
        log.i(TAG, "Files of the render cache went behind its back; its index was read again")
    }

    private fun deleteLater(files: List<File>) {
        try {
            deleter.execute {
                beforeDelete()
                deleteNow(files)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Closed: the sweep at the next start removes them.
        }
    }

    /** Runs on the delete thread before each batch; a test holds the thread there. */
    @Volatile
    internal var beforeDelete: () -> Unit = {}

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

        /**
         * Photo ID and image version of a rendering's [name]: the ID is the part before the first `-v<digits>-`, which no
         * ID contains (`local-…`, `nextcloud-…` and `s<8 hex>-…`, each followed by a SHA-1 in hex). `null` for any
         * other name.
         */
        internal fun parse(name: String): Pair<String, Int>? {
            var from = 0
            while (true) {
                val mark = name.indexOf("-v", from)
                if (mark <= 0) return null
                var end = mark + 2
                while (end < name.length && name[end].isDigit()) end++
                if (end > mark + 2 && end < name.length && name[end] == '-') {
                    val version = name.substring(mark + 2, end).toIntOrNull() ?: return null
                    return name.substring(0, mark) to version
                }
                from = mark + 1
            }
        }

        private const val TAG = "render"

        /** Starts the key in the name of a display size; thumbnail and full size start with a digit there. */
        private const val DISPLAY_MARK = "p"
        private const val WORKER_STOP_TIMEOUT_MS = 10_000L
        private const val DOWNLOAD_STOP_TIMEOUT_MS = 1_000L
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val MIB = 1024L * 1024
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

/** A rendering that was not done on purpose: its photo left the library or the window of renderings ahead. */
class RenderDroppedException(
    message: String,
) : Exception(message)

/** A rendering ahead of time refused because less free storage than the reserve is left (design decision 2). */
class StorageShortException(
    message: String,
) : Exception(message)
