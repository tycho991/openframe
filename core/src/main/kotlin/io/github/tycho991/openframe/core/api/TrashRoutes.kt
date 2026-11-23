package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.library.AlbumPaths
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.library.TrashItem
import io.github.tycho991.openframe.core.library.TrashStore
import io.github.tycho991.openframe.core.platform.Clock
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * `/api/v1/trash`: the list of deleted local photos, restoring them, and their thumbnails (design decisions 10 and 11
 * of `paired-device-guards`). Every paired device may list and restore; there is no way to empty the trash.
 */
fun Router.trashRoutes(
    library: PhotoLibrary,
    photos: PhotoDtos,
    clock: Clock,
    /** Safe mode: restoring and thumbnails answer 503 `safe_mode`, since both may decode. Listing works. */
    safeMode: () -> Boolean = { false },
) {
    val trash = library.trash

    fun storageUnavailable() = HttpException(503, "storage_unavailable", "The photo folder is not available; is the storage mounted?")

    fun dto(
        item: TrashItem,
        clientId: String,
    ): TrashEntryDto {
        val now = clock.millis()
        // Unknown while the clock is not yet set, or for an entry stamped by such a clock, which is re-stamped later.
        val plausible = now >= TrashStore.TRASH_CLOCK_FLOOR && item.deletedAtMs >= TrashStore.TRASH_CLOCK_FLOOR
        return TrashEntryDto(
            id = item.id,
            photoId = item.photoId,
            path = item.path,
            album = item.path?.let(AlbumPaths::parent),
            fileSize = item.bytes,
            deletedAt = Instant.ofEpochMilli(item.deletedAtMs).toString(),
            purgeAfter = if (plausible) Instant.ofEpochMilli(item.deletedAtMs + TrashStore.RETENTION_MS).toString() else null,
            deletedBy = item.record?.deletedBy,
            quarantined = item.quarantined,
            damaged = item.damaged,
            thumb = item.thumbEntry?.let { photos.trashThumbUrl(item.id, clientId) },
        )
    }

    get("/api/v1/trash") { req ->
        if (!library.local.available()) throw storageUnavailable()
        val items = trash.items()
        HttpResponse.json(TrashListDto.serializer(), TrashListDto(items.map { dto(it, req.clientId!!) }, items.sumOf { it.bytes }))
    }

    post("/api/v1/trash:restore") { req ->
        val body = req.bodyAs(TrashRestoreRequestDto.serializer())
        if (body.ids.isEmpty() || body.ids.size > 10_000) throw HttpException.badRequest("1 to 10000 IDs")
        if (safeMode()) throw safeModeRefusal()
        val result = library.restore(body.ids, req.clientId)
        HttpResponse.json(
            TrashRestoreResultDto.serializer(),
            TrashRestoreResultDto(result.restored.map { (id, photoId) -> RestoredDto(id, photoId) }, result.missing, result.failed),
        )
    }

    get("/api/v1/trash/{trashId}/thumb", Access.TOKEN_OR_IMAGE_SIGNATURE) { req ->
        if (safeMode()) throw safeModeRefusal()
        val item = trash.get(req.pathParam("trashId")) ?: throw HttpException.notFound("No such trash entry")
        // Never decoded: a quarantined or damaged entry, or one without its record, whose quarantine is unknown.
        val entry = item.thumbEntry ?: throw HttpException(503, "render_failed", "This trash entry has no thumbnail")
        val target = library.cache.target(entry, RenderSize.THUMB)
        val etag = "\"${target.name.removeSuffix(".jpg")}\""
        // An entry never changes; a re-stamp gives it a new ID and so a new URL.
        val headers = mapOf("ETag" to etag, "Cache-Control" to "private, max-age=31536000, immutable")
        if (req.header("if-none-match") == etag) return@get HttpResponse.Empty(304, headers)
        val file =
            try {
                library.cache.get(entry, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw HttpException(503, "render_failed", "The thumbnail could not be rendered")
            }
        HttpResponse.FileBody(200, file, "image/jpeg", headers)
    }
}
