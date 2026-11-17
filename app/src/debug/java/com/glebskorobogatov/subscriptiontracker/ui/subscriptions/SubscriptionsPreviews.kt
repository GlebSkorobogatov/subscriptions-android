package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.LocalDate
import java.util.UUID

/** Fictional UI fixtures only. Nothing is inserted into Room or loaded by the launcher. */
internal object SubscriptionsPreviewData {
    val video = SubscriptionCardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Видеосервис", "Личный",
        Money(49900, CurrencyCode.of("RUB")), Frequency(FrequencyUnit.MONTH, 1), SubscriptionState.ACTIVE,
        LocalDate.of(2027, 6, 17), 2, 0.75)
    val family = video.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000002"), accountLabel = "Семейный",
        price = Money(1999, CurrencyCode.of("RUB")), eventDate = LocalDate.of(2027, 6, 24), remainingDays = 9, remainingFraction = 0.5)
    val cancelled = video.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000003"), name = "Облачное хранилище", accountLabel = null,
        status = SubscriptionState.CANCELLED, eventDate = null, remainingDays = null, remainingFraction = null)
    val otherCancelled = video.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000004"), name = "Музыкальный сервис", accountLabel = null,
        status = SubscriptionState.CANCELLED, eventDate = null, remainingDays = null, remainingFraction = null)
    fun content(active: List<SubscriptionCardModel> = listOf(video, family),
        cancelledRows: List<SubscriptionCardModel> = listOf(cancelled)) = SubscriptionsUiState.Content(
        cancelledRows, active, (active + cancelledRows).associate { it.id to it.name })
}

/** Same screen, header and island as the application, with callbacks owned by the caller. */
@Composable
internal fun SubscriptionsShowcase(state: SubscriptionsUiState, theme: Theme = Theme.SYSTEM,
    onAdd: () -> Unit = {}, onSettings: () -> Unit = {}, onOpen: (UUID) -> Unit = {}, onRetry: () -> Unit = {},panelsVisible: Boolean? = null) {
    TrackerTheme(theme) {
        TrackerRootChrome(TrackerTab.Subscriptions,{ if(it == TrackerTab.Settings) onSettings() },onAdd,visibleOverride = panelsVisible) {
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(panelsVisible) { if(panelsVisible == false) list.scrollToItem(6) }
            SubscriptionsScreen(state,onAdd,onOpen,onRetry,list)
        }
    }
}

@Preview(name = "Подписки • светлая", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Подписки • тёмная", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Подписки • шрифт ×2", widthDp = 320, heightDp = 720, fontScale = 2f)
@Composable private fun ContentPreview() = SubscriptionsShowcase(SubscriptionsPreviewData.content())

@Preview(name = "Пустой экран • светлая", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Пустой экран • тёмная", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun EmptyPreview() = SubscriptionsShowcase(SubscriptionsUiState.Empty())

@Preview(name = "Ошибка", widthDp = 390, heightDp = 844)
@Composable private fun ErrorPreview() = SubscriptionsShowcase(SubscriptionsUiState.Error)

@Preview(name = "Загрузка", widthDp = 390, heightDp = 844)
@Composable private fun LoadingPreview() = SubscriptionsShowcase(SubscriptionsUiState.Loading)

@Preview(name = "R4a · landscape ×2",widthDp = 640,heightDp = 360,fontScale = 2f)
@Composable private fun LandscapeHomePreview() = SubscriptionsShowcase(SubscriptionsPreviewData.content())
