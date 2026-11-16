package com.glebskorobogatov.subscriptiontracker.data.lifecycle

import com.glebskorobogatov.subscriptiontracker.data.payment.*
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LifecycleConflict : IllegalStateException("Подписка изменена или удалена; обновите данные")

class LifecycleRepository(private val database: TrackerDatabase,
    private val commands: LifecycleCommands = LifecycleCommands()) {
    private fun current(expected: SubscriptionEntity): SubscriptionEntity {
        val actual = database.subscriptions().find(expected.id)
        if (actual != expected) throw LifecycleConflict()
        return actual
    }

    suspend fun execute(expected: SubscriptionEntity, command: LifecycleCommand, changedAt: Instant): SubscriptionEntity =
        withContext(Dispatchers.IO) {
            database.runInTransaction(Callable {
                val stored = current(expected)
                val updated = stored.withDomain(commands.apply(stored.toDomain(), command), changedAt)
                check(database.subscriptions().update(updated) == 1)
                updated
            })
        }

    /** Explicit confirmation and optimistic snapshot are both required; assets/files remain untouched. */
    suspend fun delete(expected: SubscriptionEntity, confirmed: Boolean) = withContext(Dispatchers.IO) {
        require(confirmed) { "Окончательное удаление требует явного подтверждения" }
        database.runInTransaction(Callable {
            current(expected)
            database.payments().forSubscription(expected.id).forEach {
                database.retiredPaymentOperations().insert(RetiredPaymentOperation(it.operationId))
            }
            check(database.subscriptions().delete(expected.id) == 1)
        })
    }

    /** Entry point for a future app-open/worker check. Returns only records changed by this check. */
    suspend fun autoArchiveExpired(today: LocalDate, changedAt: Instant): Int = withContext(Dispatchers.IO) {
        database.runInTransaction(Callable {
            var count = 0
            database.subscriptions().all().forEach { stored ->
                val before = stored.toDomain()
                val after = com.glebskorobogatov.subscriptiontracker.domain.lifecycle.autoArchiveExpired(before, today)
                if (after != before) {
                    check(database.subscriptions().update(stored.withDomain(after, changedAt)) == 1)
                    count++
                }
            }
            count
        })
    }
}
