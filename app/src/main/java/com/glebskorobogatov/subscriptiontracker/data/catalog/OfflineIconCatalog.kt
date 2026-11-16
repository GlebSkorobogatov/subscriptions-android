package com.glebskorobogatov.subscriptiontracker.data.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.glebskorobogatov.subscriptiontracker.R

/** IDs are persisted as-is. The generic- prefix cannot collide with the retired brand IDs. */
data class CatalogIcon(val id: String, @param:StringRes val title: Int, @param:DrawableRes val drawable: Int)

object OfflineIconCatalog {
    const val OTHER_ID = "generic-other"
    val entries = listOf(
        CatalogIcon("generic-music", R.string.icon_music, R.drawable.ic_generic_music_note),
        CatalogIcon("generic-video", R.string.icon_video, R.drawable.ic_generic_movie),
        CatalogIcon("generic-games", R.string.icon_games, R.drawable.ic_generic_sports_esports),
        CatalogIcon("generic-ai", R.string.icon_ai, R.drawable.ic_generic_smart_toy),
        CatalogIcon("generic-finance", R.string.icon_finance, R.drawable.ic_generic_account_balance_wallet),
        CatalogIcon("generic-cloud", R.string.icon_cloud, R.drawable.ic_generic_cloud),
        CatalogIcon("generic-books", R.string.icon_books, R.drawable.ic_generic_menu_book),
        CatalogIcon("generic-work", R.string.icon_work, R.drawable.ic_generic_work),
        CatalogIcon("generic-learning", R.string.icon_learning, R.drawable.ic_generic_school),
        CatalogIcon("generic-sport", R.string.icon_sport, R.drawable.ic_generic_fitness_center),
        CatalogIcon("generic-shopping", R.string.icon_shopping, R.drawable.ic_generic_shopping_bag),
        CatalogIcon(OTHER_ID, R.string.icon_other, R.drawable.ic_generic_category),
    )
    private val byId = entries.associateBy(CatalogIcon::id)
    fun find(id: String?): CatalogIcon? = id?.let(byId::get)
    fun resolve(id: String?): CatalogIcon = find(id) ?: byId.getValue(OTHER_ID)
    fun requireKnown(id: String): CatalogIcon = requireNotNull(find(id)) { "Unknown icon ID" }
}
