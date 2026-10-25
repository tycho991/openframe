package io.github.tycho991.openframe.core.sources

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.http.HttpException
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom

/**
 * How a source reaches its server; which one a source has depends on its kind. It holds the secrets, so [toString]
 * of each names the server only: no log line or error text can carry a password or a share token.
 */
@Serializable
sealed interface Connection {
    /** Base address of the server, without a trailing `/`, e.g. `https://cloud.example.org` or `http://nas/nextcloud`. */
    val serverUrl: String

    /** A Nextcloud account, connected with the login flow. */
    @Serializable
    @SerialName("account")
    data class Account(
        override val serverUrl: String,
        val userId: String,
        val loginName: String,
        val appPassword: String,
    ) : Connection {
        override fun toString() = "Account(server=$serverUrl, user=$loginName)"
    }

    /** The link of a Nextcloud folder share: the token is a capability and is kept like a password. */
    @Serializable
    @SerialName("share")
    data class Share(
        override val serverUrl: String,
        val token: String,
        /** Empty for a share without a password. */
        val password: String = "",
    ) : Connection {
        override fun toString() = "Share(server=$serverUrl)"
    }
}

/**
 * One source as it is stored in `sources.json`, including its secrets. It never leaves the frame: the API sees what
 * the routes take from it, never [connection] itself.
 */
@Serializable
data class StoredSource(
    /** The source part of the IDs of its photos: `nextcloud` for a migrated account, `s` plus hex digits for new ones. */
    val id: String,
    /** The ID of its [SourceKind]. */
    val kind: String,
    val name: String,
    val connection: Connection,
    val enabled: Boolean = true,
    /** Empty for an account until the user has chosen one: such a source syncs nothing. */
    val folder: String = "",
    val includeSubfolders: Boolean = true,
    val removeVanished: Boolean = true,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val originalsBudgetMb: Int = DEFAULT_BUDGET_MB,
) {
    /** Switched on and a folder chosen: only then the source syncs. */
    val ready: Boolean get() = enabled && folder.isNotEmpty()

    val budgetBytes: Long get() = originalsBudgetMb * 1024L * 1024L

    companion object {
        const val MAX_SOURCES = 8
        const val DEFAULT_INTERVAL_MINUTES = 20
        const val DEFAULT_BUDGET_MB = 2_000
        val INTERVAL_MINUTES = 5..1_440
        val BUDGET_MB = 100..50_000
        const val FOLDER_MAX_LENGTH = 1_024
        const val NAME_MAX_LENGTH = 64
    }
}

/** The content of `sources.json`. */
@Serializable
data class StoredSources(
    val version: Int = 1,
    val sources: List<StoredSource> = emptyList(),
)

/**
 * The configuration of all remote sources. Changes are validated completely and applied or refused as a whole,
 * visible at once as [sources] and stored through the [JsonStore].
 */
