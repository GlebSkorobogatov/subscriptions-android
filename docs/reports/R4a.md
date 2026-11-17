# R4a — компактная навигация и новый главный экран

## База, границы и состояние приёмки

Исходная ветка: `codex/r3-design-system`, принятый SHA `76a655306ba16fd1b828262f14d4c2c3af1053f1`, Draft PR #9.
До работы проверены точный HEAD и чистое рабочее дерево; новая ветка `codex/r4a-navigation-home` создана непосредственно от этой точки.
SHA реализации: **3ec468ceb348c52f2266e9dcf75cf6765f3584d9**. Итоговый documentation-only commit указан в Draft PR и итоговом сообщении; самоссылка SHA в содержимом коммита не используется.

Изучены AGENTS, PRD, UI_DESIGN_SPEC 2.1, ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_PLAN,
DECISIONS (включая №110, №115–121), отчёты R1–R3, существующие компоненты и gallery.
Изменены навигация, UI-проекции и минимальная компоновка корней. Новая визуальная концепция не создавалась.
R4b, R5, R6, H не выполнялись. PERSONAL-READY не объявляется.

## Карта навигации и восстановление

Три корня: **Подписки → Статистика → Настройки**. Сериализованные имена `Subscriptions`, `Finance`, `Settings` сохранены.
Архив открывается из настроек как вложенный экран. Детали и редактор также вложенные; на всех трёх островок отсутствует.
Сохранён единый NavController: saveState/restoreState/launchSingleTop, штатные сохраняемые состояния списков,
выбранный месяц FinanceViewModel и существующие SavedStateHandle редактора. Повторное нажатие активной вкладки не навигирует.
Back из статистики/настроек возвращает в библиотеку; из архива — в настройки, из редактора — к месту открытия.
Удаление из деталей возвращает к предыдущему экрану. Заголовки корней не дублируются внутри содержимого.

Для старого back stack, где Archive был корнем, проверяется непосредственный родитель: при отсутствии Settings
перестраивается только этот Archive entry с родителем Settings. **Прокрутка именно старого Archive entry при этом сбрасывается**;
данные подписок и черновики не очищаются. Новый вложенный Archive сохраняет entry при переходе в детали и обратно.
LegacyNavigationUiTest моделирует старую топологию настоящим NavController и пересоздание через StateRestorationTester;
это не выполнение старого APK и не доказательство совместимости любого исторического Bundle. Старый sanitizer PaymentHistory сохранён.

NotificationViewModel теперь передаёт уже проверенный UUID поколения в UI-запрос навигации.
Перед отложенным переходом повторно проверяются поколение и существование записи. Missing/stale → главный экран;
повторный тап по текущим деталям не добавляет маршрут. Уведомление во время редактора использует существующее подтверждение
выхода: продолжить редактирование отменяет ожидающий переход, явный отказ от черновика продолжает переход.
Изменён только presentation request; PendingIntent, payload напоминаний, ledger и логические ключи неизменны.

## Островок, верхняя панель и insets

| Параметр | Реализация |
|---|---|
| Островок | Максимум 204 dp × 60 dp, по центру даже на широком экране |
| Поля и targets | Внутри 6 dp; три цели 64 × 48 dp при ширине 204 dp; снаружи горизонтальный запас 16 dp |
| Нижний зазор | 12 dp плюс navigationBars, без полноэкранного обработчика касаний |
| Геометрия | Capsule, тень 2 dp, без рамки |
| Фон островка | Только surfaceRaised имеет alpha **0.85** |
| Иконки / выбранная капсула | Alpha **1.0**; selectedTabBg, selected semantics, роль Tab |
| Иконки | Контурные список / диаграмма / настройки; 26 dp выбранная, 24 dp остальные |
| Подписи | Только русские доступные названия, без видимого текста |
| Верхняя панель | Только фон background имеет alpha **0.94**, текст и кнопки непрозрачны |
| Заголовок / добавить | R3 28 sp SemiBold, слева; отдельная круглая кнопка + справа, target 48 dp |
| Анимация | Общий TrackerMotion, штатно 220 ms; system scale=0 или animationsEnabled=false → 0 ms |

Никакого blur, градиента, dynamic colors или alpha всего островка. Различимость выбранного элемента дополнительно
поддержана геометрией иконки и selected semantics. Контраст foreground над крайними подложками белого/чёрного проверен
JVM: обычный текст верхней панели ≥4.5:1, иконки островка ≥3:1, выбранная иконка ≥3:1.
Восприятие полупрозрачности над реальным прокручиваемым содержимым ещё NOT RUN.

SubcomposeLayout сначала измеряет обе панели, затем предоставляет неизменные при скрытии contentPadding.
Полный viewport содержимого проходит позади полупрозрачных поверхностей. Начало/конец LazyColumn получают измеренные
верхний/нижний размеры плюс 12 dp. Верхняя панель включает safeDrawing top/horizontal; островок — navigationBars bottom/horizontal.
Сами корни применяют horizontal safeDrawing один раз. Вложенные экраны используют Scaffold safeDrawing с consumeWindowInsets;
сохранены штатные IME-механизмы форм. Нет растягивания островка на всю ширину и прозрачной полноэкранной кнопки над содержимым.
Фактическая ширина окна берётся из LocalWindowInfo с LocalDensity; R3 padding 16 dp на узком окне и 20 dp на обычном.

## Единое скрытие панелей

RootPanelController / RootScrollPolicy используются всеми тремя корнями через BindRootList/BindRootScroll.
Учитывается только вертикальный consumed UserInput, переведённый из px в dp, с преобладанием вертикального направления.
Накопленные **24 dp** вниз скрывают обе панели, 24 dp вверх возвращают. Порог равен половине минимальной интерактивной области:
отсекает малые случайные движения без требования длинного свайпа. При смене направления накопление сбрасывается;
после остановки сбрасывается только неполный ход, а выбранная видимость сохраняется. Горизонтальный график и programmatic/SideEffect scroll
не переключают панели. Viewport вверху, короткий/пустой/Loading/Error экран принудительно показывает их.

Анимация — только смещение измеренных overlay; padding и позиция списка не меняются.
Полностью скрытые панели не размещаются layout и не получают касаний; при скрытии выключаются действия и accessibility semantics.
Внешняя область островка не имеет click handler. TalkBack touch exploration закрепляет панели видимыми.
Готов вход animationsEnabled для R4b, без нового DataStore-ключа или переключателя.
Реальные жесты, системный reduced motion и TalkBack — Android/manual NOT RUN, а не доказаны одной JVM-политикой.

## Библиотека, сортировка и строка

Главная библиотека больше не читает `use.forecasts`: combine(`use.subscriptions`, `use.date.today`) строит карточки
на Dispatchers.Default и distinctUntilChanged. Полный 12-месячный прогноз не запускается для первоначального открытия библиотеки.
Реактивные rows/today остаются единым согласованным набором для списка; mapping/read failures не превращаются в пустой успех.
Отдельные ошибки календарной проекции сохраняют проблемную строку и остальные подписки; StorageBlocked остаётся блокирующим.
Финансовая ошибка и Retry находятся на статистике. Статистика сохраняет отдельный существующий финансовый поток R2a-fix.
Если FinanceViewModel уже создан и сохранён навигацией, его существующий observer может оставаться активным; он не является источником списка.

