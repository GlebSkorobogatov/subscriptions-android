package com.glebskorobogatov.subscriptiontracker.data.lifecycle

import com.glebskorobogatov.subscriptiontracker.data.mapping.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LifecycleConflict : IllegalStateException("Подписка изменена или удалена; обновите данные")

class LifecycleRepository(private val database: TrackerDatabase,
    private val domain: com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionDomain = com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionDomain()) {
    private fun current(expected: SubscriptionEntity): SubscriptionEntity {
        val actual = database.subscriptions().find(expected.id)
        if (actual != expected) throw LifecycleConflict()
        return actual
    }

    suspend fun execute(expected: SubscriptionEntity, command: LifecycleCommand, changedAt: Instant, today: LocalDate): SubscriptionEntity =
        withContext(Dispatchers.IO) {
            database.runInTransaction(Callable {
                val stored = current(expected)
                val record = stored.toDomain()
                val result = when (command) {
                    is LifecycleCommand.Cancel -> command.endExclusive?.let { domain.cancel(record, today, it) } ?: domain.cancel(record, today)
                    is LifecycleCommand.EditEnd -> domain.editEnd(record, command.endExclusive)
                    LifecycleCommand.Archive -> domain.archive(record, today, confirmed = true)
                    is LifecycleCommand.Reactivate -> domain.reactivate(record, command.price, command.schedule, today)
                }
                val updated = stored.withDomain(result, changedAt)
                check(database.subscriptions().update(updated) == 1)
                updated
            })
        }

    /** Explicit confirmation and optimistic snapshot are both required; assets/files remain untouched. */
    suspend fun delete(expected: SubscriptionEntity, confirmed: Boolean) = withContext(Dispatchers.IO) {
        require(confirmed) { "Окончательное удаление требует явного подтверждения" }
        database.runInTransaction(Callable {
            val request = requireNotNull(domain.delete(current(expected).toDomain(), confirmed))
            check(database.subscriptions().delete(request.subscriptionId.value) == 1)
        })
    }
}
