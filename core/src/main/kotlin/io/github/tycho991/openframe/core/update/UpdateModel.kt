package io.github.tycho991.openframe.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The error codes of the update state, the enum `UpdateError.code` of the contract. */
@Serializable
enum class UpdateErrorCode(
    val api: String,
) {
    @SerialName("update_unreachable")
    UPDATE_UNREACHABLE("update_unreachable"),

    @SerialName("update_rate_limited")
    UPDATE_RATE_LIMITED("update_rate_limited"),

    @SerialName("update_not_found")
    UPDATE_NOT_FOUND("update_not_found"),

    @SerialName("update_no_apk")
    UPDATE_NO_APK("update_no_apk"),

    @SerialName("update_no_checksum")
    UPDATE_NO_CHECKSUM("update_no_checksum"),

    @SerialName("update_checksum")
    UPDATE_CHECKSUM("update_checksum"),

    @SerialName("update_invalid_apk")
    UPDATE_INVALID_APK("update_invalid_apk"),

    @SerialName("update_signature")
    UPDATE_SIGNATURE("update_signature"),

    @SerialName("update_storage")
    UPDATE_STORAGE("update_storage"),

    @SerialName("update_tls")
    UPDATE_TLS("update_tls"),

    @SerialName("update_failed")
    UPDATE_FAILED("update_failed"),

    @SerialName("install_cancelled")
    INSTALL_CANCELLED("install_cancelled"),

    @SerialName("install_unanswered")
    INSTALL_UNANSWERED("install_unanswered"),

    @SerialName("install_failed")
    INSTALL_FAILED("install_failed"),
}

/** The phase of the updater, the enum `UpdatePhase` of the contract. */
enum class UpdatePhase(
    val api: String,
) {
    IDLE("idle"),
    CHECKING("checking"),
    DOWNLOADING("downloading"),
    READY("ready"),
    INSTALLING("installing"),

    /** The installed app is not an official build: no request to GitHub, also not on request. */
    UNSUPPORTED("unsupported"),
}

@Serializable
enum class CheckResult(
    val api: String,
) {
    @SerialName("up_to_date")
    UP_TO_DATE("up_to_date"),

    @SerialName("update_found")
    UPDATE_FOUND("update_found"),

    @SerialName("failed")
    FAILED("failed"),
}

@Serializable
data class UpdateError(
    val code: UpdateErrorCode,
    val message: String,
)

@Serializable
data class LastCheck(
    val atMs: Long,
    val result: CheckResult,
)

/** A file attached to a release. */
@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long,
    /** As GitHub states it, `sha256:<64 hex>`, `null` without one. */
    val digest: String? = null,
    val url: String,
)

/** The latest release of the repository as GitHub describes it. */
@Serializable
data class Release(
    val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val notes: String = "",
    val publishedAt: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** `0.4.0` for the tag `v0.4.0`; `null` for another tag, a draft or a pre-release, which are no update. */
    val version: String? get() = if (draft || prerelease) null else Versions.fromTag(tag)
}

/** The updater's state for the API, the device menu and the hint (spec `app-update`, "Update state in the API"). */
data class UpdateState(
    val installedVersion: String,
    val phase: UpdatePhase,
    /** The latest release the last successful check found, also when it is not newer. */
    val latest: Release? = null,
    val lastCheck: LastCheck? = null,
    val error: UpdateError? = null,
    val installAllowed: Boolean = false,
    /** The version that is ready or being installed. */
    val readyVersion: String? = null,
    /** The end of the open installation dialog on [io.github.tycho991.openframe.core.platform.Clock.monotonicMillis]. */
    val installUntilMono: Long? = null,
)

/** A failed step of the updater with its [code]; [detail] goes only to the log. */
class UpdateException(
    val code: UpdateErrorCode,
    override val message: String,
    val detail: String? = null,
    /** When GitHub's rate limit resets, in wall-clock milliseconds. */
    val resetAtMs: Long? = null,
    /** A check of the file that fails the same way every time: no backoff, the file is not fetched again. */
    val deterministic: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The answer to an installation request (design decision 6); the route maps it to `202` or `409`. */
sealed class InstallRequest(
    /** The refusal code of the contract's `InstallRefusal`, `null` for [Accepted]. */
    val code: String?,
    val message: String,
) {
    data object Accepted : InstallRequest(null, "The installation dialog opens on the frame")

    data object NotReady : InstallRequest("update_not_ready", "No update is ready to install")

    data object NotAllowed :
        InstallRequest(
            "install_not_allowed",
            "Android does not allow openframe to install apps: allow apps from unknown sources on the frame",
        )

    data object InProgress : InstallRequest("install_in_progress", "The installation dialog is already open on the frame")

    data object Cooldown :
        InstallRequest("install_cooldown", "The dialog was declined a short while ago; install from the device menu on the frame")
}

/** Versions as `release-pipeline` numbers them. */
object Versions {
    private val TAG = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")

    /** `0.4.0` for `v0.4.0`, `null` for any other tag. */
    fun fromTag(tag: String): String? = TAG.matchEntire(tag)?.let { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}" }

    /**
     * The version code `X*10000 + Y*100 + Z` of `release-pipeline`, `null` for a version that is not `X.Y.Z` or whose
     * minor or patch is 100 or more, because its code would collide with another version's.
     */
    fun code(version: String): Long? {
        val parts = version.split('.').map { it.toLongOrNull() ?: return null }
        if (parts.size != 3 || parts[1] >= 100 || parts[2] >= 100 || parts[0] >= 100_000) return null
        return parts[0] * 10_000 + parts[1] * 100 + parts[2]
    }
}
