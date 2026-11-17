package com.glebskorobogatov.subscriptiontracker.ui.editor

import com.glebskorobogatov.subscriptiontracker.domain.calendar.DefaultRenewalCalendar
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.RecurringSchedule
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class EditorTest {
    private fun fields(date: String = "2027-02-28") = mapOf("date" to date,"unit" to "MONTH","count" to "1","anchorMonth" to "2027-02","anchorDay" to "31")
    @Test fun februaryCanRetain31() {
        val s = EditorViewModel.schedule(fields()); val c = DefaultRenewalCalendar()
        assertEquals(LocalDate.of(2027,2,28),s.firstDue)
        assertEquals(LocalDate.of(2027,3,31),c.plannedCycleDate(RecurringSchedule.starting(s.firstDue,s.frequency,s.anchorDay),1))
    }
    @Test fun leapAnchorReturnsInLeapYear() {
        val s = EditorViewModel.schedule(fields("2028-02-29") + mapOf("anchorMonth" to "2028-02","anchorDay" to "29","unit" to "YEAR"))
        assertEquals(LocalDate.of(2032,2,29),DefaultRenewalCalendar().plannedCycleDate(RecurringSchedule.starting(s.firstDue,s.frequency,s.anchorDay),4))
    }


    @Test fun precisionZeroTwoThreeNeverRounds() {
        assertEquals(123L,Money.parse("1,23",CurrencyCode.of("RUB")).amountMinor)
        assertThrows(IllegalArgumentException::class.java) { CurrencyCode.of("USD") }

    }
    @Test fun rejectsInvalidMoneyAndCurrency() {
        for (value in listOf("","0","-1","1.234","1,2.3","999999999999999999999999999")) {
            try { Money.parse(value,CurrencyCode.of("RUB")); fail(value) } catch (_: IllegalArgumentException) { }
        }
        try { CurrencyCode.of("BAD"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun invalidIntervalAndDateAreRejected() {
        for (f in listOf(fields()+mapOf("count" to "0"),fields()+mapOf("count" to "-1"),fields()+mapOf("count" to "999999999999999"),fields("2027-02-30"))) {
            try { EditorViewModel.schedule(f); fail() } catch (_: Exception) { }
        }
    }
    @Test fun oldAnchorCanBeLoadedWithoutResettingToCycleZero() {
        val s = EditorViewModel.schedule(fields("2030-03-31"))
        assertEquals(LocalDate.of(2030,3,31),s.firstDue)
    }
}
