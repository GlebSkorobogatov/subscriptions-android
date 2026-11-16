package com.glebskorobogatov.subscriptiontracker.domain.lifecycle

import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import java.time.LocalDate

sealed interface LifecycleCommand {
    data class Cancel(val endExclusive: LocalDate? = null) : LifecycleCommand
    data class EditEnd(val endExclusive: LocalDate) : LifecycleCommand
    data object Archive : LifecycleCommand
    data class Reactivate(val price: RubPrice, val schedule: ScheduleEdit) : LifecycleCommand
}
