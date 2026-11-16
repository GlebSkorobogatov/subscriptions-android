package com.glebskorobogatov.subscriptiontracker.data.assets

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.app.ActiveDataset
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDraft
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase
import com.glebskorobogatov.subscriptiontracker.data.settings.Settings
import com.glebskorobogatov.subscriptiontracker.data.settings.SettingsRepository
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.RenewalMode
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetStoreTest {
    private fun isolated(block: (Context, UUID) -> Unit) {
        val base: Context = ApplicationProvider.getApplicationContext()
        val root = File(base.cacheDir, "asset-test-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
            block(context, ActiveDataset(root).getOrCreate())
        } finally { root.deleteRecursively() }
    }
    private fun source(context: Context): File = File(context.filesDir, "sample.png").also { file ->
        file.outputStream().use { Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.TRANSPARENT)
        }.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun privateCopySurvivesLossOfSource() = isolated { context, generation ->
        val input = source(context)
        val store = AssetStore(context, generation)
        val id = runBlocking { store.stage(Uri.fromFile(input)) }
        val metadata = store.prepared(id)
        input.delete()
        store.finish(id)
        assertTrue(metadata.relativePath.startsWith("assets/$generation/images/"))
        assertEquals("image/png", metadata.mimeType)
        assertNotNull(BitmapFactory.decodeFile(store.readable(metadata)?.path))
    }

    @Test fun damagedFileIsNotImported() = isolated { context, generation ->
        val input = File(context.filesDir, "bad.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThrows(Exception::class.java) {
            runBlocking { AssetStore(context, generation).stage(Uri.fromFile(input)) }
        }
    }

    @Test fun oldGenerationCannotStageIntoNewGeneration() = isolated { context, generation ->
        val store = AssetStore(context, generation)
        val input = source(context)
        val another = UUID.randomUUID()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AssetStore(context, another).stage(Uri.fromFile(input)) }
        }
        assertNull(store.pending(another))
    }

    @Test fun discardingAfterAmbiguousSuccessfulSaveDoesNotDeleteCommittedImage() = isolated { context, generation ->
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val settings = object : SettingsRepository {
                override val settings = flowOf(Settings())
                override suspend fun update(transform: (Settings) -> Settings) = Unit
            }
            val store = AssetStore(context, generation)
            val today = LocalDate.of(2027, 1, 1)
            val use = SubscriptionUseCases(db, settings, CurrentDate(today), assetStore = store)
            val imageId = runBlocking { use.stageImage(Uri.fromFile(source(context))) }
            val subscriptionId = UUID.randomUUID()
            runBlocking {
                use.createSubscription(SubscriptionDraft(subscriptionId, "Пример", null, Money(100, CurrencyCode.of("RUB")),
                    RenewalMode.AUTO, RenewalSchedule(RenewalAnchor.from(today), Frequency(FrequencyUnit.MONTH, 1)),
                    logoAssetId = imageId), Instant.now())
                use.discardImage(imageId)
                assertEquals(imageId, db.subscriptions().find(subscriptionId)?.logoAssetId)
                assertNotNull(use.assetFile(imageId))
            }
        } finally { db.close() }
    }
}
