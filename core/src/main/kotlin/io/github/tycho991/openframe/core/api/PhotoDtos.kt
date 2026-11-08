package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.auth.ImageSigner
import io.github.tycho991.openframe.core.library.Library
import io.github.tycho991.openframe.core.library.PhotoEntry
import io.github.tycho991.openframe.core.library.PhotoLibrary
import io.github.tycho991.openframe.core.library.Presentation
import io.github.tycho991.openframe.core.library.RenderCache
import io.github.tycho991.openframe.core.places.PlaceIndex
import io.github.tycho991.openframe.core.platform.PlatformInfo
import io.github.tycho991.openframe.core.settings.SettingsRepository
import java.time.Instant

/** The `Photo` record of the API with its signed image URLs, for the photo list and the slideshow alike. */
class PhotoDtos(
    private val library: PhotoLibrary,
    private val settings: SettingsRepository,
    private val signer: ImageSigner,
    private val places: PlaceIndex,
    private val platform: PlatformInfo,
) {
    // The display URL carries the key of the shown window, so it changes with section, fill mode and format.
    fun urls(
        entry: PhotoEntry,
        presentation: Presentation = library.cache.presentation(entry),
    ): ImageUrlsDto {
        val base = "/api/v1/photos/${entry.id}/image?v=${entry.version}&t=${signer.sign(entry.id)}"
        return ImageUrlsDto("$base&size=thumb", "$base&size=display&p=${RenderCache.displayKey(presentation)}", "$base&size=full")
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
            quarantined = entry.quarantined,
            width = entry.width,
            height = entry.height,
            takenAt = Instant.ofEpochMilli(entry.takenAtMs).toString(),
            fileSize = entry.fileSize,
            version = entry.version,
            crop = entry.crop,
            presentation = PresentationDto(p.x, p.y, p.width, p.height, p.zoom),
            urls = urls(entry, p),
            comment = entry.comment,
            location =
                entry.gps?.let {
                    LocationDto(
                        it.lat,
                        it.lon,
                        places.placeName(
                            it.lat,
                            it.lon,
                            settings.settings.value.language
                                .locale(platform),
                        ),
                    )
                },
        )
    }

    /** The record of [entry] as the library has it now, so that an edited comment shows at once; [entry] itself if it is gone. */
    fun current(entry: PhotoEntry): PhotoDto {
        val lib = library.index.library.value
        return dto(lib.photos[entry.id] ?: entry, lib)
    }
}
