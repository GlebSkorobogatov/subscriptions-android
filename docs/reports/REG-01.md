# REG-01 — стабилизация накопленных регрессий перед R5b

## База и границы

Ветка: `codex/r5a-editor`. Исходный SHA: `cdd62211392d44a904e43a2f5d9dc588f1371fff`. Base Draft PR #12: `codex/r4b-settings` (`36d6f84`).
R5a условно принят. Этот этап не добавляет продукт. R5b, R6, R7, R8 и H не начаты. PERSONAL-READY не объявляется.
PR #11 не менялся. Merge, force push и ручной запуск Actions не выполнялись.

Изучены PRD, UI_DESIGN_SPEC, ARCHITECTURE, TEST_PLAN, DECISIONS №74, №78, №93 и отчёты R4a, R4b, R5a. Предварительная классификация восьми падений не принималась как доказанная: каждый случай сверен с кодом и повторно прогнан на API 31.

Среда ENV-01 не менялась: JDK 21.0.13, Android SDK `/opt/android-sdk`, Build Tools 35.0.0, Gradle 8.13, эмулятор `emulator-5554`, API 31, `sdk_gphone64_x86_64`. Постоянные настройки эмулятора не переписывались.

Затронуты R-STAT-06, T-STAT-06, R-UI-01, R-UI-07, решения №74, №78, №93 и правила UI_DESIGN_SPEC для суммы, пустого экрана и строки времени. Room, AssetStore, денежная и календарная арифметика, WorkManager, ledger, Manifest, Gradle и зависимости не менялись.

## 1. Прогноз после overflow

Тест: `SubscriptionsUiTest.financialOverflowLeavesLibraryDetailsAndEditorAvailableUntilUserRepairsPrice`.

Первопричина в тестовом источнике, не в `ForecastEngine` и не в залипшей фазе после `ForecastState.Error`. Анонимный `SubscriptionsSource` не переопределял `finance()`. Реализация по умолчанию бросает `Finance stream unavailable`. `FinanceViewModel` при исключении из `source.observe()` фиксирует `FinancePhase.Error` до `retry()`. Поэтому после смены `Long.MAX_VALUE` на 1,23 ₽ тег `finance_30_total` не появлялся за 5 секунд. Сам коллектор snapshot при `ForecastState.Error` не обрывается: `calculationOrNull` возвращает ошибку вместе со строками, а `finance_30_total` рисуется только вне Loading и Error. Производственный `SubscriptionsComposition.finance()` уже отдаёт снимки сессии.

Воспроизведение: API 31, цена `Long.MAX_VALUE`, переход в статистику, ошибка без `finance_total`, ремонт цены до 123 копеек, возврат в статистику. Ожидаемый текст окна today..today+30 — `2,46 RUB` (сегодня в тесте 2027-01-31, месяц, два начисления по 123).

Исправление: `override fun finance() = use.forecasts`. Денежная арифметика, защита от переполнения и нулевые суммы не менялись. Частичный итог не подставляется.

Файлы: `SubscriptionsUiTest.kt`.

Результат: PASS, 5.671 s, в общем прогоне восьми методов.

## 2. Второй вход в архив

Тест: `KeyboardRotationUiTest.landscapeGlobalOffsetsRecreationPreservesOriginTabAndFixedTime`.

Первопричина не в назначении `NavController` и не в незавершённой анимации. Первый `openArchive` открывает архив и убирает островок. После landscape, диалога смещений, `recreate` и прокрутки к 09:00 восстановленный scroll оставляет центр строки «Архив» на вкладке «Финансы». `performClick` касается центра узла. Островок лежит выше списка, поэтому касание вызывает `select(Finance)`, а `onArchive` не выполняется. Ожидание `bottom_navigation.assertDoesNotExist` падает, потому что переход в архив не начинался. `performScrollToNode` не сдвигает строку, которую уже считает видимой, даже если её центр под островком.

Исправление: `openArchive` после прокрутки к строке сравнивает её bounds с островком и при пересечении делает `ScrollBy`, затем кликает и проверяет `archive_screen` и отсутствие островка. В `TrackerRootChrome` запрошенный в зону видимости ребёнок, который помещается над островком, докручивается выше измеренной высоты островка (`BringIntoViewSpec`). Обычный fling и геометрия островка не менялись. Анимации в production не отключались. Поведение `LegacyNavigationUiTest` и R4a сохранено: навигационный код `TrackerApp` не менялся.

