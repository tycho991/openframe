package io.github.tycho991.openframe.core.devserver

import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response.Status
import io.github.tycho991.openframe.core.devserver.TestServerMain.CONTROL_HEADER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** The guards of the test server's control port, which a web page open in the same browser could otherwise reach. */
class TestServerMainTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `A USB copy stays inside the photo folder`() {
        val root = File(dir, "photos").apply { mkdirs() }
        assertEquals(File(root, "album/a.jpg").canonicalFile, TestServerMain.resolveInside(root, "album/a.jpg"))
        assertEquals(File(root, "b.jpg").canonicalFile, TestServerMain.resolveInside(root, "album/../b.jpg"))
        for (outside in listOf("../x", "a/../../x", "/etc/x", "..", ".", "", "../photos-old/x")) {
            assertNull(TestServerMain.resolveInside(root, outside), outside)
        }
    }

    @Test
    fun `A USB copy does not follow a link out of the photo folder`() {
        val root = File(dir, "photos").apply { mkdirs() }
        val elsewhere = File(dir, "elsewhere").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "link").toPath(), elsewhere.toPath())
        assertNull(TestServerMain.resolveInside(root, "link/x.jpg"))
    }

    @Test
    fun `The control port takes only GET with its header for its own host`() {
        val good = mapOf(CONTROL_HEADER to "1", "host" to "127.0.0.1:8091")

        fun refusal(
            headers: Map<String, String>,
            method: Method = Method.GET,
        ) = TestServerMain.controlRefusal(method, headers, 8091)?.first

        assertNull(refusal(good))
        assertNull(refusal(good + ("host" to "localhost:8091")))
        // An image or a link on another page sends no header.
        assertEquals(Status.FORBIDDEN, refusal(good - CONTROL_HEADER))
        assertEquals(Status.FORBIDDEN, refusal(good + (CONTROL_HEADER to "0")))
        // The question a browser asks before it sends the header to another site, and a form.
        assertEquals(Status.METHOD_NOT_ALLOWED, refusal(good, Method.OPTIONS))
        assertEquals(Status.METHOD_NOT_ALLOWED, refusal(good, Method.POST))
        // A page whose own name points at 127.0.0.1, another port and no host at all.
        assertEquals(Status.FORBIDDEN, refusal(good + ("host" to "evil.example:8091")))
        assertEquals(Status.FORBIDDEN, refusal(good + ("host" to "127.0.0.1:8090")))
        assertEquals(Status.FORBIDDEN, refusal(good - "host"))
    }
}
