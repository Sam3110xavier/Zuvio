package com.nuvio.app.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InAppLoggerTest {
    @Test
    fun playerDiagnosticsUsePlayerCategory() {
        InAppLogger.clear()
        try {
            InAppLogger.info("NuvioPlayerDiag", "test-player-diagnostic-category")
            InAppLogger.info("PartySync", "test-party-sync-category")

            val entries = InAppLogger.entries.value.associateBy { it.message }

            assertEquals(
                "Player",
                assertNotNull(entries["test-player-diagnostic-category"]).category,
            )
            assertEquals(
                "Player",
                assertNotNull(entries["test-party-sync-category"]).category,
            )
        } finally {
            InAppLogger.clear()
        }
    }
}
