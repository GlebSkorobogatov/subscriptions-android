package com.glebskorobogatov.subscriptiontracker.domain.subscription

import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.time.ClockDateProvider
import java.time.*
import org.junit.Assert.*
import org.junit.Test

/** JVM portions of T-CAL-01..08, T-MONEY-01 and T-LIFE-01..04; not device acceptance. */
class SubscriptionDomainTest {
    private val domain = SubscriptionDomain()
    private val calendar = DefaultRenewalCalendar()
    private val month = Frequency(FrequencyUnit.MONTH, 1)
    private val today = d("2027-02-10")
    private fun d(value: String) = LocalDate.parse(value)
    private fun id(n: Int) = SubscriptionId.parse("00000000-0000-0000-0000-${n.toString().padStart(12, '0')}")
    private fun row(n: Int = 1, first: String = "2027-01-31", frequency: Frequency = month) =
        domain.create(id(n), "Пример", RubPrice.parse("299,50"), ScheduleEdit(d(first), frequency), d(first),
            accountLabel = "Тест", appearance = SubscriptionAppearance(catalogLogoId = "other"))
    private fun events(first: String, frequency: Frequency, count: Int, day: Int? = null): List<LocalDate> {
        val schedule = RecurringSchedule.starting(d(first), frequency, day)
        return (0 until count).map { calendar.plannedCycleDate(schedule.calendarInput(), it.toLong()) }
    }

