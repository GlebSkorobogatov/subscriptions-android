# R5a — редактор создания и редактирования подписки

## База и границы

Исходная ветка: `codex/r4b-settings`, принятый SHA `36d6f84f4ea310d75e0bffb50cb85b52ccadd8f1`, Draft PR #11.
До правок HEAD совпадал с этим SHA, рабочее дерево было чистым. Ветка `codex/r5a-editor` создана от этой точки.
SHA реализации: **0bf02dcd481199f5648d39f57ce3629410f6faa0**. Documentation-only commit указан в Draft PR и итоговом сообщении; самоссылка его SHA в этом файле не используется.

Изучены AGENTS, PRD, ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_PLAN, UI_DESIGN_SPEC §3.4 и §3.7, DECISIONS №21, №25, №95, №108, №123 и отчёты R3, R4a, R4b.
Затронуты R-SUB-01…07, R-UI-06, R-UI-09, R-BASE-02/03/04 и проверки T-UI-04, T-ASSET-01, T-CAL-05, T-MONEY-01, T-DATA-02.

R5b не начат: детали, отмена, архив, восстановление и их оформление не менялись. Главный экран, настройки и статистика не переделывались. Новая дизайн-система не создавалась. PERSONAL-READY не объявляется.
PR #11 и принятые ветки R0–R4b не изменялись. Merge, force push и ручной запуск Actions не выполнялись.

## Компоновка

Редактор остаётся полноэкранным вложенным маршрутом без нижнего островка. Верхняя панель с Back и заголовком «Добавление подписки» / «Редактирование подписки» сохранена. Форма прокручивается отдельно от кнопки «Сохранить».

Порядок полей: значок, «Изменить значок», название, необязательная подпись аккаунта, стоимость, периодичность, ближайшая дата, «Дополнительно». Значок не стоит сбоку от полей. Светлая, тёмная и системная темы идут через существующий `TrackerTheme`.

Значок — существующий `SubscriptionImage` размером `TrackerDimensions.logoSize` (54 dp). Это единственный утверждённый размер значка (значок списка 54×54, решение №120). Второй размер был бы новым токеном. Значки списка не менялись. Неизвестный каталожный id по-прежнему даёт «Другое». Выбор значка не пишет остальные поля. Своё изображение и «Убрать оформление» сохранены. Брендовые иконки и внешние каталоги не возвращались.

## Периодичность

Пять капсул `TrackerSelectedCapsule`: Неделя (WEEK/1), Месяц (MONTH/1), Квартал (MONTH/3), Год (YEAR/1), Другая. Отдельной капсулы «Полгода» нет. Шесть месяцев — это «Другая», MONTH/6.

«Другая» показывает положительное целое и единицы день / неделя / месяц / год. Повторное нажатие «Другая», когда произвольный период уже открыт, не сбрасывает число. Существующая нестандартная серия открывается как «Другая».

`EditorViewModel.change` не менялся по правилам: последовательная смена unit и count по-прежнему публикует промежуточное состояние, как ждут прежние тесты. Для капсул добавлен один метод `changeFrequency(unit, count)`. Он проверяет единицу и целое ≥ 1, пишет каноническую строку и один раз вызывает тот же `reconcileSchedule`, что и `change`. Временный уход с исходной частоты и возврат к ней восстанавливают anchor, start и periodDue одним наблюдением, без промежуточной серии WEEK/3 или MONTH/1.

Строка `editor_year` оставлена «Ежегодно»: экран деталей R5b использует её для единицы года. Капсула года берёт новую строку `editor_unit_year` («Год»).

## Значок и календарь

`CatalogPickerDialog` больше не отдельный `Dialog`. Это `TrackerBottomSheet` с ровно 12 записями `OfflineIconCatalog`, адаптивной сеткой `FlowRow` (не `LazyVerticalGrid`: внутри вертикальной прокрутки листа она падает), названиями, `selectable` / `Role.RadioButton`, «Выбрать своё изображение» и «Закрыть каталог». Закрытие без выбора не меняет черновик. Копирование, лимиты, `AssetStore`, счётчик ссылок и временные файлы не менялись.

Дата выбирается в таком же листе. Подтверждение — «Выбрать», `editor_date_confirm`. Поля `field_date` и `field_start` сохранены: существующие тесты вводят дату текстом. Отдельного корневого экрана календаря нет. Epoch-day по-прежнему `LocalDate.ofEpochDay(floorDiv(millis, 86_400_000))`. Смена опорной даты обновляет будущую серию. Правка только имени или цены не очищает опору. Невалидная дата не сохраняется.

