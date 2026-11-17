package com.glebskorobogatov.subscriptiontracker.domain.integration
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.forecast.ForecastEngine
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
class SubscriptionViewsTest {
    private val day = LocalDate.of(2027,1,31)
    private val domain = SubscriptionDomain()
    private fun sub() = domain.create(SubscriptionId.random(), "Сервис", RubPrice(100), ScheduleEdit(day,Frequency(FrequencyUnit.MONTH,1)),day)
    @Test fun dayChangesNeverChangeActiveStateOrRequireConfirmation() {
        val s = sub(); val date = CurrentDate(day)
        date.update(day.plusYears(30))
        assertEquals(listOf(s),groupSubscriptions(listOf(s),date.today.value).active)
        assertEquals(day,domain.nextDue(s.schedule,day))
    }
    @Test fun cancelledAndArchiveGroupsAgreeWithForecastExclusions() {
        val active=sub(); val cancelled=domain.cancel(sub(),day); val archived=domain.archive(sub(),day,true)
        val groups=groupSubscriptions(listOf(archived,cancelled,active),day)
        assertEquals(listOf(active),groups.active); assertEquals(listOf(cancelled),groups.cancelled); assertEquals(listOf(archived),groups.archived)
        assertTrue(ForecastEngine().calculate(listOf(cancelled,archived),day).charges.isEmpty())
    }
    @Test fun changedDateStartsWholeNewSeries() {
        val changed=domain.edit(sub(),day,scheduleEdit=ScheduleEdit(day.plusDays(1),Frequency(FrequencyUnit.MONTH,1)))
        assertEquals(listOf("2027-02-01","2027-03-01"),ForecastEngine().calculate(listOf(changed),day).charges.take(2).map { it.dueDate.toString() })
    }
    @Test fun restoreRetainsIdentityAndUsesOnlyNewScheduleAndPrice() {
        val original=sub(); val archived=domain.archive(original,day,true)
        val next=day.plusDays(1)
        val active=domain.reactivate(archived,RubPrice(234),ScheduleEdit(next,Frequency(FrequencyUnit.WEEK,1)),day)
        assertEquals(original.id,active.id); assertEquals(234,active.price.amountMinor.toInt()); assertNull(active.archivedAt)
    }
}