    @Test fun monthlyClampingRestoresAllAnchorDays() {
        for (day in 28..31) {
            val values = events("2027-01-$day", month, 4)
            assertEquals(d("2027-02-28"), values[1])
            assertEquals(day, values[2].dayOfMonth)
            assertEquals(minOf(day, 30), values[3].dayOfMonth)
        }
        assertEquals(d("2028-02-29"), events("2028-01-31", month, 2)[1])
    }
    @Test fun leapAnnualAndMultiYearIntervals() {
        assertEquals(listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29").map(::d),
            events("2028-02-29", Frequency(FrequencyUnit.YEAR, 1), 5))
        assertEquals(d("2032-02-29"), events("2028-02-29", Frequency(FrequencyUnit.YEAR, 4), 2)[1])
    }
    @Test fun exactDaysWeeksQuarterAndHalfYear() {
        assertEquals(listOf("2027-01-31", "2027-03-02", "2027-04-01").map(::d),
            events("2027-01-31", Frequency(FrequencyUnit.DAY, 30), 3))
        assertEquals(d("2027-02-14"), events("2027-01-31", Frequency(FrequencyUnit.WEEK, 2), 2)[1])
        assertEquals(d("2028-02-29"), events("2027-11-30", Frequency(FrequencyUnit.MONTH, 3), 2)[1])
        assertEquals(d("2028-02-29"), events("2027-08-31", Frequency(FrequencyUnit.MONTH, 6), 2)[1])
    }
    @Test fun dueTodayThenAutomaticNextPeriodWithoutMutation() {
        val s = row(first = "2027-11-15")
        assertEquals(0L, domain.period(s, d("2027-11-15")).remainingDays)
        assertEquals(d("2027-12-15"), domain.nextDue(s.schedule, d("2027-11-16")))
        assertEquals(d("2027-11-15"), calendar.plannedCycleDate(s.schedule.calendarInput(), 0))
    }
    @Test(timeout = 2000) fun directLookupAfterExtremeAbsence() {
        val series = RecurringSchedule.starting(LocalDate.MIN, Frequency(FrequencyUnit.DAY, 1))
        assertEquals(today, domain.nextDue(series, today))
        assertEquals(LocalDate.MAX, domain.nextDue(series, LocalDate.MAX))
    }
    @Test fun priceNameAndAppearanceEditsPreserve31EvenWhenDisplayIsFebruary28() {
        val original = row()
        val changed = domain.edit(original, today, name = "Другой пример", price = RubPrice(777),
            appearance = SubscriptionAppearance(catalogLogoId = "test"))
        assertSame(original.schedule, changed.schedule)
        assertEquals(d("2027-03-31"), domain.nextDue(changed.schedule, d("2027-03-01")))
        assertEquals(original.state, changed.state)
    }
    @Test fun dateEditReanchorsWholeSeries() {
        val original = row(first = "2027-01-15")
        val changed = domain.edit(original, today, scheduleEdit = ScheduleEdit(d("2027-02-20"), month))
        assertEquals(d("2027-03-20"), domain.nextDue(changed.schedule, d("2027-02-21")))
        assertEquals(d("2027-04-20"), domain.nextDue(changed.schedule, d("2027-03-21")))
        assertEquals(15, original.schedule.anchor.day)
    }
    @Test fun frequencyEditStartsNewSeriesAndClearsPeriodOverride() {
        val original = domain.edit(row(), today, periodStart = PeriodStart(d("2027-02-01"), d("2027-02-28")))
        val changed = domain.edit(original, today, scheduleEdit = ScheduleEdit(d("2027-02-28"), Frequency(FrequencyUnit.WEEK, 1)))
        assertEquals(d("2027-03-07"), domain.nextDue(changed.schedule, d("2027-03-01")))
        assertNull(changed.periodStart)
    }
    @Test fun explicitAnchorRefinementAndOrdinary28AreDifferent() {
        assertEquals(d("2027-03-31"), events("2027-02-28", month, 2, 31)[1])
        assertEquals(d("2027-03-28"), events("2027-02-28", month, 2)[1])
        assertThrows(IllegalArgumentException::class.java) { RecurringSchedule.starting(d("2027-02-27"), month, 31) }
        assertThrows(DateTimeException::class.java) {
            RecurringSchedule.starting(d("2027-02-28"), Frequency(FrequencyUnit.DAY, 1), 31)
        }
    }
    @Test fun invalidIntervalsAndDatesRejectExplicitly() {
        for (n in listOf(0, -1)) assertThrows(IllegalArgumentException::class.java) { Frequency(FrequencyUnit.MONTH, n) }
        assertThrows(IllegalArgumentException::class.java) { domain.create(id(1), " ", RubPrice(1), ScheduleEdit(today, month), today) }
        assertThrows(IllegalArgumentException::class.java) { domain.edit(row(), today, scheduleEdit = ScheduleEdit(today.minusDays(1), month)) }
        assertThrows(CalendarCalculationException::class.java) {
            domain.nextDue(RecurringSchedule.starting(LocalDate.MAX, month), LocalDate.MAX) // Last date is valid.
                .let { calendar.plannedCycleDate(RecurringSchedule.starting(it, month).calendarInput(), 1) }
        }
        assertThrows(CalendarCalculationException::class.java) {
            domain.nextDue(RecurringSchedule.starting(d("2027-01-01"), Frequency(FrequencyUnit.YEAR, Int.MAX_VALUE)), today)
        }
    }
    @Test fun progressFirstPeriodHasCalculatedPreviousBoundary() {
        val s = row(first = "2027-02-28")
        assertEquals(1.0, domain.period(s, d("2027-01-28")).remainingFraction!!, 0.0)
        assertEquals(0.0, domain.period(s, d("2027-02-28")).remainingFraction!!, 0.0)
        assertTrue(domain.period(s, d("2027-03-01")).remainingFraction!! in 0.0..1.0)
        assertEquals(1.0, domain.period(s, d("2020-01-01")).remainingFraction!!, 0.0)
    }
    @Test fun calculatedBoundaryRestores31AndLeapDayBackwards() {
        val monthly = RecurringSchedule.starting(d("2027-02-28"), month, 31)
        assertEquals(d("2027-01-31"), calendar.previousRegularBoundary(monthly.calendarInput(), d("2027-02-28")))
        val yearly = RecurringSchedule.starting(d("2029-02-28"), Frequency(FrequencyUnit.YEAR, 1), 29)
        assertEquals(d("2028-02-29"), calendar.previousRegularBoundary(yearly.calendarInput(), d("2029-02-28")))
    }
    @Test fun overrideAppliesOnlyToItsEndBoundary() {
        val original = row()
        val changed = domain.edit(original, today, periodStart = PeriodStart(d("2027-02-05"), d("2027-02-28")))
        assertEquals(18.0 / 23, domain.period(changed, today).remainingFraction!!, 1e-12)
        assertEquals(domain.period(original, d("2027-03-01")), domain.period(changed, d("2027-03-01")))
        assertThrows(IllegalArgumentException::class.java) {
            domain.edit(original, today, periodStart = PeriodStart(today, d("2027-03-31")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            domain.edit(original, today, periodStart = PeriodStart(d("2027-03-01"), d("2027-02-28")))
        }
    }
    @Test fun unknownOrInvalidStartHidesOnlyFraction() {
        val atMin = row(first = LocalDate.MIN.toString())
        assertNull(domain.period(atMin, LocalDate.MIN).remainingFraction)
        assertEquals(0L, domain.period(atMin, LocalDate.MIN).remainingDays)
        val invalid = row().copy(periodStart = PeriodStart(d("2027-03-01"), d("2027-02-28")))
        assertNull(domain.period(invalid, today).remainingFraction)
        assertNotNull(domain.period(invalid, today).remainingDays)
    }
    @Test fun rubParsingRoundTripAndCheckedArithmetic() {
        assertEquals(29950L, RubPrice.parse("299,50").amountMinor)
        assertEquals("299,50", RubPrice(29950).format())
        assertEquals(1L, RubPrice.parse("0.01").amountMinor)
        assertEquals(Long.MAX_VALUE, RubPrice.parse("92233720368547758.07").amountMinor)
        for (text in listOf("0", "-1", "abc", "1.001", "1e2", " 1", "92233720368547758.08"))
            assertThrows(IllegalArgumentException::class.java) { RubPrice.parse(text) }
        for (n in listOf(0L, -1L)) assertThrows(IllegalArgumentException::class.java) { RubPrice(n) }
        assertThrows(ArithmeticException::class.java) { RubPrice(Long.MAX_VALUE) + RubPrice(1) }
        assertThrows(ArithmeticException::class.java) { RubPrice(Long.MAX_VALUE).multipliedBy(2) }
    }
    @Test fun foreignAmountIsRejectedNeverRelabelled() {
        for (code in listOf("USD", "EUR", "JPY"))
            assertThrows(IllegalArgumentException::class.java) { RubPrice.fromLegacy(Money(29950, CurrencyCode.of(code))) }
        assertEquals(RubPrice(29950), RubPrice.fromLegacy(Money(29950, CurrencyCode.of("RUB"))))
    }
    @Test fun createAndEditKeepIdentityAndIndependentDuplicateNames() {
        assertEquals(SubscriptionState.ACTIVE, row().state)
        assertNotEquals(row(1).id, row(2).id)
        assertEquals(row(1).name, row(2).name)
        val cancelled = domain.cancel(row(), today)
        assertEquals(cancelled.state, domain.edit(cancelled, today, price = RubPrice(10)).state)
    }
    @Test fun cancellationFreezesPeriodAndImmediatelyExcludesCalculations() {
        val cancelled = domain.cancel(row(), today)
        assertEquals(d("2027-02-28"), cancelled.cancelledAccessEndExclusive)
        assertEquals(d("2027-01-31"), cancelled.frozenCancelledPeriodStart)
        assertFalse(cancelled.participatesInCalculations)
        assertNotNull(domain.period(cancelled, d("2027-02-27")).remainingFraction)
        assertNull(domain.period(cancelled, d("2027-02-28")).remainingFraction)
        assertNull(domain.period(cancelled, d("2037-02-28")).remainingFraction)
        assertEquals(SubscriptionState.CANCELLED, cancelled.state)
    }
    @Test fun cancellationEndChangesNeverMoveFrozenStartOrSchedule() {
        val cancelled = domain.cancel(row(), today)
        for (end in listOf("2027-02-01", "2027-03-20", "2027-01-30")) {
            val changed = domain.editEnd(cancelled, d(end))
            assertEquals(cancelled.frozenCancelledPeriodStart, changed.frozenCancelledPeriodStart)
            assertEquals(cancelled.schedule, changed.schedule)
        }
        assertNull(domain.period(domain.editEnd(cancelled, d("2027-01-30")), today).remainingFraction)
    }
    @Test fun cancelledDueTodayAndLegacyUnknownEndHideProgress() {
        assertNull(domain.period(domain.cancel(row(), d("2027-02-28")), d("2027-02-28")).remainingFraction)
        val unknown = row().copy(state = SubscriptionState.CANCELLED)
        assertNull(domain.period(unknown, today).remainingFraction)
        assertNull(domain.period(unknown, today).due)
    }
    @Test fun archiveIsExplicitPreservesFieldsAndHasNoProgress() {
        for (s in listOf(row(), domain.cancel(row(), today))) {
            assertSame(s, domain.archive(s, today, confirmed = false))
            val archived = domain.archive(s, today, confirmed = true)
            assertEquals(s.id, archived.id)
            assertEquals(s.appearance, archived.appearance)
            assertEquals(s.schedule, archived.schedule)
            assertEquals(today, archived.archivedAt)
            assertFalse(archived.participatesInCalculations)
            assertNull(domain.period(archived, today).remainingFraction)
        }
    }
    @Test fun reactivateCancelledAndArchivedResetsCancellationKeepsIdentity() {
        val cancelled = domain.cancel(row(), today)
        for (s in listOf(cancelled, domain.archive(cancelled, today, true))) {
            val active = domain.reactivate(s, RubPrice(10), ScheduleEdit(today, month), today)
            assertEquals(s.id, active.id)
            assertEquals(s.appearance, active.appearance)
            assertEquals(s.name, active.name)
            assertEquals(s.accountLabel, active.accountLabel)
            assertEquals(SubscriptionState.ACTIVE, active.state)
            assertTrue(active.participatesInCalculations)
            assertNull(active.cancelledAccessEndExclusive)
            assertNull(active.frozenCancelledPeriodStart)
            assertNull(active.periodStart)
            assertNull(active.archivedAt)
            assertThrows(IllegalArgumentException::class.java) {
                domain.reactivate(s, RubPrice(10), ScheduleEdit(today.minusDays(1), month), today)
            }
        }
    }
    @Test fun invalidTransitionsAndUnconfirmedDeletionHaveNoEffects() {
        val active = row()
        val cancelled = domain.cancel(active, today)
        val archived = domain.archive(active, today, true)
        assertThrows(IllegalArgumentException::class.java) { domain.cancel(cancelled, today) }
        assertThrows(IllegalArgumentException::class.java) { domain.cancel(archived, today) }
        assertThrows(IllegalArgumentException::class.java) { domain.archive(archived, today, true) }
        assertThrows(IllegalArgumentException::class.java) { domain.editEnd(active, today) }
        assertThrows(IllegalArgumentException::class.java) { domain.reactivate(active, RubPrice(1), ScheduleEdit(today, month), today) }
        assertNull(domain.delete(active, false))
        assertEquals(active.id, domain.delete(active, true)!!.subscriptionId)
    }
    @Test fun sortingIsByStateDateThenStableIdWithUnknownLast() {
        val a = row(1)
        val b = row(2)
        val c = domain.cancel(row(3), today)
        val unknown = row(4).copy(state = SubscriptionState.CANCELLED)
        val earlier = domain.editEnd(domain.cancel(row(5), today), today)
        val archived = domain.archive(row(6), today, true)
        assertEquals(listOf(a, b, earlier, c, unknown), domain.sortedMain(listOf(unknown, archived, b, c, earlier, a), today))
        val old = archived.copy(id = id(7), archivedAt = today.minusDays(1))
        val noDate = archived.copy(id = id(8), archivedAt = null)
        assertEquals(listOf(archived, old, noDate), domain.sortedArchive(listOf(noDate, a, old, archived)))
    }
    @Test fun localDateProviderRecomputesAcrossMidnightAndDstWithoutShiftingSeries() {
        val s = row(first = "2027-03-28")
        val instant = Instant.parse("2027-03-28T23:30:00Z")
        val west = ClockDateProvider(Clock.fixed(instant, ZoneId.of("America/New_York"))).today()
        val east = ClockDateProvider(Clock.fixed(instant, ZoneId.of("Europe/Berlin"))).today()
        assertEquals(d("2027-03-28"), domain.nextDue(s.schedule, west))
        assertEquals(d("2027-04-28"), domain.nextDue(s.schedule, east))
        assertEquals(28, s.schedule.anchor.day)
    }
    @Test fun customStartIsFrozenOnceEvenWhenEndOrScheduleChanges() {
        val active = domain.edit(row(), today, periodStart = PeriodStart(d("2027-02-05"), d("2027-02-28")))
        val cancelled = domain.cancel(active, today, d("2027-03-10"))
        val edited = domain.edit(domain.editEnd(cancelled, d("2027-04-10")), today,
            scheduleEdit = ScheduleEdit(d("2027-02-20"), Frequency(FrequencyUnit.WEEK, 1)))
        assertEquals(d("2027-02-05"), edited.frozenCancelledPeriodStart)
        assertEquals(SubscriptionState.CANCELLED, edited.state)
        assertEquals(59.0 / 64, domain.period(edited, today).remainingFraction!!, 1e-12)
    }
    @Test fun archiveDateTiesAndMissingDatesUseStableId() {
        val first = domain.archive(row(1), today, true)
        val second = domain.archive(row(2), today, true)
        assertEquals(listOf(first, second), domain.sortedArchive(listOf(second, first)))
        assertEquals(listOf(first.copy(archivedAt = null), second.copy(archivedAt = null)),
            domain.sortedArchive(listOf(second.copy(archivedAt = null), first.copy(archivedAt = null))))
    }

}
