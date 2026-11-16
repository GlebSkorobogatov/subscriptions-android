package com.glebskorobogatov.subscriptiontracker.data.integration

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.data.settings.DatasetSettingsFactory
import com.glebskorobogatov.subscriptiontracker.domain.integration.CurrentDate
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.fixtures.*
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionMigrationIntegrationTest {
    @Test fun legacyChainsKeepHistoryAndNewRetiredKeysSurviveReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (version in listOf(1, 2)) {
            val dataset = UUID.randomUUID(); val name = "subscriptions-$dataset.db"
            val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
            val schema = JSONObject(instrumentation.context.assets.open(
                "com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase/$version.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            val subscription = UUID.randomUUID(); val payment = UUID.randomUUID(); val operation = UUID.randomUUID()
            val refund = UUID.randomUUID()
            val retiredOperation = UUID.randomUUID(); val retiredRefund = UUID.randomUUID()
            try {
                SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        fun ddl(raw: String) = raw.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                        sql.execSQL(ddl(entity.getString("createSql")))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (j in 0 until indices.length()) {
                            sql.execSQL(ddl(indices.getJSONObject(j).getString("createSql")))
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                    sql.execSQL("""INSERT INTO subscriptions (id,name,priceMinor,currencyCode,intervalUnit,intervalCount,renewalMode,lifecycleState,anchorYear,anchorMonth,anchorDay,nextCycleIndex,createdAt,updatedAt)
                        VALUES (?, 'Legacy', 999, 'RUB', 'MONTH', 1, 'AUTO', 'ACTIVE', 2027, 1, 31, 0, '2027-01-01T00:00:00Z', '2027-01-01T00:00:00Z')""", arrayOf(subscription.toString()))
                    sql.execSQL("""INSERT INTO payments (id,subscriptionId,operationId,paidOn,amountMinor,currencyCode,recordedAt,source)
                        VALUES (?, ?, ?, 0, 999, 'USD', '2027-01-01T00:00:00Z', 'MANUAL_HISTORY')""", arrayOf(payment.toString(), subscription.toString(), operation.toString()))
                    sql.execSQL("INSERT INTO refunds (id,paymentId,refundedOn,amountMinor,currencyCode) VALUES (?, ?, 0, 300, 'USD')",
                        arrayOf(refund.toString(), payment.toString()))
                    sql.version = version
                }
                val lease = DatasetSettingsFactory.acquire(context, dataset)
                var db = TrackerDatabaseFactory.open(context, dataset)
                try {
                    val date = CurrentDate(LocalDate.of(2027, 1, 31))
                    var use = SubscriptionUseCases(db, lease.repository, date)
                    val details = withTimeout(5000) { use.details(subscription).first() }!!
                    val expectedPayment = PaymentEntity(payment,subscription,operation,LocalDate.ofEpochDay(0),999,
                        CurrencyCode.of("USD"),Instant.parse("2027-01-01T00:00:00Z"),
                        com.glebskorobogatov.subscriptiontracker.domain.lifecycle.PaymentSource.MANUAL_HISTORY)
                    val expectedRefund = RefundEntity(refund,payment,LocalDate.ofEpochDay(0),300,CurrencyCode.of("USD"))
                    withContext(Dispatchers.IO) {
                        assertEquals(expectedPayment,db.payments().forSubscription(subscription).single())
                        assertEquals(expectedRefund,db.refunds().forPayment(payment).single())
                        // Fixture-only pre-existing v4 technical rows, no application receipt command.
                        db.retiredPaymentOperations().insert(RetiredPaymentOperation(retiredOperation))
                        db.retiredPaymentOperations().retireRefund(RetiredRefundId(retiredRefund))
                    }
                    assertEquals(31, details.subscription.anchorDay)
                    db.close(); db = TrackerDatabaseFactory.open(context, dataset)
                    use = SubscriptionUseCases(db, lease.repository, date)
                    assertEquals(details, withTimeout(5000) { use.details(subscription).first() })
                    withContext(Dispatchers.IO) {
                        assertEquals(4, db.openHelper.readableDatabase.version)
                        assertEquals(expectedPayment,db.payments().forSubscription(subscription).single())
                        assertEquals(expectedRefund,db.refunds().forPayment(payment).single())
                        assertTrue(db.retiredPaymentOperations().contains(retiredOperation))
                        assertTrue(db.retiredPaymentOperations().containsRefund(retiredRefund))
                        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                    }
                    // Only explicit, confirmed deletion of this subscription retires its old operation ids.
                    use.deleteSubscription(details.subscription,true)
                    withContext(Dispatchers.IO) {
                        assertTrue(db.retiredPaymentOperations().contains(operation))
                        assertTrue(db.payments().forSubscription(subscription).isEmpty())
                        assertTrue(db.refunds().forPayment(payment).isEmpty())
                    }

                } finally { db.close(); lease.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
