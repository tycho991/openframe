package io.github.tycho991.openframe.core.nextcloud

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URISyntaxException

/**
 * What is stored in `nextcloud.json`, including the app password. It never leaves the frame: the API sees only
 * [NextcloudConfig], and [toString] hides the password so that no log line or error text can carry it.
 */
@Serializable
data class StoredNextcloud(
    /** Base address of the server, without a trailing `/`, e.g. `https://cloud.example.org` or `http://nas/nextcloud`. */
    val serverUrl: String = "",
    val userId: String = "",
    val loginName: String = "",
    val appPassword: String = "",
    val enabled: Boolean = true,
    val folder: String = "/",
    val includeSubfolders: Boolean = true,
    val removeVanished: Boolean = true,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val originalsBudgetMb: Int = DEFAULT_BUDGET_MB,
) {
    val connected: Boolean get() = serverUrl.isNotEmpty() && appPassword.isNotEmpty()

    override fun toString() = "StoredNextcloud(server=$serverUrl, user=$loginName, connected=$connected, enabled=$enabled, folder=$folder)"

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 20
        const val DEFAULT_BUDGET_MB = 2_000
        val INTERVAL_MINUTES = 5..1_440
        val BUDGET_MB = 100..50_000
        const val FOLDER_MAX_LENGTH = 1_024
    }
}

/** The configuration as the API shows it: everything but the password and the user ID. */
data class NextcloudConfig(
    val connected: Boolean,
    val serverUrl: String?,
    val user: String?,
    val enabled: Boolean,
    val folder: String,
    val includeSubfolders: Boolean,
    val removeVanished: Boolean,
    val intervalMinutes: Int,
    val originalsBudgetMb: Int,
)

/**
 * The configuration of the Nextcloud source. Changes are validated completely and applied or refused as a whole,
 * visible at once as [config] and stored through the [JsonStore].
 */
class NextcloudConfigStore(
    private val store: JsonStore<StoredNextcloud>,
) {
    private val _config = MutableStateFlow(StoredNextcloud())

    /** Includes the password: for the client of the source only, never for the API. */
    val config: StateFlow<StoredNextcloud> = _config.asStateFlow()

    fun load() {
        _config.value = store.load() ?: StoredNextcloud()
    }

    fun view(): NextcloudConfig = _config.value.let { toView(it) }

    /** Applies a partial change. Throws [ValidationException] with the errors per field. */
    @Synchronized
    fun patch(patch: JsonObject): NextcloudConfig {
        val errors = LinkedHashMap<String, String>()
        var next = _config.value
        for ((key, value) in patch) {
            when (key) {
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
                    int(value, key, StoredNextcloud.INTERVAL_MINUTES, errors)?.let { next = next.copy(intervalMinutes = it) }
                }

                "originalsBudgetMb" -> {
                    int(value, key, StoredNextcloud.BUDGET_MB, errors)?.let { next = next.copy(originalsBudgetMb = it) }
                }

                else -> {
                    errors[key] = "Unknown field"
                }
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        update(next)
        return toView(next)
    }

    /** Stores the result of a login. The options stay as they are. */
    @Synchronized
    fun connect(
        serverUrl: String,
        userId: String,
        loginName: String,
        appPassword: String,
    ) = update(_config.value.copy(serverUrl = serverUrl, userId = userId, loginName = loginName, appPassword = appPassword, enabled = true))

    /** Forgets server and credentials, keeps the options. */
    @Synchronized
    fun disconnect() = update(_config.value.copy(serverUrl = "", userId = "", loginName = "", appPassword = ""))

    suspend fun flush() = store.flush()

    private fun update(next: StoredNextcloud) {
        if (next == _config.value) return
        _config.value = next
        store.save(next)
    }

    private fun toView(c: StoredNextcloud) =
        NextcloudConfig(
            connected = c.connected,
            serverUrl = c.serverUrl.ifEmpty { null },
            user = c.loginName.ifEmpty { null },
            enabled = c.enabled,
            folder = c.folder,
            includeSubfolders = c.includeSubfolders,
            removeVanished = c.removeVanished,
            intervalMinutes = c.intervalMinutes,
            originalsBudgetMb = c.originalsBudgetMb,
        )

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

    companion object {
        /**
         * The folder as `/a/b` (`/` for the top), or `null` for something that is no folder path: control
         * characters, `.` or `..` segments, or more than [StoredNextcloud.FOLDER_MAX_LENGTH] characters.
         */
        fun normalizeFolder(raw: String): String? {
            if (raw.length > StoredNextcloud.FOLDER_MAX_LENGTH || raw.any { it < ' ' }) return null
            val segments = raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." }) return null
            return "/" + segments.joinToString("/")
        }

        /**
         * The address of a server as it is stored: `http` or `https`, a host, no user, query or fragment, no trailing `/`.
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
            val ok =
                uri != null &&
                    (uri.scheme == "http" || uri.scheme == "https") &&
                    !uri.host.isNullOrEmpty() &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            if (!ok) throw ValidationException(mapOf("serverUrl" to "An address starting with http:// or https://"))
            return text.trimEnd('/')
        }
    }
}