Файлы: `RootNavigationActions.kt`, `TrackerRootChrome.kt`.

Результат: PASS, 6.333 s. Класс `KeyboardRotationUiTest` целиком прошёл и в прогоне классов, и в пакете редактора.

## 3. Длинные суммы

Тест: `CrossScreenDesignUiTest.longAmountsKeepZeroTwoAndThreeFractionDigitsAcrossWidthsAndFonts`.

Первопричина: при горизонтальной раскладке цена ограничена 45% ширины. Для `92 233 720 368 547 758,07 ₽` текст шире этой колонки и переполняется. Сокращения многоточием в `TrackerMoney` нет, но узел не вмещает все знаки.

Исправление: `SubscriptionRow` измеряет цену и подпись периода без ограничения ширины. Если окно уже 240 dp, шрифт не меньше 1.5 или измеренный текст шире 45%, цена ставится под названием на всю ширину строки и выравнивается вправо. Шрифт не уменьшается, копейки не отбрасываются, значок остаётся 54 dp. Матрица 320/360/390/412 dp, font 1/1.5/2, обе темы сохранена.

Отдельная поправка теста: вертикальная прокрутка скрывает островок по R-UI-07. Перед измерением островка список возвращается наверх, затем последняя строка прокручивается и сравнивается с местом покоя островка. Это не замена проверки суммы.

Файлы: `SubscriptionRow.kt`, `CrossScreenDesignUiTest.kt`.

Результат: PASS, 14.812 s.

## 4. Пустой экран в landscape

Тест: `CrossScreenDesignUiTest.landscapeRootIslandAndEmptyActionStaySeparatedAtLargeFont`.

Первопричина: пустое состояние было одним элементом `LazyColumn`. `performScrollToNode` считал «Добавить подписку» видимой, пока кнопка пересекала островок, и не докручивал её целиком выше него.

Исправление: элемент занимает высоту viewport. Внутренняя колонка ограничена высотой viewport минус верхняя и нижняя chrome и 24 dp и прокручивается сама. Кнопка доезжает выше островка. Геометрия и прозрачность островка не менялись. CTA не удалялась.

Файлы: `SubscriptionsScreen.kt`.

Результат: PASS, 2.629 s.

## 5. Строка времени

Тест: `CrossScreenDesignUiTest.timeRowWidthFontAndThemeMatrixKeepsHorizontalWordsAndCurrentValue`.

Первопричина: порог `maxWidth < 240.dp` смотрел на внутреннюю ширину карточки. При окне 320 dp и font 1 она около 220 dp, поэтому 09:00 уходило на вторую строку ещё при обычном шрифте.

Исправление: ряд складывается только при font 1.5 и выше или если измеренные название (не больше двух строк) и подпись не помещаются слева от часов. При font 1, включая 320 dp, 09:00 остаётся справа. Время по-прежнему фиксированные 09:00. Picker не добавлялся. Текст не обрезается ради одной строки. Проверка островка идёт после возврата прокрутки в начало: прокрутка к строке скрывает его по R-UI-07.

Файлы: `SettingsScreen.kt`, `CrossScreenDesignUiTest.kt`.

Результат: PASS, 8.454 s. Проверка «Time on right 320» входит в этот метод.

## 6. Три устаревших ожидания

Это не дефекты продукта.

`LargeFontWindowUiTest.landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont`. `EditEnd` и отключение пишут `details_field_end`. `details_field_date` относится к возобновлению. Подтверждение и отмена проверяются до поля и сохранены. PASS, 6.994 s. Файл: `LargeFontWindowUiTest.kt`.

`AccessibilityUiTest.enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable`. После прокрутки к `settings_time` панели скрыты. Спецификация возвращает их у верхнего края. Тест теперь делает этот возврат перед `tab_subscriptions`. Проверки диалогов, архива и кнопок сохранены. PASS, 6.093 s. Файл: `AccessibilityUiTest.kt`.

