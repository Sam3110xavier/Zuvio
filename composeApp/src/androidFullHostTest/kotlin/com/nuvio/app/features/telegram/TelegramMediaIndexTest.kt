package com.nuvio.app.features.telegram

import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMediaIndexTest {
    @Test fun indexedLookupUsesPrimaryTitleFallback() {
        assertTrue(
            TelegramSearchMatcher.score(
                "Ramayana.2026.1080p.mkv", "", "Ramayana: Rise of a Legend",
                2026, null, null, "movie",
            ) > 0,
        )
    }
}