Удалены ForecastSummary и его поле из SubscriptionsUiState.Content, заголовки ACTIVE/CANCELLED, разделители,
карточки строк, chevron и дополнительная кнопка настроек главного экрана. Новых фильтров нет.
Список UUID-keyed: ACTIVE по ближайшей дате, unknown последними, tie-break UUID; затем CANCELLED по exclusive access end,
unknown последними, tie-break UUID. ARCHIVED исключены; одинаковые названия не объединяются. Статус записи сортировка не меняет.

Строка: слева значок 54 × 54 dp с существующим R3 оформлением; справа от него единый текстовый блок.
Название, account label и компактная дата/оставшиеся дни слева; точная сумма TrackerMoney и периодичность справа.
Для узкой текстовой области <240 dp либо fontScale ≥1.5 денежная часть переносится в отдельную строку с правым выравниванием.
Высота контентная, вертикальные поля 16 dp, промежуток значок/текст 12 dp, без фиксированных 98 dp.
Никакого обрезания строк maxLines/ellipsis. Вся строка — единая clickable область, открывающая детали.

TrackerPeriodIndicator расположен ниже **всего** текстового блока, включая ширину цены, а не под значком.
Доменный Double, порог ≤20% одновременно с ≤3 днями, нейтральный/янтарный стиль и отсутствие фиктивной линии сохранены R3-компонентом.
UI календарь повторно не вычисляет. «Сегодня» при нуле; неизвестная дата не выдумывается.
CANCELLED имеет вторичный читаемый текст и плашку «Отменена», без alpha всей строки.
После окончания доступа плашка и запись остаются, индикатор скрывает доменная проекция; автоархивирования нет.

Empty — локальная нейтральная контурная иллюстрация, «Подписок пока нет», пояснение и одна основная CTA
«Добавить подписку». Верхний + и островок сохранены. Архив-only имеет отдельное пояснение.
Loading/Empty/Error/StorageBlocked и ошибка отдельной даты не смешиваются.

## Проверки и фактические результаты

Среда: JDK 21, Android SDK 36, Build Tools 35.0.0. Только синтетические fixtures.

