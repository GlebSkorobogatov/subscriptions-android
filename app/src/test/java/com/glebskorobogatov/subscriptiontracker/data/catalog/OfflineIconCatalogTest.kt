package com.glebskorobogatov.subscriptiontracker.data.catalog

import com.glebskorobogatov.subscriptiontracker.R
import org.junit.Assert.*
import org.junit.Test

class OfflineIconCatalogTest {
    @Test fun approvedIdsAreStableUniqueAndDisjointFromBrands() {
        val expected = listOf("generic-music", "generic-video", "generic-games", "generic-ai",
            "generic-finance", "generic-cloud", "generic-books", "generic-work", "generic-learning",
            "generic-sport", "generic-shopping", OfflineIconCatalog.OTHER_ID)
        assertEquals(expected, OfflineIconCatalog.entries.map { it.id })
        assertEquals(12, OfflineIconCatalog.entries.map { it.id }.distinct().size)
        assertTrue(OfflineIconCatalog.entries.all { it.id.startsWith("generic-") && it.title != 0 && it.drawable != 0 })
        assertEquals(R.string.icon_music, OfflineIconCatalog.find("generic-music")?.title)
    }

    @Test fun nullUnknownAndRetiredBrandIdsResolveToOtherWithoutRewritingStorage() {
        assertEquals(OfflineIconCatalog.OTHER_ID, OfflineIconCatalog.resolve(null).id)
        assertEquals(OfflineIconCatalog.OTHER_ID, OfflineIconCatalog.resolve("chatgpt").id)
        assertEquals(OfflineIconCatalog.OTHER_ID, OfflineIconCatalog.resolve("unrecognized").id)
        assertNull(OfflineIconCatalog.find("chatgpt"))
        assertThrows(IllegalArgumentException::class.java) { OfflineIconCatalog.requireKnown("chatgpt") }
    }
}
