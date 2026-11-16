package com.glebskorobogatov.subscriptiontracker.ui.subscriptions

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.ui.design.*
import java.time.LocalDate
import java.util.UUID

/** Fictional UI fixtures only. Nothing is inserted into Room or loaded by the launcher. */
internal object SubscriptionsPreviewData {
    val video = SubscriptionCardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Видеосервис", "Личный",
        Money(49900, CurrencyCode.of("RUB")), Frequency(FrequencyUnit.MONTH, 1), RenewalMode.AUTO, VisibleStatus.ACTIVE,
        LocalDate.of(2027, 6, 17), 2, 0.75f)
    val family = video.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000002"), accountLabel = "Семейный",
        price = Money(1999, CurrencyCode.of("USD")), eventDate = LocalDate.of(2027, 6, 24), remainingDays = 9, remainingFraction = 0.5f)
    val issue = video.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000003"), name = "Облачное хранилище", accountLabel = null,
        status = VisibleStatus.PAYMENT_ISSUE, eventDate = null, remainingDays = null, remainingFraction = null)
    val awaiting = video.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000004"), name = "Музыкальный сервис", accountLabel = null,
        mode = RenewalMode.MANUAL, status = VisibleStatus.AWAITING_RENEWAL, eventDate = null, remainingDays = null, remainingFraction = null)
    fun content(active: List<SubscriptionCardModel> = listOf(video, family),
        decision: List<SubscriptionCardModel> = listOf(issue),
        forecast: List<Money> = listOf(video.price, family.price)) = SubscriptionsUiState.Content(
        forecast, decision, active, (active + decision).associate { it.id to it.name })
}

/** Same screen, header and island as the application, with callbacks owned by the caller. */
@Composable
internal fun SubscriptionsShowcase(state: SubscriptionsUiState, theme: Theme = Theme.SYSTEM,
    onAdd: () -> Unit = {}, onSettings: () -> Unit = {}, onOpen: (UUID) -> Unit = {}, onRetry: () -> Unit = {}) {
    TrackerTheme(theme) {
        Scaffold(topBar = { SubscriptionsTopBar(onAdd, onSettings) },
            bottomBar = { TrackerBottomNavigation(TrackerTab.Subscriptions, {}) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                SubscriptionsScreen(state, onAdd, onOpen, onRetry)
            }
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
