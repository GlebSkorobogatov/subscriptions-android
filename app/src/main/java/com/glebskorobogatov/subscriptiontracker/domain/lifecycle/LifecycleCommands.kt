package com.glebskorobogatov.subscriptiontracker.domain.lifecycle

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import java.time.LocalDate

sealed interface LifecycleCommand {
    data class ResolveWithDate(val nextDate: LocalDate, val reanchor: Boolean = false) : LifecycleCommand
    /** Null means preserve a known end; never infer an unknown end. */
    data class DisableRenewal(val paidAccessEnd: LocalDate? = null) : LifecycleCommand
    data object Archive : LifecycleCommand
    data class Reactivate(val nextDate: LocalDate, val frequency: Frequency, val mode: RenewalMode, val price: Money) : LifecycleCommand
}

class LifecycleCommands(private val service: SubscriptionLifecycleService = SubscriptionLifecycleService()) {
    fun apply(s: Subscription, command: LifecycleCommand): Subscription = when (command) {
        is LifecycleCommand.ResolveWithDate -> service.resolveWithDate(s, command.nextDate, command.reanchor).subscription
        is LifecycleCommand.DisableRenewal -> service.cancelRenewal(s, command.paidAccessEnd ?: s.paidAccessEnd).subscription
        LifecycleCommand.Archive -> service.archive(s).subscription
        is LifecycleCommand.Reactivate -> service.reactivate(s, command.mode,
            RenewalSchedule(RenewalAnchor.from(command.nextDate), command.frequency), command.price).subscription
    }
}

/** paidAccessEnd is the last paid calendar day, inclusive; no system clock. */
fun autoArchiveExpired(s: Subscription, today: LocalDate): Subscription =
    if (s.state == LifecycleState.CANCELLATION_SCHEDULED && s.paidAccessEnd?.isBefore(today) == true)
        SubscriptionLifecycleService().archive(s).subscription else s