```text
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

| Проверка | Фактический результат |
|---|---|
| git diff --check | PASS |
| :app:testDebugUnitTest | PASS: 349 выполнено, 0 failures/errors/skipped по JUnit XML |
| :app:assembleDebug | PASS |
| :app:lintDebug | PASS: 0 ошибок, 8 предупреждений — 6 NewerVersionAvailable, ObsoleteSdkInt, UnusedResources |
| :app:compileDebugAndroidTestKotlin | PASS: 407 @Test-аннотаций методов в скомпилированных class files |
| Общая Gradle-команда | BUILD SUCCESSFUL, 2m 20s, 61 actionable tasks: 25 executed, 36 up-to-date |
| Android/UI tests / реальные screenshots | NOT RUN — устройство/renderer недоступны |

В промежуточных запусках устранены ошибки компиляции адаптируемых тестов после удаления Home.forecast и рассинхронизация
из-за изменения исходников во время сборки. Итоговая команда выполнена с неизменяемыми source/test inputs.
Шесть новых предупреждений ConfigurationScreenWidthHeight устранены общим LocalWindowInfo-адаптером;
зависимости/существующие восемь предупреждений не изменялись. Недоступная запись Android analytics metrics не помешала Gradle-задачам.

APK-аудит проверяет отсутствие определений отменённых моделей/состояний и сохранение целевых SubscriptionRecord/RubPrice/DefaultRenewalCalendar.
Schema exports 1–5 в debug APK совпадают с исходниками, а исходники — побайтово с принятой базой.
Debug gallery доступна только прежней debug-инфраструктуре, в пользовательскую навигацию не добавлена.

Точное изменение тестов относительно принятой базы:

| Набор | Прежних | Без изменения тела метода | Адаптировано тел методов | Добавлено | Удалено | Итого |
|---|---:|---:|---:|---:|---:|---:|
| JVM | 330 | 324 | 6 | 19 | 0 | 349 |
| Android | 389 | 329 | 60 | 18 | 0 | 407 |

Счёт body-адаптаций учитывает изменение самого метода; общие helpers/fixtures могут затрагивать и методы с прежним телом.
Методы старых UI-тестов местами сохраняют исторические имена, но их ожидания адаптированы к утверждённому R4a, а не удалены.
Все 6 JVM/18 Android-регрессий редактора R1b.4-fix сохранены. Финансовые overflow/error/recovery проверки не удалены:
они проверяют доступную библиотеку/детали/реальный EditorViewModel → Room и отдельный FinanceViewModel/Error/автоматическое восстановление.

## Матрица требований

| ID | Реализация / проверка | Фактический результат |
|---|---|---|
| R-UI-01 / T-UI-01 | Три корня; Archive из Settings; root/deep-link/legacy topology tests | PASS статический аудит и JVM состава вкладок; Android navigation/recreation NOT RUN |
| R-UI-02 / T-UI-02 | RootChrome, compact overlay, shared policy/insets | PASS JVM policy/alpha/contrast/motion; реальные жесты/pixels/insets NOT RUN |
| R-UI-03 | Плоская UUID-библиотека, новый ряд, Empty | PASS JVM sorting/source/error isolation; Android layout/CTA NOT RUN |
| R-UI-07 / T-UI-07 | Soft selected capsule, icon-only semantics, 85% background | PASS JVM tokens/contrast; pixel/selected/touch Android NOT RUN |
| R-UI-09 / T-UI-08 | 48dp targets, wrapping/adaptive row, insets, TalkBack pin | PASS статический аудит/API и JVM policy; TalkBack/fontScale=2/landscape/IME NOT RUN |
| R-LIFE-06 / T-UI-09 | Использован R3 period indicator/Double, cancelled projection | PASS JVM прежних порогов и новой expired-cancelled регрессии; фактическая отрисовка NOT RUN |
| T-UI-11, часть R4a | Loading/Empty/Error/Retry/StorageBlocked, отдельный календарный error | PASS JVM read/source isolation; UI переходы и ошибки NOT RUN |
| R1/R2 regression | Сохранённые domain/calendar/storage/editor/forecast/reminder tests | JVM PASS; Android compile PASS, выполнение NOT RUN |

## Визуальная и отложенная приёмка

Реальных изображений не получено. ADB probe завершился кодом −6; доступного устройства/эмулятора и настроенного real Compose renderer нет.
Компиляция @Preview не считалась рендерингом. Android/UI выполнение, screenshot rendering и визуальная приёмка **NOT RUN**.
Подготовлены previews LIGHT/DARK, narrow/fontScale=2, landscape и hidden panels; `R4aGalleryUiTest` — 16 реальных будущих captures,
а не 16 полученных файлов. План: [R4a/README.md](R4a/README.md). Сохранены материалы/план [R3](R3/README.md).

Консолидированные backlog [R1](R1b.5.md), [R2a](R2a.md), [R2b](R2b.md) не переписаны и остаются NOT RUN.
Дополнение R4a: все 18 новых Android-тестов и адаптированные UI-регрессии, фактическое возвращение к scroll offset каждого корня,
реальный process death, rotation, все исторические persisted Bundles, обе темы, fontScale=2, narrow/landscape/длинные цены,
TalkBack, system scale=0, gesture/3-button navigation, IME и касания под/вне скрытых панелей — NOT RUN.
На устройстве также проверить тёплый deep link поверх dirty editor (keep/discard), missing/stale generation, повторный тап,
статистику с overflow и реальное исправление через редактор, сохранение месяца и настройку темы.

## Неизменность защищённых механизмов и ограничения

Полный diff относительно принятого SHA не меняет Room v5/entities/DAO/миграции/schema exports 1–5,
InitializationJournal/preflight/PreservingOpenHelper/поколения и generation/mutation guards, AssetStore/иконки/лицензии,
SubscriptionDomain/DefaultRenewalCalendar, ForecastEngine/денежную арифметику, ReminderRules/ledger/WorkManager,
DataStore-схему, applicationId/Manifest/Backup/D2D, зависимости/Gradle/CI и продуктовые документы.
Изменения EditorScreen — только отмена отложенной навигации при сохранении черновика; optimistic snapshot, save и lifecycle-команды неизменны.
NotificationViewModel затронут только для поколения UI-запроса, не для публикации или логической идентичности уведомления.

Ограничения: старая позиция прокрутки legacy Archive entry при repair не переносится; фактическое UI/системное поведение требует device-приёмки.
Финансовый observer сохранённого FinanceViewModel не отключается новым механизмом: список от него независим, дополнительная lifecycle-переработка не делалась.
Окончательный Settings/Editor/Details/Statistics redesign, animations preference и нормализация стоимости остаются R4b/R5/R6.
R4a подготовлен к независимой **статической** приёмке; визуальная приёмка ожидает реального рендеринга.
Merge, ручной запуск Actions, сброс устройства, автоматическая очистка и R4b не выполнялись.

## Новые и адаптированные тесты с обоснованием

### JVM

- `data/integration/ForecastIsolationTest.kt`. Адаптированы: `actualEventBudgetFailureKeepsAllRowsManageable`, `overflowSnapshotRetainsRowsAndAccessibleLibraryInsteadOfFalseZero`, `readFailureDoesNotPublishRowsOrAnEmptyFinancialSuccess`, `unrepresentableDateRetainsProblemRecordAndOtherSubscriptions`. Home.forecast удалён; финансовые результаты/ошибки проверяются отдельным реальным финансовым источником, а библиотека — по rows/today. Арифметика, восстановление и конкурентные проверки сохранены.
- `data/integration/ForecastModelsAvailabilityTest.kt`. Адаптированы: `overflowDoesNotPreventActualEditorReloadOrDetailsLoading`. Home.forecast удалён; финансовые результаты/ошибки проверяются отдельным реальным финансовым источником, а библиотека — по rows/today. Арифметика, восстановление и конкурентные проверки сохранены.
- `notifications/NotificationViewModelTest.kt`. Адаптированы: `duplicateIntentCannotReopenDetailsAfterBackDuringLookup`. Новые корни/CTA и безопасное отложенное уведомление поверх редактора; проверки поколения, отказа и дедупликации сохранены.
- `ui/design/RootScrollPolicyTest.kt`. Добавлены: `foregroundContrastSurvivesExtremeContentBehindTranslucentPanels`, `futureAnimationPreferenceAndSystemZeroBothGiveInstantTransitions`, `horizontalChartGesturesCannotHideThePanels`, `islandHasThreeRootsAndCompactAccessibleGeometry`, `reversingDirectionResetsHysteresisInsteadOfJittering`, `shortOrEmptyViewportNeverHides`, `stoppingKeepsChosenVisibilityAndDropsOnlyPartialTravel`, `talkBackCanPinPanelsWithoutAnotherPreference`, `tinyIndependentGesturesNeverAccumulateToAnAccidentalHide`, `topAlwaysShowsBothPanels`, `twentyFourDpDownHidesAndTwentyFourUpShows`, `unavailableViewportAndNonfiniteCoordinatesNeverInventScroll`. Новые инварианты R4a: общий chrome/корни/источник библиотеки/отложенный реальный визуальный аудит.
- `ui/subscriptions/HomeProjectionTest.kt`. Добавлены: `actualOwnerNeverCallsFinanceForLibraryAndStorageFailureStillBlocks`, `archivedRowsAreAbsentFromLibraryButRemainInNamesForSafeNavigation`, `cancelledDatesFollowActiveWithUnknownDatesLastAndStableTies`, `duplicateNamesKeepIndependentStableIdsAndChronologicalOrdering`, `expiredCancelledSubscriptionStaysVisibleWithoutProgressOrMutation`, `invalidCalendarIsIsolatedAndSortedAfterKnownActiveDates`, `rowsAndTodayReactWithoutCollectingAFullFinancialProjection`. Новые инварианты R4a: общий chrome/корни/источник библиотеки/отложенный реальный визуальный аудит.
### Android (выполнение NOT RUN)

- `app/DatasetCompositionTest.kt`. Адаптированы: `projectionIgnoresHistoryAndUpdatesFuturePriceAndSchedule`. Home.forecast удалён; финансовые результаты/ошибки проверяются отдельным реальным финансовым источником, а библиотека — по rows/today. Арифметика, восстановление и конкурентные проверки сохранены.
- `notifications/NotificationNavigationUiTest.kt`. Добавлены: `confirmedDiscardContinuesNotificationToCorrectDetailsWithoutSavingDraft`, `deferredNotificationRechecksRecordAfterDeletionBeforeDiscard`, `notificationDuringEditorRequiresDiscardAndKeepCancelsPendingNavigation`. Адаптированы: `deletedSubscriptionHasSafeHomeFallback`. Новые корни/CTA и безопасное отложенное уведомление поверх редактора; проверки поколения, отказа и дедупликации сохранены.
- `notifications/NotificationPermissionUiTest.kt`. Адаптированы: `firstSaveExplainsBeforeRuntimeRequestDenialDoesNotBlockOrRepeat`. Новые корни/CTA и безопасное отложенное уведомление поверх редактора; проверки поколения, отказа и дедупликации сохранены.
- `ui/AccessibilityUiTest.kt`. Адаптированы: `archiveLoadingAndReadErrorNeverRenderEmptyUntilSuccessfulRetry`, `enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/ArchiveSettingsRedesignUiTest.kt`. Адаптированы: `aboutShowsVersionAndDataInfoWithoutFakeBackupActionOrIsland`, `emptyArchiveIsMinimalAndIslandKeepsArchiveSelected`, `largeFontLongArchiveEntryAndNavigationStayAccessible`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/ArchiveSettingsUiTest.kt`. Адаптированы: `archiveReactivationPreservesUuidAndUpdatesActiveForecast`, `duplicateArchiveNamesKeepAccountLabelsAndIndependentCards`, `emptyArchiveAndSettingsReturnToOriginWithoutFourthTab`, `themeChangesImmediatelyAndSurvivesRecreationIncludingSystemChoice`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/DesignSystemUiTest.kt`. Адаптированы: `islandReservesInsetsAndLastScrollableItemStaysAboveIt`, `narrowLargeFontKeepsLabelsAndTouchTargetsAvailable`, `tabsExposeNamesRolesAndExactlyOneSelection`, `wideIslandHasBoundedWidth`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/DetailsUiTest.kt`. Адаптированы: `archiveProvidesMinimalPathBackToDetailsAndReactivation`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/LegacyNavigationUiTest.kt`. Добавлены: `legacyArchiveParentIsRepairedAndNestedArchiveSurvivesStateRestoration`. Новые инварианты R4a: общий chrome/корни/источник библиотеки/отложенный реальный визуальный аудит.
- `ui/NavigationUiTest.kt`. Адаптированы: `archiveTabShowsItsEmptyState`, `floatingIslandKeepsRootContentAboveNavigation`, `orientationChangePreservesRouteAndControls`, `rapidSettingsTapDoesNotCreateDuplicateRoute`, `repeatedTabSelectionDoesNotAccumulateBackStack`, `returnToSubscriptionsFromOtherTabs`, `russianLabelsAndActionSemanticsAreVisible`, `settingsIsSeparateFromThreeTabs`, `settingsRouteAndOriginSurviveRecreation`, `systemBackReturnsToOriginatingTab`, `toolbarBackReturnsToOriginatingTab`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/RootNavigationUiTest.kt`. Добавлены: `archiveIsNestedInSettingsAndBackReturnsToSettingsAfterRecreation`, `createEditorIsNestedAndBackPreservesHomeWithoutBottomIsland`, `exactlyThreeIconTabsExposeRussianNamesAndNoArchiveTab`, `repeatedRootTapDoesNotAddAnotherDestinationAndSystemBackReturnsHome`. Новые инварианты R4a: общий chrome/корни/источник библиотеки/отложенный реальный визуальный аудит.
- `ui/SubscriptionsRedesignUiTest.kt`. Адаптированы: `cancelledRowsHaveNoProblemOrAwaitingStatus`, `captureDarkTopListAndEmptyScreens`, `captureLightTopListAndEmptyScreens`, `duplicateNamesKeepLabelsDividersAndCorrectUuid`, `forecastRendersExactRubEstimateWithoutForeignCurrencyControls`, `knownAccessIsShownOnceAndUnknownAccessHasNoProgress`, `largeFontKeepsLongNamesAmountsAndToolbarActionsAvailable`, `loadingEmptyAndErrorAreDistinctAndActionsWork`, `suppliedDomainFractionIsRenderedWithThinProgressAndTextStatus`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/SubscriptionsUiTest.kt`. Адаптированы: `cancelledUnknownAccessNeverShowsImaginedDate`, `cleanLaunchAndFirstAddNavigateToRealEditor`, `financialOverflowLeavesLibraryDetailsAndEditorAvailableUntilUserRepairsPrice`, `largeFontKeepsPrimaryActionsReachableByScrolling`, `onlyArchiveExplainsAbsenceAndKeepsArchiveTab`, `realForecastOverflowShowsErrorAndRetryPreservesSubscription`, `useCaseInsertionIsReactiveAndDuplicateServicesRetainLabels`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/design/RootChromeUiTest.kt`. Добавлены: `emptyAndShortScreensKeepPanelsVisible`, `firstAndLastRowsStayAccessibleAboveOverlaysAndIconsRemainFiftyFourDp`, `horizontalAndProgrammaticScrollDoNotHideRootChrome`, `islandBackgroundAloneIsEightyFivePercentAndSelectionAndIconsAreOpaque`, `realVerticalGesturesHideAndReverseRestoresBothPanels`, `rowPlacesPriceRightOfNameAndProgressUnderEntireTextPart`, `showHideDoesNotChangeListPositionOrReservedPadding`, `translucentWrapperDoesNotInterceptTouchesOutsideTheCompactIsland`, `unplacedHiddenPanelsDoNotInterceptFormerIslandArea`. Новые инварианты R4a: общий chrome/корни/источник библиотеки/отложенный реальный визуальный аудит.
- `ui/finance/FinanceIntegrationTest.kt`. Адаптированы: `currentDateRefreshRebuildsBothWindowsAndMainProjectionWithoutRoomWrite`, `overflowIsScreenErrorRatherThanEmptyOrStorageBlockAndRetryCanRecover`. Home.forecast удалён; финансовые результаты/ошибки проверяются отдельным реальным финансовым источником, а библиотека — по rows/today. Арифметика, восстановление и конкурентные проверки сохранены.
- `ui/finance/FinanceNavigationTest.kt`. Адаптированы: `createdSubscriptionAppearsInForecastAndSelectionSurvivesRecreationAndSettingsBack`, `lastChartMonthSurvivesThemeTabReturnRecreationAndActualRotation`. Home.forecast удалён; финансовые результаты/ошибки проверяются отдельным реальным финансовым источником, а библиотека — по rows/today. Арифметика, восстановление и конкурентные проверки сохранены.
- `ui/finance/ForecastRecoveryIntegrationTest.kt`. Адаптированы: `actualEventBudgetDoesNotBlockCreateManualArchiveOrArchiveReader`, `cancellationAndConfirmedDeletionThroughDetailsRestoreForecastWithoutOtherRowChanges`, `failedForecastDoesNotWeakenOptimisticSnapshotChecks`, `overflowIsRepairedThroughActualEditorAndBothExistingObserversRecoverAutomatically`, `todayChangeRecoversBudgetErrorWithoutRecreatingTheLibraryObserver`, `unrepresentableRecordCanOpenDetailsDismissDeletionAndRepairScheduleInEditor`. Home.forecast удалён; финансовые результаты/ошибки проверяются отдельным реальным финансовым источником, а библиотека — по rows/today. Арифметика, восстановление и конкурентные проверки сохранены.
- `ui/review/CrossScreenDesignUiTest.kt`. Адаптированы: `landscapeRootIslandAndEmptyActionStaySeparatedAtLargeFont`, `longAmountsKeepZeroTwoAndThreeFractionDigitsAcrossWidthsAndFonts`, `timeRowWidthFontAndThemeMatrixKeepsHorizontalWordsAndCurrentValue`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/review/KeyboardRotationUiTest.kt`. Адаптированы: `landscapeGlobalOffsetsRecreationPreservesOriginTabAndFixedTime`, `realKeyboardRotationCalendarAndUnsavedBackPreserveDraftAndRoot`. Ожидания старого UI адаптированы: Archive вложен в Settings, три корня без подписей, нет сводки на Home, новый формат и viewport позади overlay. Сохранены действия, сохранение состояния, доступность и проверки бизнес-результатов.
- `ui/review/R4aGalleryUiTest.kt`. Добавлены: `rootAndNestedVisualAcceptanceMatrix`. Новые инварианты R4a: общий chrome/корни/источник библиотеки/отложенный реальный визуальный аудит.
- `ui/settings/ArchiveSettingsIntegrationTest.kt`. Адаптированы: `archiveProjectionReactivationPreservesHistoryAndUuidAndRecomputesForecast`. Home.forecast удалён; финансовые результаты/ошибки проверяются отдельным реальным финансовым источником, а библиотека — по rows/today. Арифметика, восстановление и конкурентные проверки сохранены.

## Изменённые файлы

- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/app/DatasetCompositionTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationNavigationUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationPermissionUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/AccessibilityUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsRedesignUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DesignSystemUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/EditorUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/LegacyNavigationUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/NavigationUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/RootNavigationActions.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/RootNavigationUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsRedesignUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/design/RootChromeUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceIntegrationTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceNavigationTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/ForecastRecoveryIntegrationTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/CrossScreenDesignUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/KeyboardRotationUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/R4aGalleryUiTest.kt`
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/settings/ArchiveSettingsIntegrationTest.kt`
- `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/ArchiveSettingsPreviews.kt`
- `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/review/DesignReviewGallery.kt`
- `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsPreviews.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationViewModel.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/design/TrackerBottomNavigation.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/design/TrackerPageHeader.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/design/TrackerRootChrome.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/design/TrackerTokens.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorScreen.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceScreen.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/navigation/TrackerApp.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/ForecastSummary.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionRow.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsScreen.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsTopBar.kt`
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsUiState.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/integration/ForecastIsolationTest.kt`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/integration/ForecastModelsAvailabilityTest.kt`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationViewModelTest.kt`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/design/RootScrollPolicyTest.kt`
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/HomeProjectionTest.kt`
- `docs/reports/R4a.md`
- `docs/reports/R4a/README.md`

