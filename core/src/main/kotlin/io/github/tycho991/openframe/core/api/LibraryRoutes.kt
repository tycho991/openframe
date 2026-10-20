package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.auth.ImageSigner
import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.library.Library
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64

@Serializable
data class ImageUrlsDto(
    val thumb: String,
    val display: String,
)

@Serializable
data class PhotoDto(
    val id: String,
    val path: String,
    val album: String?,
    val hidden: Boolean,
    val visible: Boolean,
    val width: Int,
    val height: Int,
    val takenAt: String,
    val fileSize: Long,
    val version: Int,
    val urls: ImageUrlsDto,
)

@Serializable
data class PhotoPageDto(
    val items: List<PhotoDto>,
    val total: Int,
    val nextCursor: String?,
)

@Serializable
data class PhotoPatchDto(
    val hidden: Boolean,
)

@Serializable
data class UploadResultDto(
    val photos: List<PhotoDto>,
)

@Serializable
data class BatchRequestDto(
    val action: String,
    val ids: List<String>,
)

@Serializable
data class BatchResultDto(
    val processed: Int,
    val missing: List<String>,
    val failed: List<String>,
)

@Serializable
data class SkippedFileDto(
    val path: String,
    val reason: String,
    val fileSize: Long,
    val modifiedAt: String,
)

@Serializable
data class SkippedFileListDto(
    val items: List<SkippedFileDto>,
)

@Serializable
data class AlbumDto(
    val name: String,
    val count: Int,
    val hidden: Boolean,
    val cover: ImageUrlsDto?,
)

@Serializable
data class AlbumListDto(
    val albums: List<AlbumDto>,
)

@Serializable
data class AlbumPatchDto(
    val hidden: Boolean,
)

@Serializable
data class StateDto(
    val libraryVersion: Long,
    val settingsVersion: Long,
)

/** Position in a sorted list: key of the last entry of the previous page. */
@Serializable
private data class Cursor(
    val t: Long,
    val p: String,
    val i: String,
)

private enum class Sort(
    val apiName: String,
) {
    DATE_DESC("date_desc"),
    DATE_ASC("date_asc"),
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc"),
    ;

    fun comparator(): Comparator<PhotoEntry> =
        when (this) {
            DATE_ASC -> Library.DATE_ORDER
            DATE_DESC -> Library.DATE_ORDER.reversed()
            NAME_ASC -> Library.NAME_ORDER
            NAME_DESC -> Library.NAME_ORDER.reversed()
        }

    fun list(library: Library): List<PhotoEntry> =
        when (this) {
            DATE_ASC -> library.byDate
            DATE_DESC -> library.byDate.asReversed()
            NAME_ASC -> library.byName
            NAME_DESC -> library.byName.asReversed()
        }
}

