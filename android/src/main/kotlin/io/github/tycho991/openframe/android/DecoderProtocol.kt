package io.github.tycho991.openframe.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import io.github.tycho991.openframe.core.platform.GeoPoint
import io.github.tycho991.openframe.core.platform.ImageException
import io.github.tycho991.openframe.core.platform.ImageFormat
import io.github.tycho991.openframe.core.platform.ImageInfo
import io.github.tycho991.openframe.core.platform.ImageMetadata
import io.github.tycho991.openframe.core.platform.MediaEngine
import io.github.tycho991.openframe.core.platform.Region
import io.github.tycho991.openframe.core.platform.SkipReason
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The raw binder protocol between [RemoteMediaEngine] in the app process and [DecoderService] in the process `:decode`
 * (design decision 1 of `crash-resilience`): one transaction code per call, arguments and results in the [Parcel]. A
 * reply starts with a status; an error carries what the caller throws again, so only a crash or a kill of the decoder
 * process reaches the caller as a `DeadObjectException`.
 */
internal object DecoderProtocol {
    const val PROBE = IBinder.FIRST_CALL_TRANSACTION
    const val READ_METADATA = IBinder.FIRST_CALL_TRANSACTION + 1
    const val RENDER = IBinder.FIRST_CALL_TRANSACTION + 2
    const val PID = IBinder.FIRST_CALL_TRANSACTION + 3

    private const val OK = 0
    private const val IMAGE_ERROR = 1
    private const val IO_ERROR = 2

    fun probe(
        binder: IBinder,
        file: File,
    ): ImageInfo = call(binder, PROBE, { writeString(file.path) }) { readInfo() }

    fun readMetadata(
        binder: IBinder,
        file: File,
    ): ImageMetadata = call(binder, READ_METADATA, { writeString(file.path) }) { ImageMetadata(readGps()) }

    fun render(
        binder: IBinder,
        source: File,
        target: File,
        width: Int,
        height: Int,
        region: Region,
        quality: Int,
    ) = call(binder, RENDER, {
        writeString(source.path)
        writeString(target.path)
        writeInt(width)
        writeInt(height)
        writeDouble(region.x)
        writeDouble(region.y)
        writeDouble(region.width)
        writeDouble(region.height)
        writeInt(quality)
    }) { }

    fun pid(binder: IBinder): Int = call(binder, PID, {}) { readInt() }

    private inline fun <T> call(
        binder: IBinder,
        code: Int,
        write: Parcel.() -> Unit,
        read: Parcel.() -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.write()
            binder.transact(code, data, reply, 0)
            return when (reply.readInt()) {
                OK -> {
                    reply.read()
                }

                IMAGE_ERROR -> {
                    val reason = reply.readString()
                    throw ImageException(
                        SkipReason.entries.firstOrNull { it.name == reason } ?: SkipReason.CORRUPT,
                        reply.readString() ?: "",
                    )
                }

                else -> {
                    throw IOException(reply.readString())
                }
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Answers one call with [engine], in the decoder process. Every [Throwable] of the call, an [OutOfMemoryError]
     * included, becomes a reply, so that only a native crash or a kill ends the process. `false` for an unknown code.
     */
    fun dispatch(
        engine: MediaEngine,
        code: Int,
        data: Parcel,
        reply: Parcel,
    ): Boolean {
        if (code !in PROBE..PID) return false
        try {
            when (code) {
                PROBE -> {
                    val info = engine.probe(File(data.readString()!!))
                    reply.writeInt(OK)
                    reply.writeInfo(info)
                }

                READ_METADATA -> {
                    val metadata = engine.readMetadata(File(data.readString()!!))
                    reply.writeInt(OK)
                    reply.writeGps(metadata.gps)
                }

                RENDER -> {
                    val source = File(data.readString()!!)
                    val target = File(data.readString()!!)
                    val width = data.readInt()
                    val height = data.readInt()
                    val region = Region(data.readDouble(), data.readDouble(), data.readDouble(), data.readDouble())
                    engine.render(source, target, width, height, region, data.readInt())
                    reply.writeInt(OK)
                }

                PID -> {
                    reply.writeInt(OK)
                    reply.writeInt(Process.myPid())
                }
            }
        } catch (e: ImageException) {
            reply.setDataPosition(0)
            reply.writeInt(IMAGE_ERROR)
            reply.writeString(e.reason.name)
            reply.writeString(e.message)
        } catch (e: Throwable) {
            reply.setDataPosition(0)
            reply.writeInt(IO_ERROR)
            reply.writeString("The decoder failed: $e")
        }
        return true
    }

    private fun Parcel.writeInfo(info: ImageInfo) {
        writeString(info.format.name)
        writeInt(info.width)
        writeInt(info.height)
        writeInt(if (info.takenAt != null) 1 else 0)
        info.takenAt?.let {
            writeLong(it.epochSecond)
            writeInt(it.nano)
        }
        writeGps(info.gps)
    }

    private fun Parcel.readInfo(): ImageInfo {
        val format = ImageFormat.valueOf(readString()!!)
        val width = readInt()
        val height = readInt()
        val takenAt = if (readInt() == 1) Instant.ofEpochSecond(readLong(), readInt().toLong()) else null
        return ImageInfo(format, width, height, takenAt, readGps())
    }

    private fun Parcel.writeGps(gps: GeoPoint?) {
        writeInt(if (gps != null) 1 else 0)
        gps?.let {
            writeDouble(it.lat)
            writeDouble(it.lon)
        }
    }

    private fun Parcel.readGps(): GeoPoint? = if (readInt() == 1) GeoPoint(readDouble(), readDouble()) else null
}

/** The binder of the decoder: [DecoderProtocol.dispatch] with [engine]. Also used in-process by tests. */
internal open class DecoderBinder(
    private val engine: MediaEngine,
) : Binder() {
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (reply != null && DecoderProtocol.dispatch(engine, code, data, reply)) return true
        return super.onTransact(code, data, reply, flags)
    }
}