Заголовок Material «Select date» убран (`title = null`). Названия месяца и буквы дней берутся из локали устройства. На API 31 эмулятор английский, поэтому в кадре «Mar 31, 2027» и S M T W T F S при русских строках приложения. Процессная локаль не подменялась.

`TrackerBottomSheet` получил необязательные `skipPartiallyExpanded` и `footer`. Без footer лист остаётся одной прокручиваемой колонкой. У редактора лист раскрыт полностью, «Выбрать» и действия каталога закреплены. Фон подвала — `colors.background`, чтобы вторичные кнопки не сливались с `surfaceRaised` в тёмной теме.

## Закреплённое сохранение и IME

«Сохранить» стоит под прокруткой, в области, которую `Scaffold` уже сдвинул на `WindowInsets.safeDrawing` (IME и navigation bars). Второго `imePadding` / `navigationBarsPadding` нет. `subscription_editor` заполняет уже ужатую область, поэтому низ редактора остаётся не ниже IME.

Сохранение только явное. Повторный тап не проходит, пока состояние `saving`, `importingImage`, `loading`, `conflict`, `loadFailure`, `notFound` или `done`. Невалидная форма показывает ошибки и не закрывает экран. `onSaved` и запрос разрешения уведомлений не менялись. Кнопка «Назад» внутри формы убрана: выход — стрелка шапки и системный Back.

## Черновики и Back

Версия черновика 5, стабильный UUID новой записи, `SavedStateHandle`, optimistic snapshot и проверка поколения не менялись. Неуспешная запись не стирает черновик и не считается успехом. Подтверждение выхода, отмена выхода и очистка временного изображения при отказе сохранены.

Back сначала закрывает открытый лист календаря или каталога, затем применяется к редактору. `ModalBottomSheet` потребляет системный Back сам; обработчик редактора в это время выключен. Стрелка шапки идёт через `exitRequest` и тот же `leave()`. `NotificationNavigationResolver` и `NotificationNavigationSession` не менялись. Уведомление поверх редактора по-прежнему требует подтверждения выхода.

Ошибка в скрытом `anchorDay` или `start` раскрывает «Дополнительно» (`editorExtraVisible`). Ошибка суммы этого не делает. Алгоритм прогресса не менялся.

## Отменённые и архивные

Редактирование отменённой серии не возобновляет её. При `scheduleEditable = false` капсулы, дата и дополнительные поля расписания недоступны. Имя, цена и изображение остаются доступными, как позволяет текущая модель. Отдельного режима восстановления нет.

## Защищённые компоненты

Не менялись Room v5 и schema exports, InitializationJournal, защита хранилища, generation/mutation guards, доменная модель и календарные алгоритмы, ForecastEngine, денежная арифметика, ReminderRules, WorkManager, ledger, PendingIntent, AssetStore, конвейер Photo Picker, схема DataStore, Manifest, applicationId, Gradle, зависимости, Backup/D2D и продуктовые требования.

`RubPrice.parse` / `Money` остаются целочисленными копейками. Float и Double для денег не используются. Валюта только RUB, выбора валюты нет. Проверены 299,50, целая цена, ноль, отрицательная, нечисловой ввод, лишняя дробная точность и overflow (`92233720368547758,08` и `9223372036854775807` отклоняются, `92233720368547758,07` принимается как `Long.MAX_VALUE`).

## Первоначальные падения R5a

До исправления, на API 31:

- `presetsCustomCalendarAndValidation`: клик «Месяц» в блоке «Другая» при количестве 1 превращал период в пресет MONTH/1, и капсула единицы уходила из дерева.
- Тот же тест: день календаря не находится по `Text = "10"`. У `DatePicker` номер дня снят с семантики, описание — полная дата.
- После перехода на описание день 10 текущего месяца оказался раньше сегодня (18 ноября 2026). Ошибка сохранения относилась к дате, скрытый `anchorDay` не раскрывался.
- `iconIsCenteredAndCatalogCloseKeepsOtherFields`: `assertTextEquals("")` для суммы ложно падает, потому что суффикс ₽ входит в `Text`, а `EditableText` пустой.
- `blockedAndFailedStatesKeepTheirActions` и `hiddenAnchorErrorOpensWithoutAnotherTap`: `setContent` на `IsolatedMainActivity`, которая уже задала content.
- `photoPickerSelectsSyntheticImageWhenAvailable`: жест попадал в «Preview» DocumentsUI, открывался Photos, и следующий запрос Compose не находил иерархию.

