package com.glebskorobogatov.subscriptiontracker.ui.details

import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDetails
import com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionUseCases
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.LifecycleCommand
import org.junit.Assert.*
import org.junit.Test

/** Partial T-SCOPE-01: prevent reintroducing retired financial commands into application entry points. */
class NonFinancialContractTest {
    @Test fun applicationContractHasNoFinancialCommandsOrHistoryPayload() {
        val methods = SubscriptionUseCases::class.java.declaredMethods.map { it.name }.toSet()
        for (retired in listOf("confirmPayment","addHistoricalPayment","editPayment","deletePayment","addRefund","editRefund","deleteRefund","suggestNextDate"))
            assertFalse("Retired application command: $retired",methods.any { it.startsWith(retired) })
        assertFalse(SubscriptionDetails::class.java.declaredFields.any { it.name == "history" })
        assertFalse(DetailsAction.entries.any { it.name in setOf("Payment","MarkIssue") })
        assertFalse(LifecycleCommand::class.java.declaredClasses.any { it.simpleName == "MarkPaymentIssue" })
    }
}
