package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.*
import java.util.UUID

/** Durable tombstone, deliberately no FK: deleting history must not revive a consumed operationId. */
@Entity(tableName = "retired_payment_operations")
data class RetiredPaymentOperation(@PrimaryKey val operationId: UUID)

/** Prevent delayed AddRefund retries from resurrecting an explicitly deleted refund. */
@Entity(tableName = "retired_refund_ids")
data class RetiredRefundId(@PrimaryKey val id: UUID)

@Dao
interface RetiredPaymentOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun retireRefund(value: RetiredRefundId)
    @Query("SELECT EXISTS(SELECT 1 FROM retired_refund_ids WHERE id = :id)")
    fun containsRefund(id: UUID): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(value: RetiredPaymentOperation)
    @Query("SELECT EXISTS(SELECT 1 FROM retired_payment_operations WHERE operationId = :id)")
    fun contains(id: UUID): Boolean
}
