package io.github.tycho991.openframe.core.slideshow

import io.github.tycho991.openframe.core.library.Library.Companion.DATE_ORDER
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.settings.Order
import kotlin.random.Random

/**
 * Reihenfolge der Slideshow. Bei `shuffle` erscheint jedes sichtbare Foto
 * einmal pro Durchgang, bevor sich eines wiederholt. Bei `chronological`
 * aufsteigend nach Aufnahmedatum. Nach dem letzten Foto beginnt ein neuer Durchgang.
 */
class Playlist(
    private val random: Random = Random.Default,
) {
    private var order = Order.SHUFFLE
    private var photos: Map<String, PhotoEntry> = emptyMap()

    /** IDs des aktuellen Durchgangs. */
    private var pass: MutableList<String> = mutableListOf()
    private var previousPass: List<String> = emptyList()
    private var pos = 0

    /** Das aktuelle Foto ist weggefallen und [pos] steht schon auf dem nächsten. */
    private var advanced = false

    val current: PhotoEntry? get() = pass.getOrNull(pos)?.let(photos::get)

    val size: Int get() = pass.size

    /**
     * Übernimmt die sichtbaren Fotos ([visible], nach Aufnahmedatum) und die
     * Reihenfolge. Liefert `true`, wenn das aktuelle Foto weggefallen ist.
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
                // Neue Fotos kommen an zufällige Stellen nach dem aktuellen, damit sie noch in diesem Durchgang erscheinen.
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

    /** Wechselt zum nächsten Foto und beginnt nach dem letzten einen neuen Durchgang. */
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

    /** Wechselt zum vorigen Foto, auch über den Anfang des Durchgangs hinweg. */
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

    /** Springt zum Foto [id], wenn es im aktuellen Durchgang ist. */
    fun moveTo(id: String): PhotoEntry? {
        val i = pass.indexOf(id)
        if (i < 0) return null
        pos = i
        advanced = false
        return current
    }

    /** Die nächsten [n] Fotos nach dem aktuellen, ohne zu wechseln. */
    fun upcoming(n: Int): List<PhotoEntry> {
        if (pass.isEmpty()) return emptyList()
        val result = ArrayList<PhotoEntry>(n)
        var i = pos + 1
        while (result.size < minOf(n, pass.size - 1)) {
            if (i >= pass.size) {
                // Im neuen Durchgang ist die Reihenfolge bei shuffle noch offen.
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
                // Weggefallen: weiter mit dem Foto, das in der Sortierung danach kommt.
                pass.indexOfFirst { DATE_ORDER.compare(photos.getValue(it), gone) > 0 }.takeIf { it >= 0 }
            } ?: 0
        if (order == Order.SHUFFLE && keep != null && keep in photos) {
            // Das aktuelle Foto steht am Anfang, damit der Durchgang ab hier vollständig ist.
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
            // Kein Foto zweimal hintereinander über die Grenze zweier Durchgänge.
            if (pass.size > 1 && pass.first() == last) {
                val swap = 1 + random.nextInt(pass.size - 1)
                pass[0] = pass[swap].also { pass[swap] = pass[0] }
            }
        }
        pos = 0
    }
}
