package io.github.tycho991.openframe.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.SystemClock
import io.github.tycho991.openframe.android.HomeChoice.Component
import io.github.tycho991.openframe.android.HomeChoice.Record
import io.github.tycho991.openframe.core.FrameCore
import io.github.tycho991.openframe.core.health.Health
import io.github.tycho991.openframe.core.health.RunEnd
import io.github.tycho991.openframe.core.platform.DisplayPower
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * Spec `kiosk-host`: HOME choice after an update, HOME choice as a system screen, Restart after an update › HOME
 * preference lost (design decisions 6 to 8 of `core-cleanup`). [FrameApp.start] arms the record, the display opens
 * Android's choice; [FrameApp.homeApps] stands in for the package manager, because Robolectric resolves an intent to
 * its first match and cannot show Android's resolver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeChoiceOnDisplayTest {
    private val app = RuntimeEnvironment.getApplication() as FrameApp
    private var core: FrameCore? = null
    private val power = SwitchablePower()
    private val home = HomeChoice.homeIntent()

    private val own = Component(app.packageName, DisplayActivity::class.java.name)
    private val launcher = Component("com.example.launcher", "com.example.launcher.Main")
    private val resolver = Component("android", "com.android.internal.app.ResolverActivity")

    /** What the package manager reports; Android asks unless a test says otherwise. */
    private var homeApps = HomeApps(resolver, listOf(own, launcher), app.packageName)

    @Before
    fun seams() {
        app.homeApps = { homeApps }
    }

    @After
    fun stop() {
        core?.let { runBlocking { it.stop() } }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun record() = Record.parse(app.homeChoiceStore.read())

    private fun runsFile() = File(app.filesDir, "state/health/runs.json")

    /** This process is the first start of a new installation: the journal's previous run had another one. */
    private fun startAfterUpdate() {
        app.journal.onProcessStart(System.currentTimeMillis(), SystemClock.elapsedRealtime(), null, "100:1")
        app.start(app.packageName)
    }

    /** A light core on the app's health with [power] and the service that hands it out; started unless said otherwise. */
    private fun startCore(
        health: Health = app.health,
        start: Boolean = true,
    ): FrameCore {
        val c = TestCore.create(File(app.cacheDir, "home-core-${System.nanoTime()}"), health = health, displayPower = power)
        core = c
        app.coreFactory = { c }
        if (start) runBlocking { c.start() }
        val service = Robolectric.buildService(FrameService::class.java).create().get()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, FrameService::class.java), service.onBind(null))
        return c
    }

    private fun display(): ActivityController<DisplayActivity> {
        val controller = Robolectric.buildActivity(DisplayActivity::class.java).setup()
        idle()
        return controller
    }

    /** Every HOME intent started since the last call. */
    private fun startedHomeIntents(): List<Intent> {
        val started = mutableListOf<Intent>()
        while (true) {
            val next = shadowOf(app).nextStartedActivity ?: break
            if (next.action == Intent.ACTION_MAIN && next.hasCategory(Intent.CATEGORY_HOME)) started += next
        }
        return started
    }

    private fun logged(text: String) = core!!.log.recent(500).filter { text in it.message }

    /** Waits for [condition], which may depend on the core's display loop on its own thread. */
    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5000
        while (true) {
            idle()
            if (condition()) return
            assertTrue("Timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    private fun resolvesHome(pkg: String) {
        val pm = shadowOf(app.packageManager)
        pm.removeResolveInfosForIntent(home, app.packageName)
        pm.addResolveInfoForIntent(
            home,
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = pkg
                        name = "$pkg.Home"
                    }
            },
        )
    }

    // --- Arming at the process start

    @Test
    fun `The first start of a new installation arms the choice before any core exists`() {
        startAfterUpdate()
        assertEquals(RunEnd.REPLACED, app.health.state.value.previousEnd)
        assertFalse(app.coreCreated)
        assertEquals(Record(app.installId, done = false), record())
    }

    /** `kiosk-host` › HOME choice after an update › Restart without an update. */
    @Test
    fun `A first installation and a device restart arm nothing, and a second start keeps done`() {
        runsFile().delete()
        app.start(app.packageName)
        assertNull("a first installation", record())

        app.journal.onProcessStart(System.currentTimeMillis(), SystemClock.elapsedRealtime(), "another-boot", app.installId)
        app.start(app.packageName)
        assertNull("a device restart", record())

        startAfterUpdate()
        app.homeChoiceStore.write(Record(app.installId, done = true).encode())
        app.start(app.packageName)
        assertEquals("the same installation again", Record(app.installId, done = true), record())
    }

    @Test
    fun `A core that fails to start leaves the record pending`() {
        app.coreFactory = { throw IllegalStateException("the core fails") }
        startAfterUpdate()
        assertThrows(IllegalStateException::class.java) { runBlocking { FrameApp.startLogged({ app.core.start() }) {} } }
        assertEquals(Record(app.installId, done = false), record())
    }

    @Test
    fun `A store whose writes fail leaves no record and nothing opens later`() {
        app.homeChoiceStore = FailingStore(backing = null)
        startAfterUpdate()
        assertNull(record())
        startCore()
        display()
        assertEquals(emptyList<Intent>(), startedHomeIntents())
    }

    // --- Opening on the display

    /** `kiosk-host` › HOME choice after an update › Choice after the update. */
    @Test
    fun `The choice opens when the core turns ready with the display on, without another resume`() {
        startAfterUpdate()
        val c = startCore(start = false)
        Robolectric
            .buildActivity(DisplayActivity::class.java)
            .create()
            .start()
            .resume()
        idle()
        assertEquals("not before the core is ready", emptyList<Intent>(), startedHomeIntents())

        runBlocking { c.start() }
        idle()
        val started = startedHomeIntents().single()
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(Record(app.installId, done = true), record())
    }

    /** `kiosk-host` › HOME choice after an update › Choice after the update; › HOME choice as a system screen. */
    @Test
    fun `The choice opens as a system screen with a record and a held display`() {
        startAfterUpdate()
        startCore()
        display()
        assertEquals(1, startedHomeIntents().size)
        assertEquals(Record(app.installId, done = true), record())
        assertTrue(app.health.state.value.systemScreenOpen)
        assertTrue(app.journal.systemScreenOpen(SystemClock.elapsedRealtime()))
        waitUntil("the hold") { logged("Display held on for a system screen (home choice)").isNotEmpty() }
        assertEquals(1, logged("Opening Android's HOME choice after the update: no HOME app is chosen").size)
    }

    /** `kiosk-host` › HOME choice after an update › Chosen just once or dismissed. */
    @Test
    fun `The choice opens once per installation, also for a new display and after a restart of the app`() {
        startAfterUpdate()
        startCore()
        val controller = display()
        assertEquals(1, startedHomeIntents().size)

        controller.pause().resume()
        idle()
        display()
        assertEquals("resumed again, and a new display", emptyList<Intent>(), startedHomeIntents())

        // The next start of the process finds the record done (also after an end under the choice).
        app.start(app.packageName)
        assertEquals(Record(app.installId, done = true), record())
        display()
        assertEquals(emptyList<Intent>(), startedHomeIntents())
    }

    @Test
    fun `A record that cannot be written opens nothing and makes no system-screen record`() {
        startAfterUpdate()
        app.homeChoiceStore = FailingStore(backing = HomeChoice.PrefsStore(app))
        startCore()
        display()
        assertEquals(emptyList<Intent>(), startedHomeIntents())
        assertFalse(app.health.state.value.systemScreenOpen)
        assertTrue(logged("HOME choice not opened: its record could not be written").isNotEmpty())
        assertEquals(Record(app.installId, done = false), record())
    }

    /**
     * `kiosk-host` › HOME choice after an update › Display off after the update, › Dark wake after the update. A dark
     * wake reports the display off in `DisplayController.state` (and sets `dark`, which `HomeChoiceTest` covers), so
     * the display waits in both cases.
     */
    @Test
    fun `Nothing while the display is off, the choice once it is on`() {
        startAfterUpdate()
        power.switch(false)
        val c = startCore()
        waitUntil("the display off") { !c.display.state.value.on }
        display()
        assertEquals(emptyList<Intent>(), startedHomeIntents())
        assertEquals(Record(app.installId, done = false), record())
        assertFalse(app.health.state.value.systemScreenOpen)

        power.switch(true)
        waitUntil("the choice") { record()?.done == true }
        assertEquals(1, startedHomeIntents().size)
    }

    /** `kiosk-host` › HOME choice after an update › Safe mode after the update. */
    @Test
    fun `Nothing in safe mode, the record stays pending`() {
        startAfterUpdate()
        startCore(health = Health.safe(System.currentTimeMillis()))
        display()
        assertEquals(emptyList<Intent>(), startedHomeIntents())
        assertEquals(Record(app.installId, done = false), record())
    }

    /** `kiosk-host` › HOME choice after an update › Preference kept. */
    @Test
    fun `Nothing while openframe is the HOME app, and the record is done`() {
        homeApps = HomeApps(own, listOf(own, launcher), app.packageName)
        startAfterUpdate()
        startCore()
        display()
        assertEquals(emptyList<Intent>(), startedHomeIntents())
        assertEquals(Record(app.installId, done = true), record())
    }

    /** `kiosk-host` › HOME choice after an update › Another app chosen. */
    @Test
    fun `Nothing while another app is the HOME app, the start's warning stays and the record is done`() {
        homeApps = HomeApps(launcher, listOf(own, launcher), app.packageName)
        resolvesHome(launcher.packageName)
        startAfterUpdate()
        startCore()
        BootReceiver.logHome(app)
        display()
        assertEquals(emptyList<Intent>(), startedHomeIntents())
        assertEquals(Record(app.installId, done = true), record())
        assertEquals(1, logged("openframe is not the HOME app (com.example.launcher)").size)
    }

    /**
     * `kiosk-host` › HOME choice after an update › Chosen just once or dismissed; › HOME choice as a system screen;
     * › Restart after an update › HOME preference lost.
     */
    @Test
    fun `Back in front while Android still asks, one warning, and the record and the hold are withdrawn`() {
        startAfterUpdate()
        startCore()
        val controller = display()
        assertEquals(1, startedHomeIntents().size)
        waitUntil("the hold") { logged("Display held on for a system screen (home choice)").isNotEmpty() }
        resolvesHome("android")

        controller
            .pause()
            .stop()
            .restart()
            .start()
            .resume()
        idle()
        assertFalse(app.health.state.value.systemScreenOpen)
        assertFalse(app.journal.systemScreenOpen(SystemClock.elapsedRealtime()))
        waitUntil("the end of the hold") { logged("Display hold ended").isNotEmpty() }
        assertEquals(1, logged("openframe is not the HOME app (android)").size)
        assertEquals(emptyList<Intent>(), startedHomeIntents())
    }

    /** `kiosk-host` › HOME choice after an update › openframe chosen for always. */
    @Test
    fun `Back in front after openframe was chosen for always, no warning`() {
        startAfterUpdate()
        startCore()
        val controller = display()
        assertEquals(1, startedHomeIntents().size)
        waitUntil("the hold") { logged("Display held on for a system screen (home choice)").isNotEmpty() }
        resolvesHome(app.packageName)
        homeApps = HomeApps(own, listOf(own, launcher), app.packageName)

        controller
            .pause()
            .stop()
            .restart()
            .start()
            .resume()
        idle()
        assertFalse(app.health.state.value.systemScreenOpen)
        waitUntil("the end of the hold") { logged("Display hold ended").isNotEmpty() }
        assertTrue(logged("openframe is not the HOME app").isEmpty())
    }

    /** A display that is only switched by the system: [switch] reports the screen going off or on. */
    private class SwitchablePower : DisplayPower {
        @Volatile override var screenOn = true
            private set
        override val available = false

        @Volatile private var listener: DisplayPower.Listener? = null

        override fun turnOff() = false

        override fun turnOn() {}

        override fun setListener(listener: DisplayPower.Listener) {
            this.listener = listener
        }

        fun switch(on: Boolean) {
            screenOn = on
            listener?.screenChanged(on)
        }
    }

    /** Reads through [backing], refuses every write. */
    private class FailingStore(
        private val backing: HomeChoice.Store?,
    ) : HomeChoice.Store {
        override fun read() = backing?.read()

        override fun write(value: String) = false
    }
}
