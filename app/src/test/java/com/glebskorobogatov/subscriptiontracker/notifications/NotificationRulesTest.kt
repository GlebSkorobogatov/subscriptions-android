package com.glebskorobogatov.subscriptiontracker.notifications

import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.reminders.*
import java.time.*
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Assert.*
import org.junit.Test

class NotificationRulesTest {
    private val id = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val generation = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val today = LocalDate.of(2027, 1, 4)
    private val words = object : ReminderWords {
        val xml = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .newDocumentBuilder().parse(javaClass.classLoader!!.getResourceAsStream("res/values/notifications.xml"))
        val names = mapOf("payment_title" to "notification_payment_title",
            "today" to "notification_today", "tomorrow" to "notification_tomorrow", "in_days" to "notification_in_days",
            "payment_body" to "notification_payment_body")
        override fun text(key: String, vararg arguments: Any): String {
            val nodes = xml.getElementsByTagName("string")
            val element = (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == names.getValue(key) }
            return String.format(Locale.forLanguageTag("ru"), element.textContent, *arguments)
        }
        override fun days(value: Long): String {
            val name = "notification_days_${russianDayForm(value).name.lowercase(Locale.ROOT)}"
            val nodes = xml.getElementsByTagName("string")
            val element = (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == name }
            return String.format(Locale.ROOT, element.textContent, value)
        }
    }
    private fun message(days: Long = 1): ReminderMessage {
        val event = PlannedReminder(SubscriptionId(id),ReminderEventType.PAYMENT,today.plusDays(days),3,
            today.plusDays(days).minusDays(3).atTime(9,0))
        return ReminderMessage(SubscriptionId(id),today,listOf(CurrentReminderReason(ReminderEventType.PAYMENT,
            today.plusDays(days),days,listOf(event))))
    }
    private fun copy(days: Long, code: String = "RUB", minor: Long = 39900) =
        ReminderText.render(ReminderPresentation("Сервис", Money(minor, CurrencyCode.of(code))), message(days), words)
    @Test fun paymentTodayIsExpectedNotCharged() { assertTrue(copy(0).body.startsWith("Сегодня ожидается платёж 399,00 RUB")) }
    @Test fun paymentTomorrowUsesActualRemainingDayInsteadOfMissedThreeDayOffset() { assertTrue(copy(1).body.startsWith("Завтра")); assertFalse(copy(1).body.contains("3 дня")) }
    @Test fun russianDayFormsUseResources() {
        listOf(2L to "2 дня", 3L to "3 дня", 5L to "5 дней", 11L to "11 дней", 21L to "21 день", 24L to "24 дня").forEach { (day, text) ->
            assertTrue(copy(day).body.startsWith("Через $text"))
        }
    }


    @Test fun renewalTitleNamesSubscriptionWithoutCancellationCopy() {
        val c = copy(1); assertEquals("Скоро продление: Сервис",c.title)
        assertFalse(c.body.contains("крайний срок"))
    }

    @Test fun moneyRetainsJpyZeroPrecision() { assertTrue(copy(1,code="JPY",minor=399).body.contains("399 JPY")) }
    @Test fun moneyRetainsKwdThreePrecisionAndLargeValues() { assertTrue(copy(1,code="KWD",minor=Long.MAX_VALUE).body.contains("9223372036854775,807 KWD")) }
    @Test fun pendingIdentityIsStableAndGenerationBound() {
        val r = NotificationRoute(generation,id); assertEquals(r,NotificationRoute.parse(r.identity))
        assertEquals(r.identity,NotificationRoute(generation,id).identity)
        assertNotEquals(r.identity,NotificationRoute(UUID.randomUUID(),id).identity)
    }
    @Test fun duplicateNamesNeverMergeSubscriptionIdentity() {
        assertNotEquals(NotificationRoute(generation,id).identity,NotificationRoute(generation,UUID.randomUUID()).identity)
        assertEquals(NotificationIdentity(ReminderScheduling.identity(generation,id)),NotificationIdentity(ReminderScheduling.identity(generation,id)))
        assertEquals(0,NotificationIdentity(ReminderScheduling.identity(generation,id)).id)
    }
    @Test fun routeRejectsNoncanonicalAndExternalInput() {
        listOf(null,"", "https://example.com", "tracker-reminder://subscription/v1/not-uuid/$id",
            "${NotificationRoute(generation,id).identity}?x=1", "${NotificationRoute(generation,id).identity}#x",
            "tracker-reminder://evil/v1/$generation/$id", "tracker-reminder://subscription/v1/1-1-1-1-1/$id").forEach { assertNull(NotificationRoute.parse(it)) }
    }
    @Test fun blockedPermissionPreventsAnyReason() { assertFalse(NotificationAccess(false,true,true).allows(message())) }
    @Test fun blockedAppPreventsAnyReason() { assertFalse(NotificationAccess(true,false,true).allows(message())) }
    @Test fun renewalCannotBypassDisabledChannel() { assertFalse(NotificationAccess(true,true,false).allows(message())) }

    @Test fun securityExceptionIsUnavailableNeverDelivered() { assertEquals(PublicationResult.UNAVAILABLE,publicationFailure(SecurityException("denied"))) }
    @Test fun transientSystemFailureUsesBoundedRetryContract() { assertEquals(PublicationResult.RETRYABLE_FAILURE,publicationFailure(IllegalStateException("binder unavailable"))) }
    @Test fun invalidLocalPayloadIsPermanentSkip() { assertEquals(PublicationResult.PERMANENT_SKIP,publicationFailure(IllegalArgumentException("invalid"))) }
    @Test fun enabledChannelAllowsRenewal() { assertTrue(NotificationAccess(true,true,true).allows(message())) }

    @Test fun russianDayFormsHandleZeroTeensAndLongWithoutOverflow() {
        listOf(0L, 11L, 12L, 14L, 111L, Long.MAX_VALUE).forEach { assertEquals(RussianDayForm.MANY, russianDayForm(it)) }
        assertEquals(RussianDayForm.ONE, russianDayForm(101))
        assertEquals(RussianDayForm.FEW, russianDayForm(102))
    }
    @Test(expected = IllegalArgumentException::class) fun negativeRemainingDaysAreRejected() { russianDayForm(-1) }

}