`FinanceNavigationTest.lastChartMonthSurvivesThemeTabReturnRecreationAndActualRotation`. После landscape прокрутка к графику скрывает островок. Перед `tab_finance.assertIsSelected()` список возвращается наверх. Выбранный месяц по-прежнему проверяется в `chartSelected()` до и после поворота. PASS, 7.929 s. Файл: `FinanceNavigationTest.kt`.

## 7. Нестабильность редактора

`R5aEditorUiTest.pinnedSaveStaysAvailableWithImeScrollFontAndLandscape`. После `font_scale 2` система сама пересоздаёт Activity. Немедленный `scenario.recreate()` сталкивается с этим пересозданием, и `field_name` не находится за 8 секунд. Теперь повторный `recreate` вызывается только когда Activity уже `RESUMED` и `fontScale` около 2. Таймаут 8 секунд не увеличен. Метод PASS в пакете редактора. Прежнее единичное падение R5a-fix этим не стирается: там повтор отдельно тоже был PASS, а первый прогон — FAIL.

`SubscriptionsViewModelTest.overflowNeverProducesEmptyOrPartialTotals` в текущих 390 JVM-тестах PASS, 0.002 s. Историческая нестабильность в этом прогоне не повторилась. Утечка `Dispatchers.Main` отдельно не разбиралась и не считается ни доказанной, ни снятой.

`SettingsRedesignUiTest.largeFontAndLandscapeKeepLabelsAndTheLastSection` в прогоны REG-01 не входил. Зависание на 551 s из R4b не повторялось и не снимается.

## 8. Листы и два уведомления

`trackerMotionDuration` равен нулю и когда пользователь выключает анимации, и когда системный `animator_duration_scale` равен 0. Оба запрошенных сочетания идут через `TrackerImmediateSheet` (`Popup`). Модальный sheet остаётся только при ненулевой длительности.

Новый `sheetsStayUsableWhenMotionIsZeroAndWhenItIsEnabled` проверяет оба сочетания, выбор каталога, подтверждение даты, системный Back, сохранение черновика, отсутствие диалога выхода при закрытии листа и bounds нижних кнопок внутри окна в landscape при font 2. Фон редактора не меняет черновик.

Первый прогон FAIL: `editor_date_confirm.performScrollTo()`. Подвал листа вне колонки прокрутки, поэтому действие Scroll отсутствует. Это не доказательство, что кнопка вне экрана. Вызов убран, видимость проверяется через `assertIsDisplayed` и bounds. Второй прогон FAIL: повтор листов начинался с настроек, где нет «Добавить подписку». Третий прогон FAIL: в landscape при font 2 кнопка есть в дереве, но без прокрутки касание не открывает редактор за 5 секунд. После `performScrollTo` метод PASS, затем ещё раз PASS в пакете 80.

Уведомление поверх уже открытого календаря и каталога покрыто прежними `notificationWithOpenCalendarAsksBeforeLeavingAndCancelKeepsTheDraft` и `notificationWithOpenCatalogContinuesToDetailsAfterDiscard`. Оба вошли в пакет 80 и прошли.

`cancellingAnOlderExitDoesNotDropANewerNotification`: при открытом диалоге выхода приходит второе уведомление, нажимается «Продолжить редактирование», и если диалог ещё на экране — «Выйти без сохранения». Открываются детали второй подписки, детали первой отсутствуют. PASS в отдельном прогоне 3.264 s и в пакете 80. `TrackerApp.onExitCancelled` не менялся. Для этого порядка отмена старого запроса не съедает более новый. Окно, в котором отмена прочитала бы уже опубликованный новый `notificationState` до `confirmExit`, этим сценарием не поймано. `decide()` возвращает `AwaitEditor` до `exists()`, поэтому пауза lookup не удерживает `confirmExit`, пока редактор открыт.

Файлы: `R5aEditorUiTest.kt`, `NotificationNavigationUiTest.kt`.

## Проверки

