package com.glebskorobogatov.subscriptiontracker.data.room

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StorageSafetyTest {
    @Test fun failedValidationClosesResourceWithoutReplacingFailure() {
        var closed = 0
        val failure = StorageUnavailable(StorageFailure.INCOMPATIBLE)
        assertSame(failure, assertThrows(StorageUnavailable::class.java) {
            validatedResource({ Any() }, { throw failure }, { closed++; throw IOException("fixture") })
        })
        assertEquals(1, closed)
    }
    @Test fun cancelledValidationClosesAndRethrowsCancellation() {
        var closed = false
        val cancel = CancellationException("fixture")
        assertSame(cancel, assertThrows(CancellationException::class.java) {
            validatedResource({ Any() }, { throw cancel }, { closed = true })
        })
        assertTrue(closed)
        assertSame(cancel, assertThrows(CancellationException::class.java) { storageFailure(cancel) })
    }
    @Test fun successDoesNotCloseAndCreationFailureDoesNotInventResource() {
        val token = Any()
        assertSame(token, validatedResource({ token }, {}, { fail("Premature close") }))
        assertThrows(IOException::class.java) {
            validatedResource<Any>({ throw IOException("fixture") }, {}, { fail("No resource") })
        }
    }
    @Test fun errorsAreTypedAndDoNotRetainPrivateDiagnosticText() {
        assertEquals(StorageFailure.TEMPORARY, storageFailure(IOException("fixture-private")).failure)
        val unknown = storageFailure(IllegalStateException("fixture-private"))
        assertEquals(StorageFailure.UNKNOWN, unknown.failure)
        assertNull(unknown.cause)
        assertFalse(unknown.toString().contains("fixture-private"))
        assertEquals(StorageFailure.INCOMPATIBLE,
            storageFailure(IllegalStateException("Room cannot verify the data integrity. fixture-private")).failure)
    }
    @Test fun retriesAreBoundedAndPermanentFailuresNeverRetry() {
        for (failure in StorageFailure.entries) {
            assertEquals(failure == StorageFailure.TEMPORARY, failure.retryWorker(0))
            assertEquals(failure == StorageFailure.TEMPORARY, failure.retryWorker(1))
            assertFalse(failure.retryWorker(2))
            assertFalse(failure.retryWorker(20))
        }
    }
    @Test fun staleWriterIsRejectedUnderTheMutationGateBeforeActionOrReconciliation() = kotlinx.coroutines.runBlocking {
        var changed = false; var signalled = false
        val gate = kotlinx.coroutines.sync.Mutex()
        val changes = com.glebskorobogatov.subscriptiontracker.data.reminders.ReminderChanges(gate,
            validate = {
                assertTrue(gate.isLocked)
                throw StorageUnavailable(StorageFailure.STALE_GENERATION)
            }, request = { signalled = true })
        try { changes.write { changed = true }; fail("Must reject stale writer") }
        catch (error: StorageUnavailable) { assertEquals(StorageFailure.STALE_GENERATION, error.failure) }
        assertFalse(changed); assertFalse(signalled); assertFalse(gate.isLocked)
    }

}
