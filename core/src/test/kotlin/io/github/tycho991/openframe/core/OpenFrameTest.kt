package io.github.tycho991.openframe.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenFrameTest {
    @Test
    fun `API liegt unter v1`() {
        assertEquals("/api/v1", OpenFrame.API_PREFIX)
    }
}
