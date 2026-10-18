package io.github.tycho991.openframe.core.library

import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.settings.Orientation
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class RenderSize(
    val apiName: String,
) {
    THUMB("thumb"),
    DISPLAY("display"),
}

/** Wer auf ein Bild wartet. Höhere Priorität wird zuerst gerendert. */
enum class RenderPriority { PREFETCH, NEXT, DEMAND }

data class PixelSize(
    val width: Int,
    val height: Int,
)

/** Zielgrößen der gerenderten Bilder, als reine Funktionen testbar. */
object RenderSizes {
    const val THUMB_EDGE = 400
    const val MAX_PIXEL_FACTOR = 2.5

    /** Bildschirm in der eingestellten Ausrichtung, aus der natürlichen Auflösung des Geräts. */
    fun screen(
        naturalWidth: Int,
        naturalHeight: Int,
        orientation: Orientation,
    ): PixelSize {
        val long = max(naturalWidth, naturalHeight)
        val short = minOf(naturalWidth, naturalHeight)
        return if (orientation == Orientation.LANDSCAPE) PixelSize(long, short) else PixelSize(short, long)
    }

    /** Vorschaubild: längste Kante 400 px, nie größer als das Original. */
    fun thumb(
        width: Int,
        height: Int,
    ): PixelSize = scaled(width, height, minOf(1.0, THUMB_EDGE.toDouble() / max(width, height)))

    /**
     * Anzeigegröße: die kleinste Größe, die den Bildschirm vollständig bedeckt,
     * höchstens das 2,5-Fache seiner Pixelzahl und nie größer als das Original.
     */
    fun display(
        width: Int,
        height: Int,
        screen: PixelSize,
    ): PixelSize {
        var scale = max(screen.width.toDouble() / width, screen.height.toDouble() / height)
        val maxPixels = MAX_PIXEL_FACTOR * screen.width * screen.height
        val pixels = width * scale * height * scale
        if (pixels > maxPixels) scale *= sqrt(maxPixels / pixels)
        return scaled(width, height, minOf(1.0, scale))
    }

    private fun scaled(
        width: Int,
        height: Int,
        scale: Double,
    ) = PixelSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
}

/**
 * Cache der gerenderten Bildgrößen. Gerendert wird auf genau einem Worker-Thread
 * mit niedriger Priorität, Anfragen der Anzeige und der Web-UI vor dem Vorab-Rendern.
 * Der Cache hält das Budget ein, indem er die am längsten nicht genutzten Dateien
 * entfernt. Originale fasst er nie an.
 */
class RenderCache(
    private val dir: File,
    private val engine: MediaEngine,
    private val originalOf: (PhotoEntry) -> File,
    private val screen: () -> PixelSize,
    private val budgetBytes: () -> Long,
    private val log: AppLog,
) {
    private class Job(
        val priority: RenderPriority,
        val seq: Long,
        val key: String,
        val entry: PhotoEntry,
        val size: PixelSize,
        val result: CompletableDeferred<File>,
    )

    private val queue =
        PriorityBlockingQueue<Job>(16, compareByDescending<Job> { it.priority }.thenBy { it.seq })
    private val seq = AtomicLong()
    private val inFlight = HashMap<String, CompletableDeferred<File>>()

    /** Name → letzte Nutzung, für die LRU-Verdrängung. */
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

    fun targetSize(
        entry: PhotoEntry,
        size: RenderSize,
    ): PixelSize =
        when (size) {
            RenderSize.THUMB -> RenderSizes.thumb(entry.width, entry.height)
            RenderSize.DISPLAY -> RenderSizes.display(entry.width, entry.height, screen())
        }

    /** Die gerenderte Datei, bei Bedarf jetzt gerendert. Wirft, wenn das Rendern scheitert. */
    suspend fun get(
        entry: PhotoEntry,
        size: RenderSize,
        priority: RenderPriority = RenderPriority.DEMAND,
    ): File {
        peek(entry, size)?.let { return it }
        return enqueue(entry, targetSize(entry, size), priority).await()
    }

    /** Die gerenderte Datei, wenn es sie schon gibt, ohne zu rendern. */
    fun peek(
        entry: PhotoEntry,
        size: RenderSize,
    ): File? {
        val file = File(dir, fileName(entry, targetSize(entry, size)))
        if (!file.exists()) return null
        touch(file.name)
        return file
    }

    /** Irgendeine fertige Anzeigegröße dieser Bildversion, etwa noch die vom alten Format. */
    fun peekAnyDisplay(entry: PhotoEntry): File? {
        val prefix = "${entry.id}-v${entry.version}-"
        val thumb = fileName(entry, targetSize(entry, RenderSize.THUMB))
        val name =
            synchronized(lastUse) {
                lastUse.entries
                    .filter { it.key.startsWith(prefix) && it.key != thumb }
                    .maxByOrNull { it.value }
                    ?.key
            }
                ?: return null
        return File(dir, name).takeIf { it.exists() }?.also { touch(name) }
    }

    /** Rendert die Anzeigegrößen von [entries] vorab, sofern sie fehlen. */
    fun prefetch(
        entries: List<PhotoEntry>,
        priority: RenderPriority = RenderPriority.PREFETCH,
    ) {
        for (entry in entries) {
            val size = targetSize(entry, RenderSize.DISPLAY)
            if (!File(dir, fileName(entry, size)).exists()) enqueue(entry, size, priority)
        }
    }

    /** Entfernt alle gerenderten Größen eines Fotos, etwa nach dem Löschen. */
    fun remove(photoId: String) {
        deleteMatching { it.startsWith("$photoId-") }
    }

    /** Hält das Budget ein. [keep] bleibt in jedem Fall erhalten, etwa das gerade gerenderte Bild. */
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
        size: PixelSize,
        priority: RenderPriority,
    ): CompletableDeferred<File> {
        val key = fileName(entry, size)
        val deferred =
            synchronized(inFlight) {
                inFlight.getOrPut(key) { CompletableDeferred() }
            }
        queue.add(Job(priority, seq.incrementAndGet(), key, entry, size, deferred))
        return deferred
    }

    /** Hält den Worker an. Wartende Aufträge scheitern, laufende werden zu Ende gerendert. */
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
        // Ein Rendern kann das Interrupt-Flag schlucken, deshalb zusätzlich [closed].
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
                log.w(TAG, "Rendern von ${job.entry.path} fehlgeschlagen", e)
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
        engine.render(originalOf(job.entry), tmp, job.size.width, job.size.height)
        if (!tmp.renameTo(target)) throw IllegalStateException("Umbenennen nach ${target.name} fehlgeschlagen")
        // Ältere Bildversionen dieses Fotos werden nicht mehr gebraucht.
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

    private fun fileName(
        entry: PhotoEntry,
        size: PixelSize,
    ) = "${entry.id}-v${entry.version}-${size.width}x${size.height}.jpg"

    private companion object {
        const val TAG = "render"
        const val WORKER_STOP_TIMEOUT_MS = 10_000L
    }
}