`git diff --check`: PASS, вывод пустой.

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain`: BUILD SUCCESSFUL in 50s, 61 задача (11 выполнено, 50 UP-TO-DATE). JVM: 390 тестов, 46 файлов, 0 failures, 0 errors, 0 skipped. `assembleDebug` и `compileDebugAndroidTestKotlin` в этой команде UP-TO-DATE: тот же исходный набор уже был собран пакетом Android-тестов. `lintReportDebug` тоже был UP-TO-DATE и указывал на отчёт 15:25 UTC, то есть до этих правок. После удаления отчёта отдельный `:app:lintDebug` пересобрал его в 16:59 UTC: BUILD SUCCESSFUL in 8s, 0 ошибок, 19 прежних предупреждений (`AndroidGradlePluginVersion` 2, `GradleDependency` 9, `NewerVersionAvailable` 6, `ObsoleteSdkInt` 1, `UnusedResources` 1). Нового предупреждения от `ExperimentalFoundationApi` нет.

Восемь методов одним прогоном: 8/8 PASS, время тестов 60.677 s, BUILD SUCCESSFUL in 1m 15s.

Классы этих методов: `SubscriptionsUiTest`, `KeyboardRotationUiTest`, `CrossScreenDesignUiTest`, `LargeFontWindowUiTest`, `AccessibilityUiTest`, `FinanceNavigationTest`. 33 теста, 32 PASS, 1 FAIL, BUILD FAILED in 2m 40s. Единственное падение — новое, см. ниже.

Пакет редактора, календаря, ассетов, навигации уведомлений и `KeyboardRotationUiTest` целиком: 80 тестов, 80 PASS, 0 failures, BUILD SUCCESSFUL in 4m 8s. К прежним 77 добавлены `sheetsStayUsableWhenMotionIsZeroAndWhenItIsEnabled`, `cancellingAnOlderExitDoesNotDropANewerNotification` и второй метод `KeyboardRotationUiTest`, потому что прогонялся класс, а не один метод. `pinnedSaveStaysAvailableWithImeScrollFontAndLandscape` в этом пакете PASS.

Полный накопленный Android-набор, 448 методов: NOT RUN. Его остановило новое падение класса, см. ниже. TalkBack и Photo Picker API 33: NOT RUN.

## Новое падение

`AccessibilityUiTest.settingsReadErrorRetainsThemeAndRetryUsesStoredData`, строка 166. `waitUntil` 5 секунд не дождался `theme == DARK`.

Изолированный повтор тоже FAIL. Тот же метод на производственных файлах SHA `cdd6221`, без правок REG-01, тоже FAIL. Диагностика: `use.settings.first()` уже `DARK`, а `SettingsViewModel` остаётся `Content` с `theme=SYSTEM`, `error=null`, `pending=false`; список при этом `Empty`. Экран настроек ещё не открыт. Запись в репозиторий есть, активное состояние ViewModel её не показывает.

Это не регрессия вёрстки REG-01. Точная причина, почему уже запущенный коллектор не применяет значение, которое новый коллектор читает сразу, к одной строке не свелась. Код настроек, DataStore и ViewModel не менялся. Полный набор из-за этого падения не запускался.

## Что сохранено и что вне scope

Сохранены точные копейки, отказ при overflow, фиксированное 09:00, островок на трёх корнях, его прозрачность и скрытие по прокрутке, русский интерфейс, офлайн и прежние маршруты редактора R5a-fix. Производственные анимации не выключались ради теста.

Не менялись Room, AssetStore, алгоритмы денег и календаря, WorkManager, ledger, Manifest, Gradle, зависимости и `TrackerApp`.

## Риски и следующий шаг

`BringIntoViewSpec` дополнительный и экспериментальный. Он влияет на программный доскролл детей корня, когда островок измерен. Редактор и архив без островка получают прежний spec. Классы корней, кроме одного нового падения настроек, и пакет редактора это пережили.

`openArchive` стал строже: после клика обязан появиться `archive_screen`. Вызовы из прошедших классов и пакета редактора это выдержали. Остальные классы, которые тоже вызывают helper, в этот этап не входили.

Новое падение `settingsReadErrorRetainsThemeAndRetryUsesStoredData` остаётся открытым и не должно считаться исправленным. Зависание `largeFontAndLandscapeKeepLabelsAndTheLastSection` тоже не снято.

Остановиться до независимой приёмки. R5b не начинать.

## REG-01-fix

Исходный SHA: `d4bb6b0d35e85900d1c3fd0bc3443b52a6085416`. Среда ENV-01 не менялась. PR #11 не менялся. Новый PR не создавался. Merge, force push и ручной запуск Actions не выполнялись. R5b не начат. PERSONAL-READY не объявляется.

### 1. Тема остаётся SYSTEM при уже записанном DARK

Тест: `AccessibilityUiTest.settingsReadErrorRetainsThemeAndRetryUsesStoredData`.

Это не сброс ViewModel и не потеря файла. `SettingsViewModel` уже собрал первый снимок `SYSTEM` и остался в `Content`. Новый коллектор `settings.first()` и отдельный коллектор, подписанный до записи, видели `DARK`. Повторная запись `LIGHT` в тот же уже работающий коллектор доходила. Значит, DataStore по-прежнему доставляет более новую версию, а теряется именно запись, чья версия не больше версии, захваченной первым `readState`.

Механизм DataStore 1.1.7: поток `data` сразу отдаёт результат `readState`, затем отбрасывает состояния кэша с `version <= startState.version`. Запись `DARK`, пересекающаяся с этим первым чтением, получает версию не выше уже отданной. Уже запущенный коллектор её не видит. Коллектор, открытый после записи, читает кэш и получает `DARK`. Файл не очищается. `retryRead()` перед проверкой темы не вызывался.

Исправление в производственном репозитории, не в тесте. `PreferencesSettingsRepository` после `edit` публикует записанный `Settings`. `SettingsPublication` отдаёт значения по порядку и не даёт устаревшему снимку хранилища заменить уже опубликованную запись. Поля по-прежнему меняются одним `edit`: тема, анимации и offsets не затирают друг друга.

Сохранены проверки исходного теста: сохранённый `DARK` применяется до открытия экрана; ошибка чтения оставляет тему и не удаляет данные; повтор загружает сохранённое; после записи состояние обновляется без перезапуска. `systemThemeRespondsToConfigurationWhileExplicitChoicesRemainStable` в том же прогоне PASS.

Файлы: `SettingsRepository.kt`, `SettingsUpdateTest.kt`. JVM-регрессия `staleStoreValueDoesNotReplaceAPublishedWrite`.

### 2. Островок и горизонтальный график

`BringIntoViewSpec.calculateScrollDistance` не знает ось. `verticalScroll` и `horizontalScroll` читают один `LocalBringIntoViewSpec`. Вычет высоты островка из `containerSize` на графике становится правым отступом: колонка, которая уже целиком видна у правого края, докручивается на высоту островка. Собственный `scrollTo` графика этот spec не использует, поэтому выбор месяца по `LaunchedEffect` не смещался. Смещение появляется у программного bring-into-view.

`TrackerRootChrome` запоминает spec, который был до островка. `MonthlyForecastChart` возвращает его вокруг единственного `horizontalScroll`. Вертикальный доскролл по-прежнему поднимает ребёнка над островком. При `bottom == 0` формула не меняется.

Файл: `TrackerRootChrome.kt`, `MonthlyForecastChart.kt`. Тест `HorizontalChartBringIntoViewTest.visibleChartColumnDoesNotScrollByTheIslandHeight`: колонка `2027-03` уже в окне 300 dp, inset 100 px, после `performScrollTo` горизонтальное смещение 0. PASS.

### 3. Отмена старого диалога и новый запрос

`consumed(sequence)` по-прежнему ничего не делает, если аргумент не равен текущей последовательности. Дефект был в вызове: `onExitCancelled` читал живой `notificationState` в момент нажатия. Если новая последовательность уже попала в композицию, отмена старого диалога вызывала `consumed` с новым id и снимала новый запрос.

`ExitBinding` запоминает первую последовательность, которая открыла диалог. Повторный `confirmExit` её не подменяет. Диалог, открытый без уведомления, не принимает запрос, пришедший позже. «Продолжить редактирование» потребляет только связанный id. «Выйти без сохранения» связь снимает и запрос не потребляет: после выхода из редактора решение R4a открывает детали. `decide`, `applyNotificationNavigation` и контракт `consumed` не менялись.

Детерминированные id: JVM `openDialogKeepsTheFirstRequestId` (11 остаётся при 12), `secondRequestBeforeTheDialogComposesDoesNotReplaceTheFirst`, `dialogOpenedWithoutANotificationDoesNotAdoptALaterRequest`. Инструментальный `olderExitDialogKeepsItsRequestIdWhenANewerNotificationIsPublished` ждёт, пока метка диалога сохраняет старый id, а живой id уже другой, затем продолжает редактирование и выходит. Открываются детали второй подписки.

Прежний `cancellingAnOlderExitDoesNotDropANewerNotification` в первом прогоне после правки FAIL: диалог уже закрыт, детали сами не появляются, потому что редактор ещё открыт. Тест теперь выходит из редактора, если диалога нет. Повтор PASS. Первое падение не скрывается.

Файлы: `EditorExitRequest.kt`, `EditorScreen.kt`, `TrackerApp.kt`, `TrackerComponents.kt`, `EditorExitRequestTest.kt`, `NotificationNavigationUiTest.kt`.

### Проверки

`git diff --check`: PASS, вывод пустой.

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain`: BUILD SUCCESSFUL in 49s. JVM: 394 теста, 46 файлов, 0 failures, 0 errors, 0 skipped. К прежним 390 добавлены четыре метода, названные выше. Lint пересобран в этом же запуске: 0 ошибок, 19 прежних предупреждений (`AndroidGradlePluginVersion` 2, `GradleDependency` 9, `NewerVersionAvailable` 6, `ObsoleteSdkInt` 1, `UnusedResources` 1).