## R4a-fix — навигация из уведомления не теряется на проверке записи

Исходный SHA: `3472aad74b1a47bc1e07ae973354a666b900e10a`.
SHA исправления: `99ac87c9614e82f7c627cdbb17f7fe082928b74e`.
Base остаётся `codex/r3-design-system`. Новый PR не создавался. Force push, merge и ручной запуск Actions не выполнялись.
PERSONAL-READY не объявляется. R4b и следующие этапы не начинались.

### Первопричина

В `TrackerApp` эффект `LaunchedEffect(notificationState?.navigation?.sequence, editor)` вызывал `consumed(sequence)` до `owner.details(id).first()` и до навигации.
`consumed` обнуляет navigation state и меняет ключ эффекта. Выполняющийся эффект отменяется вместе с незавершённой проверкой.
Запрос уже считается обработанным, а детали не открываются.

Это подтверждено по исходному коду и воспроизведением. На исходном `TrackerApp` холодный запуск из уведомления (`coldActivityIntentOpensCorrectUuidAndBackReturnsHome`) завершился `ComposeTimeoutException`: экран деталей не появился. Отдельный прогон с управляемой задержкой UI-проверки (`delayedLookupStillOpensTheNotifiedDetails`) тоже не открыл детали; процесс затем падал на уничтожении activity, потому что переход так и не был доведён до устойчивого back stack. Лог первого падения гонки сохранён как артефакт прогона, успешным повтором он не подменяется.

