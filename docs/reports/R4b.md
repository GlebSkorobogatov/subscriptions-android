# R4b — редизайн настроек и управление анимациями

## База и границы

Исходная ветка: `codex/r4a-navigation-home`, принятый SHA `72a89fd57734d0a69ebdcc0980344a36dc084b8b`, Draft PR #10.
До работы проверены точный HEAD и чистое рабочее дерево. Ветка `codex/r4b-settings` создана от этой точки.
SHA реализации: **b6b454f2ef4f9ae73895dd0ae0c34e1e1ef000ed**. Documentation-only commit указан в Draft PR и итоговом сообщении; самоссылка его SHA в этом файле не используется.

Изучены AGENTS, PRD, ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_PLAN, UI_DESIGN_SPEC, DECISIONS №49, №74, №125 и отчёты R3/R4a.
Изменены существующие Settings, codec, ViewModel, экран настроек, проведение темы и анимаций, один фрагмент §3.6 и тесты.
Новая архитектура настроек не создавалась. R5, R6, R7, R8 и H не начинались. PERSONAL-READY не объявляется.
PR #10 и ветка R4a не изменялись. Merge, force push и ручной запуск Actions не выполнялись.

## Разрешённое противоречие о времени

UI_DESIGN_SPEC §3.6 ещё описывал переход в picker времени напоминаний.
Действующие PRD R-NOT-01 и модель `Settings.reminderLocalTime` фиксируют местное время 09:00.
Приоритет отдан продуктовому требованию. В §3.6 заменено только это предложение: дни по умолчанию 3 и 1, время 09:00 одной строкой, picker отсутствует, ранее сохранённое время не читается. Остальные продуктовые требования не переписывались.

На экране строка `settings_time` не clickable, значения picker нет, `settings_time_dialog` не существует. `LocalTime.of(9, 0)` по-прежнему вычисляется, а не хранится.

## Что сделано

Настройки остаются третьим корнем. Верхняя панель, островок, скрытие при прокрутке, insets и восстановление маршрута R4a не переделывались.

Четыре секции на токенах Quiet UI: «Оформление», «Уведомления», «Архив», «О приложении».
Карточки — `surfaceSubtle`, `TrackerShapes.card`, tonalElevation 0. Внешней рамки и внутренних `HorizontalDivider` нет. Новых цветов вне `TrackerColors` нет.

Тема заменяет вертикальный список `RadioButton` тремя миниатюрами: светлая, тёмная, системная. Системная — левая половина светлая, правая тёмная. Выбранный вариант имеет обводку `textPrimary` 2 dp и контурную галочку. Семантика: `Role.RadioButton`, `selectableGroup`, ровно одно selected, подписи «Светлая» / «Тёмная» / «Как в системе». Хранение прежнее: `Theme.SYSTEM` / `LIGHT` / `DARK`. `MainActivity` по-прежнему обновляет светлые/тёмные status и navigation bars через существующий `SideEffect`. Перезапуск не нужен.

При ширине ряда миниатюр меньше 280 dp или fontScale ≥ 1.5 подписи складываются в колонку и не обрезаются. Порог 360 dp здесь не используется: это ширина окна, а внутри карточки места меньше.

Сразу под миниатюрами строка «Анимации». Видимые «Включены» / «Выключены», `Role.Switch`, цель не ниже 48 dp. Переключатель читает сохранённое значение. «Сохраняем настройку…» есть только в фазе Saving и не считается успехом.

«О приложении» берёт `versionName` установленного пакета. Сейчас это «Версия 0.1». Сохранены «Данные» и обе фразы о локальном хранении. Кнопок резервного копирования, экспорта и H нет. Архив открывается из настроек; Back возвращает на настройки, островок на архиве отсутствует.

## Схема хранения

Версия настроек остаётся 1. Room не менялся.

Новый ключ DataStore: `animations_enabled`, `booleanPreferencesKey`.
Поле модели: `animationsEnabled: Boolean = true`.
`SettingsCodec.read`: отсутствующий ключ даёт `true`.
`write` записывает theme, `payment_offsets`, `animations_enabled` и `settings_version`. Неизвестные и выбывшие ключи не удаляются и не читаются. `reminder_time` по-прежнему инертен.

`SettingsViewModel.setAnimations(true|false)` кладёт явный intent `kind=animations`. `applySetting` копирует только это поле. `retryWrite` повторяет тот же intent и не инвертирует значение. Пока фаза не Content или intent ещё pending, следующий вызов отбрасывается, а не ставится в очередь: это прежний контракт `start()`. Чтение идёт через `collectLatest`. Ошибка записи оставляет pending и `settings_uncertain`. Битый intent (`value`, который не проходит `toBooleanStrict`) снимается при восстановлении и не пишется. Выбывшие time/cancellation intent по-прежнему отбрасываются.

