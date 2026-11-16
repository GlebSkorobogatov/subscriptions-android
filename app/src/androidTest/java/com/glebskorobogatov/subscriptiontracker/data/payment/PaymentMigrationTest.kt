package com.glebskorobogatov.subscriptiontracker.data.payment

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabaseFactory
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentMigrationTest {
    @Test fun v1ToV2PreservesLegacyPaymentWithoutInventingRequestPayload() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val id = UUID.randomUUID(); val name = "subscriptions-$id.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val subscriptionId = UUID.randomUUID().toString(); val paymentId = UUID.randomUUID().toString()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    sql.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", entity.getString("tableName")))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                sql.execSQL("""INSERT INTO subscriptions (id,name,priceMinor,currencyCode,intervalUnit,intervalCount,renewalMode,lifecycleState,anchorYear,anchorMonth,anchorDay,nextCycleIndex,createdAt,updatedAt)
                    VALUES (?, 'Legacy', 100, 'RUB', 'MONTH', 1, 'AUTO', 'ACTIVE', 2027, 1, 31, 0, '2027-01-01T00:00:00Z', '2027-01-01T00:00:00Z')""", arrayOf(subscriptionId))
                sql.execSQL("""INSERT INTO payments (id,subscriptionId,operationId,paidOn,amountMinor,currencyCode,recordedAt,source)
                    VALUES (?, ?, ?, 0, 100, 'USD', '2027-01-01T00:00:00Z', 'MANUAL_HISTORY')""", arrayOf(paymentId, subscriptionId, UUID.randomUUID().toString()))
                sql.version = 1
            }
            val db = TrackerDatabaseFactory.open(context, id)
            try {
                val payment = db.payments().find(UUID.fromString(paymentId))!!
                assertEquals(100L, payment.amountMinor); assertEquals("USD", payment.currencyCode.value)
                assertNull(payment.requestPayload); assertEquals(1, db.subscriptions().all().size)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