Полный Android-набор на API 31, `emulator-5554`. В исходниках 450 методов `@Test`: базовые 448 плюс `visibleChartColumnDoesNotScrollByTheIslandHeight` и `olderExitDialogKeepsItsRequestIdWhenANewerNotificationIsPublished`. Прогон 12m 35s. Зависаний не было: ни один метод не оставался текущим три минуты, дамп потоков не снимался.

| | Количество |
| --- | --- |
| Методов `@Test` в исходниках | 450 |
| Успешно | 437 |
| Упало | 4 метода и отдельно `initializationError` класса |
| Пропущено | 0 |
| Не запускалось | 9 методов `FinanceIntegrationTest` |

`FinanceIntegrationTest` не стартовал ни одним методом. JUnit отверг класс: `livePriceDateAndLifecycleChangesRecomputeOnlyActiveForecasts` не `void`, потому что `runBlocking` возвращает значение `await`. Это 9 методов. Раннер вместо них записал один `initializationError`. Файл в этом этапе не менялся.

Четыре упавших метода и повторы, тем же сообщением:

- `StorageBlockedUiTest.incompatibleFailureDrawsDespiteSettingsLoadingAndRemainsBlockedAfterRestoreAndNotification`. Тело проходит, `ActivityScenario.close` не дожидается `DESTROYED`, последнее состояние `PAUSED`. Изолированный повтор FAIL. Тот же FAIL на SHA `d4bb6b0` без правок REG-01-fix.
- `ReminderSystemRegressionTest.expiredManualPaymentAndDeadlineNeverCreateCatchUpNotification`, строка 86, `assertTrue` без сообщения. Изолированный повтор FAIL. Тот же FAIL на `d4bb6b0`.
- `StorageOpeningTest.committedWalSchemaIsInspectedAndNotIgnoredOrCheckpointedOnRejection` и `compatibleWalCommittedRowsSurvivePreflightAndRoomOpen`. Оба падают на `execSQL("PRAGMA wal_autocheckpoint=0")` до вызова `TrackerDatabaseFactory.open`: `SQLiteException`, code 0, запрос нельзя выполнить через `execSQL`. Изолированный повтор обоих FAIL. Код Room и эти тесты не менялись.

Это прежние дефекты прогона, не регрессия REG-01-fix. В этом этапе они не исправлялись.

API 33, TalkBack и process death: NOT RUN.

### Что сохранено и граница

Сохранены Room, денежная и календарная арифметика, AssetStore, Gradle, зависимости, контракт `consumed(sequence)` и вертикальный доскролл над островком. Восемь регрессий REG-01 не переоткрывались.

Остановиться до независимой приёмки ChatGPT. R5b не начинать. PERSONAL-READY не объявлять.