Смена темы или анимаций не запрашивает перепланирование. `settingsChangeReconcilesReminders` истинен только при отличии `renewalOffsets`. Пустой набор offsets по-прежнему отключает напоминания и даёт один сигнал. WorkManager, ReminderRules, ledger и PendingIntent не менялись.

## Применение анимаций

Путь: DataStore → `SettingsViewModel.state.data` → `MainActivity` → `TrackerApp(animationsEnabled)` → `TrackerRootChrome` и fade `NavHost`.

`TrackerMotion.transitionMillis` = 220. `durationMillis` возвращает 0, если настройка выключена или системный scale не конечен либо ≤ 0. `trackerMotionDuration` читает `MotionDurationScale` из context window recomposer. Других собственных анимаций в `src/main` нет: только `animateFloatAsState` панелей и fade корней. Индикатор загрузки и системные механизмы не отключались. Смена настройки не создаёт новый `NavController` и не сбрасывает scroll state.

## Совместимость preferences

Старые preferences без `animations_enabled` читаются с темой, offsets и version как раньше, анимации включены. Явные `true` и `false` переживают повторное чтение. Запись анимаций не стирает `reminder_time` и не меняет тему/offsets. Запись темы или offsets не сбрасывает `animationsEnabled`.

## Проверки JVM и сборка

Среда: JDK 21, Android SDK 36, Build Tools 35.0.0, Gradle 8.13, эмулятор API 31. Только синтетические данные.

Итоговая команда после удаления неиспользуемой строки `settings_reminders`:

```text
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

| Проверка | Результат |
|---|---|
| git diff --check | PASS, вывод пустой |
| :app:testDebugUnitTest | PASS, задача выполнена, не UP-TO-DATE: 373 теста, 0 failures, 0 errors, 0 skipped |
| :app:assembleDebug | PASS, выполнена: ресурсы пересобраны, packageDebug не был UP-TO-DATE |
| :app:lintDebug | PASS, выполнена: 0 ошибок, 19 предупреждений |
| :app:compileDebugAndroidTestKotlin | PASS, выполнена |
| Общая Gradle-команда | BUILD SUCCESSFUL in 55s, 61 actionable task: 27 executed, 34 up-to-date |

База R4a была 365 JVM-тестов. Добавлены 8: один codec, пять ViewModel, два update/motion. `SubscriptionsViewModelTest.overflowNeverProducesEmptyOrPartialTotals` в этом прогоне PASS, 0.001 s. Прежняя нестабильность метода не воспроизводилась и не снимается.

Предупреждения lint, все прежние для окружения Cursor: AndroidGradlePluginVersion 2, GradleDependency 9, NewerVersionAvailable 6, ObsoleteSdkInt 1, UnusedResources 1 (`welcome_message`). Зависимости не обновлялись.

Промежуточная сборка до удаления `settings_reminders` тоже была BUILD SUCCESSFUL in 49s (13 executed, 48 up-to-date) с тем же 373/373, но lint дал 20 предупреждений: лишнее UnusedResources на уже неиспользуемую «Напоминания». Строка удалена, итог — 19.

Соответствие двенадцати требуемым JVM-проверкам:

| Проверка | Тест | Результат |
|---|---|---|
| Нет ключа → true; true/false читаются снова; старые preferences | `missingAnimationPreferenceStaysEnabledAndExplicitValuesRoundTrip` | PASS |
| Тема и offsets сохраняются при смене анимаций и наоборот | `animationAssignmentIsExplicitIdempotentAndKeepsThemeAndOffsets`, `themeAndAnimationsDoNotReconcileOffsetsDoAndNeighboursStay` | PASS |
| Pending intent нового типа, восстановление без немедленной записи | `pendingAnimationIntentRestoresWithoutRewritingUntilRetry` | PASS |
| Ошибка записи и идемпотентный retry | `failedAnimationWriteKeepsTheSameIntentForRetry` | PASS |
| Быстрые изменения: в полёте остаётся первое, остальные отбрасываются | `rapidAnimationChangesKeepOnlyTheInFlightAssignment` | PASS |
| Битый intent не пишется | `malformedAnimationIntentIsDiscarded` | PASS |
| Тема и анимации не дают reconciliation; offsets дают | `themeAndAnimationsDoNotReconcileOffsetsDoAndNeighboursStay` | PASS |
| TrackerMotion 220 / 0 / scale 0 / NaN | `trackerMotionIsInstantWhenDisabledOrSystemScaleIsZero` | PASS |

Прежние тесты codec, retired keys и защиты хранилища сохранены.

## Android, API 31

Эмулятор `api31(AVD)`, Android 12. Образ API 33+ в окружении ENV-01 отсутствует и не устанавливался.

Итоговый прогон перечисленных классов: **PASS, 93 теста, 0 failures, 0 errors, 0 skipped, 238.112 s**, `BUILD SUCCESSFUL in 4m 11s`. Пропусков по SDK в XML нет: ветки `SDK_INT >= 33` внутри прошедших методов просто не выполнялись.

| Класс | Тесты | Результат |
|---|---:|---|
| ArchiveSettingsIntegrationTest | 4 | PASS |
| ArchiveSettingsRedesignUiTest | 15 | PASS |
| ArchiveSettingsUiTest | 7 | PASS |
| LegacyNavigationUiTest | 2 | PASS |
| NavigationUiTest | 15 | PASS |
| NotificationNavigationRaceUiTest | 7 | PASS |
| NotificationNavigationUiTest | 14 | PASS |
| NotificationPermissionUiTest | 2 | PASS на путях API 31 |
| R4bGalleryUiTest | 1 | PASS, 11 кадров |
| RootChromeUiTest | 10 | PASS |
| RootNavigationUiTest | 4 | PASS |
| SettingsIntegrationTest | 5 | PASS |
| SettingsRedesignUiTest | 7 | PASS |

`SettingsRedesignUiTest` на реальной activity проверил: каждую миниатюру и ровно одну selected; смену светлой/тёмной темы и bars без перезапуска; системную тему по текущему night mode; сохранение анимаций после recreation; маршрут настроек и прокрутку; пустой набор offsets и фиксированные 09:00 без picker; архив и Back; версию 0.1 и локальные фразы; отсутствие «Резерв» / «Экспорт» / backup; открытие `com.android.settings` и возврат; font scale 2 и landscape с доступным островком после возврата прокрутки к началу.

`RootChromeUiTest` отдельно проверил мгновенное скрытие и появление панелей при выключенных анимациях и, при включённых, что островок ещё нарисован через 32 ms и уходит к 220 ms. Сравнение — средняя яркость прямоугольника, снятого пока панель была видима. Semantics скрытой панели по-прежнему снимаются в начале скрытия: алгоритм R4a не менялся.

`systemAnimatorScaleZeroIsReadAsInstantMotion` записал `animator_duration_scale=0` и проверил `TrackerMotion.durationMillis(true, 0)`. Это не отдельная покадровая съёмка панелей при системном scale. После прогона scale, font scale и портретная ориентация возвращены.

Полный накопленный Android-набор не запускался.

### Первоначальные падения

Первый полный прогон тех же 93 тестов: **FAIL, 6 падений, 0 skipped**, `BUILD FAILED in 4m 22s`.

1. `ArchiveSettingsRedesignUiTest.narrowLargeFontTimeLabelNeverBecomesVerticalColumn` — искал ContentDescription «Назад». С R4a настройки — корень без этой кнопки; класс не входил в 70/70 R4a. Ожидание заменено на заголовок «Настройки», отсутствие «Назад» и выбранный `tab_settings`. Проверки строки времени сохранены.
2. `RootChromeUiTest.disabledAnimationsAndZeroScaleHideOnTheNextFrameWhileEnabledMotionStaysVisible` — `bottom_navigation` уже не displayed. Жест `performTouchInput` продвинул тестовые часы на длительность свайпа, и 220 ms успевали завершиться.
3. `SettingsRedesignUiTest.thumbnailsSelectExactlyOneThemeAndUpdateBarsWithoutRestart` — `settings_theme_check` не находился: `testTag` стоял внутри `clearAndSetSemantics`. Метка вынесена на внешний box.
4. `offsetsStayGlobalTimeStaysFixedAndArchiveReturns` — не появлялось «Напоминания отключены.». Пока запись в Saving, следующие нажатия отбрасываются; последнее нажатие включало день 0. Тест ждёт каждое состояние и день 0 не включает.
5. `aboutShowsInstalledVersionAndLocalDataWithoutBackup` — фраза о локальных данных была ниже прокрутки к версии. Каждая фраза вызывается через `performScrollTo`.
6. `largeFontAndLandscapeKeepLabelsAndTheLastSection` — островок скрыт прокруткой к версии. Перед проверкой список возвращается к началу.

Второй прогон 40 тестов после этих правок: **FAIL, 1 падение**, `BUILD FAILED in 2m 36s`. Снова chrome-тест: через кадр после начала скрытия узел `bottom_navigation` уже снят, потому что `enabled = visible` очищает semantics в начале хода, а не потому что пиксели исчезли. Итоговая проверка сравнивает яркость сохранённых bounds и в финальных 93 вошла как PASS. Диагностические `fail(...)` в коде не оставлены.

## Визуальная приёмка

Кадры сняты `R4bGalleryUiTest` с эмулятора API 31. В Git не добавлялись. Файлы приложены к описанию Draft PR:

- `r4b-settings-light.png` — светлая тема, выбрана «Светлая», анимации включены, 09:00, «За 3 дн., За 1 дн.».
- `r4b-settings-dark.png` — тёмная тема, выбрана «Тёмная».
- `r4b-settings-system.png` — системная тема при ночном режиме: выбранная половинная миниатюра, экран тёмный.
- `r4b-animations-on.png` — переключатель включён, подпись «Включены».
- `r4b-animations-off.png` — переключатель выключен, подпись «Выключены».
- `r4b-offsets.png` — диалог: 7 выкл, 3 и 1 вкл, день продления выкл, «Готово».
- `r4b-offsets-empty.png` — все четыре выключены, «Напоминания отключены.».
- `r4b-notifications-disabled.png` — «Уведомления отключены в системе» и «Настройки уведомлений». Это собранный `NotificationAccess`, не тумблер ОС.
- `r4b-large-font.png` — ширина 320 dp, font scale 2, миниатюры колонкой, подписи не обрезаны.
- `r4b-landscape.png` — тёмная тема, три миниатюры в ряд, подписи помещаются.
- `r4b-bottom.png` — архив, «Версия 0.1», «Данные» и обе локальные фразы целиком.

Композиция, отступы и контраст светлой и тёмной карточек читаются. Обрезки названий тем нет. На первом экране полупрозрачный островок R4a закрывает начало «Данные»; в landscape и при крупном шрифте он закрывает начало карточки уведомлений, пока список не прокручен. После прокрутки вниз панели скрываются, и нижний блок читается полностью. Это прежнее поведение chrome, алгоритм скрытия не менялся. Picker времени и фиктивного backup на кадрах нет.

Реальный переход в системные настройки уведомлений и состояние API 31 проверены `SettingsRedesignUiTest.notificationSettingsOpensSystemScreenAndReflectsCurrentAccess`, не галереей.

## Известные ограничения

- TalkBack не запускался. Проверены Compose semantics, не озвучивание экранным чтецом. Результат TalkBack: **NOT RUN**.
- Диалог POST_NOTIFICATIONS и ветки API 33+ в `NotificationPermissionUiTest`: **NOT RUN**. Совместимого образа нет, постоянный build ENV-01 не менялся.
- Покадровая съёмка панелей именно при `animator_duration_scale=0` не делалась. Системный ноль проверен записью глобальной настройки и чистой функцией длительности. Пиксели 32 ms / 220 ms сняты для пользовательской настройки.
- Кадр «уведомления отключены» — fixture галереи. Состояние живого `NotificationManager` проверено отдельным UI-тестом.
- Быстрые последовательные записи не накапливаются: пока идёт первая, остальные вызовы `start()` отбрасываются.
- Существующая фраза диалога «Индивидуальные настройки подписок сохраняют свои значения» не переписывалась. Отдельных напоминаний по подпискам по-прежнему нет.
- Полный накопленный Android-набор: **NOT RUN**.

## Защищённые компоненты

Не изменялись навигационная архитектура R4a, главный экран, геометрия островка, порог и алгоритм скрытия панелей, Room v5 и schema exports, InitializationJournal, generation/mutation guards, SubscriptionDomain, DefaultRenewalCalendar, ForecastEngine, денежная арифметика, ReminderRules, WorkManager, ledger, PendingIntent, AssetStore, Manifest, applicationId, Gradle, зависимости, Backup и D2D.

`SubscriptionUseCases.updateSettings` по-прежнему пишет через `ReminderChanges`. Изменился только предикат: сигнал есть лишь при смене offsets. Это покрыто `SettingsUpdateTest` и `productionUseCasesSignalLifecycleChangesButNotThemeOrChartSettings`.

## Изменённые файлы

- `UI_DESIGN_SPEC.md`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/app/MainActivity.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCases.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/settings/Settings.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsRepository.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsViewModel.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/ArchiveSettingsPreviews.kt`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsTest.kt`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsUpdateTest.kt`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsViewModelTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderInfrastructureTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsRedesignUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/design/RootChromeUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/R4bGalleryUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsRedesignUiTest.kt`
- `docs/reports/R4b.md`