/** Photos, albums, upload, image sizes, skipped files and state. */
fun Router.libraryRoutes(
    library: PhotoLibrary,
    settings: SettingsRepository,
    signer: ImageSigner,
) {
    val index = library.index

    fun urls(entry: PhotoEntry): ImageUrlsDto {
        val base = "/api/v1/photos/${entry.id}/image?v=${entry.version}&t=${signer.sign(entry.id)}"
        return ImageUrlsDto("$base&size=thumb", "$base&size=display")
    }

    fun dto(
        entry: PhotoEntry,
        lib: Library,
    ) = PhotoDto(
        id = entry.id,
        path = entry.path,
        album = entry.album,
        hidden = entry.hidden,
        visible = lib.isVisible(entry),
        width = entry.width,
        height = entry.height,
        takenAt = Instant.ofEpochMilli(entry.takenAtMs).toString(),
        fileSize = entry.fileSize,
        version = entry.version,
        urls = urls(entry),
    )

    fun entryOrThrow(id: String) = index.library.value.photos[id] ?: throw HttpException.notFound("No such photo")

    get("/api/v1/photos") { req ->
        val sort =
            req.queryParam("sort")?.let { s ->
                Sort.entries.firstOrNull { it.apiName == s }
                    ?: throw HttpException.badRequest("Unknown sort order $s")
            }
                ?: Sort.DATE_DESC
        val limit =
            req.queryParam("limit")?.let {
                it.toIntOrNull()?.takeIf { n -> n in 1..500 }
                    ?: throw HttpException.badRequest("limit 1 to 500")
            }
                ?: 100
        val visible =
            when (req.queryParam("visible")) {
                null -> null
                "true" -> true
                "false" -> false
                else -> throw HttpException.badRequest("visible is true or false")
            }
        val album = req.queryParam("album")
        val lib = index.library.value
        val filtered = sort.list(lib).filter { (album == null || it.album == album) && (visible == null || lib.isVisible(it) == visible) }
        val start =
            req.queryParam("cursor")?.let { raw ->
                val c =
                    runCatching { Json.decodeFromString(Cursor.serializer(), String(Base64.getUrlDecoder().decode(raw))) }
                        .getOrElse { throw HttpException.badRequest("Invalid cursor") }
                val probe = PhotoEntry(c.i, "", c.p, 1, 1, c.t, 0, 0, 0)
                val pos = filtered.binarySearch(probe, sort.comparator())
                if (pos >= 0) pos + 1 else -pos - 1
            } ?: 0
        val page = filtered.subList(start.coerceAtMost(filtered.size), (start + limit).coerceAtMost(filtered.size))
        val next =
            if (start + limit < filtered.size) {
                val last = page.last()
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    Json.encodeToString(Cursor.serializer(), Cursor(last.takenAtMs, last.path, last.id)).toByteArray(),
                )
            } else {
                null
            }
        HttpResponse.json(PhotoPageDto.serializer(), PhotoPageDto(page.map { dto(it, lib) }, filtered.size, next))
    }

    post("/api/v1/photos") { req ->
        val entries = library.upload(req)
        val lib = index.library.value
        HttpResponse.json(UploadResultDto.serializer(), UploadResultDto(entries.map { dto(it, lib) }), 201)
    }

    post("/api/v1/photos:batch") { req ->
        val body = req.bodyAs(BatchRequestDto.serializer())
        if (body.ids.isEmpty() || body.ids.size > 10_000) throw HttpException.badRequest("1 to 10000 IDs")
        if (body.action !in BATCH_ACTIONS) throw HttpException.badRequest("action is hide, show or delete")
        val missing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var processed = 0
        for (id in body.ids.distinct()) {
            // One photo that cannot be processed must not stop the rest, so every ID ends in exactly one list.
            try {
                val found =
                    if (body.action == "delete") {
                        library.delete(id)
                    } else {
                        try {
                            library.setHidden(id, body.action == "hide")
                            true
                        } catch (e: HttpException) {
                            if (e.status == 404) false else throw e
                        }
                    }
                if (found) processed++ else missing += id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += id
            }
        }
        HttpResponse.json(BatchResultDto.serializer(), BatchResultDto(processed, missing, failed))
    }

    patch("/api/v1/photos/{photoId}") { req ->
        val body = req.bodyAs(PhotoPatchDto.serializer())
        val entry = library.setHidden(req.pathParam("photoId"), body.hidden)
        HttpResponse.json(PhotoDto.serializer(), dto(entry, index.library.value))
    }

    delete("/api/v1/photos/{photoId}") { req ->
        if (!library.delete(req.pathParam("photoId"))) throw HttpException.notFound("No such photo")
        HttpResponse.noContent()
    }

    get("/api/v1/photos/{photoId}/image", Access.TOKEN_OR_IMAGE_SIGNATURE) { req ->
        val entry = entryOrThrow(req.pathParam("photoId"))
        val size =
            RenderSize.entries.firstOrNull { it.apiName == req.queryParam("size") }
                ?: throw HttpException.badRequest("size is thumb or display")
        val target = library.cache.targetSize(entry, size)
        val etag = "\"${entry.id}-${entry.version}-${target.width}x${target.height}\""
        val cacheControl =
            if (req.queryParam("v") ==
                entry.version.toString()
            ) {
                "private, max-age=31536000, immutable"
            } else {
                "private, no-cache"
            }
        val headers = mapOf("ETag" to etag, "Cache-Control" to cacheControl)
        if (req.header("if-none-match") == etag) return@get HttpResponse.Empty(304, headers)
        val file =
            try {
                library.cache.get(entry, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw HttpException(503, "render_failed", "The image could not be rendered")
            }
        HttpResponse.FileBody(200, file, "image/jpeg", headers)
    }

    get("/api/v1/skipped-files") {
        val items =
            index.library.value.skipped.map {
                SkippedFileDto(it.path, it.reason.apiName, it.fileSize, Instant.ofEpochMilli(it.modifiedMs).toString())
            }
        HttpResponse.json(SkippedFileListDto.serializer(), SkippedFileListDto(items))
    }

    get("/api/v1/albums") {
        val lib = index.library.value
        val albums =
            lib.photos.values
                .filter { it.album != null }
                .groupBy { it.album!! }
                .map { (name, photos) ->
                    AlbumDto(name, photos.size, name in lib.hiddenAlbums, urls(photos.maxWith(Library.DATE_ORDER)))
                }.sortedBy { it.name.lowercase() }
        HttpResponse.json(AlbumListDto.serializer(), AlbumListDto(albums))
    }

    patch("/api/v1/albums/{albumName}") { req ->
        val name = req.pathParam("albumName")
        val body = req.bodyAs(AlbumPatchDto.serializer())
        library.setAlbumHidden(name, body.hidden)
        val lib = index.library.value
        val photos = lib.photos.values.filter { it.album == name }
        HttpResponse.json(
            AlbumDto.serializer(),
            AlbumDto(name, photos.size, name in lib.hiddenAlbums, photos.maxWithOrNull(Library.DATE_ORDER)?.let(::urls)),
        )
    }

    get("/api/v1/state") {
        HttpResponse.json(StateDto.serializer(), StateDto(index.library.value.version, settings.version.value))
    }
}

private val BATCH_ACTIONS = setOf("hide", "show", "delete")
