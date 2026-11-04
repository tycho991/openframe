package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.store.AtomicFile
import io.github.tycho991.openframe.core.store.StoreJson
import io.github.tycho991.openframe.core.update.CheckResult
import io.github.tycho991.openframe.core.update.LastCheck
import io.github.tycho991.openframe.core.update.ReadyApk
import io.github.tycho991.openframe.core.update.Release
import io.github.tycho991.openframe.core.update.ReleaseAsset
import io.github.tycho991.openframe.core.update.StoredUpdate
import io.github.tycho991.openframe.core.update.Versions
import java.io.File

/** An update that an earlier run downloaded and verified, for frames that should not wait for a check. */
object ReadyUpdate {
    /**
     * Leaves [version] ready in the folders of a [TestFrame] at [dir] (`state/update.json` and `update/`), as checked at
     * [checkedAtMs], so that the next automatic check is 6 hours later.
     */
    fun seed(
        dir: File,
        version: String = "0.4.0",
        notes: String = "Fixes for $version",
        checkedAtMs: Long = System.currentTimeMillis(),
    ) {
        val apk = Fixtures.apk(version)
        val name = "openframe-$version.apk"
        File(dir, "update").apply { mkdirs() }.resolve(name).writeBytes(apk)
        val sha = FakeReleaseSource.sha256(apk)
        val release =
            Release(
                "v$version",
                notes = notes,
                publishedAt = "2026-11-01T10:00:00Z",
                assets = listOf(ReleaseAsset(name, apk.size.toLong(), "sha256:$sha", "https://release-assets.example.com/$name")),
            )
        val stored =
            StoredUpdate(
                lastAttemptAt = checkedAtMs,
                release = release,
                lastCheck = LastCheck(checkedAtMs, CheckResult.UPDATE_FOUND),
                ready = ReadyApk(version, Versions.code(version)!!, name, sha, apk.size.toLong()),
            )
        val file = File(dir, "state/update.json").apply { parentFile.mkdirs() }
        AtomicFile(file).writeText(StoreJson.encodeToString(StoredUpdate.serializer(), stored))
    }
}