### Исправление

Решение вынесено из эффекта:

- `NotificationNavigationResolver` после асинхронного чтения заново проверяет поколение, последовательность и наличие записи. `CancellationException` пробрасывается. Обычная ошибка хранилища даёт `ReadFailed` и ведёт на главный экран, а не в детали. Более новый sequence не позволяет старому завершению ни перейти, ни вызвать `consumed`.
- `NotificationNavigationSession` запускает проверку в `rememberCoroutineScope`, а не внутри `LaunchedEffect`. Очистка того же запроса ключ эффекта меняет, но свою проверку не отменяет. Другой sequence или другой режим редактора предыдущую проверку отменяют.
- Побочные действия (`navigate`, `consumed`, запрос выхода из редактора) выполняются на `Dispatchers.Main.immediate` после состояния lifecycle не ниже `STARTED`. Чтение `NavController` вне главного потока не используется для решения.
- Перед решением ждётся реальный destination через Compose `State` (`currentBackStackEntryAsState`). Прямой `snapshotFlow` по `NavController.currentBackStackEntry` не просыпается: это не snapshot state, и после пересоздания activity проверка зависала. Если к моменту решения редактор уже восстановлен, переход не кладётся поверх черновика.
- `EditorExitRequest`: выход во время `saving` не проглатывается и не двигает `lastExit`. Успешное сохранение само закрывает редактор. Неуспешное сохранение по-прежнему спрашивает подтверждение грязного черновика.
- Проверка существования не отключена. Optimistic snapshot, правила сохранения, изображения и защита черновика не менялись.

Инструментальная задержка только у debug-хоста `IsolatedMainActivity`: `NavigationLookupPause` держит повторную UI-проверку. `NotificationViewModel.open` по-прежнему читает Room без этой задержки. В release этого типа нет.

### Изменённые файлы

- `app/src/main/java/.../ui/navigation/TrackerApp.kt`
- `app/src/main/java/.../ui/navigation/NotificationNavigationResolver.kt`
- `app/src/main/java/.../ui/navigation/NotificationNavigationSession.kt`
- `app/src/main/java/.../ui/editor/EditorExitRequest.kt`
- `app/src/main/java/.../ui/editor/EditorScreen.kt`
- `app/src/debug/java/.../app/NavigationLookupPause.kt`
- `app/src/debug/java/.../app/IsolatedMainActivity.kt`
- `app/src/test/java/.../ui/navigation/NotificationNavigationResolverTest.kt` — 12
- `app/src/test/java/.../ui/editor/EditorExitRequestTest.kt` — 4
- `app/src/androidTest/java/.../notifications/NotificationNavigationRaceUiTest.kt` — 6
- `app/src/androidTest/java/.../notifications/NotificationNavigationUiTest.kt` — 2 новых метода и синхронизация `ActivityScenario` после `setIntent`
- `app/src/androidTest/java/.../notifications/ActivityScenarioIntentSync.kt`
- `docs/reports/R4a.md`

### Тесты, которые закрывают требуемые сценарии

JVM, с управляемой задержкой lookup, не мгновенный fake:

1. Задержанное чтение потребляет запрос только после чтения.
2. Новый запрос во время чтения предыдущего сохраняется, старый не навигирует.
3. Завершение старого запроса не очищает новый sequence.
4. Удаление во время чтения ведёт домой.
5. Смена поколения после чтения блокирует открытие.
6. Ошибка хранилища не считается успешным открытием.
7. `CancellationException` не потребляет запрос.
8. Обёрнутая отмена не превращается в «записи нет».
9. Редактор ждёт без lookup и без `consumed`.
10. Уже открытые детали не пушатся повторно.
11. Последовательные запросы открываются по порядку.
12. Отмена scope во время чтения оставляет запрос pending.

Инструментально, Compose → ViewModel → Room → Navigation, API 31:

- холодный и тёплый запуск, в том числе с задержкой lookup;
- повтор не дублирует маршрут;
- новый запрос во время проверки предыдущего открывает вторую цель один раз;
- удаление во время проверки возвращает на главный экран с другого корня;
- пересоздание activity во время проверки и во время диалога выхода;
- отказ от выхода сохраняет черновик и отменяет переход;
- подтверждение выхода открывает нужные детали и не пишет черновик;
- уведомление во время сохранения редактора сохраняет запись и открывает запрос;
- прежние методы `NotificationNavigationUiTest` сохранены.

`ActivityScenario` после `setIntent` перестаёт видеть lifecycle, потому что сравнивает action и data исходного intent. Это ломало `close()` и `recreate()` уже после успешного UI-решения. Тесты, которые доставляют уведомление в живую activity, завершают её через lifecycle monitor, не через этот матчинг. Продуктовый `setIntent` сохранён: он нужен, чтобы пересоздание activity видело последний intent.

### Фактические проверки

Среда: JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`), Android SDK локально в `/opt/android-sdk` (в коммит не входит), platform 36, build-tools 35.0.0, emulator, system image google_apis API 31 x86_64, AVD `api31`, устройство `emulator-5554`. Сохранённый снимок окружения SDK не содержал; инструменты в репозиторий и в `local.properties` не добавлялись. `/dev/kvm` доступен как `crw-rw-rw-` без отдельной группы `kvm`. Сохранённый start script и режим доступа не менялись.

`git diff --check` — PASS.

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain` — PASS.

- JVM: **365 выполнено, 365 успешно, 0 падений, 0 пропущено.** Это 349 прежних и 16 новых. `SubscriptionsViewModelTest.overflowNeverProducesEmptyOrPartialTotals` / `UncaughtExceptionsBeforeTest` в этом прогоне не повторился. Гипотеза об утечке `Dispatchers.Main` не подтверждена и не опровергнута; scope из-за неё не расширялся.
- Lint: **0 ошибок, 19 предупреждений.** Все вне кода R4a-fix: версии Gradle/зависимостей, `mipmap-anydpi-v26`, неиспользуемый `welcome_message`. Прежний отчёт Codex указывал 8 предупреждений на исходном SHA; этот прогон зафиксирован как есть, предупреждения не подавлялись.
- `assembleDebug` — PASS.
- `compileDebugAndroidTestKotlin` — PASS. В дереве 415 методов `@Test` (прежние 407 плюс 8 новых). Компиляция не равна выполнению всего набора.

Инструментально на API 31, один прогон финального кода:

| Набор | Выполнено | Успешно | Падения | Пропуски |
|---|---:|---:|---:|---:|
| `NotificationNavigationRaceUiTest` | 6 | 6 | 0 | 0 |
| `NotificationNavigationUiTest` | 14 | 14 | 0 | 0 |
| `RootNavigationUiTest` | 4 | 4 | 0 | 0 |
| `EditorUiTest` | 8 | 8 | 0 | 0 |
| `LegacyNavigationUiTest` | 1 | 0 | 1 | 0 |
| Итого | 33 | 32 | 1 | 0 |

Полный накопленный Android-набор не запускался.

### Первоначальные падения, которые не скрыты повторным зелёным прогоном

1. Исходная реализация: холодный переход и задержанный lookup не открывают детали. Это и есть дефект.
2. После выноса проверки из эффекта переход выполнялся не на main looper (`LifecycleRegistry.setCurrentState` / `setMaxLifecycle`). На API 31 это роняло activity. Исправлено переносом navigate/consume на `Dispatchers.Main.immediate`.
3. Первый прогон `NotificationNavigationRaceUiTest` на уже исправленной навигации: 6 выполнено, 3 падения (`warmDelayedLookupOpensTheNotifiedDetails`, `newerRequestDuringLookupOpensOnceAndDropsTheStaleTarget`, `deletionDuringLookupReturnsHomeFromAnotherRoot`). UI-проверки внутри теста исключения не бросали; `ActivityScenario.close()` ждал `DESTROYED`, а после `setIntent` событие игнорировалось. Последнее известное состояние — `PAUSED`.
4. `recreationDuringLookupKeepsTheRequestUntilItCanOpen` на 15 с не дождался повторного входа в lookup: ожидание destination было на `snapshotFlow`, который не видит смену back stack. После чтения Compose `State` метод проходит.
5. `LegacyNavigationUiTest.legacyArchiveParentIsRepairedAndNestedArchiveSurvivesStateRestoration` падает и на финальном коде, и на исходном `TrackerApp` с тем же SHA: после `StateRestorationTester` системный Back не выбирает `tab_subscriptions` (`Selected = false`, строка 52). Это не регрессия R4a-fix. В scope не входило.

### Скриншоты с эмулятора API 31

Сняты с живого `IsolatedMainActivity`, не нарисованы. В git не добавлялись. Файлы прогона:

- главный экран со синтетической «Целевая подписка»;
- детали, открытые intent уведомления;
- диалог «Есть несохранённые изменения» поверх черновика «Несохранённый учебный черновик»;
- тот же редактор после «Продолжить редактирование»: черновик на месте, диалога нет;
- детали «Целевая подписка» после «Выйти без сохранения».

### Ограничения и защищённые компоненты

Не менялись: дизайн главного экрана и островка, алгоритм скрытия панелей, дизайн-система R3, Room v5 и schema exports, InitializationJournal, защита хранилища, generation/mutation guards, SubscriptionDomain, DefaultRenewalCalendar, ForecastEngine, денежная арифметика, ReminderRules, WorkManager, ledger, PendingIntent, AssetStore, схема DataStore, Manifest, applicationId, Backup/D2D, Gradle и зависимости, продуктовые требования.

Не выполнялись: весь накопленный Android-набор, TalkBack, системная шторка уведомлений, process death, реальные exact alarm. Отказ ОС по-прежнему не блокирует приложение. Успешная доставка уведомления не утверждается.

`LegacyNavigationUiTest` остаётся FAIL на этой среде и на исходном SHA. К приёмке R4a-fix это отдельный известный дефект восстановления Back, не часть потерянного перехода из уведомления.

Статус: готово к независимой приёмке ChatGPT. PERSONAL-READY не объявляется.

## R4a-fix-2 — системный Back после восстановления старого Archive

Исходный SHA: `6896ed0e708f6afadff2a78a77fe07fc10ee54c2`.
SHA исправления: `688c988ad179357dbedb18756f7283e5999e466a`.
Base остаётся `codex/r3-design-system`. Новый PR не создавался. Force push, merge и ручной запуск Actions не выполнялись.
PERSONAL-READY не объявляется. R4b не начинался.

