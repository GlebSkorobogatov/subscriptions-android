package com.glebskorobogatov.subscriptiontracker.domain.integration

import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CurrentDate(initial: LocalDate) {
    private val mutable = MutableStateFlow(initial)
    val today = mutable.asStateFlow()
    fun update(today: LocalDate) { mutable.value = today }
}
data class SubscriptionGroups(val active: List<SubscriptionRecord>, val cancelled: List<SubscriptionRecord>, val archived: List<SubscriptionRecord>)
fun groupSubscriptions(rows: List<SubscriptionRecord>, today: LocalDate): SubscriptionGroups {
    val domain = SubscriptionDomain()
    val main = domain.sortedMain(rows, today)
    return SubscriptionGroups(main.filter { it.state == SubscriptionState.ACTIVE },
        main.filter { it.state == SubscriptionState.CANCELLED }, domain.sortedArchive(rows))
}
