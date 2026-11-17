package com.glebskorobogatov.subscriptiontracker.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*

class CalendarRefreshReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun midnightClockAndZoneChangesRefreshExistingSessionOnly() {
        var calls = 0
        val receiver = CalendarRefreshReceiver { calls++ }
        for (action in listOf(Intent.ACTION_DATE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED)) {
            assertTrue(CalendarRefreshReceiver.filter().hasAction(action))
            // Direct synthetic delivery: never change the system clock or spoof protected broadcasts.
            receiver.onReceive(context,Intent(action))
        }
        assertEquals(3,calls)
    }
    @Test fun unrelatedAndMissingActionsDoNotRefresh() {
        var calls = 0
        val receiver = CalendarRefreshReceiver { calls++ }
        receiver.onReceive(context,Intent(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(context,Intent())
        assertEquals(0,calls)
    }
}
