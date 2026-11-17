# R1b.5 — итоговая регрессия R1

Дата: 17.11.2026. **Подготовлено к независимой статической приёмке; инструментальная приёмка не выполнена. PERSONAL-READY не объявляется.**

## База и границы

- Репозиторий: `GlebSkorobogatov/subscriptions-android`.
- Принятая база: `codex/r1b-04-room-domain-integration`, `555b471b128e5d565b7a06e8d359c3cb53744a34` (Draft PR [#5](https://github.com/GlebSkorobogatov/subscriptions-android/pull/5)). HEAD совпал, дерево до начала было чистым.
- Рабочая ветка создана непосредственно от этой базы: `codex/r1b-05-final-regression`.
- SHA проверенной реализации R1b.5: `87dddcd8ca9afb48541b863e6adf8cd65d9336fb`. Следующий коммит содержит только этот отчёт. Итоговый SHA ветки с отчётом фиксируется в описании нового Draft PR и итоговом ответе; хеш собственного коммита нельзя включить в его же содержимое.
- Base нового stacked Draft PR: `codex/r1b-04-room-domain-integration`. Merge, R2, H и последующие этапы не выполнялись.

Прочитаны AGENTS, PRD, ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_PLAN, UI_DESIGN_SPEC, DECISIONS 2.1, отчёты R1b.2–R1b.4 с исправлениями и описания PR [#1](https://github.com/GlebSkorobogatov/subscriptions-android/pull/1), [#2](https://github.com/GlebSkorobogatov/subscriptions-android/pull/2), [#3](https://github.com/GlebSkorobogatov/subscriptions-android/pull/3), [#4](https://github.com/GlebSkorobogatov/subscriptions-android/pull/4), [#5](https://github.com/GlebSkorobogatov/subscriptions-android/pull/5). Результаты прежних этапов использованы как история, не как результат новой проверки. Расхождение старых UI-текстов документации о выборе времени разрешено последним решением владельца: фиксированное 09:00, без time picker. Продуктовые документы не менялись.

## Объём аудита и отменённые возможности

Проверены production/debug Kotlin, manifest/resources, настройки и SavedStateHandle, навигационные регистрации, публичные операции UseCases, Room entities/DAO/export, mapping, открытие foreground/background, календарь/домен, прогнозные snapshots, ledger/worker/publisher, ассеты и тестовые fixtures. После сборки отдельно проверены DEX определений классов и строк, packaged schema exports и merged manifest. Наличие имени не приравнивалось к доступной операции.

**PASS — статический аудит T-SCOPE-01:** в прикладном runtime нет Payment/Refund repositories, commands, entities/DAO; HistoryScreen/ViewModel/PaymentHistory destination; подтверждения/пропуска циклов, RenewalMode, старых lifecycle states, изменяемого индекса подтверждённого цикла, one-off дат, индивидуальных правил, событий отмены и автоархивирования. У UseCases нет финансовых команд; details читает одну подписку, без истории. Цена и расписание меняются только явными оставшимися операциями. Настройки не предоставляют валюту, горизонт, индивидуальные уведомления или время. Есть только ACTIVE/CANCELLED/ARCHIVED; вычисление даты не записывает цикл или платёж в Room.

**NOT RUN — device-часть T-SCOPE-01:** отсутствие удалённых действий на установленном APK, восстановление старой очереди/навигации и UI после обновления. Подготовленные тесты не запускались.

Остатки поиска классифицированы следующим образом:

| Упоминание / компонент | Может выполняться | Назначение и решение |
|---|---|---|
| Schema exports v1–v4, SQL старых таблиц в StorageOpeningTest | JSON читается только из fixture; рабочее открытие отклоняет версии 1–4 | Исторический источник/проверка недеструктивного отказа. Сохранены побайтово; не миграционный runtime и не удаляются. |
| `RestoredNavigation.removedDestinationId` / строка `PaymentHistory` | Только фильтрация старого Bundle | Старый destination не зарегистрирован. Сохраняет остальные маршруты/черновики; не открывает историю. Оставить до отдельного решения о сроке совместимости. |
| `DetailsViewModel` cleanup старого `operation`, whitelist actions/fields; editor draftVersion=5; FinanceViewModel cleanup `currency/horizon` | Только очистка несовместимого временного состояния | Не трогает Room/Preferences/изображения; не выполняет старую команду. Оставить безопасное отклонение. |
| Worker policy=0, unknown policy/payload rejection, ROOT/POLICY tags | Старый worker завершается без открытия Room, claim или публикации; возможен запрос актуального reconciliation | Нейтрализация старых задач, не compatibility-реализация удалённых правил. Отменяются только устаревшие задачи reminder-инфраструктуры, unrelated work сохраняется. Ledger не очищается. |
| `subscription_payments_v1`, ресурсные имена `notification_payment_*`, `payment_title/body` | Да, только техническая идентичность и текст ожидаемого продления | Стабильный канал сохраняет настройки ОС. Нет подтверждения или записи факта. Не переименовывать канал ради косметики. |
| DataStore `payment_offsets`, SettingsViewModel intent kind `payment`, тестовые теги `settings_payment_*` | Да, только глобальный набор дней продления | Стабильный ключ/черновик глобальных настроек, не платёж. Missing → {3,1}; явный empty → empty; невалидные члены отбрасываются; старые ключи других настроек не читаются и не стираются. |
| `Money`, `CurrencyCode`, totalsByCurrency, availableCurrencies, `forecastMonths(3/6/12)`, fixed horizon/currency getters | Да, технические RUB DTO/форматирование/расчёт | CurrencyCode принимает исключительно RUB. Пользовательский экран всегда 12 месяцев и RUB; выбора нет, Room валюту не хранит. Возможное упрощение DTO — R2; нормализация — R6a. |
| `history` в permission preferences/comments, cancellation в корутинах, manual period start | Да, действующие нефинансовые механизмы | История предложения разрешения, отмена coroutine и разрешённое уточнение начала периода не являются удалёнными продуктовыми функциями. |
| Старые названия тестовых методов с Payment/Manual/History/Currencies/Override | Только синтетические тесты | Тела проверяют отсутствие прежних действий, целевой lifecycle/ledger/UUID/согласованность RUB snapshots либо восстановление старого состояния. Массовое переименование не требуется. |
| Debug `IsolatedMainActivity` deleteDatabase/forget marker | Только явный instrumentation host для синтетического UUID; release launcher его не использует | Teardown изолированного fixture, не recovery. Production deleteDatabase отсутствует. Не менялся. |
| Debug `DesignReviewGallery` fields `mode=AUTO`, `currency`, `reanchor` | Поля не читались диалогом | Удалены как неиспользуемый остаток старого preview. Не восстанавливали функции даже до удаления. |

Других независимых runtime-моделей подписки/отменённых сервисов по итогам поиска не обнаружено. APK содержит исторические JSON и защитную строку старого маршрута; их наличие не считается финансовой функциональностью.

## Room v5 и единая модель

Схема и exports **1–5 не изменены** относительно принятой базы. В export v5 ровно три прикладные таблицы:

| Таблица | Проверенная структура |
|---|---|
| `subscriptions` | 19 полей; UUID PK, имя/подпись, priceMinor Long, unit/count, три состояния, anchorYear/month/day, scoped start/end, cancelled exclusive end/frozen start, ссылки оформления, createdAt/updatedAt/archivedAt. Нет сохранённого nextDue, валюты, cycle index, режимов или разовых исключений. FK logoAssetId → assets.id RESTRICT; индексы logoAssetId и state. |
| `assets` | 6 полей; UUID PK, безопасный relativePath, mime/size/hash/createdAt; unique relativePath. |
| `notification_delivery_ledger` | 15 полей; UUID PK, subscription/event date/offset/generation, delivery state/time/version, claim token/lease, failureCount/notBefore/deliveredAt. Только RENEWAL; unique (subscriptionId,eventType,eventDate,offsetDays,generation); FK subscriptionId → subscriptions.id CASCADE. |

Generated validator и snapshot имеют один источник истины. Дополнительный аудит DDL выполнил создание этих таблиц в in-memory SQLite среды; это проверка синтаксиса export, **не замена Android/Room integration**.

Mapping `SubscriptionEntity.toDomain()/toEntity()/withDomain()` переносит все поля SubscriptionRecord, scoped PeriodStart, exclusive/frozen даты, оформление и archivedAt, сохраняет createdAt и точность updatedAt. У Entity нет собственной старой модели поведения; Calendar/Domain/Forecast/Reminder inputs используют SubscriptionRecord. Snapshot содержит версионированный полный ряд, а не только ID/дату. JVM round-trip/money/domain/optimistic tests выполнены; реальная запись, FK/cascade и повторное открытие на Android — NOT RUN.

## Сохранность хранилища, жизненный цикл и ассеты

Статически прослежены оба production-пути: SubscriptionsComposition и ReminderRuntime → одна TrackerDatabaseFactory. Другого Room builder для рабочей базы нет. Read-only preflight проверяет version=5, quick_check, generated validator, snapshot-структуру, defaults/PK/FK/индексы и hashes до writable-open. v1–v4 несовместимы, не мигрируют и не очищаются. OPEN_READONLY не использует immutable URI, не игнорирует committed WAL и не делает checkpoint/recovery. Служебный API Room 2.8.3 не менялся; его платформенные свойства остаются предметом отложенных тестов.

InitializationJournal AUTHORIZED/CREATING/READY, fsync/atomic rename, привязка UUID, одноразовое создание и потеря READY-базы сохранены. Неизвестный/повреждённый pointer/marker или незавершённый .next блокируют работу; не выделяется новое поколение для обхода. Неполные ресурсы закрываются, DataStore leases освобождаются; cancellation не превращается в успешный empty. Порядок mutation gate → pointer guard → journal → SQLite не менялся. Нет destructive fallback, catch→delete или подмены имени базы. Foreground UI при ошибке блокирует навигацию и операции; preDraw учитывает failure Settings и не должен скрывать сообщение. Постоянные ошибки не повторяются бесконечно, временные имеют bounded retry.

Сессия публикуется после фактического открытия, окончательной валидации и READY. Sweep начинается только после успешного чтения достоверных asset-ссылок актуального поколения; ошибка не превращается в пустой набор. Все изменения lifecycle используют полный expected row и транзакцию. Delete требует confirm, каскад затрагивает ledger выбранной записи, общий asset защищён подсчётом ссылок и RESTRICT. Cancel фиксирует начало/конец exclusive, сразу выключает forecast/reminders, EditEnd не двигает frozen start; passage of time не архивирует. Reactivate сохраняет ID/appearance и очищает cancellation/старый period. Эти выводы по Room/ОС — **статический аудит**, не инструментальный PASS.

Сохранены 12 офлайн-значков и «Другое», атрибуции/лицензии, Photo Picker без gallery permission, приватная нормализованная копия, проверки формата/размера/пути, staging/draft/grace period, shared assets. ОС-доступность Photo Picker/поворот/реальный файл/очистка после последней ссылки требуют device-проверки.

Уведомления используют только ACTIVE и актуальные цену/расписание; настройки {7,3,1,0}, defaults {3,1}, empty off и 09:00 сохранены. Claim/lease/retry, logical key, generation/mutation guards и финальная транзакционная freshness-проверка перед publisher сохранены. DELIVERED только после acknowledgement. Stale/deleted/cancelled/archived подписка не авторизует новую публикацию. Повторное сохранение/reconciliation не очищает delivered keys. Time/timezone, startup/resume, настройки и записи по-прежнему инициируют reconciliation; reboot обеспечивается WorkManager. H restore ещё отсутствует, поэтому его триггер NOT APPLICABLE в R1.

## Подтверждённые дефекты и исправления

### E1. Временное изменение расписания теряло уточнение начала периода

Воспроизведение: MONTH с anchorDay=31, displayedDue=28.02.2027, start=01.02 и periodDue=28.02; изменить дату на 02.03 или unit/count, затем вернуть исходные значения и сохранить цену. `change()` удалял anchorDay/start/periodDue; общий schedule decision правильно возвращал null, но save передавал Set(null) и стирал уточнение.

Исправление: связанные уточнения сохраняются в SavedStateHandle `scheduleDraft` на время изменения серии. Возврат date/unit/count к исходным значениям восстанавливает именно прежний черновик уточнений, включая новое пользовательское начало или явную очистку. Остальные поля не откатываются. Новая серия по-прежнему не наследует устаревшие уточнения. Backup переживает recreation, удаляется при успешном save/явном reload/несовместимом draftVersion. validate/save по-прежнему используют один schedule decision; RecurringSchedule.starting не ослаблен.

### E2. Черновик только изображения обходил проверку восстановленного snapshot/generation

Воспроизведение: выбрать значок/изображение или удалить его, не изменяя текст; сохранить SavedStateHandle; конкурентно изменить ряд либо поколение; пересоздать EditorViewModel. Раньше reload проверял stale только при наличии saved fields, а image-only действия их не записывали: expected/generation обновлялись поверх старого контекста.

Исправление: image-only действия сохраняют текущие поля, в том числе displayedDue. Reload распознаёт pending image/catalog/removal как черновик даже у ранее созданного v5 состояния без fields; старые expected/generation сравниваются до их обновления. Конфликт блокирует save; явный reload отбрасывает черновик как раньше. Room/snapshot/generation gates не менялись.

До production-исправления выполнены 5 новых JVM-тестов: **4 FAIL, 1 PASS** (оба возврата уточнений и оба stale image-only сценария воспроизведены). После первого исправления целевой набор из 17 JVM-тестов прошёл. Добавлена ещё проверка сохранения изменённого/явно очищенного уточнения при возврате серии; окончательные 6 новых тестов включены в полный прогон. Android путь EditorViewModel → UseCases → Domain → Room подготовлен отдельно; его выполнение NOT RUN.

### E3. Текущая дата не обновлялась в непрерывно открытой Activity

Статическое воспроизведение: оставить Activity RESUMED при переходе через полночь либо изменении времени/пояса; CurrentDate обновлялся только startup/onResume/явным refresh, а системный receiver перепланировал только background work. Индикатор/ближайшая дата могли оставаться вчерашними до нового resume. Это уже требование R-CAL-03, не новая задача R2.

Исправление: MainActivity регистрирует в onStart и снимает в onStop небольшой CalendarRefreshReceiver для DATE_CHANGED/TIME_SET/TIMEZONE_CHANGED. Он вызывает существующий refreshToday текущей сессии, не открывает базу заново, не меняет сохранённую опору и не создаёт цикл. Добавлены 2 Android-теста разрешённых/посторонних actions. Они используют прямую синтетическую delivery без изменения системного времени или отправки protected broadcasts. Реальная доставка ОС/регистрация в lifecycle и обновление открытых экранов — NOT RUN; подлежит системной проверке, не заявлено доказанным.

## Тесты и выполненные команды

Среда: JDK 21.0.9, Android SDK 36, Build Tools 35.0.0. Синтетические UUID/подписки/изображения, пользовательские данные не читались. Логи/базы/изображения и приватные пути не включены в Git.

| Проверка окончательной реализации | Результат |
|---|---|
| `git diff --check` | PASS |
| `:app:testDebugUnitTest` | PASS: **260** тестов, 0 failures/errors/skipped, 36 классов; не старый результат |
| `:app:assembleDebug` | PASS: debug APK собран |
| `:app:lintDebug` | PASS: 0 ошибок, **8 прежних warnings** (6 NewerVersionAvailable, ObsoleteSdkInt, UnusedResources); новых нет |
| `:app:compileDebugAndroidTestKotlin` | PASS: **346 @Test-методов** в 39 классах, сверено по аннотациям скомпилированных class-файлов |
| Полная команда ниже | BUILD SUCCESSFUL |
| DEX, packaged schemas, Room exports, manifest/границы diff | PASS — статический аудит; НЕ device-тест |
| Android integration/Compose/system tests | **NOT RUN: 0 выполнено**. Доступного устройства/эмулятора нет; `adb devices -l` завершился кодом -6. Ограничения ADB/KVM не обходились |
| TalkBack, реальный Photo Picker, reboot/OS notification delivery, SQLite/WAL/power-loss | NOT RUN — нужно соответствующее Android-окружение |
| Ручной запуск GitHub Actions / merge / очистка устройства | NOT APPLICABLE: запрещены заданием, не выполнялись |

```bash
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

Относительно принятого SHA: **254 JVM сохранены без изменения, 6 добавлены, 0 адаптировано/удалено; 337 Android сохранены без изменения, 9 добавлены, 0 адаптировано/удалено**. Все 6 JVM и 18 Android-регрессий R1b.4-fix сохранены. Подсчёт — по существующим @Test-методам, не по количеству файлов. Ни один тест не ослаблен ради PASS.

Новые JVM: EditorDraftRegressionTest (6) — возврат даты/периода, сохранение edited start/explicit clear, image-only generation/snapshot conflicts, совместимое восстановление image-only. JVM loaded-row fixture использует reflection только для setup, как существующий EditorScheduleValidationTest; это не доказательство реальной записи.

Новые Android: EditorScheduleIntegrationTest (+7) — возврат date/unit/count и scoped period через recreation/save, явный clear, image-only recreation с продвижением today, старый fieldless image-only snapshot и generation, recreation до возврата временной серии, DAY/WEEK возврат через границу месяца. CalendarRefreshReceiverTest (+2) — системные actions/посторонние actions. Эти 9 тестов **скомпилированы, NOT RUN**.

## Матрица требований R1

PASS в колонке «аудит» означает только проверку исходников/контрактов/APK; PASS в «JVM» — фактическое выполнение связанного JVM-набора на этой реализации. NOT RUN означает отсутствие device/ручного выполнения. Ни одна строка с NOT RUN не объявляет полной приёмки требования. DEFERRED ограничен явно последующими этапами; это не скрытый PASS.

| ID | Компонент и связанные тесты | Аудит | JVM | Android/ручная часть, ограничения |
|---|---|---|---|---|
| R-BASE-01 | SubscriptionRecord, ForecastEngine, ReminderRuntime; ForecastEngineTest, NonFinancialContractTest | PASS | PASS | NOT RUN: offline сквозной запуск; новые stats UI/алгоритмы DEFERRED R2/R6 |
| R-BASE-02 | manifest, Compose/navigation, backup off; AccessibilityUiTest, NavigationUiTest | PASS в рамках сохранения | PASS (theme/contrast) | NOT RUN: API31/33/36, TalkBack, landscape/шрифт; дизайн DEFERRED R3+ |
| R-BASE-03 | RubPrice/Money/CurrencyCode, mapping; MoneyTest, SubscriptionMappingTest | PASS | PASS | NOT RUN: фактический ввод/запись копеек |
| R-BASE-04 | UseCases, Room, routes/settings/work; NonFinancialContractTest, runtime DEX audit | PASS | PASS | NOT RUN: device T-SCOPE-01 |
| R-SUB-01 | Domain/create, SubscriptionEditor, DAO; SubscriptionDomainTest, SubscriptionUseCasesTest | PASS | PASS | NOT RUN: UUID/duplicate names в Room/UI |
| R-SUB-02 | RubPrice, EditorViewModel; MoneyTest, EditorTest/StateTest | PASS | PASS | NOT RUN: UI validation и сохранение Long |
| R-SUB-03 | Frequency, calendar/editor; CalendarTypesTest, RenewalCalendarTest, EditorTest | PASS | PASS | NOT RUN: выбор интервалов в UI |
| R-SUB-04 | schedule decision, Domain/edit, mapping; EditorScheduleValidationTest, EditorScheduleIntegrationTest | PASS | PASS | NOT RUN: настоящий VM→Room и реактивный UI |
| R-SUB-05 | OfflineIconCatalog, AssetStore; OfflineIconCatalogTest, AssetStoreTest, OfflineIconCatalogIntegrationTest | PASS | PASS (каталог/ImageEdit) | NOT RUN: native vector/Photo Picker/файлы; дизайн DEFERRED R5a |
| R-SUB-06 | EditorViewModel/SavedStateHandle, explicit save; EditorDraftRegressionTest, EditorStateTest, EditorUiTest | PASS для операций | PASS | NOT RUN: rotation/process death/Back/IME; визуальный R5a DEFERRED |
| R-SUB-07 | anchor/refinement/PeriodStart; SubscriptionDomainTest, EditorScheduleValidationTest, EditorDraftRegressionTest | PASS | PASS | NOT RUN: Android save/recreation; компактная форма R5a DEFERRED |
| R-CAL-01 | единственный DefaultRenewalCalendar, CurrentDate; RenewalCalendarTest, SubscriptionDomainTest | PASS | PASS | NOT RUN: ежедневное UI/Room обновление без записей циклов |
| R-CAL-02 | anchor arithmetic и bounded lookup; RenewalCalendarTest, CalendarTypesTest | PASS | PASS | NOT RUN: persisted anchor round-trip |
| R-CAL-03 | startup/resume/calendar receiver, observeSession; timezone/calendar JVM, DatasetCompositionTest, CalendarRefreshReceiverTest | PASS после E3 | PASS для чистых дат/zone | NOT RUN: foreground midnight/OS broadcast/reboot/resume |
| R-LIFE-01 | SubscriptionState и ACTIVE eligibility; SubscriptionDomainTest, ForecastEngineTest, ReminderRulesTest | PASS | PASS | NOT RUN: Room/UI snapshots |
| R-LIFE-02 | Cancel/EditEnd/frozen period; SubscriptionDomainTest, LifecycleRepositoryTest, SubscriptionUseCasesTest | PASS | PASS | NOT RUN: полный cancel→UI→notifications путь |
| R-LIFE-03 | manual Archive, no time mutation; SubscriptionDomainTest, DetailsViewModelTest, LifecycleRepositoryTest | PASS | PASS | NOT RUN: confirm/Room/восстановление |
| R-LIFE-04 | reactivate validation, old ID/appearance; SubscriptionDomainTest, DetailsViewModelTest, LifecycleRepositoryTest | PASS | PASS | NOT RUN: форма и Room restore |
| R-LIFE-05 | confirm/full snapshot/transaction/cascade/asset refs; DetailsViewModelTest, SubscriptionTransactionsTest, OfflineIconCatalogIntegrationTest | PASS | PASS (VM/domain) | NOT RUN: FK/delete/work/общий файл |
| R-LIFE-06 | Domain.period, card mapping; SubscriptionDomainTest, SubscriptionCardModelTest | PASS | PASS | NOT RUN: UI exact end/midnight; цвет/дизайн R3/R5b DEFERRED |
| R-DATA-01 | ActiveDataset, guards/journal/preflight/leases/assets/ledger; InitializationStateTest, StorageSafetyTest, StorageOpeningTest | PASS | PASS (чистые контракты) | NOT RUN: Android SQLite/WAL/concurrency/OS/files |
| R-DATA-02 | factory v5 only + blocked screen; StorageOpeningTest, TrackerDatabaseTest, StorageBlockedUiTest | PASS | PASS (ошибки/one-use contract) | NOT RUN: native old DB byte preservation/fresh/reopen |
| R-NOT-01 | SettingsCodec/global inputs/09:00; SettingsTest, ReminderInputsTest, ReminderRulesTest | PASS в R1 | PASS | NOT RUN: DataStore/UI; overlapping short cycles DEFERRED R2 |
| R-NOT-02 | WorkManager scheduler/time receiver/CalendarRefreshReceiver; ReminderSchedulingTest, ReminderWorkManagerTest | PASS в R1 | PASS (чистое планирование) | NOT RUN: native reboot/timezone/background; итоговые правила R2 DEFERRED; H restore NOT APPLICABLE |
| R-NOT-03 | ledger/claim/generation/final freshness; ReminderRulesTest, ReminderInfrastructureTest, NotificationPublisherTest | PASS в R1 | PASS (pure dedup/rules) | NOT RUN: native delivery/concurrency/queue; horizon/retention R2 DEFERRED |
| R-NOT-04 | NotificationViewModel/Environment; NotificationViewModelTest, NotificationPermissionUiTest | PASS для сохранения | PASS | NOT RUN: реальный deny/grant/API31/33+ |
| R-STAT-06 | coherent rows+today, error propagation; ForecastEngineTest, FinanceViewModelTest, FinanceIntegrationTest | PASS в R1 | PASS | NOT RUN: Room reactive snapshots; окончательная статистика R2/R6 DEFERRED |
| R-STAT-01 | существующий ForecastEngine, eligibility уже целевая | PASS для ACTIVE | PASS для текущего контракта | DEFERRED: plusDays(30)→31 R2, годовая стоимость/UI R6; целевой T-STAT-01 не объявлен PASS |
| R-STAT-02 | RUB forecast DTO, нет истории | PASS для отсутствия истории | PASS (текущий VM) | DEFERRED R6a/R6b: нормализованный рейтинг и scale persistence |
| R-STAT-03 | новая нормализация отсутствует | NOT APPLICABLE в R1 | NOT APPLICABLE | DEFERRED R6a |
| R-STAT-04 | сохранён календарный ForecastEngine/фиксированные 12 | PASS для R1 adapter | PASS | DEFERRED R2/R6: окончательный прогноз/дизайн; текущий UI NOT RUN |
| R-STAT-05 | сохранён выбор месяца/согласованные snapshots | PASS для сохранения | PASS (текущий VM) | NOT RUN: UI; независимый рейтинг/итоговый UI DEFERRED R6b |
| R-UI-01 | сохранённый TrackerApp, старые маршруты sanitised | PASS только регрессия маршрутов | NOT APPLICABLE | NOT RUN; целевые три корня/архив в настройках DEFERRED R4a |
| R-UI-02 | целевая сортировка уже Domain; экран ещё прежний | PASS сортировка | PASS | NOT RUN; удаление главной сводки и новый список DEFERRED R4a |
| R-UI-03 | существующие empty/add/details/editor | PASS регрессия | PASS (state VM) | NOT RUN; визуальная часть DEFERRED R4a |
| R-UI-04 | сохранены details/menu/остальные lifecycle actions | PASS операции | PASS (DetailsViewModelTest) | NOT RUN; окончательный дизайн DEFERRED R5b |
| R-UI-05 | target archive projection/date/ID | PASS данные | PASS (ArchiveViewModelTest/domain) | NOT RUN; навигация/дизайн DEFERRED R4a/R5b |
| R-UI-06 | существующие themes/tokens, без redesign | PASS сохранение | PASS (theme/contrast) | NOT RUN; новые tokens DEFERRED R3 |
| R-UI-07 | не изменялось в R1b.5 | NOT APPLICABLE | NOT APPLICABLE | DEFERRED R4a/R4b: animations/reduced motion |
| R-UI-08 | theme/global reminders/старые controls удалены | PASS R1 | PASS (Settings VM/Codec) | NOT RUN; cards/animations/archive entry DEFERRED R4b |
| R-UI-09 | сохранён navigation/drafts/errors/IME, без redesign | PASS R1 | PASS (VM/state/contrast) | NOT RUN; insets/85% island/final accessibility DEFERRED R3+ |
| R-UI-10 | launcher не менялся | NOT APPLICABLE в R1 | NOT APPLICABLE | DEFERRED R8 |
| R-REL-01 | gates/отчёт, приёмка владельца | PASS прозрачность результатов | PASS текущий suite | NOT RUN device, PERSONAL-READY не объявляется |
| R-REL-02 | backup off, фиктивного H нет | PASS | NOT APPLICABLE | Риск локальной потери сохраняется; принятие владельцем требуется перед личной готовностью |
| R-REL-03 | H не реализуется | NOT APPLICABLE | NOT APPLICABLE | DEFERRED отдельный H после личной готовности |

### Матрица контрольных T-кейсов

| ID | Выполненная часть | Отложенная часть |
|---|---|---|
| T-CAL-01 | PASS JVM: 31→28/29→31, исходный день | NOT RUN persist/UI |
| T-CAL-02 | PASS JVM: 29.02→28.02→29.02, N лет | NOT RUN persist/UI |
| T-CAL-03 | PASS JVM: +30 дней=02.03, недели, квартал/N | NOT RUN editor→Room |
| T-CAL-04 | PASS JVM: due today/следующий день/долгая пауза | NOT RUN native refresh; историю не создаёт схема/API (статический PASS) |
| T-CAL-05 | PASS JVM: unchanged/new series/refinements | NOT RUN EditorScheduleIntegrationTest (25), включая сохранённые 18 из fix |
| T-CAL-06 | PASS JVM: invalid count/range/overflow, bounded queries | NOT RUN поля UI |
| T-CAL-07 | PASS JVM: fraction/clamp/scoped override/frozen end | NOT RUN визуальный индикатор и полночь |
| T-CAL-08 | PASS JVM: календарь не зависит от zone, DST resolution | NOT RUN Android receiver/date UI/реальная смена timezone |
| T-MONEY-01 | PASS JVM: parse/kopecks/overflow/RUB-only | NOT RUN Room input/reopen |
| T-LIFE-01 | PASS JVM domain/VM | NOT RUN duplicate UUID/Room/create/edit UI |
| T-LIFE-02 | PASS JVM domain/exclusive/frozen end | NOT RUN Room/reactive/OS notifications |
| T-LIFE-03 | PASS JVM archive explicit/no autoarchive | NOT RUN confirm/Room |
| T-LIFE-04 | PASS JVM validation/same ID/reset | NOT RUN форма/reactivation persistence |
| T-LIFE-05 | PASS JVM confirm/domain/VM, PASS audit cascade/refs | NOT RUN transaction/files/work/native confirmation |
| T-DATA-01 | PASS static preflight/marker/blocked state и JVM pure safety | NOT RUN native fresh/reopen/old schemas/bytes/WAL |
| T-DATA-02 | PASS JVM snapshot/error/generation contracts | NOT RUN Room concurrency/rollback/generation/publisher |
| T-ASSET-01 | PASS JVM catalog/ImageEdit, PASS audit 12 offline icons/licenses | NOT RUN private files/Photo Picker/rotation/shared deletion |
| T-SCOPE-01 | PASS source/DEX/schema/API/JVM contracts; отменённого runtime нет | NOT RUN установленный APK/старый back stack/notification/work queue |
| T-NOT-01…03 | PASS JVM global settings/rules/keys/eligibility | NOT RUN native ledger/work/publisher; R2 horizon/short cycles DEFERRED |
| T-NOT-04…06 | PASS static preserved trigger/permission/navigation paths, PASS pure VM | NOT RUN ОС/физическое устройство; финальные R2 rules DEFERRED |
| T-STAT-06/08 | PASS JVM ACTIVE/snapshot/error; без фактов | NOT RUN live Room/UI; нормализация R6 DEFERRED |
| T-UI-01…11 | Только существующие JVM theme/contrast/state PASS | Существующая UI регрессия NOT RUN; новые R3–R8 макеты DEFERRED |
| T-NF-01/02, T-REL-01 | PASS static offline/backup/applicationId invariants | NOT RUN device/stress/process death/личная приёмка |
| T-REL-02 | NOT APPLICABLE в R1 | DEFERRED H |

## Консолидированная отложенная инструментальная приёмка R1a–R1b.5

**Все строки ниже NOT RUN**, не «пройдено по компиляции». Таблица охватывает все 346 скомпилированных методов. Указание класса означает запуск всех его @Test, включая прежние методы с историческими именами. Среда **I**: изолированный instrumentation APK, Android API31, API33+ и актуальный API36, app-private synthetic fixtures; **UI**: то же с Compose/ActivityScenario, обе темы; **S**: физическое устройство владельца/отдельная тестовая установка и явные ручные системные действия. Приоритет P0 запускать первым. Выполнение на одном API не заменяет оставшуюся системную матрицу. Тестовые teardown не запускать на пользовательском хранилище.

| Класс (все методы), число | Требование / T-кейс | Приоритет / среда | Ожидаемый результат |
| [CalendarRefreshReceiverTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/app/CalendarRefreshReceiverTest.kt) (2) | R-CAL-03 / T-CAL-08 | P1 / I | Только date/time/zone actions вызывают refresh, посторонние не влияют; без системного изменения часов. |
| [DatasetCompositionTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/app/DatasetCompositionTest.kt) (6) | R-DATA-01/02, R-CAL-03 / T-DATA-01/02, T-CAL-08 | P0 / I | Reopen сохраняет pointer/Room/настройки, readers закрываются до ресурсов, refresh не создаёт финансовых фактов. |
| [ReminderInfrastructureTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderInfrastructureTest.kt) (31) | R-NOT-02/03, R-DATA-01 / T-NOT-02/03 | P0 / I | Claim/lease/retry и final freshness сохраняют дедупликацию; stale schedule/state/generation/open failure не публикуют. |
| [ReminderWorkManagerTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderWorkManagerTest.kt) (15) | R-NOT-02/03, R-BASE-04 / T-NOT-02/03, T-SCOPE-01 | P0 / I | Старая очередь нейтрализована без Room, неизвестные payload отклонены, unrelated work остаётся. |
| [AssetStoreTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/assets/AssetStoreTest.kt) (4) | R-SUB-05, R-DATA-01 / T-ASSET-01 | P0 / I | Приватная копия живёт без source URI, повреждение и stale generation отклонены, committed image не удаляется. |
| [OfflineIconCatalogIntegrationTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/assets/OfflineIconCatalogIntegrationTest.kt) (6) | R-SUB-05, R-LIFE-05 / T-ASSET-01 | P0 / I | 12 vectors загружаются, catalog/photo round-trip, shared image переживает удаление первого владельца. |
| [SubscriptionUseCasesTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCasesTest.kt) (15) | R-SUB-01…07, R-LIFE-01…05 / T-LIFE-01…05, T-DATA-02 | P0 / I | Target операции атомарны, copies/anchor/frozen period сохраняются, snapshots актуальны, READY reopen одноразовый. |
| [LifecycleRepositoryTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/lifecycle/LifecycleRepositoryTest.kt) (6) | R-LIFE-01…05 / T-LIFE-01…05 | P0 / I | Один concurrent winner, cancel/EditEnd/restore/delete атомарны, ID и оформление сохраняются. |
| [StorageOpeningTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/room/StorageOpeningTest.kt) (31) | R-DATA-01/02 / T-DATA-01/02 | P0 / I | Fresh/reopen безопасны; v1–v4/hash/shape/corruption/marker/missing READY блокируют до callbacks, byte/row/settings/image preservation, WAL/hot journal без destructive recovery. |
| [SubscriptionTransactionsTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/room/SubscriptionTransactionsTest.kt) (6) | R-DATA-01/02, R-LIFE-05 / T-DATA-02, T-LIFE-05 | P0 / I | Rollback не меняет ряд/ledger, каскад атомарен, READY reopen сохраняет полную метаинформацию. |
| [TrackerDatabaseTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/room/TrackerDatabaseTest.kt) (10) | R-DATA-01/02 / T-DATA-01/02, T-SCOPE-01 | P0 / I | Ровно 3 target tables, PK/FK/unique/cascade/RESTRICT, без финансовых таблиц, общие ассеты защищены. |
| [SettingsIntegrationTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsIntegrationTest.kt) (5) | R-NOT-01, R-DATA-01 / T-NOT-01, T-DATA-01 | P1 / I | Дни/theme сохраняются, empty не становится default, obsolete keys inert, lease и поколения изолированы. |
| [NotificationNavigationUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationNavigationUiTest.kt) (9) | R-NOT-03 / T-NOT-06 | P1 / UI | Cold/warm intent ведёт к правильному UUID, stale/missing к главной, нет дублей/back stack corruption. |
| [NotificationPermissionUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationPermissionUiTest.kt) (2) | R-NOT-04 / T-NOT-05 | P1 / UI+S API33+ | Контекстное объяснение/deny/grant, нет навязчивого повторного offer, работа приложения не блокируется. |
| [NotificationPublisherTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationPublisherTest.kt) (19) | R-NOT-03/04 / T-NOT-03/06 | P0 / I+S | Нативная публикация/ack/канал и актуальный текст; stale legacy payload удаляется без новых действий. |
| [ReminderSystemRegressionTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/ReminderSystemRegressionTest.kt) (15) | R-NOT-02/03, R-DATA-01 / T-NOT-02…04 | P0 / I+S | Гонка цены/даты/состояния/поколения не публикует старый snapshot; logical keys не сбрасываются. |
| [AccessibilityUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/AccessibilityUiTest.kt) (4) | R-BASE-02, R-UI-09 / T-UI-08 | P1 / UI+S | Обе темы/крупный шрифт/ошибки читаемы, оставшиеся действия достижимы; TalkBack отдельно вручную. |
| [ArchiveSettingsRedesignUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsRedesignUiTest.kt) (15) | R-LIFE-03/04, R-NOT-01 / T-LIFE-03/04 | P1 / UI | Архив по ID, loading/error различимы, глобальные напоминания и фиксированное время; прежний UI без новых макетов. |
| [ArchiveSettingsUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsUiTest.kt) (7) | R-LIFE-03/04 / T-LIFE-03/04 | P1 / UI | Ручной архив/reactivation/Back и одинаковые имена остаются рабочими. |
| [DesignSystemUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DesignSystemUiTest.kt) (9) | R-BASE-02, R-UI-09 / T-UI-08/11 | P1 / UI | Существующие semantics/targets/insets/крупный шрифт не регрессируют; новый R3 дизайн вне этого прогона. |
| [DetailsEditorRedesignUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsEditorRedesignUiTest.kt) (15) | R-SUB-06/07, R-LIFE-02/06 / T-UI-03/04 | P1 / UI | Target states и корректные даты/progress, без фактической истории и скрытых старых действий. |
| [DetailsUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsUiTest.kt) (5) | R-LIFE-02…05, R-BASE-04 / T-LIFE-02…05, T-SCOPE-01 | P0 / UI | Confirm/dismiss/delete/archive/restore работают; payment/history actions отсутствуют. |
| [EditorUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/EditorUiTest.kt) (8) | R-SUB-01…07 / T-LIFE-01, T-MONEY-01, T-ASSET-01 | P0 / UI | Ввод/валидация/save/recreation/photo/catalog, double Save не дублирует запись. |
| [NavigationUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/NavigationUiTest.kt) (15) | R-SUB-06, R-BASE-04 / T-UI-01/04, T-SCOPE-01 | P1 / UI | Существующие routes/Back/editor/settings/архив сохраняются, удалённых destinations нет. |
| [StorageBlockedUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/StorageBlockedUiTest.kt) (2) | R-DATA-02 / T-DATA-01 | P0 / UI | Ошибка видна несмотря на preDraw/Settings loading, no editor/routes/deeplink mutation, retry только temporary. |
| [SubscriptionsRedesignUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsRedesignUiTest.kt) (9) | R-LIFE-01/06, R-STAT-06 / T-LIFE-01/02 | P1 / UI | Текущие loading/empty/error/list/RUB projections согласованы, без foreign controls. |
| [SubscriptionsUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsUiTest.kt) (10) | R-SUB-01/06, R-LIFE-01…04 / T-LIFE-01…04 | P1 / UI | Реактивный список после операций, отменённые внизу, нет проблемных состояний/истории. |
| [DetailsOperationsIntegrationTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsOperationsIntegrationTest.kt) (3) | R-LIFE-01…05, R-DATA-02 / T-LIFE-01…05 | P0 / I | Настоящий VM→Room путь, stale snapshot/reopen/lost response безопасны, только выбранная запись меняется. |
| [EditorScheduleIntegrationTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorScheduleIntegrationTest.kt) (25) | R-SUB-04/06/07, R-CAL-01/02 / T-CAL-05, T-DATA-02 | P0 / I | Все 18 прежних fix и 7 новых: неизменённая серия/реальные save, новый anchor только явно, scoped draft/recreation/image-only/snapshot/generation безопасны. |
| [FinanceIntegrationTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceIntegrationTest.kt) (3) | R-STAT-06, R-LIFE-01 / T-STAT-06/08 | P0 / I | Цена/метки/eligible state и прогноз из одного snapshot, READY reopen, ошибки не превращаются в нулевой успех. |
| [FinanceNavigationTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceNavigationTest.kt) (2) | R-SUB-06, R-STAT-06 / T-UI-04 | P1 / UI | Сохранённая запись появляется в прогнозе, выбор месяца переживает навигацию/rotation. |
| [FinanceUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceUiTest.kt) (3) | R-BASE-04, R-STAT-06 / T-SCOPE-01, T-STAT-08 | P1 / UI | Нет history/currency/horizon controls, корректны loading/error/empty и существующий month selection. |
| [LargeFontChartUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/LargeFontChartUiTest.kt) (2) | R-BASE-02 / T-UI-08 | P1 / UI | Узкое окно, landscape, font2 и обе темы: точные суммы/все месяцы доступны. |
| [MonthlyChartUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/MonthlyChartUiTest.kt) (4) | R-STAT-06 / T-STAT-08 | P1 / UI | Текущий chart соответствует текущему ForecastEngine, пустые корзины/большие суммы доступны; не PASS будущей границы окна. |
| [NavigationRestoreTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/navigation/NavigationRestoreTest.kt) (3) | R-BASE-04, R-SUB-06 / T-SCOPE-01 | P0 / UI | Старый PaymentHistory back stack безопасно очищен, остальные routes/editor draft сохранены. |
| [CrossScreenDesignUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/CrossScreenDesignUiTest.kt) (10) | R-BASE-02, R-SUB-07, R-NOT-01 / T-UI-08 | P1 / UI | Названия date actions/global offsets/фиксированное время/семантика текущих форм согласованы; без индивидуальных правил. |
| [KeyboardRotationUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/KeyboardRotationUiTest.kt) (2) | R-SUB-06, R-NOT-01 / T-UI-04/08 | P1 / UI | Реальные IME/rotation/Back сохраняют редактор и global offsets, время остаётся 09:00. |
| [LargeFontWindowUiTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/LargeFontWindowUiTest.kt) (4) | R-BASE-02, R-UI-09 / T-UI-08 | P1 / UI | Окна/диалоги в landscape/font2/темах доступны; gallery только synthetic debug. |
| [ArchiveSettingsIntegrationTest](../../app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/settings/ArchiveSettingsIntegrationTest.kt) (4) | R-LIFE-03/04, R-NOT-01, R-DATA-01 / T-LIFE-03/04, T-DATA-01 | P0 / I | Archive/reactivation/settings/retry сохраняют UUID/поля, lease/reopen без повторной fresh-авторизации. |

Особенно важные методы для первого P0-прогона: `StorageOpeningTest.versionsOneThroughFourAreRejectedBeforeWritableOpenWithoutChangingAnyFile`, `changedV5WithOriginalIdentityIsRejectedBeforeWritableCallbacksAndPreservesBytes`, `wrongIdentityHashIsRejectedWithoutRewritingMetadata`, `lostReadyDatabaseWithoutSettingsOrImagesCannotBecomeEmptyInstall`, `committedWalSchemaIsInspectedAndNotIgnoredOrCheckpointedOnRejection`, `compatibleWalCommittedRowsSurvivePreflightAndRoomOpen`, `hotRollbackJournalIsNotRecoveredByReadOnlyDiagnostics`, `failedBackgroundOpenDoesNotAcquireSettingsMutateLedgerOrPublish`; все методы EditorScheduleIntegrationTest; `ReminderInfrastructureTest.changedDateBetweenClaimAndPublishDoesNotProduceStaleMessage`, `deleteInvalidatesClaimAndCascadesPendingLedger`, `generationRecheckedAfterReservationBeforeExternalAction`; `OfflineIconCatalogIntegrationTest.switchingBetweenPhotoAndCatalogKeepsSharedAssetUntilLastReference`.

### Отложенные ручные/системные сценарии

Все NOT RUN, включая ранее не выполненные в R1a–R1b.4. Только владелец вручную сбрасывает свою экспериментальную установку через настройки Android перед чистой приёмкой. Это удаляет локальные подписки, настройки и изображения. Агент не выполнял pm clear, удаление приложения, смену системного времени или очистку устройства. Для destructive fixtures использовать исключительно отдельные синтетические наборы; не базу владельца.

| Сценарий | Требование / приоритет | Окружение | Ожидаемый результат |
|---|---|---|---|
| Обновление экспериментальной v1–v4 до v5, восстановленный editor/notification intent при blocked storage | R-DATA-02, T-SCOPE-01 / P0 | I/UI API31/33/36, synthetic old install | Блокирующее сообщение видно без бесконечного splash; файлы/настройки/изображения не удалены, mutation/sweep/worker publish не вызываются. |
| Холодный restart/process kill после create/edit/settings/photo | R-DATA-01, R-SUB-06 / P0 | I/UI, отдельная synthetic установка | READY reopen, все копейки/опора/ID/настройки/изображения сохраняются; нет повторного Save или нового generation. |
| Реальный power-loss на стадиях AUTHORIZED/CREATING/READY/atomic .next | R-DATA-01/02 / P0 | Эмулятор snapshot/fault injection отдельно от пользовательского устройства | Неоднозначность блокируется, исчезнувшая READY база не создаётся заново. Синтетические crash markers не доказывают реальные fsync/power-loss свойства. |
| SQLite/WAL/SHM/hot journal на каждой поддерживаемой API | R-DATA-01/02 / P0 | I, native SQLite, закрытые и активные synthetic соединения | Committed WAL rows видны; несовместимая закрытая база byte-identical; journal/settings/images сохранены; нет checkpoint/deletion ради диагностики. Для active SHM не обещается абсолютная неизменность lock bytes. |
| Полный create/edit/cancel/EditEnd/archive/restore/delete с отказом формы | R-SUB-01…07, R-LIFE-01…06 / P0 | UI, обе темы, Room | UUID/оформление/anchor/frozen start сохраняются по контракту, cancel сразу исключён, autoarchive нет, delete только confirm, общий asset и чужая запись остаются. |
| Полночь/DATE_CHANGED и TIME_SET/TIMEZONE_CHANGED при открытых списке/details/editor; затем stop/start/rotation | R-CAL-03, T-CAL-08 / P1 | S API31/33/36, отдельное тестовое устройство | Новый today/due/progress/forecast без onResume и без записи цикла; receivers не дублируются; неизменённый editor draft не reanchor. Синтетический direct receiver test не заменяет эту проверку. |
| Reboot/долгое отключение/timezone/DST/батарея/доставки без UI | R-NOT-02/03 / P1 | S, физический Android и native WorkManager | Не более актуального сообщения по текущему контракту, нет истёкших/отменённых/удалённых уведомлений; измерить фактическую задержку best effort. R2 expansion отдельно. |
| Обновление с policy0/старым cancellation payload/соседней unrelated WorkManager task | R-BASE-04, R-NOT-03 / P0 | I/S, synthetic прежняя очередь/notifications | Отменённая функция не исполняется, старый worker не открывает Room, чужие задачи не отменены, ledger не очищен. |
| POST_NOTIFICATIONS: первый подходящий Save, deny/grant, restart; disabled channel на API31 | R-NOT-04 / P1 | S API31 и API33+, физический Android | Отказ не блокирует приложение, нет навязчивых повторов, settings отражают ОС, DELIVERED не выдумывается. |
| Tap cold/warm notification, missing/deleted/stale generation, Back/recreation | R-NOT-03, R-SUB-06 / P1 | S, физический Android | Только правильный доступный UUID, иначе главный; без дублей/старых команд. |
| Photo Picker: cancel, PNG/JPEG/WebP, неверный/большой файл, rotation/process kill, общая ссылка | R-SUB-05, T-ASSET-01 / P1 | S, synthetic файлы, приватное хранилище | Сохранена только проверенная приватная копия, отказ не меняет форму, после restart изображение доступно, удаление только без ссылок. |
| 12 offline icons + unknown fallback, лицензии | R-SUB-05 / P1 | I/UI offline, обе темы | Каждый vector отображается, unknown → «Другое», выбор не меняет остальные поля; атрибуции сохранены. |
| TalkBack, font2, узкий экран, landscape, IME, gesture/3-button, обе темы | R-BASE-02, применимые T-UI-08/11 / P1 | S, физический Android | Действия/ошибки/состояния читаемы, доступны и не кодируются только цветом. Согласованные будущие макеты проверять на их этапах. |
| 100 активных synthetic записей, включая daily, cold start/process death/долгая пауза | T-NF-02 / P1 | I/S, замер на Android | Нет ANR/частичного нулевого успеха/истории; записать измерения, не выдумывать пороги. |
| Offline/backup off/no external account/нет секретов в логах | R-BASE-01/02, R-DATA-01, T-NF-01 / P0 | S, физический Android | Нет зависимости от сети, Auto Backup/D2D выключены, технические ошибки не раскрывают private данные. |

## Изменённые файлы

1. `app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorViewModel.kt` — E1/E2, без изменения доменного validator.
2. `app/src/main/java/com/glebskorobogatov/subscriptiontracker/app/MainActivity.kt` — E3, foreground calendar refresh.
3. `app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/review/DesignReviewGallery.kt` — удаление неиспользуемых старых полей preview.
4. `app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorDraftRegressionTest.kt` — 6 новых JVM.
5. `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorScheduleIntegrationTest.kt` — 7 дополнительных VM→Room сценариев, 18 прежних сохранены.
6. `app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/app/CalendarRefreshReceiverTest.kt` — 2 новых Android.
7. `docs/reports/R1b.5.md` — этот отчёт/матрица/консолидированный NOT RUN backlog.

Полный diff относительно принятого коммита проверен на scope и конфиденциальность. Room v5/entities/DAO/exports/preflight/journal/gates, Domain/calendar/ForecastEngine/ReminderRules/ledger/WorkManager, DataStore, зависимости/Gradle/CI, manifest/applicationId/backup, launcher, лицензии и утверждённые требования не изменены. Только synthetic fixtures, GitHub noreply author/committer. Логи/базы/фотографии/реальные сведения в diff отсутствуют.

## Ограничения, технические адаптеры и передача в R2

- 346 Android-тестов и ручные сценарии выше NOT RUN. Нативная сохранность SQLite/WAL/SHM, power-loss, реальные OS notifications, Photo Picker, TalkBack и lifecycle receiver не доказаны компиляцией. Нет полной инструментальной приёмки и PERSONAL-READY.
- Room 2.8.3 generated delegate/SQLite bridge RestrictedApi остаётся закреплённой технической зависимостью: повторять preflight tests при будущем обновлении. Автоматического обновления библиотек не было.
- В R2 передаются существующая half-open граница `today.plusDays(30)` вместо целевой +31, окончательный rolling horizon/пересекающиеся короткие циклы, политика obsolete work/ledger retention и при необходимости упрощение forecast DTO. В R1 эти части не исправлялись и целевым PASS не объявлены.
- Money/CurrencyCode/ForecastResult — технически RUB-only; реальной многовалютности/конвертации нет. Доменный RubPrice — источник стоимости подписки. Нормализация MONTH/YEAR, comparisonScale и итоговый финансовый UI — R6a/R6b.
- Текущая навигация ещё содержит архив как root, настройки вложены; главный экран ещё использует прежнюю прогнозную сводку. Это известная граница R4a/R4b, не возврат удалённой функции и не дефект R1. Дизайн/анимации/launcher — R3–R8.
- Защитные старые route/payload/draft handlers и стабильные OS/DataStore идентификаторы оставлены сознательно; они не исполняют отменённые операции. Исторические snapshots остаются историческими. Неиспользуемых отменённых продуктовых сервисов для «совместимости» не сохранено.
- H, импорт/экспорт и автоматическое переключение наборов не реализованы. Локальная потеря данных при удалении/сбросе/потере устройства по-прежнему возможна; архив не backup.

R1b.5 завершён в части реализации, статического аудита, JVM-регрессии и подготовки инструментальной приёмки. Независимой проверке передаются минимальный diff, исходная/проверенная ревизии, эта матрица и явный NOT RUN backlog. После Draft PR работа останавливается до решения владельца; R2 не начинается.
