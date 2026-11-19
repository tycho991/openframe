package io.github.tycho991.openframe.core.auth

import io.github.tycho991.openframe.core.api.InviteDto
import io.github.tycho991.openframe.core.api.PairingResponseDto
import io.github.tycho991.openframe.core.http.ApiJson
import io.github.tycho991.openframe.core.log.LogLevel
import io.github.tycho991.openframe.core.testing.PairingHarness
import io.github.tycho991.openframe.core.testing.PairingHarness.Companion.secretOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Spec `admin-access`, "Log of device management". */
class DeviceLogTest {
    @TempDir
    lateinit var dir: File

    private val h by lazy { PairingHarness(dir) }

    @AfterEach
    fun tearDown() = h.close()

    private fun idOf(name: String) =
        h.clients.clients.value
            .single { it.name == name }
            .id

    @Test
    fun `Pairing, rename, invitation and revocation each leave one line with the IDs`() {
        val phone = h.pairedAuth("Phone")
        val phoneId = idOf("Phone")
        val laptopToken = h.pairing.pair(h.shownCode(), "Laptop").first
        val laptopId = idOf("Laptop")

        assertEquals(200, h.http.request("PATCH", "/api/v1/clients/$laptopId", """{"name":"Old laptop"}""", phone).statusCode())
        val inviteRes = h.http.request("POST", "/api/v1/pairing/invites", null, phone)
        assertEquals(201, inviteRes.statusCode(), inviteRes.body())
        val invite = ApiJson.decodeFromString(InviteDto.serializer(), inviteRes.body())
        val paired = h.http.request("POST", "/api/v1/pairing", """{"secret":"${secretOf(invite.url)}","deviceName":"Tablet"}""")
        assertEquals(201, paired.statusCode(), paired.body())
        val tabletToken = ApiJson.decodeFromString(PairingResponseDto.serializer(), paired.body()).token
        val tabletId = idOf("Tablet")
        assertEquals(204, h.http.request("DELETE", "/api/v1/clients/$laptopId", null, phone).statusCode())

        val lines =
            h.log
                .recent(100)
                .filter { it.tag == "pairing" && it.level == LogLevel.INFO }
                .map { it.message }
        assertEquals(
            listOf(
                "Paired $phoneId: Phone",
                "Paired $laptopId: Laptop",
                "Device $phoneId renamed $laptopId to Old laptop",
                "Device $phoneId created an invitation",
                "Paired $tabletId by invitation of $phoneId: Tablet",
                "Device $phoneId revoked $laptopId: Old laptop",
            ),
            lines,
        )
        val all = h.log.recent(1000).joinToString("\n") { it.message }
        for (secret in listOf(
            phone.getValue("Authorization").removePrefix("Bearer "),
            laptopToken,
            tabletToken,
            secretOf(invite.url),
            invite.url,
        )) {
            assertFalse(secret in all, "the log contains a token, secret or link")
        }
    }
}
