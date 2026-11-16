package com.glebskorobogatov.subscriptiontracker.fixtures

import android.content.Context
import com.glebskorobogatov.subscriptiontracker.data.room.InitializationJournal
import java.util.UUID

/** Explicit first-install credit for a new, synthetic UUID. Never used on reopen/error fixtures. */
fun authorizeFreshFixture(context: Context, id: UUID) {
    check(!context.getDatabasePath("subscriptions-$id.db").exists())
    InitializationJournal(context.filesDir, id).locked { authorizeFresh() }
}

/** Teardown only, after fixture readers/Room have closed. Never called by production recovery. */
fun forgetFixtureInitialization(context: Context, databaseName: String) {
    val raw = databaseName.removePrefix("subscriptions-").removeSuffix(".db")
    val id = UUID.fromString(raw)
    check(databaseName == "subscriptions-$id.db")
    for (extension in listOf("state", "next", "lock"))
        java.io.File(context.filesDir, "${InitializationJournal.DIRECTORY}/$id.$extension").delete()
}
