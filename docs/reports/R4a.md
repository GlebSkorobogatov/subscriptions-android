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
