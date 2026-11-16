package com.glebskorobogatov.subscriptiontracker.domain.integration

import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App owner supplies today on startup/resume. No timer or system clock. */
class CurrentDate(initial: LocalDate) {
    private val mutable = MutableStateFlow(initial)
    val today = mutable.asStateFlow()
    fun update(today: LocalDate) { mutable.value = today }
}

data class SubscriptionGroups(val active: List<Subscription>, val needsDecision: List<Subscription>, val archived: List<Subscription>)
fun groupSubscriptions(rows: List<Subscription>, today: LocalDate): SubscriptionGroups {
    val lifecycle = SubscriptionLifecycleService()
    val active = mutableListOf<Subscription>()
    val decisions = mutableListOf<Subscription>()
    val archived = mutableListOf<Subscription>()
    rows.forEach { row ->
        when (lifecycle.view(row, today).status) {
            VisibleStatus.ACTIVE, VisibleStatus.CANCELLATION_SCHEDULED -> active += row
            VisibleStatus.AWAITING_RENEWAL, VisibleStatus.PAYMENT_ISSUE -> decisions += row
            VisibleStatus.ARCHIVED -> archived += row
        }
    }
    return SubscriptionGroups(active, decisions, archived)
}