### Первопричина

Падение `LegacyNavigationUiTest` воспроизведено на исходном SHA до правки. Это не ошибка `StateRestorationTester` и не сломанный родитель Archive.

После восстановления стек чинится правильно: `[корневой граф, Подписки, Настройки, Архив]`. Кнопка «Назад» в шапке снимает архив и оставляет настройки выбранными. Дальше системный Back вызывает тот же `select(Подписки)`, что и вкладка:

`navigate(Подписки) { popUpTo(start) { saveState = true }; launchSingleTop = true; restoreState = true }`.

На этом стеке вызов ничего не меняет. Повтор того же `navigate` из теста тоже не меняет стек. Обычный `popBackStack()` с того же места открывает подписки и выбирает вкладку.

`BackHandler` при этом включён и стоит выше `PredictiveBackHandler`. Навигация до него доходит. Её гасит `restoreState` стартового назначения. Navigation 2.9 при `popUpTo(start) { saveState = true }` записывает только что снятый корень в сохранённое состояние старта, если этот id ещё не был зарегистрирован. `restoreState = true` тут же восстанавливает его обратно. Обычный переход на статистику или настройки через вкладку сначала регистрирует старт пустым сохранением, поэтому обратный Back там проходит. Починка старого Archive кладёт настройки прямым `navigate`, без этой регистрации. Поэтому ломается только этот путь.

### Исправление

Возврат на главный экран больше не восстанавливает сохранённое состояние старта. Запись «Подписки» и так остаётся в стеке, её прокрутка живёт в этой записи. `saveState` по-прежнему запоминает статистику и настройки, когда с них уходят. `restoreState` для этих двух корней сохранён. Повторное нажатие текущей вкладки по-прежнему ничего не добавляет. Проверка существования подписки из уведомления не отключалась.

### Граница уведомления

Отдельно проверено, не объявлено дефектом и не менялось в коде уведомлений:

- Пока UI-проверка держалась на паузе, activity переводилась в `CREATED`, затем снова в `RESUMED`. После старта открылись нужные детали. Запрос не потерялся. Запрос Compose-дерева в состоянии `CREATED` даёт «No compose hierarchies found» — это ограничение тестового правила, не отказ навигации.
- Более новый sequence по-прежнему не даёт старому завершению ни перейти, ни вызвать `consumed`. Это уже покрыто R4a-fix.
- Смена поколения после чтения и до решения по-прежнему ведёт домой внутри `decide`. Отдельного воспроизводимого окна, где поколение меняется уже после `decide` и до `apply` на главном потоке, нет: активный набор в одной композиции не переключается, а смена набора отменяет композицию. Дополнительный замок на этом месте не добавлялся.

### Изменённые файлы

- `app/src/main/java/.../ui/navigation/TrackerApp.kt` — `openRoot`: для «Подписки» `restoreState = false`.
- `app/src/androidTest/.../ui/LegacyNavigationUiTest.kt` — прежняя проверка и второе сохранение уже починенных настроек.
- `app/src/androidTest/.../notifications/NotificationNavigationRaceUiTest.kt` — lookup, отпущенный при остановленной activity.
- `docs/reports/R4a.md`.

### Фактические проверки

Среда этого продолжения: тот же VM, JDK 21, SDK в `/opt/android-sdk`, emulator-5554, API 31. Сохранённое окружение по-прежнему без finished build (`no_finished_builds`, `defaultImageFallback: true`). SDK оказался на диске, потому что предыдущий ход этого же агента его установил, а не потому что снимок окружения его содержит. Новый агент с чистого образа SDK не получит, пока владелец не положит установку SDK в install окружения и не активирует успешный build. `/dev/kvm` — `crw-rw-rw-`, группа с gid 103 в `/etc/group` не названа, пользователь `ubuntu` в неё не входит. Start script по-прежнему делает `chmod 666`. Он не менялся. Более безопасный доступ — именованная группа `kvm` в образе окружения, а не запись в репозиторий приложения.

