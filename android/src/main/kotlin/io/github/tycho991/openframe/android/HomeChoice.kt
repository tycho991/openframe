package io.github.tycho991.openframe.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.tycho991.openframe.core.health.RunEnd

/**
 * Android's HOME choice after an update that lost the HOME preference (spec `kiosk-host`, "HOME choice after an
 * update"; design decisions 6 and 7 of `core-cleanup`). [FrameApp] arms it at the start of a new installation, the
 * display opens it once when the slideshow is in front with the display on. Everything here is pure except
 * [HomeApps.read] and [PrefsStore].
 */
internal object HomeChoice {
    /** What Android does when the Home button is pressed. */
    enum class Status {
        /** openframe is the preferred or the only HOME app. */
        OWN,

        /** Another app is the preferred HOME app (or the only one, while openframe is no candidate it would pick). */
        OTHER,

        /** No app is preferred and Android would ask: openframe and at least one other app are candidates. */
        ASKS,

        /** openframe is no HOME app at all. */
        NONE,
    }

    enum class Action {
        /** No pending record for this installation: nothing to do. */
        NOTHING,

        /** Open the choice now. */
        OPEN,

        /** Not now: in safe mode, or the slideshow is not in front with the display on. */
        WAIT,

        /** No choice is needed for this installation: the record becomes `done` without opening anything. */
        DROP,
    }

    /** The record per installation in the preferences file [PREFS]: `"<installId>|pending"` or `"<installId>|done"`. */
    data class Record(
        val installId: String,
        val done: Boolean,
    ) {
        fun encode(): String = "$installId|${if (done) DONE else PENDING}"

        companion object {
            fun parse(text: String?): Record? {
                if (text == null) return null
                val state = text.substringAfterLast('|', "")
                val id = text.substringBeforeLast('|', "")
                return when (state) {
                    PENDING -> Record(id, done = false)
                    DONE -> Record(id, done = true)
                    else -> null
                }
            }
        }
    }

    /** An activity that answers the HOME intent. */
    data class Component(
        val packageName: String,
        val name: String,
    )

    /**
     * [resolved] is the activity Android resolves the HOME intent to, [candidates] every HOME app, [own] openframe's
     * package. A resolved activity that is no candidate is the system's resolver, whichever vendor's package it is in.
     */
    fun status(
        resolved: Component?,
        candidates: List<Component>,
        own: String,
    ): Status {
        if (candidates.none { it.packageName == own }) return Status.NONE
        if (resolved != null && resolved in candidates) return if (resolved.packageName == own) Status.OWN else Status.OTHER
        return if (candidates.any { it.packageName != own }) Status.ASKS else Status.OWN
    }

    /**
     * Whether to arm the choice at a process start: the journal found a new installation ([RunEnd.REPLACED]) and there
     * is no record for [installId] yet. A first installation arms nothing.
     */
    fun arms(
        previousEnd: RunEnd,
        record: Record?,
        installId: String,
    ): Boolean = previousEnd == RunEnd.REPLACED && installId.isNotEmpty() && record?.installId != installId

    /**
     * What the display does with the HOME choice now. [displayShowing]: see [displayShowing]. [status] asks the
     * package manager; it runs only once nothing else decides, so no IPC runs on the main thread while the display
     * waits. `null` (the package manager failed) waits as well.
     */
    fun decide(
        record: Record?,
        installId: String,
        safeMode: Boolean,
        displayShowing: Boolean,
        status: () -> Status?,
    ): Action {
        if (record == null || record.done || record.installId != installId) return Action.NOTHING
        if (safeMode || !displayShowing) return Action.WAIT
        return when (status()) {
            null -> Action.WAIT
            Status.ASKS -> Action.OPEN
            else -> Action.DROP
        }
    }

    /**
     * The slideshow is in front with the display on and not in a dark wake. A dark wake reports the display off in
     * `DisplayController.state` already; `dark` is checked as well, because the two flows change one after the other.
     */
    fun displayShowing(
        resumed: Boolean,
        displayOn: Boolean,
        dark: Boolean,
    ): Boolean = resumed && displayOn && !dark

    /** Where the record is kept; a failed write returns `false`. */
    interface Store {
        fun read(): String?

        fun write(value: String): Boolean
    }

    /** The record in the `SharedPreferences` file [PREFS], written synchronously with `commit()`. */
    class PrefsStore(
        private val context: Context,
    ) : Store {
        private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun read(): String? =
            try {
                prefs().getString(KEY, null)
            } catch (e: Exception) {
                Log.w(TAG, "Reading the HOME choice record failed", e)
                null
            }

        override fun write(value: String): Boolean =
            try {
                prefs().edit().putString(KEY, value).commit()
            } catch (e: Exception) {
                Log.w(TAG, "Writing the HOME choice record failed", e)
                false
            }
    }

    /** The HOME intent, which Android answers with its resolver while no HOME app is preferred. */
    fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    const val PREFS = "home_choice"
    private const val KEY = "record"
    private const val PENDING = "pending"
    private const val DONE = "done"
    private const val TAG = "openframe/app"
}

/** The HOME state as the package manager reports it; from Android 11 on it needs the HOME intent in `<queries>`. */
internal data class HomeApps(
    val resolved: HomeChoice.Component?,
    val candidates: List<HomeChoice.Component>,
    /** openframe's package. */
    val own: String,
) {
    fun status(): HomeChoice.Status = HomeChoice.status(resolved, candidates, own)

    companion object {
        fun read(
            pm: PackageManager,
            own: String,
        ): HomeApps {
            val intent = HomeChoice.homeIntent()
            val resolved =
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let {
                    HomeChoice.Component(it.packageName, it.name)
                }
            val candidates =
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { info ->
                    info.activityInfo?.let { HomeChoice.Component(it.packageName, it.name) }
                }
            return HomeApps(resolved, candidates, own)
        }
    }
}
