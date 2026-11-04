package io.github.tycho991.openframe.core.update

import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.health.HealthState
import io.github.tycho991.openframe.core.health.RunJournal
import io.github.tycho991.openframe.core.nextcloud.TrustAnchors
import io.github.tycho991.openframe.core.testing.Fixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Spec `app-update`: Source of updates, Automatic check, Retry after a failed check, Check on request, Updates only for
 * official builds and Updates in safe mode (design decisions 2 and 3).
 */
class UpdateCheckTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { UpdateHarness(dir) }

    @AfterEach
    fun stop() = h.close()

    private val requests get() = h.fake.requests.get()

    // Source of updates, with GitHub's protocol over TLS

    private fun withGithub(
        installedCode: Long,
        setup: (FakeGithub) -> Unit,
    ): Pair<UpdateState, FakeGithub> {
        val github = FakeGithub()
        val harness = UpdateHarness(File(dir, "g$installedCode-${System.nanoTime()}"), github.client())
        harness.platform.appVersionCode = installedCode
        setup(github)
        harness.start()
        harness.checkNow()
        val state = harness.state
        harness.close()
        github.close()
        return state to github
    }

    private val apk = Fixtures.apk("0.4.0")

    @Test
    fun `Source of updates`() {
        data class Case(
            val name: String,
            val installed: Long,
            val phase: UpdatePhase,
            val error: UpdateErrorCode?,
            val result: CheckResult,
            val setup: (FakeGithub) -> Unit,
        )
        val cases =
            listOf(
                Case(
                    "newer",
                    300,
                    UpdatePhase.READY,
                    null,
                    CheckResult.UPDATE_FOUND,
                ) { it.publish("v0.4.0", "openframe-0.4.0.apk" to apk) },
                Case("same", 400, UpdatePhase.IDLE, null, CheckResult.UP_TO_DATE) { it.publish("v0.4.0", "openframe-0.4.0.apk" to apk) },
                Case("older", 400, UpdatePhase.IDLE, null, CheckResult.UP_TO_DATE) { it.publish("v0.3.1", "openframe-0.3.1.apk" to apk) },
                Case("without APK", 300, UpdatePhase.IDLE, UpdateErrorCode.UPDATE_NO_APK, CheckResult.FAILED) {
                    it.publish("v0.4.0", "openframe-0.4.0.apk.sha256" to byteArrayOf(1))
                },
                Case("two APKs", 300, UpdatePhase.IDLE, UpdateErrorCode.UPDATE_NO_APK, CheckResult.FAILED) {
                    it.publish("v0.4.0", "openframe-0.4.0.apk" to apk, "openframe-0.4.0-debug.apk" to apk)
                },
                Case("without digest", 300, UpdatePhase.IDLE, UpdateErrorCode.UPDATE_NO_CHECKSUM, CheckResult.FAILED) {
                    it.publish("v0.4.0", "openframe-0.4.0.apk" to apk, digests = false)
                },
                Case(
                    "draft",
                    300,
                    UpdatePhase.IDLE,
                    null,
                    CheckResult.UP_TO_DATE,
                ) { it.publish("v0.4.0", "openframe-0.4.0.apk" to apk, draft = true) },
                Case("pre-release", 300, UpdatePhase.IDLE, null, CheckResult.UP_TO_DATE) {
                    it.publish("v0.4.0", "openframe-0.4.0.apk" to apk, prerelease = true)
                },
                Case("minor of 100", 300, UpdatePhase.IDLE, null, CheckResult.UP_TO_DATE) {
                    it.publish(
                        "v0.100.0",
                        "openframe-0.100.0.apk" to apk,
                    )
                },
                Case("404", 300, UpdatePhase.IDLE, UpdateErrorCode.UPDATE_NOT_FOUND, CheckResult.FAILED) {
                    it.latest = { FakeGithub.Answer(404) }
                },
                Case("broken JSON", 300, UpdatePhase.IDLE, UpdateErrorCode.UPDATE_FAILED, CheckResult.FAILED) {
                    it.latest = { FakeGithub.Answer(200, "{\"tag_name\":".toByteArray()) }
                },
                Case("redirect to http", 300, UpdatePhase.IDLE, UpdateErrorCode.UPDATE_FAILED, CheckResult.FAILED) {
                    it.publish("v0.4.0", "openframe-0.4.0.apk" to apk)
                    it.redirects["/assets/openframe-0.4.0.apk"] = "${it.plainBase}/openframe-0.4.0.apk"
                },
            )
        for (case in cases) {
            val (state, github) = withGithub(case.installed, case.setup)
            assertEquals(case.phase, state.phase, case.name)
            assertEquals(case.error, state.error?.code, case.name)
            assertEquals(case.result, state.lastCheck?.result, case.name)
            assertTrue(github.plainRequests.isEmpty(), case.name)
            if (case.phase != UpdatePhase.READY && case.name != "redirect to http") {
                assertTrue(github.requests.none { it.path.startsWith("/assets/") }, "${case.name}: nothing downloaded")
            }
        }
    }

    /** Spec `app-update`: Fixed codes of the update › Untrusted certificate. */
    @Test
    fun `An untrusted chain is update_tls with subjects and issuers in the log`() {
        FakeGithub().use { github ->
            github.publish("v0.4.0", "openframe-0.4.0.apk" to apk)
            val harness = UpdateHarness(File(dir, "tls"), github.client(TrustAnchors.forUpdates()))
            harness.start()
            assertEquals(UpdateErrorCode.UPDATE_TLS, harness.checkNow().error?.code)
            val line = harness.logged("Certificate chain:").single()
            assertTrue("subject CN=localhost" in line && "issuer " in line, line)
            harness.close()
        }
    }

    @Test
    fun `A 304 keeps the stored release`() {
        h.ready()
        h.hours(6)
        assertEquals(2, requests)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals("0.4.0", h.state.latest?.version)
        assertEquals(CheckResult.UPDATE_FOUND, h.state.lastCheck?.result)
        assertEquals(1, h.fake.downloads.get())
    }

    // Automatic check

    /** Spec `app-update`: Automatic check › Frame running. */
    @Test
    fun `Frame running`() {
        h.store(StoredUpdate(lastAttemptAt = h.clock.millis() - Duration.ofDays(1).toMillis()))
        h.fake.publish("0.3.0")
        h.start()
        h.minutes(2)
        assertEquals(0, requests)
        h.minutes(1)
        assertEquals(1, requests)
        h.advance(Duration.ofHours(12))
        assertEquals(3, requests)
        assertEquals(CheckResult.UP_TO_DATE, h.state.lastCheck?.result)
    }

    /** Spec `app-update`: Automatic check › Restart shortly after a check. */
    @Test
    fun `Restart shortly after a check`() {
        h.store(StoredUpdate(lastAttemptAt = h.clock.millis() - Duration.ofHours(1).toMillis()))
        h.fake.publish("0.3.0")
        h.start()
        h.advance(Duration.ofHours(5).minusSeconds(10))
        assertEquals(0, requests)
        h.minutes(1)
        assertEquals(1, requests)
    }

    @Test
    fun `A last attempt in the future is dropped`() {
        h.store(StoredUpdate(lastAttemptAt = h.clock.millis() + Duration.ofDays(30).toMillis()))
        h.fake.publish("0.3.0")
        h.start()
        h.minutes(3)
        assertEquals(1, requests)
    }

    /** Spec `app-update`: Automatic check › Clock not set. */
    @Test
    fun `Clock not set`() {
        h.clock.instant = Instant.parse("2010-01-01T00:00:00Z")
        h.fake.publish("0.4.0")
        h.start()
        h.hours(7)
        assertEquals(0, requests)
        assertNull(h.state.error)
        assertNull(h.state.lastCheck)
        // Set: the check comes, without waiting another 3 minutes.
        h.clock.instant = Instant.parse("2026-11-04T17:00:00Z")
        h.advance(Duration.ofSeconds(30))
        assertEquals(1, requests)
    }

    /** Spec `app-update`: Automatic check › Checks off; Check on request › Check now. */
    @Test
    fun `Checks off, then check now`() {
        h.settings.value = h.settings.value.copy(autoUpdateCheck = false)
        h.fake.publish("0.4.0")
        h.start()
        h.hours(7)
        assertEquals(0, requests)
        assertEquals(UpdatePhase.CHECKING, h.manager.check().phase)
        h.scope.testScheduler.runCurrent()
        assertEquals(1, requests)
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals("0.4.0", h.state.readyVersion)
    }

    @Test
    fun `Switching checks on lets the waiting check come`() {
        h.settings.value = h.settings.value.copy(autoUpdateCheck = false)
        h.fake.publish("0.3.0")
        h.start()
        h.minutes(10)
        h.settings.value = h.settings.value.copy(autoUpdateCheck = true)
        h.advance(Duration.ofSeconds(1))
        assertEquals(1, requests)
    }

    // Retry after a failed check

    /** Spec `app-update`: Retry after a failed check › No network. */
    @Test
    fun `No network`() {
        h.fake.failure = UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "api.github.com is not reachable")
        h.start()
        h.minutes(3)
        assertEquals(1, requests)
        assertEquals(UpdateErrorCode.UPDATE_UNREACHABLE, h.state.error?.code)
        assertEquals(CheckResult.FAILED, h.state.lastCheck?.result)
        h.minutes(14)
        assertEquals(1, requests)
        h.minutes(1)
        assertEquals(2, requests)
        h.minutes(59)
        assertEquals(2, requests)
        h.minutes(1)
        assertEquals(3, requests)
        h.advance(Duration.ofHours(6).minusMinutes(1))
        assertEquals(3, requests)
        h.minutes(1)
        assertEquals(4, requests)
        h.advance(Duration.ofHours(6))
        assertEquals(5, requests)
        // Back: a success resets the backoff to 6 hours.
        h.fake.failure = null
        h.fake.publish("0.3.0")
        h.advance(Duration.ofHours(6))
        assertEquals(6, requests)
        assertNull(h.state.error)
        h.minutes(20)
        assertEquals(6, requests)
    }

    /** Spec `app-update`: Retry after a failed check › Network back. */
    @Test
    fun `Network back`() {
        h.fake.failure = UpdateException(UpdateErrorCode.UPDATE_UNREACHABLE, "api.github.com is not reachable")
        h.start()
        h.minutes(3)
        h.minutes(5)
        h.fake.failure = null
        h.fake.publish("0.4.0")
        h.manager.networkChanged()
        h.minutes(1)
        assertEquals(2, requests)
        assertEquals(UpdatePhase.READY, h.state.phase)
    }

    @Test
    fun `A network change without a waiting retry checks nothing`() {
        h.fake.publish("0.3.0")
        h.start()
        h.minutes(3)
        h.manager.networkChanged()
        h.minutes(5)
        assertEquals(1, requests)
    }

    /** Spec `app-update`: Retry after a failed check › Unexpected answer. */
    @Test
    fun `Unexpected answer`() {
        val broken =
            object : ReleaseSource {
                var calls = 0

                override fun latest(etag: String?): FetchedRelease? {
                    calls++
                    throw IllegalStateException("not a release")
                }

                override fun download(
                    url: String,
                    out: java.io.OutputStream,
                    maxBytes: Long,
                ) = error("unused")
            }
        val harness = UpdateHarness(File(dir, "broken"), broken)
        harness.start()
        harness.minutes(3)
        assertEquals(UpdateErrorCode.UPDATE_FAILED, harness.state.error?.code)
        assertEquals(UpdatePhase.IDLE, harness.state.phase)
        harness.minutes(15)
        assertEquals(2, broken.calls)
        harness.close()
    }

    // Check on request

    /** Spec `app-update`: Check on request › Two requests in a row. */
    @Test
    fun `Two requests in a row`() {
        h.fake.publish("0.3.0")
        h.start()
        val first = h.checkNow()
        h.advance(Duration.ofSeconds(30))
        val second = h.checkNow()
        assertEquals(1, requests)
        assertEquals(first.lastCheck, second.lastCheck)
        h.advance(Duration.ofSeconds(30))
        h.checkNow()
        assertEquals(2, requests)
    }

    /** Spec `app-update`: Check on request › Rate limited. */
    @Test
    fun `Rate limited`() {
        val reset = h.clock.millis() + Duration.ofHours(1).toMillis()
        h.fake.failure = UpdateException(UpdateErrorCode.UPDATE_RATE_LIMITED, "GitHub's rate limit is reached", resetAtMs = reset)
        h.start()
        h.checkNow()
        assertEquals(1, requests)
        h.minutes(2)
        assertEquals(UpdateErrorCode.UPDATE_RATE_LIMITED, h.checkNow().error?.code)
        h.minutes(40)
        h.checkNow()
        assertEquals(1, requests, "neither on request nor by the backoff before the reset")
        assertEquals(UpdateErrorCode.UPDATE_RATE_LIMITED, h.state.error?.code)
        h.fake.failure = null
        h.fake.publish("0.3.0")
        h.minutes(20)
        assertEquals(2, requests)
        assertNull(h.state.error)
        assertTrue(h.stored().rateLimitResetAt == null || h.stored().rateLimitResetAt!! <= h.clock.millis())
    }

    // Updates only for official builds

    /** Spec `app-update`: Updates only for official builds › Own build. */
    @Test
    fun `Own build`() {
        h.installer.certificate = "00" + OfficialCertificate.SHA256.drop(2)
        h.fake.publish("0.4.0")
        h.start()
        h.hours(7)
        assertEquals(UpdatePhase.UNSUPPORTED, h.checkNow().phase)
        assertEquals(0, requests)
        assertEquals(InstallRequest.NotReady, h.manager.install("client"))
        assertTrue(h.logged("not signed with the official certificate").isNotEmpty())
    }

    @Test
    fun `Without a readable certificate or version code nothing is asked`() {
        h.installer.certificate = null
        h.fake.publish("0.4.0")
        h.start()
        h.hours(7)
        assertEquals(UpdatePhase.UNSUPPORTED, h.checkNow().phase)
        val other = UpdateHarness(File(dir, "nocode"))
        other.platform.appVersionCode = null
        other.start()
        other.hours(7)
        assertEquals(UpdatePhase.UNSUPPORTED, other.state.phase)
        assertEquals(0, other.fake.requests.get() + requests)
        other.close()
    }

    // Updates in safe mode

    /** Spec `app-update`: Updates in safe mode › Fix for a crashing release (the check and the download). */
    @Test
    fun `In safe mode the first check comes at once`() {
        h.store(StoredUpdate(lastAttemptAt = h.clock.millis() - Duration.ofHours(1).toMillis()))
        h.health = Health.safe(h.clock.millis())
        h.fake.publish("0.4.0")
        h.start()
        assertEquals(1, requests)
        assertEquals(UpdatePhase.READY, h.state.phase)
        // Later checks follow the schedule.
        h.hours(5)
        assertEquals(1, requests)
    }

    @Test
    fun `After an early end the first check comes at once, but not within a minute of the last`() {
        h.store(StoredUpdate(lastAttemptAt = h.clock.millis() - 20_000))
        h.health = Health(RunJournal(null), HealthState(recentEarlyEnds = 1)) { 0L }
        h.fake.publish("0.3.0")
        h.start()
        assertEquals(0, requests)
        h.advance(Duration.ofSeconds(40))
        assertEquals(1, requests)
    }

    @Test
    fun `The state is kept across a restart`() {
        h.ready()
        h.restart()
        assertEquals(UpdatePhase.READY, h.state.phase)
        assertEquals("0.4.0", h.state.latest?.version)
        assertEquals(CheckResult.UPDATE_FOUND, h.state.lastCheck?.result)
        assertEquals(1, requests)
    }
}
