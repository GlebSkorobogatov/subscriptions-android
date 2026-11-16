package com.glebskorobogatov.subscriptiontracker.domain.model

import java.util.UUID

private fun parseUuid(text: String): UUID {
    val uuid = UUID.fromString(text)
    require(uuid.toString().equals(text, ignoreCase = true)) { "Expected a canonical UUID" }
    return uuid
}

@JvmInline
value class SubscriptionId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): SubscriptionId = SubscriptionId(UUID.randomUUID())
        fun parse(text: String): SubscriptionId = SubscriptionId(parseUuid(text))
    }
}
