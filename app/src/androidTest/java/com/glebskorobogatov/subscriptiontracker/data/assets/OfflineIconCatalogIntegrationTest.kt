package com.glebskorobogatov.subscriptiontracker.data.assets

import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.app.ActiveDataset
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.data.settings.SettingsRepository
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineIconCatalogIntegrationTest {
    @Test fun everyApprovedVectorLoadsFromAndroidResources() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (icon in OfflineIconCatalog.entries) {
            val drawable = context.getDrawable(icon.drawable)
            assertNotNull("Missing vector for ${icon.id}", drawable)
            assertTrue("Invalid width for ${icon.id}", requireNotNull(drawable).intrinsicWidth > 0)
        }
    }
    private fun fixture(block: suspend (TrackerDatabase, SubscriptionUseCases, Context) -> Unit) = runBlocking {
        val base: Context = ApplicationProvider.getApplicationContext()
        val root = File(base.cacheDir, "catalog-${UUID.randomUUID()}").also { it.mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val generation = ActiveDataset(root).getOrCreate()
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val settings = object : SettingsRepository {
                override val settings = flowOf(Settings())
                override suspend fun update(transform: (Settings) -> Settings) = Unit
            }
            block(db, SubscriptionUseCases(db, settings, CurrentDate(LocalDate.of(2027, 1, 1)),
                assetStore = AssetStore(context, generation)), context)
        } finally { db.close(); root.deleteRecursively() }
    }
    private fun draft(id: UUID, logo: UUID? = null, catalog: String? = null) = SubscriptionDraft(id = id, name = "Тест $id", accountLabel = null, price = RubPrice(100), schedule = ScheduleEdit(LocalDate.of(2027, 2, 1), Frequency(FrequencyUnit.MONTH, 1)), logoAssetId = logo, catalogLogoId = catalog)
    private fun edit(name: String, image: ImageEdit) = SubscriptionEdit(name = name, accountLabel = null, price = RubPrice(100), image = image)
    private fun source(context: Context): Uri {
        val file = File(context.filesDir, "input.png")
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    @Test fun catalogCreateEditAndRemovalPersistInRoom() = fixture { db, use, _ ->
        val id = UUID.randomUUID()
        val created = use.createSubscription(draft(id, catalog = "generic-ai"), Instant.now())
        assertEquals("generic-ai", db.subscriptions().find(id)?.catalogLogoId)
        val changed = use.editSubscription(created, edit(created.name, ImageEdit.Catalog("generic-books")), Instant.now())
        assertEquals("generic-books", changed.catalogLogoId)
        assertNull(changed.logoAssetId)
        val removed = use.editSubscription(changed, edit(changed.name, ImageEdit.Remove), Instant.now())
        assertNull(removed.catalogLogoId)
        assertNull(removed.logoAssetId)
    }

    @Test fun switchingBetweenPhotoAndCatalogKeepsSharedAssetUntilLastReference() = fixture { db, use, context ->
        val image = use.stageImage(source(context))
        val first = use.createSubscription(draft(UUID.randomUUID(), logo = image), Instant.now())
        // A second Room owner is permitted by the existing FK; it must keep the shared file alive.
        val second = first.copy(id = UUID.randomUUID(), name = "Другой владелец")
        db.subscriptions().insert(second)
        assertNotNull(use.assetFile(image))
        val catalog = use.editSubscription(first, edit(first.name, ImageEdit.Catalog("generic-music")), Instant.now())
        assertEquals("generic-music", catalog.catalogLogoId)
        assertNull(catalog.logoAssetId)
        assertNotNull(use.assetFile(image))
        assertEquals(image, db.subscriptions().find(second.id)?.logoAssetId)
        val noImage = use.editSubscription(second, edit(second.name, ImageEdit.Remove), Instant.now())
        assertNull(noImage.logoAssetId)
        assertNull(use.assetFile(image))
        val newImage = use.stageImage(source(context))
        val photo = use.editSubscription(catalog, edit(catalog.name, ImageEdit.Set(newImage)), Instant.now())
        assertEquals(newImage, photo.logoAssetId)
        assertNull(photo.catalogLogoId)
        assertNotNull(use.assetFile(newImage))
    }

    @Test fun invalidCatalogAndStaleEditDoNotChangeStoredAppearance() = fixture { db, use, _ ->
        val original = use.createSubscription(draft(UUID.randomUUID(), catalog = "generic-work"), Instant.now())
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            use.editSubscription(original, edit(original.name, ImageEdit.Catalog("unknown")), Instant.now())
        } }
        val changed = use.editSubscription(original, edit(original.name, ImageEdit.Catalog("generic-other")), Instant.now())
        assertThrows(Exception::class.java) { runBlocking {
            use.editSubscription(original, edit(original.name, ImageEdit.Catalog("generic-books")), Instant.now())
        } }
        assertEquals(changed.catalogLogoId, db.subscriptions().find(original.id)?.catalogLogoId)
    }

    @Test fun retiredBrandIdRemainsEditableUntilUserChoosesNewIcon() = fixture { db, use, _ ->
        val created = use.createSubscription(draft(UUID.randomUUID(), catalog = "generic-other"), Instant.now())
        db.subscriptions().update(created.copy(catalogLogoId = "chatgpt"))
        val legacy = requireNotNull(db.subscriptions().find(created.id))
        val renamed = use.editSubscription(legacy, edit("Переименовано", ImageEdit.Keep), Instant.now())
        assertEquals("chatgpt", renamed.catalogLogoId)
        assertEquals("Переименовано", renamed.name)
        val chosen = use.editSubscription(renamed, edit(renamed.name, ImageEdit.Catalog("generic-video")), Instant.now())
        assertEquals("generic-video", db.subscriptions().find(created.id)?.catalogLogoId)
        assertEquals(renamed.priceMinor, chosen.priceMinor)
    }
    @Test fun confirmedDeletionPreservesSharedImageAndOtherHistoryUntilLastOwner() = fixture { db, use, context ->
        val image = use.stageImage(source(context))
        val first = use.createSubscription(draft(UUID.randomUUID(),logo = image),Instant.EPOCH)
        val other = first.copy(id = UUID.randomUUID(),name = "Другой владелец")
        db.subscriptions().insert(other)
        val file = requireNotNull(use.assetFile(image))
        try { use.deleteSubscription(first,false); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(file.exists())
        val edited = use.editSubscription(first,SubscriptionEdit(name = first.name, accountLabel = "Обновлено", price = RubPrice(200)),Instant.ofEpochSecond(1))
        try { use.deleteSubscription(first,true); fail() } catch (_: com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict) { }
        assertTrue(file.exists())
        use.deleteSubscription(edited,true)
        assertTrue(file.exists()); assertNotNull(use.assetFile(image))
        assertFalse(use.forecasts.first().forecast!!.next30Days.charges.any { it.subscriptionId.value == first.id })
        use.deleteSubscription(other,true)
        assertFalse(file.exists()); assertNull(use.assetFile(image))
    }

}
