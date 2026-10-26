package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.auth.ImageSigner
import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.http.HttpRequest
import io.github.tycho991.openframe.core.http.HttpResponse
import io.github.tycho991.openframe.core.http.Router
import io.github.tycho991.openframe.core.library.AlbumPaths
import io.github.tycho991.openframe.core.library.Crop
import io.github.tycho991.openframe.core.library.Library
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.library.Presentation
import io.github.tycho991.openframe.core.library.RenderSize
import io.github.tycho991.openframe.core.settings.SettingsRepository
import io.github.tycho991.openframe.core.slideshow.TransitionGovernor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.util.Base64

@Serializable
data class ImageUrlsDto(
    val thumb: String,
    val display: String,
    val full: String,
)

@Serializable
data class PresentationDto(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val zoom: Double,
)

@Serializable
data class PhotoDto(
    val id: String,
    val source: String,
    val path: String,
    val album: String?,
    val hidden: Boolean,
    val visible: Boolean,
    val width: Int,
    val height: Int,
    val takenAt: String,
    val fileSize: Long,
    val version: Int,
    val crop: Crop?,
    val presentation: PresentationDto,
    val urls: ImageUrlsDto,
)

@Serializable
data class PhotoPageDto(
    val items: List<PhotoDto>,
    val total: Int,
    val nextCursor: String?,
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
    val overridesParent: Boolean,
    val cover: ImageUrlsDto?,
)

@Serializable
data class AlbumListDto(
    val albums: List<AlbumDto>,
)

@Serializable
data class AlbumPatchDto(
    val name: String,
    val hidden: Boolean,
)

@Serializable
data class StateDto(
    val libraryVersion: Long,
    val settingsVersion: Long,
    val transitionFallback: Boolean,
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
    transitions: TransitionGovernor,
) {
    val index = library.index

    // The display URL carries the key of the shown window, so it changes with section, fill mode and format.
    fun urls(
        entry: PhotoEntry,
        presentation: Presentation = library.cache.presentation(entry),
    ): ImageUrlsDto {
        val base = "/api/v1/photos/${entry.id}/image?v=${entry.version}&t=${signer.sign(entry.id)}"
        return ImageUrlsDto("$base&size=thumb", "$base&size=display&p=${presentation.key}", "$base&size=full")
    }

    fun dto(
        entry: PhotoEntry,
        lib: Library,
    ): PhotoDto {
        val p = library.cache.presentation(entry)
        return PhotoDto(
            id = entry.id,
            source = entry.source,
            path = entry.path,
            album = entry.album,
            hidden = entry.hidden,
            visible = lib.isVisible(entry),
            width = entry.width,
            height = entry.height,
            takenAt = Instant.ofEpochMilli(entry.takenAtMs).toString(),
            fileSize = entry.fileSize,
            version = entry.version,
            crop = entry.crop,
            presentation = PresentationDto(p.x, p.y, p.width, p.height, p.zoom),
            urls = urls(entry, p),
        )
    }

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
        val visible = req.booleanParam("visible")
        val album = req.queryParam("album")
        val subalbums = req.booleanParam("subalbums") ?: true
        val lib = index.library.value
        val filtered =
            sort.list(lib).filter {
                val inAlbum = album == null || (if (subalbums) AlbumPaths.inBranch(it.album, album) else it.album == album)
                inAlbum && (visible == null || lib.isVisible(it) == visible)
            }
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
        val change = photoPatch(req.bodyAs(JsonObject.serializer()))
        val entry = library.update(req.pathParam("photoId"), change)
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
                ?: throw HttpException.badRequest("size is thumb, display or full")
        val target = library.cache.target(entry, size)
        // The file name holds image version, size and, for the display size, the key of the window.
        val etag = "\"${target.name.removeSuffix(".jpg")}\""
        // Immutable only for the URL of what is current: an older display URL would otherwise keep its old part.
        val current = req.queryParam("v") == entry.version.toString() && req.queryParam("p") == target.key
        val cacheControl =
            if (current) {
                "private, max-age=31536000, immutable"
            } else {
                "private, no-cache"
            }
        val headers = mapOf("ETag" to etag, "Cache-Control" to cacheControl)
        if (req.header("if-none-match") == etag) return@get HttpResponse.Empty(304, headers)
        val file =
            try {
                library.cache.get(entry, target)
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

    fun albumList(): HttpResponse {
        val albums =
            index.library.value.albums.values
                .map { AlbumDto(it.name, it.count, it.hidden, it.overridesParent, urls(it.newest)) }
        return HttpResponse.json(AlbumListDto.serializer(), AlbumListDto(albums))
    }

    get("/api/v1/albums") { albumList() }

    // The album is in the body because its path contains `/`, and the answer is the whole
    // list because switching an album resets its sub-albums.
    patch("/api/v1/albums") { req ->
        val body = req.bodyAs(AlbumPatchDto.serializer())
        library.setAlbumHidden(body.name, body.hidden)
        albumList()
    }

    get("/api/v1/state") {
        HttpResponse.json(StateDto.serializer(), StateDto(index.library.value.version, settings.version.value, transitions.cut.value))
    }
}

private val BATCH_ACTIONS = setOf("hide", "show", "delete")

/**
 * The change of a `PhotoPatch`: `hidden` and `crop` are both optional, at least one is given, and `crop: null` removes
 * the section. Every field is checked before anything changes, so an invalid one rejects the whole patch.
 */
private fun photoPatch(body: JsonObject): (PhotoEntry) -> PhotoEntry {
    if (body.isEmpty()) throw HttpException.badRequest("hidden or crop is required")
    val errors = LinkedHashMap<String, String>()
    var hidden: Boolean? = null
    var crop: Crop? = null
    for ((key, value) in body) {
        when (key) {
            "hidden" -> {
                hidden = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (hidden == null) errors[key] = "Expected true or false"
            }

            "crop" -> {
                crop = cropOf(value, errors)
            }

            else -> {
                errors[key] = "Unknown field"
            }
        }
    }
    if (errors.isNotEmpty()) throw ValidationException(errors)
    val setsCrop = "crop" in body
    return { entry ->
        var next = entry
        hidden?.let { next = next.copy(hidden = it) }
        if (setsCrop) next = next.copy(crop = crop)
        next
    }
}

/** `null` for `crop: null` or an invalid section, whose errors go to [errors]. */
private fun cropOf(
    value: JsonElement,
    errors: MutableMap<String, String>,
): Crop? {
    if (value is JsonNull) return null
    val obj = value as? JsonObject ?: return null.also { errors["crop"] = "Expected an object with x, y and zoom, or null" }
    for (key in obj.keys - CROP_FIELDS) errors["crop.$key"] = "Unknown field"
    val numbers =
        CROP_FIELDS.associateWith { name ->
            (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull.also {
                if (it == null) errors["crop.$name"] = if (name in obj) "Expected a number" else "Required"
            }
        }
    val crop = Crop(numbers["x"] ?: return null, numbers["y"] ?: return null, numbers["zoom"] ?: return null)
    errors += crop.errors()
    return crop
}

private val CROP_FIELDS = listOf("x", "y", "zoom")

private fun HttpRequest.booleanParam(name: String): Boolean? =
    when (queryParam(name)) {
        null -> null
        "true" -> true
        "false" -> false
        else -> throw HttpException.badRequest("$name is true or false")
    }
