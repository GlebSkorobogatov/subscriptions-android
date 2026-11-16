package com.glebskorobogatov.subscriptiontracker.data.room

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class InitializationStateTest {
    private val id = UUID(0, 1)
    @Test fun eachDurableStateRoundTripsForExactlyItsGeneration() {
        for (state in InitializationState.entries) {
            val encoded = InitializationRecord.encode(id, state)
            assertEquals(state, InitializationRecord.decode(id, encoded))
            assertThrows(StorageUnavailable::class.java) { InitializationRecord.decode(UUID(0, 2), encoded) }
        }
    }
    @Test fun validChecksumDoesNotAuthorizeUnknownVersionOrState() {
        for (text in listOf("", "broken", "1|$id|READY", InitializationRecord.encode(id, InitializationState.READY) + "\n",
            InitializationRecord.encode(id, InitializationState.READY).replace("READY", "AUTHORIZED"))) {
            assertEquals(StorageFailure.INITIALIZATION_UNCERTAIN,
                assertThrows(StorageUnavailable::class.java) { InitializationRecord.decode(id, text) }.failure)
        }
    }
    @Test fun onlyFreshAuthorizationAllowsMissingFileCreation() {
        assertTrue(InitializationRecord.mayCreate(InitializationState.AUTHORIZED))
        assertEquals(StorageFailure.LOST_DATABASE, assertThrows(StorageUnavailable::class.java) {
            InitializationRecord.mayCreate(InitializationState.READY)
        }.failure)
        for (state in listOf(null, InitializationState.CREATING))
            assertEquals(StorageFailure.INITIALIZATION_UNCERTAIN, assertThrows(StorageUnavailable::class.java) {
                InitializationRecord.mayCreate(state)
            }.failure)
    }
    @Test fun crashAfterConsumingCreationCannotReusePreviousPermission() {
        val fresh = InitializationRecord.decode(id, InitializationRecord.encode(id, InitializationState.AUTHORIZED))
        assertTrue(InitializationRecord.mayCreate(fresh))
        val consumed = InitializationRecord.decode(id, InitializationRecord.encode(id, InitializationState.CREATING))
        repeat(3) { assertThrows(StorageUnavailable::class.java) { InitializationRecord.mayCreate(consumed) } }
    }
}
