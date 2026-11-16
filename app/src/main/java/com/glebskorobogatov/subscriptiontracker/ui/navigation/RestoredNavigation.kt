package com.glebskorobogatov.subscriptiontracker.ui.navigation

import android.os.Bundle

/** One-time compatibility for Navigation 2.9.5 saved bundles. No retired destination is registered.
 * Only back stacks containing the removed destination are changed, before ComponentActivity restores
 * Compose's SaveableStateRegistry. Other routes, editor drafts and all persistent stores are untouched.
 */
internal object RestoredNavigation {
    // Navigation's serializer descriptor hash (serialName, then each argument name). No runtime route/model.
    internal val removedDestinationId = 31 *
        "com.glebskorobogatov.subscriptiontracker.ui.navigation.PaymentHistory".hashCode() + "subscriptionId".hashCode()
    private const val PREFIX = "android-support-nav:controller:"
    private const val BACK = PREFIX + "backStack"
    private const val STATES = PREFIX + "backStackStates"
    private const val ENTRY_ID = "nav-entry-state:destination-id"

    @Suppress("DEPRECATION") // Read Navigation's existing Bundle format; no Parcelable contents are logged.
    fun sanitize(root: Bundle?) {
        if (root == null) return
        root.classLoader = javaClass.classLoader
        if (root.containsKey(BACK)) {
            val entries = root.getParcelableArrayList<Bundle>(BACK)
            if (entries?.any { it.getInt(ENTRY_ID) == removedDestinationId } == true) {
                val remaining = entries.filter { it.getInt(ENTRY_ID) != removedDestinationId }
                if (remaining.size <= 1) root.remove(BACK) else root.putParcelableArrayList(BACK, ArrayList(remaining))
            }
        }
        val invalid = mutableSetOf<String>()
        root.getStringArrayList(STATES)?.forEach { id ->
            val key = "$STATES:$id"
            if (root.getParcelableArrayList<Bundle>(key)?.any { it.getInt(ENTRY_ID) == removedDestinationId } == true) {
                invalid += id; root.remove(key)
            }
        }
        if (invalid.isNotEmpty()) root.putStringArrayList(STATES, ArrayList(root.getStringArrayList(STATES).orEmpty().filterNot { it in invalid }))
        val dests = root.getIntArray(PREFIX + "backStackDestIds")
        val ids = root.getStringArrayList(PREFIX + "backStackIds")
        if (dests != null && ids != null && dests.size == ids.size) {
            val keep = dests.indices.filter { dests[it] != removedDestinationId && ids[it] !in invalid }
            root.putIntArray(PREFIX + "backStackDestIds", keep.map { dests[it] }.toIntArray())
            root.putStringArrayList(PREFIX + "backStackIds", ArrayList(keep.map { ids[it] }))
        }
        // Compose saves controller bundles within registry bundles/lists. Traverse values without
        // removing unrelated state or registering a hidden fallback screen.
        fun visit(value: Any?) {
            when (value) {
                is Bundle -> sanitize(value)
                is List<*> -> value.forEach { visit(it) }
                is Array<*> -> value.forEach { visit(it) }
            }
        }
        root.keySet().toList().forEach { visit(root.get(it)) }
    }
}
