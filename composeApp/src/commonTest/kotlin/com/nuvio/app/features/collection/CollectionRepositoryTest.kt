package com.nuvio.app.features.collection

import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionRepositoryTest {
    @Test
    fun `deduplication keeps stable order and latest collection value`() {
        val collections = listOf(
            Collection(id = "duplicate", title = "Old value"),
            Collection(id = "other", title = "Other"),
            Collection(id = "duplicate", title = "Latest value"),
        )

        val result = collections.deduplicatedById()

        assertEquals(listOf("duplicate", "other"), result.map(Collection::id))
        assertEquals("Latest value", result.first().title)
    }

    @Test
    fun `legacy Merge Row visibility field is removed from preserved JSON`() {
        val raw = Json.parseToJsonElement(
            """[{"id":"merge","title":"Merge","isMergeRow":true,"homeRowKeys":["row"],"hideIncludedHomeRows":true}]""",
        )

        val merged = CollectionJsonPreserver.merge(
            json = Json { ignoreUnknownKeys = true },
            rawCollectionsJson = raw,
            collections = listOf(
                Collection(
                    id = "merge",
                    title = "Merge",
                    isMergeRow = true,
                    homeRowKeys = listOf("row"),
                ),
            ),
        )

        assertTrue("hideIncludedHomeRows" !in merged.single().jsonObject)
    }

    @Test
    fun `upsert replaces duplicate collection without changing its position`() {
        val collections = listOf(
            Collection(id = "duplicate", title = "Old value"),
            Collection(id = "other", title = "Other"),
            Collection(id = "duplicate", title = "Stale duplicate"),
        )

        val result = collections.upsertCollectionById(
            Collection(id = "duplicate", title = "Replacement"),
        )

        assertEquals(listOf("duplicate", "other"), result.map(Collection::id))
        assertEquals("Replacement", result.first().title)
    }
}
