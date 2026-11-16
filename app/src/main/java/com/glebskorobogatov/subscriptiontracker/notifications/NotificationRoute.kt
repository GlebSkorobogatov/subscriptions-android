package com.glebskorobogatov.subscriptiontracker.notifications

import java.net.URI
import java.util.UUID

/** Explicit intents only; the URI is an identity, never an exported web deep link. */
data class NotificationRoute(val generation: UUID, val subscription: UUID) {
    val identity: String get() = "tracker-reminder://subscription/v1/$generation/$subscription"
    companion object {
        const val OPEN = "com.glebskorobogatov.subscriptiontracker.OPEN_REMINDER"
        fun parse(value: String?): NotificationRoute? = runCatching {
            val uri = URI(requireNotNull(value))
            require(uri.scheme == "tracker-reminder" && uri.rawAuthority == "subscription")
            require(uri.rawQuery == null && uri.rawFragment == null)
            val parts = uri.rawPath.split('/')
            require(parts.size == 4 && parts[1] == "v1")
            fun id(text: String) = UUID.fromString(text).also { require(it.toString() == text) }
            NotificationRoute(id(parts[2]), id(parts[3])).also { require(it.identity == value) }
        }.getOrNull()
    }
}
