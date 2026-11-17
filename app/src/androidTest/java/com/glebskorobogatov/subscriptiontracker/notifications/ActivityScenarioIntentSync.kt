package com.glebskorobogatov.subscriptiontracker.notifications

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.glebskorobogatov.subscriptiontracker.app.IsolatedMainActivity
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * ActivityScenario stops following an activity after [android.app.Activity.setIntent]
 * changes the action or data. Notification delivery does that on purpose.
 * [followActivityIntent] keeps scenario.recreate() usable when the final intent field
 * is still visible to the scenario. [whileAlive] finishes the activity without that match.
 */
internal fun ActivityScenario<*>.followActivityIntent() {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    val type = ActivityScenario::class.java
    val lock = type.field("lock").get(this) as ReentrantLock
    lock.lock()
    try {
        val activity = type.field("currentActivity").get(this) as Activity
        val intentField = type.field("startActivityIntent").also { it.makeWritable() }
        intentField.set(this, activity.intent)
        type.field("currentActivityStage").set(this, Stage.RESUMED)
        (type.field("stateChangedCondition").get(this) as Condition).signalAll()
    } finally {
        lock.unlock()
    }
}

internal inline fun <T> ActivityScenario<IsolatedMainActivity>.whileAlive(
    block: (ActivityScenario<IsolatedMainActivity>) -> T,
): T = try {
    block(this)
} finally {
    releaseTracking()
}

internal fun recreateForegroundActivity() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<IsolatedMainActivity>()
            .single()
            .recreate()
    }
    instrumentation.waitForIdleSync()
}

private fun ActivityScenario<*>.releaseTracking() {
    val observer = javaClass.getDeclaredField("activityLifecycleObserver").apply { isAccessible = true }.get(this)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.removeLifecycleCallback(observer as ActivityLifecycleCallback)
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)
            .flatMap { monitor.getActivitiesInStage(it) }
            .filterIsInstance<IsolatedMainActivity>()
            .distinct()
            .forEach { activity -> runCatching { activity.finish() } }
    }
}

private fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }

private fun Field.makeWritable() {
    isAccessible = true
    val flags = Field::class.java.getDeclaredField("accessFlags")
    flags.isAccessible = true
    flags.setInt(this, modifiers and Modifier.FINAL.inv())
}
