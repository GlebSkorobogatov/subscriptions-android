package com.glebskorobogatov.subscriptiontracker.data.assets

import com.glebskorobogatov.subscriptiontracker.data.integration.ImageEdit
import com.glebskorobogatov.subscriptiontracker.data.room.AssetEntity
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ImageEditTest {
    @Test fun keepRemoveAndSetAreDistinct() {
        val id = UUID.randomUUID()
        assertNotEquals(ImageEdit.Keep, ImageEdit.Remove)
        assertEquals(id, (ImageEdit.Set(id)).assetId)
        assertNotEquals(ImageEdit.Catalog("generic-ai"), ImageEdit.Keep)
        assertNotEquals(ImageEdit.Catalog("generic-ai"), ImageEdit.Remove)
        assertNotEquals(ImageEdit.Catalog("generic-ai"), ImageEdit.Set(id))
    }

    @Test fun metadataRejectsTraversalAndAbsolutePaths() {
        for (path in listOf("/tmp/x", "../x", "assets/../x", "assets//x", "C:/x", "assets\\x")) {
            assertThrows(IllegalArgumentException::class.java) {
                AssetEntity(UUID.randomUUID(), path, "image/png", 1, "a".repeat(64), Instant.EPOCH)
            }
        }
    }
}
