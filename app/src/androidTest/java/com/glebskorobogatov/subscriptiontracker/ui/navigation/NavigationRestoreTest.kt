package com.glebskorobogatov.subscriptiontracker.ui.navigation

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.createGraph
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

// Fixture only: the retired serializer is never registered in the application graph.
@Serializable
@SerialName("com.glebskorobogatov.subscriptiontracker.ui.navigation.PaymentHistory")
private data class RetiredHistoryFixture(val subscriptionId: String)

class NavigationRestoreTest {
    private fun controller(store: ViewModelStore) = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
        setViewModelStore(store)
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
    }
    private fun NavHostController.graph(includeRetired: Boolean) = createGraph(startDestination = Subscriptions) {
        composable<Subscriptions> { }
        composable<SubscriptionDetails> { }
        composable<SubscriptionEditor> { }
        if (includeRetired) composable<RetiredHistoryFixture> { }
    }
    @Test fun oldActiveHistoryRouteRestoresDetailsAndItsDraftWithNoHistoryDestination() = runBlocking {
        withContext(Dispatchers.Main) {
            val oldStore = ViewModelStore(); val newStore = ViewModelStore()
            try {
                val old = controller(oldStore); old.graph = old.graph(true)
                old.navigate(SubscriptionDetails("synthetic"))
                old.currentBackStackEntry!!.savedStateHandle["retainedDraft"] = "preserve"
                old.navigate(RetiredHistoryFixture("synthetic"))
                assertEquals(RestoredNavigation.removedDestinationId,old.currentDestination!!.id)
                val state = requireNotNull(old.saveState())
                val nested = Bundle().apply { putParcelableArrayList("composeRegistry",arrayListOf(state)) }
                RestoredNavigation.sanitize(nested)
                val next = controller(newStore); next.restoreState(state); next.graph = next.graph(false)
                assertTrue(next.currentDestination!!.hasRoute<SubscriptionDetails>())
                assertEquals("synthetic",next.currentBackStackEntry!!.toRoute<SubscriptionDetails>().subscriptionId)
                assertEquals("preserve",next.currentBackStackEntry!!.savedStateHandle.get<String>("retainedDraft"))
                assertNull(next.graph.findNode(RestoredNavigation.removedDestinationId))
            } finally { oldStore.clear(); newStore.clear() }
        }
    }
    @Test fun cachedRetiredStackIsDroppedWhileOtherStacksAndFormStateRemain() {
        val prefix = "android-support-nav:controller:"
        fun entry(id: Int) = Bundle().apply { putInt("nav-entry-state:destination-id",id) }
        val retained = arrayListOf(entry(7))
        val state = Bundle().apply {
            putStringArrayList(prefix+"backStackStates",arrayListOf("retired","editor"))
            putParcelableArrayList(prefix+"backStackStates:retired",arrayListOf(entry(RestoredNavigation.removedDestinationId)))
            putParcelableArrayList(prefix+"backStackStates:editor",retained)
            putIntArray(prefix+"backStackDestIds",intArrayOf(RestoredNavigation.removedDestinationId,7))
            putStringArrayList(prefix+"backStackIds",arrayListOf("retired","editor"))
            putString("unrelatedForm","preserve")
        }
        RestoredNavigation.sanitize(state)
        assertFalse(state.containsKey(prefix+"backStackStates:retired"))
        assertEquals(listOf("editor"),state.getStringArrayList(prefix+"backStackStates"))
        assertEquals(listOf("editor"),state.getStringArrayList(prefix+"backStackIds"))
        assertArrayEquals(intArrayOf(7),state.getIntArray(prefix+"backStackDestIds"))
        @Suppress("DEPRECATION") val actual = state.getParcelableArrayList<Bundle>(prefix+"backStackStates:editor")
        assertEquals(retained,actual); assertEquals("preserve",state.getString("unrelatedForm"))
    }
    @Test fun compatibleEditorRouteAndDraftRestoreWithoutBeingDiscarded() = runBlocking {
        withContext(Dispatchers.Main) {
            val a = ViewModelStore(); val b = ViewModelStore()
            try {
                val old = controller(a); old.graph = old.graph(false); old.navigate(SubscriptionEditor("synthetic"))
                old.currentBackStackEntry!!.savedStateHandle["name"] = "Черновик"
                val state = requireNotNull(old.saveState()); RestoredNavigation.sanitize(state)
                val next = controller(b); next.restoreState(state); next.graph = next.graph(false)
                assertTrue(next.currentDestination!!.hasRoute<SubscriptionEditor>())
                assertEquals("Черновик",next.currentBackStackEntry!!.savedStateHandle.get<String>("name"))
            } finally { a.clear(); b.clear() }
        }
    }
}