class SourceConfigStore(
    private val store: JsonStore<StoredSources>,
    private val random: SecureRandom = SecureRandom(),
) {
    private val _sources = MutableStateFlow<List<StoredSource>>(emptyList())

    /** Includes the secrets: for the sources and the API's rules only, never for a response. */
    val sources: StateFlow<List<StoredSource>> = _sources.asStateFlow()

    /** What [load] found. */
    enum class Loaded {
        /** `sources.json` was read. */
        READ,

        /** There is no `sources.json`: a new frame, or one that has only the `nextcloud.json` of 0.1.0. */
        MISSING,

        /** `sources.json` is there but could not be read; the [JsonStore] set it aside. The sources it held are gone. */
        UNREADABLE,
    }

    /** Reads `sources.json`. */
    fun load(): Loaded {
        val existed = store.exists()
        val stored = store.load()
        _sources.value = stored?.sources.orEmpty()
        return when {
            stored != null -> Loaded.READ
            existed -> Loaded.UNREADABLE
            else -> Loaded.MISSING
        }
    }

    fun get(id: String): StoredSource? = _sources.value.firstOrNull { it.id == id }

    /** An ID that no source has: `s` and eight hex digits. */
    @Synchronized
    fun newId(): String {
        while (true) {
            val id = "s" + ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            if (get(id) == null) return id
        }
    }

    /** Adds [source]. Throws a 409 `too_many_sources` if there are [StoredSource.MAX_SOURCES] already. */
    @Synchronized
    fun add(source: StoredSource): StoredSource {
        if (_sources.value.size >= StoredSource.MAX_SOURCES) throw tooMany()
        check(get(source.id) == null) { "A source ${source.id} exists already" }
        update(_sources.value + source)
        return source
    }

    /** Applies a partial change of the settings of [id]. Throws [ValidationException] with the errors per field, a 404 for an unknown ID. */
    @Synchronized
    fun patch(
        id: String,
        patch: JsonObject,
    ): StoredSource {
        val errors = LinkedHashMap<String, String>()
        var next = get(id) ?: throw HttpException.notFound("No such source")
        for ((key, value) in patch) {
            when (key) {
                "name" -> {
                    name(value, errors)?.let { next = next.copy(name = it) }
                }

                "enabled" -> {
                    bool(value, key, errors)?.let { next = next.copy(enabled = it) }
                }

                "includeSubfolders" -> {
                    bool(value, key, errors)?.let { next = next.copy(includeSubfolders = it) }
                }

                "removeVanished" -> {
                    bool(value, key, errors)?.let { next = next.copy(removeVanished = it) }
                }

                "folder" -> {
                    folder(value, errors)?.let { next = next.copy(folder = it) }
                }

                "intervalMinutes" -> {
                    int(value, key, StoredSource.INTERVAL_MINUTES, errors)?.let { next = next.copy(intervalMinutes = it) }
                }

                "originalsBudgetMb" -> {
                    int(value, key, StoredSource.BUDGET_MB, errors)?.let { next = next.copy(originalsBudgetMb = it) }
                }

                else -> {
                    errors[key] = "Unknown field"
                }
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        replace(next)
        return next
    }

    /** Replaces the connection of [id], for new credentials of the same account. `null` for an unknown ID. */
    @Synchronized
    fun reconnect(
        id: String,
        connection: Connection,
    ): StoredSource? {
        val next = get(id)?.copy(connection = connection) ?: return null
        replace(next)
        return next
    }

    /** Removes [id] with its secrets. `null` if there was none. */
    @Synchronized
    fun remove(id: String): StoredSource? {
        val gone = get(id) ?: return null
        update(_sources.value.filter { it.id != id })
        return gone
    }

    suspend fun flush() = store.flush()

    private fun replace(next: StoredSource) = update(_sources.value.map { if (it.id == next.id) next else it })

    private fun update(next: List<StoredSource>) {
        if (next == _sources.value) return
        _sources.value = next
        store.save(StoredSources(sources = next))
    }

    private fun bool(
        value: JsonElement,
        key: String,
        errors: MutableMap<String, String>,
    ): Boolean? =
        (value as? JsonPrimitive)?.booleanOrNull ?: run {
            errors[key] = "true or false"
            null
        }

    private fun int(
        value: JsonElement,
        key: String,
        range: IntRange,
        errors: MutableMap<String, String>,
    ): Int? {
        val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (n == null || n !in range) {
            errors[key] = "${range.first} to ${range.last}"
            return null
        }
        return n
    }

    private fun folder(
        value: JsonElement,
        errors: MutableMap<String, String>,
    ): String? {
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val normalized = raw?.let(::normalizeFolder)
        if (normalized == null) errors["folder"] = "A folder path such as /Photos"
        return normalized
    }

    private fun name(
        value: JsonElement,
        errors: MutableMap<String, String>,
    ): String? {
        val normalized = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::normalizeName)
        if (normalized == null) errors["name"] = "1 to ${StoredSource.NAME_MAX_LENGTH} characters"
        return normalized
    }

    companion object {
        fun tooMany() = HttpException(409, "too_many_sources", "The frame has ${StoredSource.MAX_SOURCES} sources already")

        /**
         * The folder as `/a/b` (`/` for the top), or `null` for something that is no folder path: control
         * characters, `.` or `..` segments, or more than [StoredSource.FOLDER_MAX_LENGTH] characters.
         */
        fun normalizeFolder(raw: String): String? {
            if (raw.length > StoredSource.FOLDER_MAX_LENGTH || raw.any { it < ' ' }) return null
            val segments = raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." }) return null
            return "/" + segments.joinToString("/")
        }

        /** The name trimmed, or `null` if it is empty, too long or has control characters. */
        fun normalizeName(raw: String): String? {
            val name = raw.trim()
            if (name.isEmpty() || name.length > StoredSource.NAME_MAX_LENGTH || name.any { it < ' ' }) return null
            return name
        }

        /**
         * The address of a server as it is stored: `http` or `https` (in any case, stored in lower case, as phone keyboards
         * like to capitalize the first letter), a host, no user, query or fragment, no trailing `/`.
         * Throws [ValidationException] for the field `serverUrl`.
         */
        fun normalizeServerUrl(raw: String): String {
            val text = raw.trim()
            val uri =
                try {
                    URI(text)
                } catch (e: URISyntaxException) {
                    null
                }
            val scheme = uri?.scheme?.lowercase()
            val ok =
                uri != null &&
                    (scheme == "http" || scheme == "https") &&
                    !uri.host.isNullOrEmpty() &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            if (!ok) throw ValidationException(mapOf("serverUrl" to "An address starting with http:// or https://"))
            return scheme + text.substring(scheme!!.length).trimEnd('/')
        }

        /** The host of [serverUrl] for a name or a log line, the address itself if it has none. */
        fun hostOf(serverUrl: String): String =
            try {
                URI(serverUrl).host ?: serverUrl
            } catch (e: URISyntaxException) {
                serverUrl
            }

        /**
         * The server as it is compared: host and path without the scheme, so that a server that moved from `http` to
         * `https` is the same one.
         */
        fun serverKey(serverUrl: String): String {
            val uri =
                try {
                    URI(serverUrl)
                } catch (e: URISyntaxException) {
                    return serverUrl.lowercase()
                }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return uri.host.orEmpty().lowercase() + port + uri.rawPath.orEmpty().trimEnd('/')
        }
    }
}