`git diff --check` — PASS.

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain` — PASS.

- JVM: **365 выполнено, 365 успешно, 0 падений, 0 пропущено.** Новых JVM-тестов нет: дефект проявляется только на настоящем `NavController`. `SubscriptionsViewModelTest.overflowNeverProducesEmptyOrPartialTotals` снова не повторился. Гипотеза об утечке `Dispatchers.Main` не доказана.
- `assembleDebug` — PASS.
- Lint: **0 ошибок, 19 предупреждений.** Те же категории, что предыдущий прогон этого окружения на SHA `6896ed0`: GradleDependency 9, NewerVersionAvailable 6, AndroidGradlePluginVersion 2, ObsoleteSdkInt 1, UnusedResources 1. Ни одно не в коде R4a-fix-2. Отчёт Codex о 8 предупреждениях относится к другому подсчёту или другой версии lint. Предупреждения не подавлялись. Повторный lint именно SHA `6896ed0` в этом ходе не запускался: набор совпадает с уже записанным прогоном того же окружения.
- `compileDebugAndroidTestKotlin` — PASS. В дереве 417 методов `@Test`.

Android API 31, один прогон после исправления:

| Набор | Выполнено | Успешно | Падения | Пропуски |
|---|---:|---:|---:|---:|
| `LegacyNavigationUiTest` | 2 | 2 | 0 | 0 |
| `RootNavigationUiTest` | 4 | 4 | 0 | 0 |
| `NavigationUiTest` | 15 | 15 | 0 | 0 |
| `NotificationNavigationUiTest` | 14 | 14 | 0 | 0 |
| `NotificationNavigationRaceUiTest` | 7 | 7 | 0 | 0 |
| `EditorUiTest` | 8 | 8 | 0 | 0 |
| `RootChromeUiTest` | 9 | 9 | 0 | 0 |
| `SubscriptionsRedesignUiTest` | 9 | 8 | 1 | 0 |
| `R4aGalleryUiTest` | 1 | 1 | 0 | 0 |
| Итого | 69 | 68 | 1 | 0 |

`NavigationUiTest` включает пересоздание activity для выбранной вкладки и для настроек. Это не process death. Старый стек Archive в живую activity так не внедрить: композиция ставится тестом, `Activity.recreate()` её бы выбросил. Его сохранение проверяет `StateRestorationTester`, в том числе повторно уже после починки.

### Первоначальные падения

1. До исправления `legacyArchiveParentIsRepairedAndNestedArchiveSurvivesStateRestoration` падает: после системного Back стек остаётся `[корень, Подписки, Настройки]`, `tab_subscriptions` не выбран. Прямой `navigate` с `restoreState = true` даёт то же. `popBackStack()` выбирает подписки. Это и есть дефект.
2. Первый пробный тест остановленной activity спрашивал Compose-дерево в `CREATED` и получил «No compose hierarchies found». Повтор без чтения дерева до `RESUMED` проходит: детали открываются.
3. В финальном прогоне 69 тестов одно падение не связано с Back: `SubscriptionsRedesignUiTest.suppliedDomainFractionIsRenderedWithThinProgressAndTextStatus`. `assertTextContains("Активна")` не находит слово в тексте строки. Текст строки: «Видеосервис, Личный, 17 июн. · 2 дн., 499 ₽, Месяц». Слово «Активна» есть только в `StateDescription`: «Оставшийся период. Активна. Осталось 75% расчётного периода». Прогресс 0,75 на узле уже есть. Тест рисует `SubscriptionsShowcase` и не проходит через `TrackerApp`. Файлы строки в этом исправлении не менялись. Ожидание теста не подменялось, дизайн строки не переделывался.

### Скриншоты

Сняты `R4aGalleryUiTest` с эмулятора API 31, не нарисованы, в git не добавлялись. Светлый и тёмный заполненный список показывают две активные строки и отменённое «Облачное хранилище» с подписью «Отменена». Пустой список — «Подписок пока нет». Скрытые панели — длинный список без шапки и без островка.

### Ограничения

Не менялись дизайн, островок, алгоритм скрытия, Room, защита хранилища, домен, календарь, прогноз, деньги, напоминания, WorkManager, ledger, PendingIntent, AssetStore, Manifest, Gradle, продуктовые требования.

Весь накопленный Android-набор, TalkBack, шторка уведомлений и process death не запускались. Старая позиция прокрутки legacy Archive при починке по-прежнему не переносится. Видимая подпись «Активна» на активной строке не добавлялась.

Статус: готово к независимой приёмке ChatGPT. PERSONAL-READY не объявляется.

## R4a-fix-3 — «Активна» в семантике, без видимой плашки

Исходный SHA: `5b11502fbb4b97f08863afc7db57dfc747d20db6`.
SHA исправления публикуется следующей строкой после коммита кода; до него раздел входит в тот же коммит.
Base остаётся `codex/r3-design-system`. Новый PR не создавался. Force push, merge и ручной запуск Actions не выполнялись.
PERSONAL-READY не объявляется. R4b не начинался.

### Причина

`SubscriptionsRedesignUiTest.suppliedDomainFractionIsRenderedWithThinProgressAndTextStatus` требовал видимый текст «Активна». Утверждённый список его не рисует.

UI_DESIGN_SPEC §3.1 показывает у активной строки название, подпись, дату и линию 4 dp. Плашка есть только у отменённой: «[Отменена]». R-UI-02 описывает ту же строку: дата и оставшееся время слева, цена и период справа, индикатор 4 dp по R-LIFE-06. Отдельной плашки «Активна» нет. R-LIFE-01 называет состояние ACTIVE «Активна», но это имя состояния, не элемент строки.

На строке с индикатором слово уже было в `StateDescription`: «Оставшийся период. Активна. Осталось 75% расчётного периода». `assertTextContains` смотрит текст, не это описание, поэтому тест падал при верном оформлении.

Без индикатора статус для вспомогательных технологий пропадал. `TrackerPeriodIndicator` при `PeriodTone.HIDDEN` выходит до семантики. Это неизвестная, некорректная или архивная доля по R-LIFE-06. Для активной строки без линии на текстовую колонку добавлено только `stateDescription = «Активна»`. Пиксели, цвета, высота и плашки не менялись. Видимая «Отменена» остаётся текстом строки.

### Что проверяет тест

Прежний метод сохранён и переведён на утверждённый контракт:

- в тексте строки нет «Активна»;
- `StateDescription` содержит «Активна»;
- дата «17 июн. · 2 дн.»;
- `ProgressBarRangeInfo` 0,75 на диапазоне 0..1;
- высота дорожки 4 dp;
- область доступности индикатора покрывает видимую дорожку.

Добавлен `activeStatusRemainsInSemanticsWhenTheIndicatorIsHidden`: активная строка без доли не рисует индикатор и плашку, а «Активна» остаётся в семантике. `cancelledRowsHaveNoProblemOrAwaitingStatus` по-прежнему требует видимую «Отменена» и запрещает «Проблема с оплатой» и «Ожидает продления».

### Изменённые файлы

- `app/src/main/java/.../ui/subscriptions/SubscriptionRow.kt` — семантика статуса только когда активная строка без индикатора.
- `app/src/androidTest/.../ui/SubscriptionsRedesignUiTest.kt` — прежний тест и новая проверка.
- `docs/reports/R4a.md`.

### Фактические проверки

Среда: тот же VM, JDK 21, SDK `/opt/android-sdk`, emulator-5554, API 31.

`git diff --check` — PASS.

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain` — PASS.

- JVM: **365 выполнено, 365 успешно, 0 падений, 0 пропущено.** Их выполнил первый запуск этой команды в 16:55 UTC. Успешный повтор в 16:58 UTC отметил `testDebugUnitTest` как UP-TO-DATE. `overflowNeverProducesEmptyOrPartialTotals` не повторился.
- `assembleDebug` — PASS.
- Lint: **0 ошибок, 19 предупреждений.** `lintReportDebug` остался UP-TO-DATE. Категории те же: GradleDependency 9, NewerVersionAvailable 6, AndroidGradlePluginVersion 2, ObsoleteSdkInt 1, UnusedResources 1. В изменённых файлах их нет. Предупреждения не подавлялись.
- `compileDebugAndroidTestKotlin` — PASS.

Android API 31, один прогон после исправления, прежний набор плюс новая проверка:

| Набор | Выполнено | Успешно | Падения | Пропуски |
|---|---:|---:|---:|---:|
| `LegacyNavigationUiTest` | 2 | 2 | 0 | 0 |
| `RootNavigationUiTest` | 4 | 4 | 0 | 0 |
| `NavigationUiTest` | 15 | 15 | 0 | 0 |
| `NotificationNavigationUiTest` | 14 | 14 | 0 | 0 |
| `NotificationNavigationRaceUiTest` | 7 | 7 | 0 | 0 |
| `EditorUiTest` | 8 | 8 | 0 | 0 |
| `RootChromeUiTest` | 9 | 9 | 0 | 0 |
| `SubscriptionsRedesignUiTest` | 10 | 10 | 0 | 0 |
| `R4aGalleryUiTest` | 1 | 1 | 0 | 0 |
| Итого | 70 | 70 | 0 | 0 |

### Первоначальное падение сборки

Первые два запуска обязательной команды остановились на `compileDebugAndroidTestKotlin`: импортов `getOrElse` и `getOrNull` в этой версии Compose нет. Чтение семантики переведено на `config.contains` и `config[]`, как в остальных UI-тестах. После этого команда прошла. Продуктовый код из-за этой ошибки не менялся повторно.

### Ограничения

Не менялись бизнес-логика, оформление, Room, Gradle и зависимости. Не запускались весь накопленный Android-набор, TalkBack на устройстве, шторка уведомлений и process death. Видимая плашка «Активна» не добавлялась.

Статус: готово к независимой приёмке. PERSONAL-READY не объявляется.
