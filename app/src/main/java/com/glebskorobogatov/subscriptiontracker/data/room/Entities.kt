package com.glebskorobogatov.subscriptiontracker.data.room

import androidx.room.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

enum class ReminderEventType { RENEWAL }
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
)], indices = [Index("logoAssetId"), Index("state")])
data class SubscriptionEntity(
    @PrimaryKey val id: UUID, val name: String, val accountLabel: String?,
    val priceMinor: Long, val intervalUnit: FrequencyUnit, val intervalCount: Int,
    val state: SubscriptionState,
    val anchorYear: Int, val anchorMonth: Int, val anchorDay: Int,
    val currentPeriodStartOverride: LocalDate?, val currentPeriodEnd: LocalDate?,
    val cancelledAccessEndExclusive: LocalDate?, val frozenCancelledPeriodStart: LocalDate?,
    val logoAssetId: UUID?, val catalogLogoId: String?,
    val createdAt: Instant, val updatedAt: Instant, val archivedAt: LocalDate?,
) {
    init {
        require((currentPeriodStartOverride == null) == (currentPeriodEnd == null))
        require(logoAssetId == null || catalogLogoId == null)
        SubscriptionRecord(SubscriptionId(id), name, RubPrice(priceMinor),
            RecurringSchedule(RenewalAnchor(YearMonth.of(anchorYear, anchorMonth), anchorDay), Frequency(intervalUnit, intervalCount)),
            accountLabel, SubscriptionAppearance(catalogLogoId, logoAssetId?.toString()), state,
            currentPeriodStartOverride?.let { PeriodStart(it, requireNotNull(currentPeriodEnd)) },
            cancelledAccessEndExclusive, frozenCancelledPeriodStart, archivedAt)
    }
}

@Entity(tableName = "notification_delivery_ledger", foreignKeys = [ForeignKey(
    entity = SubscriptionEntity::class, parentColumns = ["id"], childColumns = ["subscriptionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["subscriptionId", "eventType", "eventDate", "offsetDays", "generation"], unique = true)])
data class NotificationDeliveryEntity(
    @PrimaryKey val id: UUID, val subscriptionId: UUID, val eventType: ReminderEventType,
    val eventDate: LocalDate, val offsetDays: Int, val plannedLocalDate: LocalDate,
    val deliveredAt: Instant?, val state: DeliveryState, val generation: UUID,
    val plannedLocalTime: String = "09:00",
    @ColumnInfo(defaultValue = "1") val payloadVersion: Int = 1,
    val claimToken: UUID? = null,
    val leaseUntil: Instant? = null,
    @ColumnInfo(defaultValue = "0") val failureCount: Int = 0,
    val notBefore: Instant? = null,
) { init { require(offsetDays in setOf(0, 1, 3, 7)) } }
