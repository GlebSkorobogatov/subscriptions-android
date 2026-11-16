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
    @Test fun retiredSettingsAndPerSubscriptionReminderCommandsHaveNoRuntimeContract() {
        val settings = com.glebskorobogatov.subscriptiontracker.data.settings.Settings::class.java
        assertFalse(settings.declaredFields.any { it.name in setOf("cancellationOffsetsDefault","chartHorizonMonths","selectedChartCurrency") })
        val settingsCommands = com.glebskorobogatov.subscriptiontracker.ui.settings.SettingsViewModel::class.java.declaredMethods.map { it.name }
        assertFalse(settingsCommands.any { it in setOf("openTime","confirmTime","confirmTimeInput") })
        val financeCommands = com.glebskorobogatov.subscriptiontracker.ui.finance.FinanceViewModel::class.java.declaredMethods.map { it.name }
        assertFalse(financeCommands.any { it in setOf("selectHorizon","selectCurrency") })
        val draftFields = com.glebskorobogatov.subscriptiontracker.data.integration.SubscriptionDraft::class.java.declaredFields.map { it.name }
        assertFalse(draftFields.any { it in setOf("reminders","cancellationLeadDays") })
    }

}
