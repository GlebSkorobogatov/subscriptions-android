package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

enum class ReminderEventType { PAYMENT, CANCELLATION }
enum class ReminderMode { INHERIT_GLOBAL, CUSTOM }
/** Durable processing metadata; DELIVERED requires a publisher acknowledgement. */
enum class DeliveryState { DELIVERED, SKIPPED, PENDING, PROCESSING }

@Entity(tableName = "assets", indices = [Index(value = ["relativePath"], unique = true)])
data class AssetEntity(
    @PrimaryKey val id: UUID, val relativePath: String, val mimeType: String,
    val sizeBytes: Long, val sha256: String, val createdAt: Instant,
) {
    init {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && '\\' !in relativePath && ':' !in relativePath)
        require(relativePath.split('/').none { it in setOf("", ".", "..") })
        require(sizeBytes >= 0 && mimeType.isNotBlank())
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

@Entity(tableName = "subscriptions", foreignKeys = [ForeignKey(
    entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["logoAssetId"], onDelete = ForeignKey.RESTRICT,
)], indices = [Index("logoAssetId"), Index("lifecycleState")])
data class SubscriptionEntity(
    @PrimaryKey val id: UUID, val name: String, val accountLabel: String?,
    val priceMinor: Long, val currencyCode: CurrencyCode,
    val intervalUnit: FrequencyUnit, val intervalCount: Int,
    val renewalMode: RenewalMode, val lifecycleState: LifecycleState,
    val anchorYear: Int, val anchorMonth: Int, val anchorDay: Int, val nextCycleIndex: Long,
    val oneOffDueDate: LocalDate?, val overrideCycleIndex: Long?,
    val currentPeriodStartOverride: LocalDate?, val paidAccessEndDate: LocalDate?,
    val cancellationLeadDays: Int?, val logoAssetId: UUID?, val catalogLogoId: String?,
    val createdAt: Instant, val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank())
        require(cancellationLeadDays == null || cancellationLeadDays >= 0)
        require((oneOffDueDate == null) == (overrideCycleIndex == null))
        // Reuse domain validation, never recalculate or persist a competing due date.
        val schedule = RenewalSchedule(RenewalAnchor(YearMonth.of(anchorYear, anchorMonth), anchorDay),
            Frequency(intervalUnit, intervalCount), nextCycleIndex,
            oneOffDueDate?.let { CycleOverride(requireNotNull(overrideCycleIndex), it) })
        Subscription(SubscriptionId(id), renewalMode, schedule, Money(priceMinor, currencyCode),
            lifecycleState, paidAccessEndDate, currentPeriodStartOverride)
        DefaultRenewalCalendar().nextScheduledDate(schedule)
    }
}

@Entity(tableName = "payments", foreignKeys = [ForeignKey(entity = SubscriptionEntity::class,
    parentColumns = ["id"], childColumns = ["subscriptionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["subscriptionId", "paidOn"]), Index(value = ["operationId"], unique = true)])
data class PaymentEntity(
    @PrimaryKey val id: UUID, val subscriptionId: UUID, val operationId: UUID,
    val paidOn: LocalDate, val amountMinor: Long, val currencyCode: CurrencyCode,
    val recordedAt: Instant, val source: PaymentSource,
    @ColumnInfo(defaultValue = "NULL") val requestPayload: String? = null,
) { init { Money(amountMinor, currencyCode).requirePositive() } }

@Entity(tableName = "refunds", foreignKeys = [ForeignKey(entity = PaymentEntity::class,
    parentColumns = ["id"], childColumns = ["paymentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["paymentId", "refundedOn"])])
data class RefundEntity(
    @PrimaryKey val id: UUID, val paymentId: UUID, val refundedOn: LocalDate,
    val amountMinor: Long, val currencyCode: CurrencyCode,
) { init { Money(amountMinor, currencyCode).requirePositive() } }

@Entity(tableName = "reminder_overrides", primaryKeys = ["subscriptionId", "eventType"],
    foreignKeys = [ForeignKey(entity = SubscriptionEntity::class, parentColumns = ["id"],
        childColumns = ["subscriptionId"], onDelete = ForeignKey.CASCADE)])
data class ReminderOverrideEntity(
    val subscriptionId: UUID, val eventType: ReminderEventType, val mode: ReminderMode, val offsets: Set<Int>,
) { init { require(offsets.all { it in setOf(0, 1, 3, 7) }) } }

@Entity(tableName = "notification_delivery_ledger", foreignKeys = [ForeignKey(
    entity = SubscriptionEntity::class, parentColumns = ["id"], childColumns = ["subscriptionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["subscriptionId", "eventType", "eventDate", "offsetDays", "generation"], unique = true)])
data class NotificationDeliveryEntity(
    @PrimaryKey val id: UUID, val subscriptionId: UUID, val eventType: ReminderEventType,
    val eventDate: LocalDate, val offsetDays: Int, val plannedLocalDate: LocalDate,
    val deliveredAt: Instant?, val state: DeliveryState, val generation: UUID,
    val plannedLocalTime: String? = null,
    @ColumnInfo(defaultValue = "0") val payloadVersion: Int = 0,
    val claimToken: UUID? = null,
    val leaseUntil: Instant? = null,
    @ColumnInfo(defaultValue = "0") val failureCount: Int = 0,
    val notBefore: Instant? = null,
) { init { require(offsetDays in setOf(0, 1, 3, 7)) } }
