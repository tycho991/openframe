package io.github.tycho991.openframe.core.api

import io.github.tycho991.openframe.core.http.Access
import io.github.tycho991.openframe.core.testing.Contract
import io.github.tycho991.openframe.core.testing.TestFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `admin-access`: Protected endpoints. Applies to all routes, also future ones. */
class RouteAccessTest {
    @TempDir
    lateinit var dir: File

    private val frame by lazy { TestFrame(dir) }

    @AfterEach
    fun tearDown() = frame.close()

    private val allowedPublic =
        setOf(
            "GET /api/v1/info",
            "POST /api/v1/pairing",
            "POST /api/v1/pairing/code",
        )

    @Test
    fun `Only approved routes without a token`() {
        val routes = frame.core.router.all
        val public = routes.filter { it.access == Access.PUBLIC }.map { "${it.method} ${it.pattern}" }.toSet()
        assertEquals(allowedPublic, public)
        val signed = routes.filter { it.access == Access.TOKEN_OR_IMAGE_SIGNATURE }.map { "${it.method} ${it.pattern}" }
        assertEquals(listOf("GET /api/v1/photos/{photoId}/image"), signed)
    }

    @Test
    fun `Every protected route answers 401 without a token`() {
        for (route in frame.core.router.all
            .filter { it.access != Access.PUBLIC }) {
            val path =
                route.pattern
                    .replace("{photoId}", "local-${"a".repeat(40)}")
                    .replace("{clientId}", "x")
                    .replace("{albumName}", "holiday")
            val body = if (route.method.isMutating) "{}" else null
            val res = frame.http.request(route.method.name, path, body)
            assertEquals(401, res.statusCode(), "$route")
        }
    }

    @Test
    fun `Router and contract describe the same endpoints`() {
        val routes =
            frame.core.router.all
                .map { "${it.method} ${it.pattern}" }
                .toSet()
        assertEquals(Contract.operations(), routes)
        for (route in frame.core.router.all) {
            val public = Contract.isPublic(route.method.name, route.pattern)
            assertEquals(route.access == Access.PUBLIC, public, "Security in the contract does not match $route")
        }
    }
}
