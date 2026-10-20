package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.Library.Companion.DATE_ORDER
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.Order
import kotlin.random.Random

/**
 * Order of the slideshow. With `shuffle` every visible photo appears
 * once per pass before any repeats. With `chronological`
 * ascending by capture date. After the last photo a new pass begins.
 */
class Playlist(
    private val random: Random = Random.Default,
) {
    private var order = Order.SHUFFLE
    private var photos: Map<String, PhotoEntry> = emptyMap()

    /** IDs of the current pass. */
    private var pass: MutableList<String> = mutableListOf()
    private var previousPass: List<String> = emptyList()
    private var pos = 0

    /** The current photo has dropped out and [pos] already points to the next one. */
    private var advanced = false

    val current: PhotoEntry? get() = pass.getOrNull(pos)?.let(photos::get)

    val size: Int get() = pass.size

    /**
     * Takes over the visible photos ([visible], by capture date) and the
     * order. Returns `true` if the current photo has dropped out.
     */
    fun update(
        visible: List<PhotoEntry>,
        order: Order,
    ): Boolean {
        val before = pass.getOrNull(pos)
        val beforeEntry = before?.let(photos::get)
        photos = visible.associateBy { it.id }
        if (order != this.order || pass.isEmpty()) {
            this.order = order
            rebuild(keep = before, keepEntry = beforeEntry)
            return (before != null && before !in photos).also { if (it) advanced = true }
        }
        when (order) {
            Order.CHRONOLOGICAL -> {
                rebuild(keep = before, keepEntry = beforeEntry)
            }

            Order.SHUFFLE -> {
                val removedBeforePos = pass.take(pos).count { it !in photos }
                pass.retainAll { it in photos }
                pos -= removedBeforePos
                val known = pass.toHashSet()
                // New photos go to random positions after the current one so that they still appear in this pass.
                for (id in visible.map { it.id }.filter { it !in known }) {
                    val from = (pos + 1).coerceAtMost(pass.size)
                    pass.add(from + random.nextInt(pass.size - from + 1), id)
                }
                previousPass = previousPass.filter { it in photos }
            }
        }
        if (pos >= pass.size) pos = 0
        return (before != null && before !in photos).also { if (it) advanced = true }
    }

    /** Moves to the next photo and begins a new pass after the last one. */
    fun next(): PhotoEntry? {
        if (pass.isEmpty()) return null
        if (advanced) {
            advanced = false
            return current
        }
        pos++
        if (pos >= pass.size) newPass()
        return current
    }

    /** Moves to the previous photo, also across the start of the pass. */
    fun previous(): PhotoEntry? {
        if (pass.isEmpty()) return null
        advanced = false
        pos--
        if (pos < 0) {
            when {
                order == Order.CHRONOLOGICAL -> {
                    pos = pass.size - 1
                }

                previousPass.size == pass.size && previousPass.toSet() == pass.toSet() -> {
                    previousPass = pass.toList().also { pass = previousPass.toMutableList() }
                    pos = pass.size - 1
                }

                else -> {
                    pos = 0
                }
            }
        }
        return current
    }

    /** Jumps to the photo [id] if it is in the current pass. */
    fun moveTo(id: String): PhotoEntry? {
        val i = pass.indexOf(id)
        if (i < 0) return null
        pos = i
        advanced = false
        return current
    }

    /** The next [n] photos after the current one, without moving. */
    fun upcoming(n: Int): List<PhotoEntry> {
        if (pass.isEmpty()) return emptyList()
        val result = ArrayList<PhotoEntry>(n)
        var i = pos + 1
        while (result.size < minOf(n, pass.size - 1)) {
            if (i >= pass.size) {
                // In the new pass the order is still open with shuffle.
                if (order == Order.SHUFFLE) break
                i = 0
            }
            photos[pass[i]]?.let(result::add)
            i++
        }
        return result
    }

    private fun rebuild(
        keep: String?,
        keepEntry: PhotoEntry?,
    ) {
        val ids = photos.keys.toList()
        pass =
            when (order) {
                Order.CHRONOLOGICAL -> photos.values.sortedWith(DATE_ORDER).map { it.id }
                Order.SHUFFLE -> ids.shuffled(random)
            }.toMutableList()
        previousPass = emptyList()
        pos = keep?.let { pass.indexOf(it) }?.takeIf { it >= 0 }
            ?: keepEntry?.let { gone ->
                // Dropped out: continue with the photo that comes after it in the sort order.
                pass.indexOfFirst { DATE_ORDER.compare(photos.getValue(it), gone) > 0 }.takeIf { it >= 0 }
            } ?: 0
        if (order == Order.SHUFFLE && keep != null && keep in photos) {
            // The current photo is at the start so that the pass is complete from here on.
            pass.remove(keep)
            pass.add(0, keep)
            pos = 0
        }
    }

    private fun newPass() {
        previousPass = pass.toList()
        if (order == Order.SHUFFLE) {
            val last = pass.lastOrNull()
            pass.shuffle(random)
            // No photo twice in a row across the boundary of two passes.
            if (pass.size > 1 && pass.first() == last) {
                val swap = 1 + random.nextInt(pass.size - 1)
                pass[0] = pass[swap].also { pass[swap] = pass[0] }
            }
        }
        pos = 0
    }
}
