package com.glebskorobogatov.subscriptiontracker.data.settings

import com.glebskorobogatov.subscriptiontracker.data.room.*
import kotlinx.coroutines.flow.Flow

/** UI/Worker observation boundary. The composition owner holds DB and settings lease lifetimes. */
interface LocalDataSource {
    val subscriptions: Flow<List<SubscriptionEntity>>
    val settings: Flow<Settings>
}
class RoomLocalDataSource(database: TrackerDatabase, repository: SettingsRepository) : LocalDataSource {
    override val subscriptions = database.subscriptions().observeAll()
    override val settings = repository.settings
}
