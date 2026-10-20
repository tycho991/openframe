package io.github.tycho991.openframe.core.settings

import io.github.tycho991.openframe.core.ValidationException
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.log.AppLog
import io.github.tycho991.openframe.core.store.JsonStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Stored form: values as JSON so that new settings get their default. */
@Serializable
data class StoredSettings(
    val values: JsonObject = JsonObject(emptyMap()),
    val localOnly: Boolean = false,
    val version: Long = 0,
)

/**
 * Holds the frame's settings. Changes are validated completely and only
 * then applied, immediately visible as [settings] and stored in batches.
 */
class SettingsRepository(
    private val store: JsonStore<StoredSettings>,
    model: String,
    private val log: AppLog,
) {
    private val defaults = Settings.defaults(model)
    private val _settings = MutableStateFlow(defaults)
    private val _version = MutableStateFlow(0L)
    private val _localOnly = MutableStateFlow(false)

    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Increases with every change, also across restarts. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** "Local only" mode. Switchable only on the frame, not part of the API. */
    val localOnly: StateFlow<Boolean> = _localOnly.asStateFlow()

    fun load() {
        val stored = store.load() ?: StoredSettings()
        val result = SettingsPatch.apply(defaults, stored.values)
        if (result.errors.isNotEmpty()) {
            log.w(TAG, "Stored settings partially invalid, default applies for: ${result.errors.keys.joinToString()}")
        }
        _settings.value = result.settings
        _version.value = stored.version
        _localOnly.value = stored.localOnly
    }

    /** Applies [patch] completely or not at all. Throws [ValidationException] with errors per field. */
    @Synchronized
    fun patch(patch: JsonObject): Settings {
        val result = SettingsPatch.apply(_settings.value, patch)
        if (result.errors.isNotEmpty()) throw ValidationException(result.errors)
        if (result.settings != _settings.value) {
            _settings.value = result.settings
            _version.value += 1
            persist()
        }
        return result.settings
    }

    @Synchronized
    fun setLocalOnly(enabled: Boolean) {
        if (_localOnly.value == enabled) return
        _localOnly.value = enabled
        persist()
    }

    suspend fun flush() = store.flush()

    private fun persist() {
        val values = ApiJson.encodeToJsonElement(Settings.serializer(), _settings.value).jsonObject
        store.save(StoredSettings(values, _localOnly.value, _version.value))
    }

    private companion object {
        const val TAG = "settings"
    }
}