После правки эти сценарии проходят. Причины зафиксированы прогоном, не предположением.

## JVM

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain`

| Проверка | Результат |
|---|---|
| git diff --check | PASS, вывод пустой |
| :app:testDebugUnitTest | PASS, 384 теста, 0 failures, 0 errors, 0 skipped. База R4b была 373. Добавлены 11 тестов `EditorFrequencyTest` |
| :app:assembleDebug | PASS, UP-TO-DATE в этом прогоне: APK уже собран тем же исходником |
| :app:lintDebug | PASS, 0 ошибок, 19 предупреждений |
| :app:compileDebugAndroidTestKotlin | PASS, UP-TO-DATE |
| Общая команда | BUILD SUCCESSFUL in 48s, 61 actionable task: 12 executed, 49 up-to-date |

Предупреждения те же: GradleDependency 9, NewerVersionAvailable 6, AndroidGradlePluginVersion 2, ObsoleteSdkInt 1, UnusedResources 1 (`welcome_message`).

`EditorFrequencyTest`: пять пресетов и MONTH/6 как custom; атомарный квартал без промежуточного MONTH/1; возврат к загруженной частоте восстанавливает уточнения; имя и 299,50 не сдвигают опору; смена даты снимает уточнения до возврата; денежные строки; скрытая ошибка anchor/start; save не коммитит в blocked-состояниях и оставляет черновик; версия черновика 5 сохраняется.

## Android, API 31

Эмулятор `api31(AVD)`, Google APIs x86_64. Классы запускались пакетами. `--tests` не используется.

Первый пакет, 75 тестов, 6 failures. Второй пакет, 77 тестов, 2 failures. Повтор снимков после последней правки строки года: 10/10 PASS (`R5aEditorUiTest` 8, `R5aEditorStateUiTest` входит в первый пакет, `R5aGalleryUiTest` 1, `KeyboardRotationUiTest.realKeyboardRotationCalendarAndUnsavedBackPreserveDraftAndRoot` 1).

| Класс | Результат |
|---|---|
| R5aEditorUiTest | PASS 8/8 |
| R5aEditorStateUiTest | PASS 2/2 |
| R5aGalleryUiTest | PASS 1/1 |
| EditorUiTest | PASS 8/8 |
| EditorScheduleIntegrationTest | PASS 25/25 |
| DetailsEditorRedesignUiTest | PASS 15/15 |
| DetailsUiTest | PASS 5/5 |
| DesignSystemUiTest | PASS 9/9 |
| SubscriptionsUiTest | 10 PASS, 1 FAIL |
| KeyboardRotationUiTest | 1 PASS, 1 FAIL |
| CrossScreenDesignUiTest | 7 PASS, 3 FAIL |
| LargeFontWindowUiTest | 3 PASS, 1 FAIL |
| AccessibilityUiTest | 3 PASS, 1 FAIL |
| FinanceNavigationTest | 1 PASS, 1 FAIL |
| NotificationNavigationUiTest | PASS 14/14 |
| NotificationNavigationRaceUiTest | PASS 7/7 |
| NotificationPermissionUiTest | PASS 2/2 |
| RootNavigationUiTest | PASS 4/4 |
| LegacyNavigationUiTest | PASS 2/2 |
| ArchiveSettingsUiTest | PASS 7/7 |
| AssetStoreTest | PASS 4/4 |
| OfflineIconCatalogIntegrationTest | PASS 6/6 |

Новые проверки R5a на реальном пути Compose → ViewModel → Room: создание, редактирование, центр значка, пресеты и custom, открытие и закрытие календаря, выбор будущей даты, каталог, кнопка Photo Picker, ошибки, сохранение при IME, закреплённая кнопка после прокрутки, font scale 2, landscape, грязный Back, пересоздание Activity, повторный тап сохранения, отменённая серия без возобновления. Навигация уведомления поверх редактора: `NotificationNavigationUiTest` 14/14, включая уведомление во время редактора и во время сохранения.

### FAIL, воспроизведены на принятом SHA без R5a

Те же методы на `36d6f84` дали те же сообщения. Это не регрессия редактора. Причины самих проверок на базе R4b здесь не устанавливались заново: зафиксировано только тождество падения.

- `SubscriptionsUiTest.financialOverflowLeavesLibraryDetailsAndEditorAvailableUntilUserRepairsPrice` — timeout 5000 ms на `finance_30_total`.
- `KeyboardRotationUiTest.landscapeGlobalOffsetsRecreationPreservesOriginTabAndFixedTime` — `bottom_navigation` ещё есть в `openArchive`.
- `CrossScreenDesignUiTest.longAmountsKeepZeroTwoAndThreeFractionDigitsAcrossWidthsAndFonts` — ширина `92 233 720 368 547 758,07 ₽` на 320/1.0, 601×53 против абзаца 608.
- `CrossScreenDesignUiTest.landscapeRootIslandAndEmptyActionStaySeparatedAtLargeFont` — «CTA stays above island».
- `CrossScreenDesignUiTest.timeRowWidthFontAndThemeMatrixKeepsHorizontalWordsAndCurrentValue` — «Time on right 320».
- `LargeFontWindowUiTest.landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont` — нет `details_field_date`.
- `AccessibilityUiTest.enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable` — нет `tab_subscriptions`.
- `FinanceNavigationTest.lastChartMonthSurvivesThemeTabReturnRecreationAndActualRotation` — нет selected `tab_finance` после landscape.

Полный накопленный набор Android: **NOT RUN**.

## Изображение

На API 31 `PickVisualMedia` открывает DocumentsUI (`ACTION_OPEN_DOCUMENT`), не системный Photo Picker API 33. Синтетический PNG `r5a-synthetic.png` был в «Recent». Выбор миниатюры в стороне от кнопки предпросмотра вернул файл в редактор, появилось «Убрать оформление». PASS. Отдельный UI Photo Picker API 33: **NOT RUN**.

## Кадры

Сняты с эмулятора, в Git не входят. Каталог просмотра: `/opt/cursor/artifacts/r5a/`.

- `create-empty.png` — пустое создание, значок по центру, месяц выбран, «Сохранить» внизу.
- `create-filled.png` — заполненная форма, 799,00 и ₽.
- `edit.png` — редактирование существующей.
- `dark.png` — тёмная тема, светлая кнопка сохранения.
- `custom.png` — «Другая», количество 6, месяц.
- `extra.png` — раскрытый опорный день 31.
- `calendar.png` — лист календаря, «Выбрать», без «Select date».
- `catalog.png` — 12 значков, «Выбрать своё изображение» и «Закрыть каталог» видны.
- `validation.png` — ошибка стоимости.
- `unsaved.png` — выход без сохранения.
- `font-2.png` — font scale 2, заголовок переносится, «Сохранить» видно.
- `landscape.png` — landscape, капсулы в ряд, кнопка на всю ширину.
- `narrow.png` — 320×720, font 1.5.
- `device-create-empty.png`, `device-create-filled.png`, `device-edit-existing.png` — живая Activity.
- `device-portrait-ime.png`, `device-ime.png` — настоящая клавиатура, «Сохранить» над ней.
- `device-landscape-ime.png`, `device-landscape.png`, `device-calendar-landscape.png`.
- `device-font-2.png`, `device-calendar.png`, `device-validation.png`, `device-unsaved.png`.
- `device-picker-open.png` — DocumentsUI с синтетическим изображением.

TalkBack на устройстве: **NOT RUN**. Семантика Compose за него не выдаётся.

## Ограничения

Месяц и дни календаря следуют локали устройства. На этом эмуляторе они английские.
Капсулы при крупном шрифте переносятся; длинный заголовок на 320 dp и font scale 2 переносится на две строки и остаётся читаемым.
Повторные падения восьми методов выше существуют на принятом R4b и в этот этап не исправлялись: они относятся к главному экрану, настройкам, статистике и диалогам деталей.
Остановиться до независимой приёмки. Следующий этап не начат.

## R5a-fix

Исходный SHA: `2d7f1fb237fa6d2082fa68558e3f02b774ed38d6`.
Итоговый SHA — коммит, который добавляет этот раздел, на ветке `codex/r5a-editor`. Его значение записано в описании Draft PR #12. Самоссылка в файле не используется, как и SHA отчёта в разделе «База и границы».
База PR по-прежнему `codex/r4b-settings`, принятый SHA `36d6f84f4ea310d75e0bffb50cb85b52ccadd8f1`. PR #11 не менялся. R5b не начат. PERSONAL-READY не объявляется.

### Произвольная периодичность

На исходном SHA капсула выбиралась только через `editorPeriodPreset(unit, count)`. «Другая» открывалась как DAY/1. Следующий выбор MONTH давал MONTH/1, и та же функция возвращала пресет «Месяц»: поле количества исчезало. `presetsCustomCalendarAndValidation` вводил 6 до смены единицы, поэтому промежуточное MONTH/1 не проверял.

Режим «Другая» — только представление. `EditorState.customFrequency` и ключ `frequencyCustom` в SavedStateHandle не входят в расписание. `editorFrequencySelection` при `true` оставляет «Другая» даже для WEEK/1, MONTH/1, MONTH/3 и YEAR/1. `null` по-прежнему выводит капсулу из unit/count, поэтому MONTH/6 открывается как «Другая». Явная капсула пресета ставит флаг в false и вызывает прежний `changeFrequency`. Повторное нажатие «Другая» ничего не пишет. `setFrequencyMode` не помечает черновик грязным, не чистит ошибки и не трогает `scheduleDraft`. Версия черновика остаётся 5. Отдельный тип расписания не добавлен.

Новый Android-тест вводит единицу до числа, держит MONTH/1, WEEK/1, MONTH/3 и YEAR/1 внутри «Другая», не сбрасывает повторным нажатием и сохраняет режим после landscape и `recreate`. Затем явная капсула «Месяц» скрывает поле количества.

### Выход по уведомлению при открытом листе

На исходном SHA `leave()` при открытом календаре или каталоге только закрывал лист и возвращался. `LaunchedEffect` при `EditorExitResolution.Leave` сначала записывал `lastExit = exitRequest`, затем вызывал `leave()`. Запрос уведомления после закрытия листа считался обработанным и до подтверждения или перехода не доходил.

Обычный Back и внешний запрос теперь разделены. Стрелка шапки увеличивает `userBackRequest`. Уведомление по-прежнему увеличивает `exitRequest` через `confirmExit`. `editorBackEffects`: при сохранении — Ignore; пользовательский Back при открытом листе — только CloseSheet; внешний запрос закрывает лист и затем спрашивает подтверждение грязного черновика либо выходит из чистого. `lastExit` не сохраняется. После пересоздания Activity необработанная последовательность уведомления приходит снова. `NotificationNavigationResolver` и `NotificationNavigationSession` не менялись. Отмена по-прежнему вызывает `onExitCancelled` и снимает текущую последовательность. Новая последовательность не потребляется отменой старой. Идущее сохранение не прерывается.

Четыре Android-проверки: грязный календарь и отмена с сохранением черновика; грязный каталог и подтверждение с открытием подписки; чистый календарь без лишнего подтверждения; каталог, пересоздание и удалённая цель с возвратом на главный экран.

### Русский календарь

R-BASE-02 требует русский интерфейс. На английском эмуляторе API 31 Material показывал английские месяц, дни и описания: новое окно листа подменяло context и configuration. Процесс и устройство не переключались.

Русская конфигурация собирается из `LocalConfiguration.current` с `Locale.forLanguageTag("ru")` и даётся через `CompositionLocalProvider` внутри содержимого листа, после локалей окна. `rememberDatePickerState` создаётся там же. Подтверждение читает `selectedDateMillis` в момент нажатия. Epoch-day не менялся. Строки Material берутся из values-ru базового модуля. `@SuppressLint("AppBundleLocaleChanges")` стоит потому, что ресурсы уже в базовом модуле, а не в языковых сплитах.

Кадр эмулятора: заголовок «18 нояб. 2026 г.», месяц «Ноябрь 2026 г.», дни «П В С Ч П С В», кнопка «Выбрать». Проверка описания «Перейти к следующему месяцу» проходит, английское «Change to next month» отсутствует. Файл кадра в Git не входит: `/opt/cursor/artifacts/r5a-fix/russian-calendar.png`.

Первый общий gate остановился на lint `LocalContextConfigurationRead`: конфигурация читалась через `LocalContext.current.resources`. Чтение заменено на `LocalConfiguration.current`. Повторный gate этот дефект не содержит.

### Значок и анимации листов

Выбранная ячейка каталога получает фон `selectedTabBg`, контрастную метку с галочкой и `stateDescription` «Выбрано». Метка проверяется в unmerged-дереве: `selectable` сливает потомков.

`MotionScheme` в Material3 1.4 внутренний, и `ModalBottomSheet` не принимает длительность приложения. При `trackerMotionDuration == 0` лист — фокусируемый `Popup` без оконной анимации, с затемнением и семантикой dialog. Ненулевая длительность остаётся на `ModalBottomSheet`. Ноль даёт и выключенная пользователем анимация, и `animator_duration_scale` 0. Системный Photo Picker не оборачивается.

### Восемь прежних падений

Повтор на текущем дереве, API 31, один прогон, 8 тестов, 8 failures. Тесты не удалялись и не ослаблялись. Сообщения совпадают с разделом «FAIL, воспроизведены на принятом SHA без R5a». Это не дефекты редактора R5a. Исправление статистики, главного экрана, настроек и деталей не начиналось.

| Метод | Ожидание и источник | Класс | Серьёзность | Этап |
|---|---|---|---|---|
| `SubscriptionsUiTest.financialOverflowLeavesLibraryDetailsAndEditorAvailableUntilUserRepairsPrice` | R-STAT-06 и T-STAT-06: смена цены пересчитывает прогноз, ошибка не выглядит успешным нулём. Строка 230 находит `finance_error`, строка 240 видит цену 123. Строка 246 пять секунд не видит `finance_30_total`. Этот тег есть только вне фаз Loading и Error. Error — это `forecast == null` после `calculationOrNull`. | Дефект продукта, статистика | Средняя | R4a |
| `KeyboardRotationUiTest.landscapeGlobalOffsetsRecreationPreservesOriginTabAndFixedTime` | R-UI-01 и решение №78: на архиве островка нет. Первый `openArchive` в методе проходит. Второй, строка 88, после landscape, диалога смещений и `recreate`, находит `bottom_navigation`. Архивный маршрут ставит `root = null` и островок не рисует, значит назначение осталось корневым. | Дефект продукта, навигация после пересоздания | Средняя | R4b |
| `CrossScreenDesignUiTest.longAmountsKeepZeroTwoAndThreeFractionDigitsAcrossWidthsAndFonts` | UI_DESIGN_SPEC: осмысленную сумму не обрезать. При 320/1.0 строка `92 233 720 368 547 758,07 ₽` имеет widthOverflow: размер 601×53, абзац 608×53. Ряд при шрифте ниже 1.5 ограничивает цену 45% ширины. | Дефект продукта, строка списка | Низкая | R4a |
| `CrossScreenDesignUiTest.landscapeRootIslandAndEmptyActionStaySeparatedAtLargeFont` | UI_DESIGN_SPEC: landscape не перекрывает кнопки, пустой экран оставляет CTA «Добавить подписку». После прокрутки при font 2 низ кнопки ниже верха островка: «CTA stays above island». | Дефект продукта, пустой список | Средняя | R4a |
| `CrossScreenDesignUiTest.timeRowWidthFontAndThemeMatrixKeepsHorizontalWordsAndCurrentValue` | UI_DESIGN_SPEC: время одной строкой, перенос только при большом шрифте. При font 1 и ширине 320 `value.left >= label.right` ложно: внутренний порог 240 dp складывает ряд уже на обычном шрифте. | Дефект продукта, строка времени | Низкая | R4b |
| `LargeFontWindowUiTest.landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont` | Подтверждение и отмена находятся. Дальше тест ищет `details_field_date` для `EditEndDialog`. `DetailsScreen` для EditEnd и Disable пишет `details_field_end`; `details_field_date` относится к возобновлению. Поля и кнопок нет в недостатке. | Устаревший тест | Только тест | R5b, сверка тега |
| `AccessibilityUiTest.enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable` | R-UI-07 и решение №93: вертикальная прокрутка скрывает панели и снимает их семантику. После прокрутки к `settings_time` нет `tab_subscriptions`. Возврат вверх спецификация сохраняет. Тест возврат не делает. | Устаревший тест относительно принятого скрытия | Средняя | R4a |
| `FinanceNavigationTest.lastChartMonthSurvivesThemeTabReturnRecreationAndActualRotation` | Тот же R-UI-07. После landscape `chartSelected` прокручивает список к графику, затем строка 86 не находит `tab_finance`. Горизонтальный график сам скрытие не запускает; вертикальная прокрутка списка к графику запускает. | Устаревший тест относительно принятого скрытия | Средняя | R4a |

Точная причина, почему исправленная цена 123 не показывает `finance_30_total` за 5 секунд, дальше конвейера `calculationOrNull` → `ForecastState.Error` → фаза Error не сводилась. Точная причина, почему второй `openArchive` не уходит с корня, тоже не сводилась к одной строке. Оба вопроса остаются для отдельного решения.

### Проверки

Среда ENV-01: JDK 21.0.13, Android SDK `/opt/android-sdk`, Build Tools 35.0.0, Gradle 8.13, эмулятор `emulator-5554`, API 31, `sdk_gphone64_x86_64`.

`git diff --check`: PASS, вывод пустой.

Первая команда `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain`: **FAIL**. Unit-тесты к этому моменту прошли, 390 тестов, 0 failures, 0 errors, 0 skipped, 46 файлов. Lint остановил сборку: 1 ошибка `LocalContextConfigurationRead`, 19 предупреждений. Ошибка исправлена чтением `LocalConfiguration.current`.

Повтор той же команды после исправления: **PASS**, BUILD SUCCESSFUL in 1m, 61 actionable task: 18 executed, 43 up-to-date. Unit-тесты снова 390 / 0 / 0 / 0. К прежним 384 добавлены 6: три в `EditorFrequencyTest` (`customModeKeepsEveryPresetPairUntilTheCapsuleIsChosen`, `sequentialCustomEditsKeepTheModeAndRestoreTheLoadedSeries`, `customModeSurvivesANewViewModelOnTheSameHandle`) и три в `EditorExitRequestTest` (`userBackClosesOnlyAnOpenSheet`, `externalExitClosesTheSheetAndContinues`, `savingBackDoesNotCloseTheSheetOrTheEditor`). Lint: 0 ошибок, 19 прежних предупреждений (GradleDependency, NewerVersionAvailable, AndroidGradlePluginVersion, ObsoleteSdkInt, UnusedResources). assembleDebug и compileDebugAndroidTestKotlin: PASS.

API 31, пакет редактора, календаря, ассетов и навигации уведомлений, 77 тестов: **76 PASS, 1 FAIL**. Единственное падение — `R5aEditorUiTest.pinnedSaveStaysAvailableWithImeScrollFontAndLandscape`, `ComposeTimeoutException` 8000 ms на `field_name` после `font_scale 2` и `recreate`, строка 254. Лист даты этот метод не открывает. Отдельный повтор только этого метода: **PASS**, BUILD SUCCESSFUL in 23s. Первое падение не стирается повтором. В том же пакете прошли `R5aEditorUiTest` кроме этого метода, `R5aEditorStateUiTest` 3/3, `R5aGalleryUiTest` 1/1, `EditorUiTest` 8/8, `EditorScheduleIntegrationTest` 25/25, `NotificationNavigationUiTest` 18/18, `NotificationNavigationRaceUiTest` 7/7, `AssetStoreTest` 4/4 и `KeyboardRotationUiTest.realKeyboardRotationCalendarAndUnsavedBackPreserveDraftAndRoot`.

После правки lint заново прогнаны `presetsCustomCalendarAndValidation` и `calendarStaysRussianWhenAnimatorScaleIsZero`: 2/2 PASS, BUILD SUCCESSFUL in 34s.

Восемь прежних методов показаны выше отдельно: 8/8 FAIL, BUILD FAILED in 49s. Повтор для получения зелёного результата не запускался.

Полный накопленный набор Android: **NOT RUN**. TalkBack на устройстве: **NOT RUN**. Photo Picker API 33: **NOT RUN**. GitHub Actions вручную не запускались.

### Изменённые файлы

`EditorPeriod.kt`, `EditorExitRequest.kt`, `EditorViewModel.kt`, `EditorScreen.kt`, `TrackerApp.kt`, `TrackerRootChrome.kt`, `TrackerControls.kt`, `CatalogPickerDialog.kt`, `EditorFrequencyTest.kt`, `EditorExitRequestTest.kt`, `R5aEditorUiTest.kt`, `NotificationNavigationUiTest.kt`, `docs/reports/R5a.md`.

Не менялись Room v5, доменные и календарные алгоритмы, деньги, ReminderRules, WorkManager, AssetStore, DataStore, Manifest, applicationId, Gradle и зависимости. `changeFrequency` и восстановление `scheduleDraft` сохранены.

Остановиться до независимой приёмки. Следующий этап не начат.
