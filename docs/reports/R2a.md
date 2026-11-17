# R2a — единый календарный финансовый прогноз

Дата: 17.11.2026. Статус: **реализовано, подготовлено к независимой приёмке в указанных границах; Android-выполнение NOT RUN**. Весь R2 не завершён, PERSONAL-READY не объявляется.

## База и источники

- Исходная ветка: `codex/r1b-05-final-regression`, принятый SHA `cdcc8e681be4422c69697546ea6d2649416ec86b`, [Draft PR #6](https://github.com/GlebSkorobogatov/subscriptions-android/pull/6).
- Перед началом HEAD совпал, рабочее дерево было чистым, среда доступна. Ветка `codex/r2a-calendar-forecast` создана непосредственно от указанного SHA.
- SHA проверенной реализации R2a: `3458cf873a639270c8d2225754734459b05eefc9`. Следующий коммит содержит только отчёт. Итоговый SHA ветки с отчётом закрепляется в новом Draft PR и итоговом ответе; собственный хеш нельзя включить в содержимое его же коммита.
- Base stacked PR: `codex/r1b-05-final-regression`; head: `codex/r2a-calendar-forecast`.

Применены AGENTS, PRD, ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_PLAN, UI_DESIGN_SPEC, DECISIONS 2.1; отчёты R1b.2–R1b.5 и материалы принятых R1a/R1b.1 (PR #1/#2), включая исправления. Их результаты не считаются проверкой этой версии. Последнее явное уточнение владельца о контрольных датах имеет приоритет. Документы требований не переписывались.

## Инвентаризация и итоговый API

До изменений ForecastEngine имел отдельные `next30Days`, `calendarMonths(3/6/12,currency)` и generic `calculate(from,until,currency)`, суммы по валютам и availableCurrencies. UseCases публиковал отдельные forecast30Days/forecastMonths/selectedChart. Главный экран и FinanceSnapshot самостоятельно создавали ForecastEngine и рассчитывали разные окна. Метаданные уже читались набором строк, но весь расчёт не был одной общей проекцией.

Теперь единственный production-вызов расчёта находится в `SubscriptionUseCases.forecasts`. Он получает один набор Room-строк, однозначно преобразует его в целевые SubscriptionRecord и один `today`. Существующий DefaultRenewalCalendar остаётся единственным календарным движком. В калькулятор не передаются legacy-модели, история или настройки валюты/горизонта.

```kotlin
ForecastEngine.calculate(subscriptions: List<SubscriptionRecord>, today: LocalDate): ForecastResult

ExpectedCharge(subscriptionId, dueDate, amountMinor: Long)
MonthlyForecast(month: YearMonth, charges, totalMinor: Long)
ForecastWindow(fromInclusive, untilExclusive, charges, totalMinor: Long)
ForecastResult(fromInclusive, untilExclusive, charges, totalMinor: Long, months, next30Days)
SubscriptionForecastSnapshot(forecast: ForecastResult, rows: List<SubscriptionEntity>)
```

`ForecastResult.charges/totalMinor/untilExclusive` относятся к 12 календарным месяцам. `next30Days` — отдельное окно внутри того же результата. Все суммы — Long в копейках RUB, включая 0. RubPrice сохраняет строго положительный инвариант цены подписки и не используется для нулевого итога. Money/CurrencyCode остаются только техническим RUB-форматированием на границе UI; вычислительные DTO их не содержат. Суммирование каждого месяца, короткого окна и общего итога — Math.addExact. Ошибка не публикует частичный результат. Float/Double для денег не используются; геометрия графика получает уже точные Long-итоги.

Удалены из прогнозного API/DTO: totalsByCurrency, availableCurrencies, ExpectedCharge.currencyCode, валютные фильтры/параметры, FinanceUiState.currency, FinanceSnapshot.currency/horizon, generic горизонты 3/6, произвольный public range API и отдельные UseCases forecast30Days/forecastMonths/selectedChart. Forecast на главном теперь одно значение Long, а не список валютных Money. Стабильные cleanup-ключи старых SavedStateHandle сохранены: они только отбрасывают старый временный выбор, не управляют расчётом и не стирают Preferences.

## Математические границы и контрольные примеры

- Короткое окно: **[today, today.plusDays(31))** — сегодня и следующие 30 дней, 31 календарная дата.
- Календарное окно: **[today, YearMonth.from(today).plusMonths(12).atDay(1))**. Первый месяц неполный; прошлые дни не включаются. Ровно 12 хронологических корзин, в том числе нулевых.
- Каждая ACTIVE-подписка перечисляет все события в календарном окне через существующий bounded lookup календаря. Нижняя граница исходной серии сохраняется, дни/недели точные, месяцы/годы календарные. События сортируются date → stable ID. Одинаковые названия не объединяются.
- Короткое окно получается фильтрацией **того же** рассчитанного списка событий. Его граница всегда находится внутри представимого 12-месячного окна. Детали месяцев получаются группировкой того же списка, отдельного календаря/расчёта деталей нет. Сумма деталей совпадает с корзиной, сумма корзин — с общим итогом.

При today=16.11.2026:

| Дата | Короткое окно | 12 месяцев |
|---|---|---|
| 15.11.2026 | исключена | исключена |
| 16.11.2026 | включена | включена |
| 15.12.2026 (today+29) | включена | включена |
| 16.12.2026 (today+30) | включена | включена |
| 17.12.2026 (today+31) | исключена | включена |
| 31.10.2027 | исключена | включена |
| 01.11.2027 | исключена | исключена |

**Расхождение TEST_PLAN:** T-STAT-01 ошибочно предлагает включить 15.11 и исключить 16.12 при today=16.11; T-STAT-02 предлагает включить 15.11, хотя окно начинается 16.11. Использованы контрольные значения выше, прямо согласованные владельцем и соответствующие PRD/ARCHITECTURE. TEST_PLAN не изменён.

Независимые ожидаемые значения JVM-fixtures:

| Fixture | Ожидаемый результат |
|---|---|
| DAY=1, первый due сегодня, 123 коп. | 31 событие, 3813 коп. в коротком окне |
| WEEK=1, первый due сегодня, 123 коп. | 5 событий, 615 коп. в коротком окне |
| DAY=30, первый due 31.01.2027 | 31.01 → 02.03 → 01.04; не замена месяца 30 днями |
| MONTH=1, опора 31.01.2027 | 31.01 → 28.02 → 31.03 |
| MONTH=3 / MONTH=6, опора 31.01.2027 | 31.01/30.04/31.07… и 31.01/31.07; без накопления усечения |
| YEAR=1, опора 29.02.2028 | 28.02.2029 и 29.02.2032 в соответствующих окнах |
| 2 одинаковых имени, разные ID, 29950 и 101 коп. | отдельные события и точный короткий итог 30051 коп. при одном событии каждого |
| Нет ACTIVE / первая дата за пределами окна | 0 в обоих окнах; 12 пустых корзин с totalMinor=0 |
| Long.MAX_VALUE в итогах / overflow | представимый итог точен; превышение вызывает ошибку всего результата |

## Проверка непредставимой следующей даты

Отдельная регрессия воспроизвела успешный ноль вместо ошибки: ACTIVE с первым due=01.01.2027 и YEAR/Int.MAX_VALUE при today=02.01.2027. У серии нет следующего представимого события; запрос диапазона календаря сам по себе возвращал пустую последовательность. До исправления targeted JVM: **1 выполнен, 1 FAIL**.

ForecastEngine теперь сначала получает представимую ближайшую дату через тот же DefaultRenewalCalendar.firstOnOrAfter. Если её нет, расчёт завершается CalendarCalculationException. Если она существует, но позже конца окна, это нормальный нулевой прогноз. Далее перечисляются **все** события до конца окна; одним ближайшим событием прогноз не ограничен. Дополнительный lookup ограничен, исторические циклы не перебираются, сам календарь не изменён. В окончательном полном наборе эта регрессия PASS.

## Согласованный реактивный snapshot

`forecasts = combine(subscriptions, date.today)` вычисляется на Dispatchers.Default. Каждый emission содержит оба окна и **те же source rows**, из которых получены SubscriptionRecord. rows сохранены как существующий тип адаптера представления/optimistic row, не второй источник поведения. Название, accountLabel и оформление в детализации разрешаются по ID из этих rows, без независимого DAO/асинхронного lookup. Прогноз не хранится в Room или DataStore.

FinanceSnapshot — typealias общего SubscriptionForecastSnapshot, а financeSnapshots возвращает use.forecasts. Главный экран также берёт из него строки, today и короткий итог. Нет независимых правил активности: калькулятор использует `participatesInCalculations`. Существующие cold Flow могут пересчитаться для разных коллекционеров; каждый emission внутренне согласован. Общий mutable денежный cache/source of truth не вводился.

Создание, цена, дата, частота, cancel/archive/reactivate/restore/delete изменяют Room source и пересчитывают всю проекцию. CurrentDate обновляет оба окна. Startup/resume и системные date/time/timezone триггеры R1 не изменены. Чтение/расчёт не меняют исходную опору, состояние или цикл. Никаких записей фактических списаний.

FinanceViewModel сохраняет выбранный месяц через SavedStateHandle. Если месяц остаётся среди 12 ключей, выбор сохраняется; иначе выбирается текущий первый месяц. Изменение выбора меняет только детализацию, не финансовый snapshot/короткий итог. Empty определяется успешным отсутствием событий, а не отсутствием валюты. Даже при Empty UI показывает 0 и все 12 корзин. Loading/Error/Retry остаются отдельными состояниями.

Overflow/непредставимая дата/календарный лимит не превращаются в нули. На FinanceViewModel исключение даёт Error без snapshot, Retry повторяет источник. Для главного экрана добавлена обработка типизированной `ForecastCalculationFailure` в `subscriptionStates`: они дают обычный Error, а не попадают в общий storage-open handler как постоянная UNKNOWN-ошибка. В UseCases только исключения внутри вызова ForecastEngine (arithmetic/date/calendar) получают этот тип. Чтение и mapping находятся вне try-блока. Настоящие ошибки чтения/открытия и CancellationException проходят прежним путём. Защищённый механизм открытия не менялся. Этот дополнительный случай покрыт подготовленным Room-тестом; его Android-выполнение NOT RUN.

Минимальная UI-адаптация использует прежние компоненты: Long-значения/12 корзин/детали, короткий итог в текущей сводке финансов и нулевой результат, текст empty без предложения отменённого выбора горизонта. Навигация, темы и дизайн-система не менялись. Это не R6b и не нормализованная годовая стоимость R6a.

## Тесты: добавление и адаптация

260 исходных JVM-методов и 346 исходных Android-методов сохранены. **Удалённых тестов: 0.** Добавлены **17 JVM** и **8 Android**, итог **277/354**. Сохраняются все применимые R1-регрессии домена/календаря, редактора (в том числе 6 JVM + 18 Android R1b.4-fix), защиты хранилища, lifecycle, assets и notifications. Адаптации прежних методов и общих helpers перечислены ниже. Сохранение метода в исходниках не означает успешного Android-выполнения.

### Новые JVM-методы

**ForecastEngineTest**:

- `arbitraryThirtyDayIntervalIsNotAMonthAndFutureFirstEventIsRespected`.
- `dailyAndWeeklyIncludeAllThirtyOneCalendarDates`.
- `hundredActiveSubscriptionsIncludingDailyCalculateWithoutTruncation`.
- `monthlyQuarterlyAndHalfYearlyRestoreOriginalDay`.
- `movingTodayRebuildsBothWindowsWithoutChangingSource`.
- `ownerControlDatesUseInclusiveThirtyAndExclusiveCalendarYear`.
- `sameNamesStaySeparateAndMoneyDtoHasNoCurrencyOrFilter`.
- `shortWindowUsesExactlySameEventsAndCheckedKopecksAsBuckets`.
- `zeroForecastStillContainsTwelveChronologicalBuckets`.

- `aggregateEventBudgetFailsExplicitlyInsteadOfReturningPartialForecast`.
- `eventBudgetDoesNotLimitNumberOfSubscriptionsWithNoEventsInWindow`.

- `unrepresentableNextRenewalIsCalculationFailureRatherThanSuccessfulZero`.

**FinanceViewModelTest**:

- `calendarFailureIsErrorAndRetryRebuildsWholeSnapshot`.
- `cancellationIsNotPresentedAsCalculationError`.
- `futureActiveSeriesWithoutEventsHasTwelveZeroBucketsAndRetainsSelection`.
- `retryRecalculatesAfterOverflowCauseIsCorrected`.
- `selectingMonthNeverChangesEitherWindowOrSnapshot`.

Они проверяют контрольные границы, все повторы DAY/WEEK/N, опоры/високосность, пустые корзины, exact money/сортировку, общий набор событий, смену today, 100 записей, независимость выбора месяца, future-only ноль, overflow/calendar Error+Retry и CancellationException.

### Новые Android-методы — NOT RUN

**FinanceIntegrationTest**:

- `allMutationsUpdateBothCalendarWindowsAndPreserveSourceAnchor`.
- `concurrentEditsKeepMetadataBothWindowsAndDetailsInSameSnapshot`.
- `currentDateRefreshRebuildsBothWindowsAndMainProjectionWithoutRoomWrite`.
- `emptyAndFutureOnlySnapshotsKeepTwelveZeroBuckets`.
- `overflowIsScreenErrorRatherThanEmptyOrStorageBlockAndRetryCanRecover`.
- `realViewModelRetryRecoversAfterOverflowWithoutPublishingZeroSnapshot`.

**FinanceUiTest**:

- `monthSelectionChangesDetailsButNeverThirtyDayTotal`.
- `successfulZeroForecastKeepsBothZeroTotalsAndAllTwelveBucketsVisible`.

FinanceIntegrationTest использует изолированные synthetic UUID, существующую защищённую фабрику v5, READY reopen и настоящие UseCases/Room; новые проверки проходят все мутации, metadata/оба окна/списки событий, Main projection, overflow и настоящий FinanceViewModel Retry. FinanceUiTest проверяет видимые нули/12 корзин и неизменность короткого итога при выборе месяца. Они подготовлены/скомпилированы, а не выполнены.

### Существенные адаптации прежних тестов

| Класс / методы | Причина, сохранённая проверка |
|---|---|
| ForecastEngineTest: все 15 прежних методов fcT01/02/03/04/06/08/09/10/11/13/14, calT17/calT19, emptyInvalid…, leapYear… | plusDays(31) и единый API вместо 3/6/12/валютных карт. Сохранены независимые ожидания дат, overflow, range limits, ancient lookup, state exclusion, детализация/сортировка и отсутствие мутаций. Старые arbitrary/empty ranges больше не доступны: вместо них целевые zero12 и явные date/event-limit failures. Exact Long.MAX fixture стал yearly, чтобы представить одно ожидаемое событие в каждом проверяемом окне без заведомого overflow всего 12-месячного результата. |
| SubscriptionViewsTest.cancelledAndArchiveGroupsAgreeWithForecastExclusions / changedDateStartsWholeNewSeries | Новый API; при проверке первых двух событий полного окна берутся первые два, без восстановления удалённого горизонта. |
| FinanceViewModelTest: delayedFirstRead…, retiredCurrencyAndHorizon…, monthRestores…, removingRubData… | Новые суммы/корзины без currency/horizon полей. Сохранены loading, отсутствие записи старого выбора, month clamp/recreation, empty и read/overflow Error+Retry. |
| DatasetCompositionTest.projectionIgnoresHistoryAndUpdatesFuturePriceAndSchedule | Короткий итог теперь Long; проверка обновления цены/даты и reopen не удалена. |
| OfflineIconCatalogIntegrationTest.confirmedDeletion…; SubscriptionUseCasesTest.cancelledExclusive…; SubscriptionTransactionsTest.deliveryAcknowledgement… | Новый общий projection/API вместо удалённого forecast30Days/3-month API. Проверки безопасного удаления/shared asset, cancel exclusion/frozen start, ack без финансовых эффектов и транзакционные гарантии сохранены. |
| FinanceIntegrationTest.fixedRubTwelveMonths…, livePriceDate…, concurrentEdits… | RUB scalar вместо currency map; прежние reopen, live mutations и coherent metadata сохранены, дополнены обоими окнами. |
| FinanceUiTest.monthsSelect… / zeroMonth…; MonthlyChartUiTest.everyHorizon… / allTwelve… / reactivePrice… | Валютные/generic параметры удалены. Остались все 12 сумм/геометрия/выбор/детали/нули/большой шрифт/реактивная цена. Цикл трёх официально отменённых горизонтов заменён проверкой единственного 12-месячного; тест не удалён. |
| LargeFontChartUiTest helper | Только новый Forecast API и FinanceUiState constructor; обе темы/orientation/точная большая сумма/overflow текста проверяются по-прежнему. |
| SubscriptionsRedesignUiTest: forecast…, cancelledRows…, knownAccess…, suppliedDomainFraction…, largeFont… | Мультивалютный список заменён Long: zero fixtures=0, large-font aggregate=Long.MAX_VALUE без попытки сложить MAX с другими ценами. Все прежние status/progress/known-vs-unknown/format/target assertions сохранены. |
| SubscriptionsUiTest.useCaseInsertion… / cleanLaunch… / cancelledUnknownAccess… | Проверяется точный общий итог 149700 коп., а не количество валют. Найдены старые противоречивые device-assertions: ожидание удалённого поля currency заменено assertDoesNotExist при сохранении обязательных полей; неизвестный cancelled access проверяется в cancelled, не в пустом active. Добавлены active empty и forecast=0, проверки неизвестной даты/текста сохранены. Это исправление synthetic regression expectations, без изменений production редактора/lifecycle. |
| CrossScreenDesignUiTest.longAmounts… | Один aggregate RUB вместо трёх одноимённых валютных totals. Сохранены MAX и все три разных цены в соответствующих строках, отсутствие text overflow, widths/fonts/themes/details. |
| ArchiveSettingsIntegrationTest.archiveProjection… | Проверяется точный scalar итог восстановленной подписки, прежние UUID/цена/optimistic conflict остаются. |

В некоторых именах прежних методов остались SixAndTwelve/Auto/Mode/Currency — это имена исторических регрессий, не действующие возможности. Массовые переименования вне необходимости не выполнялись. Неприменимость старых DTO не используется как причина удалить transactional/storage/notification/asset тесты.

## Фактические проверки и производительность

Среда: JDK 21.0.9, Android SDK 36, Build Tools 35.0.0. Только synthetic fixtures.

```bash
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

| Проверка окончательной реализации | Результат |
|---|---|
| git diff --check (включая полный diff к принятой базе) | PASS |
| testDebugUnitTest | PASS — **277 выполнено**, 0 failures/errors/skipped |
| assembleDebug | PASS — APK собран |
| lintDebug | PASS — **0 ошибок, 8 прежних warnings**, новых нет |
| compileDebugAndroidTestKotlin | PASS — **354 @Test-метода скомпилировано**, сверено по class-аннотациям |
| Полная обязательная команда | BUILD SUCCESSFUL |
| APK DEX/API, packaged schemas 1–5, diff защищённых файлов | PASS — статический аудит, не device execution |
| connected/integration/Compose/system выполнение | **NOT RUN — 0 выполнено**, доступного устройства/эмулятора нет; adb devices завершился -6. ADB/KVM не обходились |
| GitHub Actions вручную / merge / очистка устройства | NOT APPLICABLE: не выполнялись |

Промежуточные частичные компиляции обнаружили оставшиеся старые List<Money>/horizon fixture-вызовы; они исправлены адаптацией потребителей. Эти компиляционные FAIL не объявляются успешными проверками. Окончательные результаты выше относятся к исправленной реализации, не к числам R1.

Стресс-проверка `ForecastEngineTest.hundredActiveSubscriptionsIncludingDailyCalculateWithoutTruncation`: 100 активных DAY=1, synthetic опора 01.01.0001, today=16.11.2026, цена 123 коп. Каждая серия содержит 350 событий до 01.11.2027; всего **35 000 событий**, totalMinor **4 305 000**; короткое окно **3 100 событий**, totalMinor **381 300**. Проверены полные количества/точные суммы, усечения нет. Измерение окончательного JVM-прогона: **280.212199 мс** (System.nanoTime, один полный вызов, включая enumeration/sort/group/totals; не Android benchmark и не гарантия latency устройства). Лимита количества подписок нет. Существующие ограничения представимости и maxEventsPerQuery календаря сохранены. Дополнительно введён явный общий ресурсный бюджет **100 000 событий на snapshot**: проверяется до выделения следующего события/сортировки, превышение даёт CalendarCalculationException → ForecastCalculationFailure → Error, без усечения и частичного результата. Это технический бюджет, не пользовательский горизонт или настройка; он задокументирован здесь. Отдельные JVM-проверки подтверждают точную границу и отсутствие лимита числа записей (1 000 future-only подписок допустимы даже при бюджете одного события). Исторические циклы до today не перебираются.

## Матрица применимых требований

PASS JVM означает фактическое выполнение нового полного набора. PASS audit — только проверка кода/артефактов. NOT RUN не заменяется компиляцией. Будущие части требований явно отделены.

| Требование / тест | Компонент и свидетельство | JVM | Android / ограничение |
|---|---|---|---|
| R-BASE-03, T-MONEY-01 | RubPrice/Money tests, Forecast totalMinor/Math.addExact, sameNames…/fcT14 | PASS | NOT RUN: Room/UI копейки; RUB-only DTO audit PASS |
| R-BASE-04 | No financial persistence, removed currency/horizon API; NonFinancialContractTest, DEX audit | PASS / audit PASS | NOT RUN: установленный UI/старые состояния; R1 backlog сохранён |
| R-CAL-01, T-CAL-04 | Единый календарь, все события, today и advance, lower bound | PASS | NOT RUN: date refresh → Room/UI |
| R-CAL-02, T-CAL-01/02/03/06 | RenewalCalendarTest, Domain tests, monthlyQuarterly…/arbitraryThirty…/leapYear…/limits | PASS | NOT RUN: persisted/editor интеграция R1 |
| R-CAL-03, T-CAL-08 | LocalDate, ancient lookup, movingToday…, unchanged R1 triggers | PASS для pure расчёта | NOT RUN: реальные midnight/timezone/resume; R1 консолидированный список сохранён |
| R-LIFE-01, календарная T-STAT-06 | participatesInCalculations; fcT08/10, coherent snapshots | PASS | NOT RUN: actual cancel/archive/restore/delete → projections |
| R-STAT-01, T-STAT-01 — календарная часть | [today,today+31), ownerControlDates…, dailyAndWeekly… | PASS | NOT RUN: сумма на экране. Нормализация годовой стоимости DEFERRED R6a |
| R-STAT-04, T-STAT-02/05 | ровно 12, incomplete current month, no 3/6, repeat events/zero buckets | PASS | NOT RUN: Compose/native reactive chart; окончательный дизайн DEFERRED R6b |
| R-STAT-05, T-STAT-05 — календарная часть | monthly detail from same events, exact sum/date+ID, same snapshot labels; VM selection/recreation | PASS | NOT RUN: Room concurrent metadata, rendering/selection. Нормализованный рейтинг DEFERRED R6a/R6b |
| R-STAT-06, T-STAT-06/08 — календарная часть | no facts/cache, exact zero12, overflow/calendar/read Error+Retry, cancellation | PASS | NOT RUN: Room/ViewModel Error→Retry и deletion integration |
| T-CAL-05/07, регрессия R1 | unchanged Domain/Editor/calendar suites | PASS JVM | NOT RUN прежних Android/editor/progress сценариев |
| T-NF-02 — расчётная часть | 100 ancient daily, 35 000/3 100 событий, замер выше, flowOn(Default) audit | PASS / audit PASS | NOT RUN: Android cold start/ANR/UI/process death/реальное устройство |

## Неизменность защищённой инфраструктуры и NOT RUN

Проверено отсутствие изменений относительно принятого SHA в Room v5/entities/DAO/schema exports 1–5, InitializationJournal/preflight/PreservingOpenHelperFactory, ActiveDataset, generation/mutation gates, AssetStore, Settings/DataStore/leases, WorkManager/ledger/publisher/ReminderRules, исходном DefaultRenewalCalendar и доменной модели подписки, MainActivity date triggers, manifest/applicationId/backup/D2D, Gradle/dependencies/CI, лицензиях и утверждённых документах. Изменения fixtures в data/room/asset тестах — только адаптация forecast calls; production защиты не менялись.

[Консолидированный отложенный список R1](R1b.5.md) сохранён **без изменения файла**. Все его device/manual проверки остаются NOT RUN. В текущем наборе 354 Android-метода вместо прежних 346; 8 новых добавляются, а не заменяют предыдущие проверки.

Дополнение R2a к очереди приёмки (все NOT RUN):

| Класс/метод | Требование / приоритет | Необходимое окружение | Ожидаемый результат |
|---|---|---|---|
| FinanceIntegrationTest — все 9 методов, особенно allMutations…, concurrentEditsKeepMetadata…, currentDateRefresh…, realViewModelRetry… | R-STAT-01/04/05/06, T-STAT-06/08 / P0 | Isolated Android Room fixture API31/33/36 | Оба окна/labels/prices из одного snapshot; мутации и today пересчитываются, overflow не zero/storage block, Retry восстанавливает расчёт. |
| FinanceUiTest — все 5 методов, включая successfulZero… / monthSelection… | T-STAT-05/08 / P1 | Compose, обе темы, font1/2 | Нули и все 12 корзин видны; выбор деталей не меняет короткий итог; error/retry не маскируется Empty. |
| MonthlyChartUiTest / LargeFontChartUiTest / FinanceNavigationTest | R-STAT-04/05, T-STAT-05 / P1 | Compose, узкий экран/landscape/rotation | 12 сумм, пиксельная геометрия, нулевые месяцы, восстановление выбора и навигация без валюты/горизонта. |
| SubscriptionsUiTest / SubscriptionsRedesignUiTest / CrossScreenDesignUiTest / ArchiveSettingsIntegrationTest | R-BASE-03/04, R-LIFE-01, R-STAT-06 / P1 | Compose/Room, обе темы/font2 | Single RUB aggregate, корректный ноль отменённых, restored price/UUID, доступность больших чисел; старые неверные fixture assertions не возвращаются. |
| Системные date/time/timezone + editor/finance/root без restart | R-CAL-03 / P1 | Физический Android, synthetic установка, действия владельца | Оба окна и выбор месяца обновляются без сдвига опоры, фоновые механизмы R1 не регрессируют. |
| 100 daily на Android при открытых экранах, process recreation | T-NF-02 / P1 | Эмулятор и физическое устройство | Измерить реальное время/отзывчивость; нет ANR, частичного успеха и потери выбранного месяца. Облачный замер этого не доказывает. |

## Изменённые файлы

- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/app/DatasetCompositionTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/assets/OfflineIconCatalogIntegrationTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCasesTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/room/SubscriptionTransactionsTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsRedesignUiTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsUiTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceIntegrationTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceUiTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/LargeFontChartUiTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/MonthlyChartUiTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/CrossScreenDesignUiTest.kt`.
- `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/settings/ArchiveSettingsIntegrationTest.kt`.
- `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/FinancePreviews.kt`.
- `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/review/DesignReviewGallery.kt`.
- `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsPreviews.kt`.
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCases.kt`.
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/forecast/ForecastEngine.kt`.
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceScreen.kt`.
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceViewModel.kt`.
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/MonthlyForecastChart.kt`.
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/ForecastSummary.kt`.
- `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsUiState.kt`.
- `app/src/main/res/values/strings.xml`.
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/forecast/ForecastEngineTest.kt`.
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/integration/SubscriptionViewsTest.kt`.
- `app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceViewModelTest.kt`.
- `docs/reports/R2a.md` — этот отчёт.

Полный diff просмотрен на scope/конфиденциальность перед коммитом. Только синтетические сведения; локальные базы, фотографии, логи, секреты и приватные пути не включены. Автор/коммитер используют GitHub noreply.

## Ограничения и следующий отдельный этап

- Android-тесты подготовлены и скомпилированы, 0 выполнено. Реальная Room reactive concurrency/SQLite, UI/rotation/IME/TalkBack и device performance остаются неподтверждёнными запуском. Старые имена отдельных fixtures не означают старую функциональность.
- До R6b остаётся нынешний экран финансов и прежняя главная сводка. Адаптация текущих totals не объявляется окончательным дизайном статистики; новые нормализованные месячные/годовые показатели не реализованы.
- R2b: rolling horizon, пересекающиеся напоминания коротких циклов, окончательная политика obsolete work/очистки ledger и системная приёмка уведомлений. Эти алгоритмы не менялись и не объявляются завершёнными.
- R6a: точная нормализация тарифов MONTH/YEAR и сравнение. R6b: окончательное представление статистики/рейтинга по утверждённому дизайну.
- H и перенос пользовательских данных не реализуются. Устройство владельца не очищалось, реальные данные не использовались. Merge и ручной запуск Actions не выполнялись.

R2a передаётся на независимую статическую и последующую инструментальную приёмку с указанными ограничениями. После подготовки stacked Draft PR работа останавливается; R2b не начинается.
