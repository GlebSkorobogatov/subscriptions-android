package com.glebskorobogatov.subscriptiontracker.ui.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import com.glebskorobogatov.subscriptiontracker.data.settings.Theme
import com.glebskorobogatov.subscriptiontracker.domain.subscription.SubscriptionState

/** Debug-only synthetic components. No database, production session or navigation dependency. */
@Composable
internal fun DesignSystemGallery(theme: Theme = Theme.SYSTEM) {
    TrackerTheme(theme) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val padding = TrackerDimensions.horizontalPadding(maxWidth)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(padding).testTag("design_system_gallery"),verticalArrangement = Arrangement.spacedBy(TrackerDimensions.sectionSpacing)) {
                Text("Дизайн-система · учебные примеры",style = MaterialTheme.typography.headlineLarge)
                Text("Палитра",style = MaterialTheme.typography.titleLarge)
                val c = TrackerDesign.colors
                listOf("background" to c.background,"surface" to c.surface,"surfaceSubtle" to c.surfaceSubtle,
                    "surfaceRaised" to c.surfaceRaised,"textPrimary" to c.textPrimary,"textSecondary" to c.textSecondary,
                    "divider" to c.divider,"primaryButtonBg" to c.primaryButtonBg,"primaryButtonText" to c.primaryButtonText,
                    "selectedTabBg" to c.selectedTabBg,"calm/success" to c.statusCalm,"soon" to c.statusSoon,"error" to c.error).forEach { (name,color) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(28.dp).background(color,TrackerShapes.field))
                        Text(name)
                    }
                }
                Text("Типографика",style = MaterialTheme.typography.titleLarge)
                Text("Корневой заголовок",style = MaterialTheme.typography.headlineLarge)
                Text("Заголовок секции",style = MaterialTheme.typography.titleMedium)
                Text("Название подписки",style = MaterialTheme.typography.titleSmall)
                Text("Вторичная подпись",style = MaterialTheme.typography.bodySmall,color = c.textSecondary)
                Text("Группа настроек",style = MaterialTheme.typography.labelSmall)
                TrackerMoney(199000); TrackerMoney(29950); TrackerMoney(Long.MAX_VALUE)
                Text("Поверхности",style = MaterialTheme.typography.titleLarge)
                TrackerSurface(Modifier.fillMaxWidth()) { Text("Обычная поверхность",Modifier.padding(16.dp)) }
                TrackerSummaryCard(Modifier.fillMaxWidth()) { Text("Мягкая карточка"); Text("Нейтральная заливка без рамки",color = c.textSecondary) }
                TrackerSurface(Modifier.fillMaxWidth(),TrackerSurfaceRole.RAISED) { Text("Приподнятая поверхность",Modifier.padding(16.dp)) }
                Text("Действия",style = MaterialTheme.typography.titleLarge)
                TrackerPrimaryButton("Основное действие",{},Modifier.fillMaxWidth())
                TrackerPrimaryButton("Недоступное действие",{},Modifier.fillMaxWidth(),enabled = false)
                TrackerPrimaryButton("Сохранение…",{},Modifier.fillMaxWidth(),loading = true)
                TrackerSecondaryButton("Вторичное действие",{})
                TrackerTextButton("Текстовое действие",{})
                TrackerDestructiveButton("Удалить учебный пример",{})
                TrackerIconButton(Icons.Outlined.Close,"Закрыть учебный пример",{})
                Text("Поля",style = MaterialTheme.typography.titleLarge)
                var text by remember { mutableStateOf("") }
                TrackerTextField(text,{ text = it },"Название",supportingText = "Необязательная учебная подпись")
                TrackerTextField("Некорректный ввод",{},"Стоимость",errorMessage = "Проверьте стоимость")
                TrackerTextField("Только чтение",{},"Подпись",readOnly = true)
                TrackerTextField("Недоступное поле",{},"Подпись",enabled = false)
                Text("Капсульный выбор",style = MaterialTheme.typography.titleLarge)
                var single by remember { mutableStateOf(true) }
                TrackerSelectedCapsule("Месяц",single,{ single = true })
                TrackerSelectedCapsule("Год",!single,{ single = false })
                var multiple by remember { mutableStateOf(true) }
                TrackerSelectedCapsule("За 3 дня",multiple,{ multiple = !multiple },multiple = true)
                TrackerSelectedCapsule("Недоступный выбор",true,{},enabled = false)
                Text("12 офлайн-значков",style = MaterialTheme.typography.titleLarge)
                OfflineIconCatalog.entries.chunked(4).forEach { icons ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { icons.forEach { SubscriptionThemeIcon(it.id) } }
                }
                Text("Индикаторы · расчётный период",style = MaterialTheme.typography.titleLarge)
                Text("Нейтральный · осталось 50%, 2 дня")
                TrackerPeriodIndicator(0.5,2,SubscriptionState.ACTIVE,"Активна")
                Text("Янтарный · осталось 20%, 3 дня")
                TrackerPeriodIndicator(0.2,3,SubscriptionState.ACTIVE,"Активна")
                Text("Сегодня · 0%")
                TrackerPeriodIndicator(0.0,0,SubscriptionState.ACTIVE,"Сегодня")
                Text("Начало периода · 100%")
                TrackerPeriodIndicator(1.0,30,SubscriptionState.ACTIVE,"Активна")
                Text("Отменена · доступ ещё 2 дня",color = c.textSecondary)
                TrackerPeriodIndicator(0.1,2,SubscriptionState.CANCELLED,"Отменена")
                Text("Начало неизвестно · осталось 2 дня, без линии",color = c.textSecondary)
                TrackerPeriodIndicator(null,2,SubscriptionState.ACTIVE,"Начало неизвестно")
                Text("Общие состояния",style = MaterialTheme.typography.titleLarge)
                TrackerLoading("Загрузка учебного примера…")
                TrackerEmpty("Пустой учебный список","Данные не добавлены")
                TrackerError("Ошибка учебного примера. Данные сохранены.","Повторить",{})
                TrackerSuccessCapsule("Учебный пример: изменения сохранены")
                var confirm by remember { mutableStateOf(false) }
                var sheet by remember { mutableStateOf(false) }
                TrackerSecondaryButton("Пример подтверждения",{ confirm = true })
                TrackerSecondaryButton("Пример bottom sheet",{ sheet = true })
                if(confirm) TrackerConfirmationDialog("Удалить учебную запись?","Простое центрированное подтверждение.",
                    "Удалить","Отмена",{ confirm = false },{ confirm = false },destructive = true)
                if(sheet) TrackerBottomSheet({ sheet = false }) {
                    Text("Прокручиваемое содержимое",style = MaterialTheme.typography.titleLarge)
                    repeat(16) { Text("Учебная строка ${it+1}",Modifier.padding(vertical = 12.dp)) }
                    TrackerPrimaryButton("Готово",{ sheet = false },Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Preview(name = "R3 · светлая",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "R3 · тёмная",widthDp = 390,heightDp = 844,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "R3 · узкая ×2",widthDp = 320,heightDp = 720,fontScale = 2f)
@Preview(name = "R3 · landscape ×2",widthDp = 640,heightDp = 360,fontScale = 2f,uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun FoundationsPreview() = DesignSystemGallery()

@Preview(name = "Подтверждение · светлая",uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Подтверждение · тёмная",uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable private fun ConfirmationPreview() {
    TrackerTheme {
        TrackerConfirmationDialog(stringResource(R.string.details_delete),stringResource(R.string.details_delete_warning),
            stringResource(R.string.details_confirm),stringResource(R.string.details_cancel),{},{},destructive = true)
    }
}
