package com.glebskorobogatov.subscriptiontracker.notifications

import com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderPresentation
import com.glebskorobogatov.subscriptiontracker.domain.reminders.*
import java.time.format.DateTimeFormatter

interface ReminderWords {
    fun text(key: String, vararg arguments: Any): String
    fun days(value: Long): String
}
data class ReminderCopy(val title: String, val body: String)

/** Resource-backed copy: overdue offsets never participate in the wording. */
object ReminderText {
    fun render(presentation: ReminderPresentation, message: ReminderMessage, words: ReminderWords): ReminderCopy {
        require(message.reasons.isNotEmpty())
        val title = words.text("payment_title", presentation.name)
        val body = message.reasons.sortedBy { it.eventType.name }.joinToString("\n") { reason ->
            require(reason.daysRemaining >= 0)
            val whenText = when (reason.daysRemaining) {
                0L -> words.text("today")
                1L -> words.text("tomorrow")
                else -> words.text("in_days", words.days(reason.daysRemaining))
            }
            val date = reason.targetDate.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
            words.text("payment_body", whenText,
                presentation.price.format(), presentation.price.currency.value, date)
        }
        return ReminderCopy(title, body)
    }
}

/** Russian-only resource copy must keep Russian day forms on devices of any locale.
 * Modulo operates on Long before conversion, including large calendar deltas. */
enum class RussianDayForm { ONE, FEW, MANY }
fun russianDayForm(value: Long): RussianDayForm {
    require(value >= 0)
    return when {
        value % 10 == 1L && value % 100 != 11L -> RussianDayForm.ONE
        value % 10 in 2..4 && value % 100 !in 12..14 -> RussianDayForm.FEW
        else -> RussianDayForm.MANY
    }
}
