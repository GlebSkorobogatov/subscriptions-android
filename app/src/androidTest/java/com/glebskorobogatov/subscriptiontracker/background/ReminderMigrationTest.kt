package com.glebskorobogatov.subscriptiontracker.background

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glebskorobogatov.subscriptiontracker.data.room.*
import java.time.*
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReminderMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val schemaRoot = "com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase"
    private fun oldDatabase(version: Int, dataset: UUID): SQLiteDatabase {
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("$schemaRoot/$version.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val file = context.getDatabasePath("subscriptions-$dataset.db"); file.parentFile!!.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file,null).also { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val name = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",name))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",name))
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
            db.version = version
        }
    }
    @Test fun migrationThreeToFourPreservesSubscriptionAndTerminalLedger() {
        val dataset = UUID.randomUUID(); val subscription = UUID.randomUUID(); val event = UUID.randomUUID()
        val now = "2027-01-01T00:00:00Z"; val date = LocalDate.of(2027,1,5)
        try {
            oldDatabase(3,dataset).use { old ->
                old.execSQL("INSERT INTO subscriptions(id,name,priceMinor,currencyCode,intervalUnit,intervalCount,renewalMode,lifecycleState,anchorYear,anchorMonth,anchorDay,nextCycleIndex,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(subscription.toString(),"Сохранённая",49900,"RUB","MONTH",1,"AUTO","ACTIVE",2027,1,5,0,now,now))
                old.execSQL("INSERT INTO notification_delivery_ledger(id,subscriptionId,eventType,eventDate,offsetDays,plannedLocalDate,deliveredAt,state,generation) VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(event.toString(),subscription.toString(),"PAYMENT",date.toEpochDay(),3,date.minusDays(3).toEpochDay(),now,"DELIVERED",dataset.toString()))
            }
            val migrated = TrackerDatabaseFactory.open(context,dataset)
            try {
                assertEquals("Сохранённая",migrated.subscriptions().find(subscription)!!.name)
                val record = migrated.deliveries().find(event)!!
                assertEquals(DeliveryState.DELIVERED,record.state); assertEquals(Instant.parse(now),record.deliveredAt)
                assertNull(record.plannedLocalTime); assertNull(record.claimToken); assertEquals(0,record.payloadVersion)
                assertNull(record.leaseUntil); assertNull(record.notBefore); assertEquals(0,record.failureCount)
                assertEquals(4,migrated.openHelper.readableDatabase.version)
            } finally { migrated.close() }
        } finally { context.deleteDatabase("subscriptions-$dataset.db") }
    }
    @Test fun migrationOneToFourUsesExistingSafeMigrationChain() {
        val dataset = UUID.randomUUID()
        try {
            oldDatabase(1,dataset).close()
            val migrated = TrackerDatabaseFactory.open(context,dataset)
            try {
                assertTrue(migrated.subscriptions().all().isEmpty()); assertTrue(migrated.deliveries().all().isEmpty())
                assertEquals(4,migrated.openHelper.readableDatabase.version)
            } finally { migrated.close() }
        } finally { context.deleteDatabase("subscriptions-$dataset.db") }
    }
}
