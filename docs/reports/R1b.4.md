# R1b.4 — целевая схема Room и интеграция доменной модели

> Актуальное исправление редактора — в дополнении **R1b.4-fix** в конце файла.
> Его проверенный код: `b3af153596647a6948557a551fad74951cc46ae1`; текущие результаты —
> 254 выполненных JVM-теста и 337 скомпилированных Android-тестов (выполнение Android NOT RUN).
> Прежние результаты 248/319 ниже относятся к исходному R1b.4 до исправления.

## База и статус

Исходная ветка: `codex/r1b-03-settings-reminders`, принятый SHA `32902ae332c19dbb53fd2b57b63ff4dedec77a1e`, Draft PR #4.
Перед изменениями HEAD совпал, рабочее дерево было чистым. Новая ветка
`codex/r1b-04-room-domain-integration` создана непосредственно от этого коммита.

Итоговый SHA проверенной реализации: `e96aa3b3b9d7eb7d70ec33af1204d7e0cfcbc748`. Следующий коммит добавляет только этот отчёт;
SHA вершины ветки с отчётом приведён в Draft PR и итоговом сообщении. Base нового stacked Draft PR:
`codex/r1b-03-settings-reminders`. Merge не выполняется.

R1b.4 реализован и подготовлен к независимой статической приёмке с ограничениями ниже.
Инструментальная приёмка не выполнена. Статус PERSONAL-READY не объявляется.
R1b.5, R2, R3–R6 и H не выполнялись.

Основание: поручение владельца, AGENTS, PRD/ARCHITECTURE/IMPLEMENTATION_PLAN/TEST_PLAN/UI_DESIGN_SPEC 2.1,
DECISIONS №114, отчёты R1a/R1b.1 в PR #1/#2 и файлы отчётов R1b.2/R1b.3.
Применимые требования: R-BASE-03/04, R-SUB-01…07, R-CAL-01…03, R-LIFE-01…06, R-DATA-01/02;
проверки T-DATA-01/02, T-LIFE-01…05, T-ASSET-01 и часть T-SCOPE-01.
Ранее согласованное фиксированное 09:00 имеет приоритет над старым упоминанием time picker в UI/test документах.
Уточнение владельца об отсутствии реальных данных разрешает разрыв экспериментальной схемы, но не удаление файлов.
Продуктовые документы не изменялись.

## Инвентаризация и новая схема

Room v5 экспортирован KSP закреплённого Room 2.8.3. Три прикладные таблицы:

| Таблица | Поля и гарантии |
|---|---|
| `subscriptions` | UUID PK; name/accountLabel; priceMinor Long в копейках; intervalUnit DAY/WEEK/MONTH/YEAR, intervalCount; anchorYear/anchorMonth/anchorDay; state ACTIVE/CANCELLED/ARCHIVED; парные currentPeriodStartOverride/currentPeriodEnd; cancelledAccessEndExclusive/frozenCancelledPeriodStart; logoAssetId/catalogLogoId; createdAt/updatedAt с наносекундами, archivedAt LocalDate |
| `assets` | Прежние UUID, безопасный относительный путь, mime, размер, SHA-256, createdAt; unique relativePath. FK subscriptions.logoAssetId → assets.id с RESTRICT, индекс ссылки |
| `notification_delivery_ledger` | UUID PK, subscriptionId, единственный eventType RENEWAL, eventDate, offsetDays, generation, plannedLocalDate/Time, payloadVersion, state, deliveredAt, claimToken/leaseUntil, failureCount/notBefore. FK → subscriptions с CASCADE; unique subscriptionId+eventType+eventDate+offsetDays+generation |

Системные таблицы Room/SQLite допустимы. Нет хранимой вычисляемой ближайшей даты, подтверждённого индекса,
истории платежей/возвратов или таблиц индивидуальных правил. plannedLocalTime и payloadVersion больше не имеют
nullable/нулевого legacy-default: свежий ledger использует 09:00 и версию payload 1.
Индекс state заменил индекс legacy lifecycleState. Одинаковые названия не уникальны; ID остаётся уникальным.

Исторические exports `1.json`–`4.json` побайтово сохранены. `5.json` — новый generated export.
Миграции 1→2→3→4 убраны из действующей конфигурации, пути в v5 нет. Старые exports остаются в APK assets
из-за существующей упаковки schemas, но версии 1–4 отвергаются до загрузки их схемы и writable-open.
Они не восстанавливают runtime старой модели.

### Соответствие полей и source of truth

| Прежние данные/контракт | Целевой контракт |
|---|---|
| ID/name/accountLabel/оформление | SubscriptionRecord.id/name/accountLabel/SubscriptionAppearance |
| Money + currencyCode | RubPrice(priceMinor); currencyCode отсутствует в subscriptions |
| anchorYear/month/day + interval | RecurringSchedule(RenewalAnchor, Frequency), без mutable cycle position |
| currentPeriodStartOverride | PeriodStart(start, due); due хранится как currentPeriodEnd и относится только к одному периоду |
| inclusive paidAccessEnd | Не преобразуется: старые базы заблокированы. Новый cancelledAccessEndExclusive имеет исключающую семантику |
| legacy lifecycle/mode | SubscriptionState с тремя состояниями; режим продления отсутствует |
| createdAt/updatedAt | Storage-only метаданные полного optimistic snapshot; withDomain сохраняет createdAt |
| архив без даты | Новый archivedAt заполняется явной архивацией; неизвестная дата не выдумывается |
| индексы подтверждений, one-off, lead days, overrides, financial facts | Полностью удалены из целевой схемы и runtime |

`SubscriptionMapping.kt` выполняет однозначный toDomain/toEntity/withDomain.
Room entity — сериализованная строка целевых полей и технических timestamps, а не второй lifecycle engine.
Полный row остаётся optimistic token для операций/черновиков. Все бизнес-переходы выполняет SubscriptionDomain.
SubscriptionSnapshot помечен subscription-v5 и содержит все 19 колонок плюс версию формата;
старые 22-полевые снимки не могут восстановить действие.

Удалены PaymentEntity/RefundEntity/ReminderOverrideEntity/RetiredPaymentOperation/RetiredRefundId,
PaymentDao/RefundDao/ReminderOverrideDao/retired DAO и ненужные converters.
Удалены Legacy Subscription, RenewalMode, LifecycleState, VisibleStatus, PaymentSource,
SubscriptionLifecycleService/старый LifecycleCommands, RenewalSchedule, CycleOverride, CycleConflictException,
moveNextOnly, moveFutureSeries и зависимости от nextCycleIndex/oneOff/overrideCycleIndex.
Сохранён файл LifecycleCommands с новыми Cancel/EditEnd/Archive/Reactivate, вызывающими R1a.
Нет runtime-создания PAYMENT_ISSUE/AWAITING_RENEWAL/CANCELLATION_SCHEDULED, финансовых фактов или отменённых команд.

## Операции, календарь и UI

SubscriptionEditor создаёт и редактирует запись через SubscriptionDomain внутри существующей Room-транзакции.
LifecycleRepository проверяет полный ожидаемый row перед переходом и удалением.
Изменение имени/цены/оформления сохраняет точную исходную опору, в том числе 31 при отображаемом 28 февраля.
Явные дата/периодичность создают новую регулярную серию. Уточнение опорного дня остаётся в дополнительных полях.
Переходы текущей даты только пересчитывают read models и reconciliation; Room-цикл не записывается.

DefaultRenewalCalendar — прежняя реализация арифметики и ограниченного двоичного поиска, адаптированная
непосредственно к RecurringSchedule. Второго движка и календарного compatibility schedule нет.
Индекс события остался только локальным параметром математики, без признака оплаты и без persistence.
Сохранены month/year clamping с восстановлением дня, leap day, точные DAY/WEEK, переполнение и лимит событий.

PeriodStartEdit отличает сохранение уточнения от явной установки/очистки. При reanchor старое уточнение сбрасывается.
Новое значение начала периода в форме привязывается к отображаемому due; старый due уточнения не переиспользуется.
Повторный выбор неизменённой даты/частоты не стирает anchor/period draft.
Черновики нового формата восстанавливаются, старые несовместимые поля/снимки сбрасываются только во временном UI state.
Сохранены защита повторной отправки, unknown outcome, optimistic conflict, generation checks и CancellationException.

Отмена подставляет ближайшую дату, замораживает начало периода и сразу исключает запись из прогнозов/напоминаний.
EditEnd изменяет только exclusive end; frozen start и опора сохраняются. После end прогресс скрывается,
состояние остаётся CANCELLED. Никакого автоархива или восстановления старых архивных записей.
Архивирование явное, archivedAt берётся из переданного today. Возобновление CANCELLED/ARCHIVED проверяет
RubPrice/частоту/следующую дату, сохраняет ID/оформление, очищает отмену/предыдущий период/archivedAt.
Удаление требует подтверждения и полного snapshot, каскадно удаляет только свой ledger.
Общий asset сохраняется до последней ссылки; private file cleanup остаётся за существующим AssetStore.

UI минимально адаптирован: три статуса, отменённые ниже активных, сортировки по датам с ID tie-break,
архив по archivedAt с неизвестной датой последней. Детали получили EditEnd и возобновление отменённой записи.
Убраны режимы, проблемные действия и разовый перенос. Неактивная запись редактирует имя/цену/оформление;
дату/частоту меняют при возобновлении, замороженное начало периода не редактируется.
Дата доступа явно подписана как exclusive, архивная дата подписана отдельно.
Текст пустого архива не обещает автоматическое архивирование.
Редизайн R3–R6 не выполнялся, темы/навигация/доступность/изображения сохраняются.

Список и FinanceSnapshot вычисляются из одного набора rows+today: подписи/цена/eligibility и прогноз
не смешиваются между независимыми emissions. Календарный алгоритм прогноза не переписан.

## Сохранность хранилища

Foreground и background по-прежнему используют единую TrackerDatabaseFactory.
ActiveDataset, pointer/generation guard, mutation gate, InitializationJournal/InitializationRecord,
AUTHORIZED/CREATING/READY, DataStore lease ownership, PreservingOpenHelperFactory и lifecycle сессий сохранены.
Файлы этих защитных механизмов, SubscriptionsComposition, ReminderRuntime, ReminderWork и AssetStore
проверены относительно базы: не изменены. В DataStore менялись только имя свойства offsets и его потребители;
имя сохранённого ключа payment_offsets оставлено, фабрика leases не перерабатывалась.

Политика supportedVersions теперь допускает только v5. Общая инфраструктура явных безопасных будущих миграций
не заменена destructive recovery: для будущего изменения версии потребуется отдельный путь миграции,
его контракт и тесты с расширением политики поддерживаемых исходных версий.

До writable-open существующая база проходит настоящее OPEN_READONLY:
quick_check, version gate, точный generated validator v5, сравнение с generated schema snapshot,
Room identity hashes, таблицы/колонки/типы/nullability/defaults/PK/FK/индексы, скрытые/partial индексы,
foreign_key_check и schema/data version tokens. Не используются immutable URI, checkpoint или восстановление журнала.
Read-only handle видит committed WAL; невозможность безопасного чтения приводит к ошибке.
После preflight сохраняется фактическое открытие Room, окончательная generated validation/quick_check и только затем READY.
Применение служебных Room/SQLite API закреплено существующими точечными RestrictedApi suppressions.
Компиляция не доказывает платформенное WAL/power-loss поведение — эти сценарии NOT RUN.

Для существующего файла AUTHORIZED расходуется после успешного preflight, сохраняя маркер при отказе старой схеме.
Для отсутствующего файла разрешение по-прежнему расходуется ДО create-capable открытия.
READY/CREATING без базы не разрешают пустое повторное создание; повреждённый маркер/.next не переписывается.
Совместимая существующая v5 без маркера подтверждается проверкой и получает READY, а указатель без базы и маркера
остаётся неопределённым состоянием. Частичные ресурсы закрываются, ошибки и отмена не превращаются в пустой успех.
Порядок блокировок не изменён: mutation gate → pointer guard → journal monitor/file lock → SQLite.

v1–v4 блокируются как INCOMPATIBLE до writable-open, без миграции, удаления/переименования файла или нового поколения.
Не выполняются sweep, claim/ledger writes, reconciliation по неподтверждённым данным и публикация уведомлений.
Постоянные ошибки не запускают бесконечный retry; временные сохраняют прежний ограниченный retry.
Блокирующий экран заменяет навигацию, включая restored routes/deep links; загрузка Settings не скрывает ошибку.
Автоматической кнопки очистки нет. Сырые приватные исключения/пути не логируются.

## Напоминания, изображения и технические адаптеры

Единственный критерий участия — SubscriptionRecord.participatesInCalculations (ACTIVE).
ReminderRules и ForecastEngine получают SubscriptionRecord. Единственный тип ledger/domain/payload — RENEWAL.
Сохранены global offsets 7/3/1/0, defaults 3/1, пустой набор off, фиксированное 09:00.
Ранее допустимые глобальные предпочтения не сбрасываются. Старые Preferences-ключи не интерпретируются и не стираются.

Claim/lease, retry counters, deterministic key, generation, WorkManager policy guards и final freshness check
сохранены. Изменение цены использует актуальную строку при публикации. Изменение расписания/состояния/удаление
пересогласовывает задачи и показанные уведомления; stale worker повторно проверяет запись перед notify/ack.
Удаление и отмена не создают финансовых фактов. Очистка ledger ради упрощения не добавлялась.
Отмена work/показанных уведомлений использует существующий best-effort reconciliation; публикация старого
неактуального события блокируется final guard. Мгновенное выполнение WorkManager не обещается.
Shared images и sweep после достоверного чтения актуальных ссылок не переписаны.

| Остаток | Классификация, выполнение и дальнейшая судьба |
|---|---|
| InitializationJournal, generations, gates, safe helper, read-only validator, leases | Действующие механизмы безопасности; не legacy-продукт. Сохраняются для будущих реальных данных |
| schema exports v1–v4 | Исторические snapshots и test fixtures; упакованы как inert assets. Не являются разрешённой схемой открытия, не удаляются задним числом |
| Литералы старых маршрутов/версий черновиков и WorkManager policy guards | Защитное отклонение старого временного состояния/заданий. Не выполняют отменённых операций; не удаляются без отдельной оценки регрессии |
| Канал subscription_payments_v1 и ключ payment_offsets | Стабильные технические имена для OS channel/global preference. Канал только про продления, поле preferences только про глобальные дни; финансового учёта нет |
| Money/CurrencyCode, ForecastResult maps и необязательный RUB-filter | Техническое точное форматирование/суммирование существующего движка. CurrencyCode создаётся только для RUB; price подписки — RubPrice. Другую валюту нельзя создать, конвертации нет. Форму прогнозных DTO можно упростить в R2 |
| ForecastEngine generic months 3/6/12 | Внутренний контракт старого движка; пользовательский экран всегда 12, настроек горизонта нет. Окончательный API/окна — R2 |
| ReminderRules nearest-target и существующий 7-дневный horizon | Действующий ограниченный алгоритм без legacy modes/исключений. Окончательные короткие/пересекающиеся циклы, rolling horizon и cleanup — R2 |
| Неизвестный catalog ID/fallback и debug fixtures | Существующая безопасная обработка оформления и синтетические проверки. Не восстанавливают legacy subscription model; лицензии/12 значков сохраняются |

Неиспользуемых отменённых entities/DAO/моделей в production/DEX не оставлено.
Имена некоторых старых тестов сохраняют историческое происхождение, но их fixtures/контракты приведены к v5.

## Ручная чистая установка владельцем

Сначала убедиться, что это именно экспериментальная установка без нужных данных.
В настройках Android открыть «Приложения» → «Подписки» → хранилище приложения → ручную очистку данных,
либо явно удалить экспериментальную установку и установить новую сборку.
Это удаляет локальную базу, настройки, изображения и служебное состояние; при необходимости заново выдать разрешение
на уведомления. Приложение и агент не выполняют эту операцию автоматически. adb pm clear не запускался.
При будущих реальных данных вместо такой очистки нужны сохранение данных и явная безопасная миграция.

## Фактические проверки

JDK 21.0.9, Android SDK 36, Build Tools 35.0.0 подтверждены в настроенной облачной среде.

| Проверка | Результат |
|---|---|
| git diff --check | PASS |
| :app:testDebugUnitTest | PASS: 248 реально выполненных, 0 failures/errors/skipped |
| :app:assembleDebug | PASS |
| :app:lintDebug | PASS: 0 ошибок, 8 прежних предупреждений |
| :app:compileDebugAndroidTestKotlin | PASS: 319 скомпилированных @Test-методов, подтверждено аннотациями class-файлов |
| connected/instrumental execution | NOT RUN: доступного устройства/эмулятора нет; ADB недоступен в среде, KVM отсутствует. Ограничения не обходились |
| APK DEX/static scope audit | PASS: удалённые модели/DAO/состояния отсутствуют среди определений приложения и runtime state strings; целевые модели присутствуют |
| APK schema assets | PASS: exports1–5 совпадают с файлами репозитория; исторические1–4 совпадают с принятой базой побайтово |
| Защищённая инфраструктура/документы | PASS по diff: без изменений Gradle/dependencies/Actions/applicationId/manifest/backup/license/продуктовых требований |

Команда полного финального прогона:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

Lint: шесть прежних NewerVersionAvailable, один ObsoleteSdkInt, один UnusedResources (welcome_message).
Suppressions/gates не отключались. GitHub Actions вручную не запускались.
Промежуточные ошибки компиляции/fixture несовместимого черновика устранены до финального полного прогона;
прежние результаты 275/328 не выдаются за проверку нового кода.
Все данные fixtures синтетические. APK, базы, приватные изображения, локальные логи и абсолютные пути не добавляются в Git.

## Тесты и обоснование изменений

Количество ниже — сравнение именованных @Test-методов с принятой базой. Переименованные/заменённые сценарии
входят одновременно в «старые имена удалены» и «новые имена добавлены», а не считаются потерянной регрессией.
«Неизменённые тела» не означает неизменность общих fixtures: их создание адаптировано под RubPrice/RecurringSchedule/v5.
Фактически выполнены только JVM-тесты; все Android-сценарии ниже NOT RUN.

| Набор | База | Итог | Неизменённые тела | Адаптированные тела с прежним именем | Старые имена удалены/заменены | Новые имена |
|---|---:|---:|---:|---:|---:|---:|
| JVM | 275 | 248 | 161 | 71 | 43 | 16 |
| Android | 328 | 319 | 182 | 91 | 55 | 46 |

Сохранены все 27 R1a-сценариев SubscriptionDomainTest, календарная математика/границы/переполнение,
InitializationState/StorageSafety и применимые storage opening/WAL/generation/assets/notification регрессии.
Транзакционные проверки, использовавшие payments/refunds, перенесены в SubscriptionTransactionsTest:
конкурентный optimistic winner, update rollback, reopen, abort каскада, независимость прогноза от ledger,
явное подтверждение и изоляция удаления. Они не удалены ради PASS.

Новые целевые сценарии покрывают парное period scope/mapping, полный snapshot, копейки/невалидную цену,
имена-дубликаты, сохранение опоры при цене, reanchor, отмену/exclusive end/frozen start, ручной архив/restore,
старые базы до callbacks, v5 shape/hash/default/FK, новый/повторный open, settings/images/READY,
реактивные coherent forecasts и безопасные новые/старые UI drafts.

## Удалённые или заменённые имена тестов

### Android (NOT RUN) — background/ReminderInfrastructureTest.kt

- `legacyCancellationLeadDoesNotAddReasonsOrCoveredKeys` — Legacy cancellation lead удалён физически; target cases проверяют один renewal event и global offsets. Старые v4 данные блокируются целиком; отключение/дедупликация/retry/generation сохранены.
- `legacyCancellationRowsAreRetiredWithoutDeletingLedgerOrBlockingRenewals` — Тип CANCELLATION больше не существует в v5; в старую Room нельзя войти для изменения ledger. Отказ v1–v4 сохраняет файл, а старый OS cancellation payload по-прежнему удаляется проверкой NotificationPublisherTest.

### Android (NOT RUN) — background/ReminderMigrationTest.kt

- `migrationOneToFourUsesExistingSafeMigrationChain` — Успешная миграция экспериментальной схемы официально отменена. Заменена StorageOpeningTest.versionsOneThroughFourAreRejectedBeforeWritableOpenWithoutChangingAnyFile и проверками отказа до callbacks; exports сохранены.
- `migrationThreeToFourPreservesSubscriptionAndTerminalLedger` — Успешная миграция экспериментальной схемы официально отменена. Заменена StorageOpeningTest.versionsOneThroughFourAreRejectedBeforeWritableOpenWithoutChangingAnyFile и проверками отказа до callbacks; exports сохранены.

### Android (NOT RUN) — data/integration/SubscriptionMigrationIntegrationTest.kt

- `legacyChainsKeepHistoryAndNewRetiredKeysSurviveReopen` — Успешная миграция экспериментальной схемы официально отменена. Заменена StorageOpeningTest.versionsOneThroughFourAreRejectedBeforeWritableOpenWithoutChangingAnyFile и проверками отказа до callbacks; exports сохранены.

### Android (NOT RUN) — data/integration/SubscriptionUseCasesTest.kt

- `cancellationLastDayReactivationAndDeletionPreserveIndependentSubscription` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `confirmedSubscriptionDeletionRetiresOnlyItsExistingOperations` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `createDuplicatesByNameAndExplicitScheduleChoices` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `dateRefreshManualIssueCancellationAndReactivation` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `detailsNeverReadOrExposeLegacyRulesAndRemainReactiveOnDelete` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `editorExtrasPreserveInertLegacyRulesAndConflictOnFullRow` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `editorPriceEditLeavesOtherPlanAndLegacyHistoryUntouched` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `existingIssueCanResolveByDateWithoutCreatingPaymentOrChangingPrice` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `foreignLegacyPriceRetainsCurrencyAcrossEditArchiveRestoreAndReopen` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `fullSnapshotRejectsStalePriceAndSameScheduleAfterReactivation` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `globalSettingsUpdateWithoutReadingOrOverwritingLegacyRules` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `invalidCurrencyEditRollsBackAndValidEditPreservesUuid` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `legacyRowsAndSettingsSurviveReopeningWithoutFinancialUseCases` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `longOverdueManualCanUseExplicitEditorSeriesWithoutPayment` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `realRoomFlowsUpdateDetailsWithoutReadingOrObservingFinancialRows` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `repeatedDateRefreshNeverArchivesOrRestoresAnyExistingState` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `sqlFailureThroughUseCaseRollsBackLifecycleAndRetainsLegacyRows` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.
- `startupAndResumeDateRefreshKeepMutationGuardAndRequestReconciliationWithoutRoomWrites` — Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.

### Android (NOT RUN) — data/lifecycle/LifecycleRepositoryTest.kt

- `existingIssueResolutionIsAtomicAndConcurrentStaleCommandsAreRejected` — Заменено target LifecycleRepositoryTest на Cancel/EditEnd/Archive/Reactivate с explicit today. Concurrent winner/rollback/ID/appearance/подтверждение/каскад и полный expected snapshot сохранены; PAYMENT_ISSUE/финансовые fixtures удалены.
- `failedLegacyDateResolutionRollsBackStateAndHistory` — Заменено target LifecycleRepositoryTest на Cancel/EditEnd/Archive/Reactivate с explicit today. Concurrent winner/rollback/ID/appearance/подтверждение/каскад и полный expected snapshot сохранены; PAYMENT_ISSUE/финансовые fixtures удалены.
- `lifeT08T09ThreeResolutionPathsKeepHistoryForNonPaymentActions` — Заменено target LifecycleRepositoryTest на Cancel/EditEnd/Archive/Reactivate с explicit today. Concurrent winner/rollback/ID/appearance/подтверждение/каскад и полный expected snapshot сохранены; PAYMENT_ISSUE/финансовые fixtures удалены.
- `lifeT13T16ReactivationPreservesOldPaymentAndRefundCurrency` — Заменено target LifecycleRepositoryTest на Cancel/EditEnd/Archive/Reactivate с explicit today. Concurrent winner/rollback/ID/appearance/подтверждение/каскад и полный expected snapshot сохранены; PAYMENT_ISSUE/финансовые fixtures удалены.

### Android (NOT RUN) — data/payment/LegacyPaymentStorageTest.kt

- `concurrentLifecycleCommandsCommitOneExpectedSnapshotAndPreserveLegacyPayment` — Финансовые fixtures больше невозможны в v5. Применимая проверка транзакций/каскада/повторного открытия/независимости прогноза перенесена в SubscriptionTransactionsTest на subscriptions+ledger; физическое сохранение старой схемы проверяет StorageOpeningTest.
- `historyReopensAndDoesNotChangeScheduleOrCurrency` — Финансовые fixtures больше невозможны в v5. Применимая проверка транзакций/каскада/повторного открытия/независимости прогноза перенесена в SubscriptionTransactionsTest на subscriptions+ledger; физическое сохранение старой схемы проверяет StorageOpeningTest.
- `injectedLifecycleFailureRollsBackSubscriptionAndPreservesExistingPayment` — Финансовые fixtures больше невозможны в v5. Применимая проверка транзакций/каскада/повторного открытия/независимости прогноза перенесена в SubscriptionTransactionsTest на subscriptions+ledger; физическое сохранение старой схемы проверяет StorageOpeningTest.

### Android (NOT RUN) — data/payment/PaymentMigrationTest.kt

- `v1ToV2PreservesLegacyPaymentWithoutInventingRequestPayload` — Успешная миграция экспериментальной схемы официально отменена. Заменена StorageOpeningTest.versionsOneThroughFourAreRejectedBeforeWritableOpenWithoutChangingAnyFile и проверками отказа до callbacks; exports сохранены.

### Android (NOT RUN) — data/payment/RefundMigrationTest.kt

- `v2ToV3PreservesPaymentsRefundsAndAddsEmptyTombstoneTable` — Успешная миграция экспериментальной схемы официально отменена. Заменена StorageOpeningTest.versionsOneThroughFourAreRejectedBeforeWritableOpenWithoutChangingAnyFile и проверками отказа до callbacks; exports сохранены.

### Android (NOT RUN) — data/refund/LegacyRefundStorageTest.kt

- `historyChangesNeverChangeSubscriptionOrForecast` — Финансовые fixtures больше невозможны в v5. Применимая проверка транзакций/каскада/повторного открытия/независимости прогноза перенесена в SubscriptionTransactionsTest на subscriptions+ledger; физическое сохранение старой схемы проверяет StorageOpeningTest.
- `monetaryHistorySurvivesReopen` — Финансовые fixtures больше невозможны в v5. Применимая проверка транзакций/каскада/повторного открытия/независимости прогноза перенесена в SubscriptionTransactionsTest на subscriptions+ledger; физическое сохранение старой схемы проверяет StorageOpeningTest.
- `sqlFailureRollsBackSubscriptionDeletionCascadeAndTombstone` — Финансовые fixtures больше невозможны в v5. Применимая проверка транзакций/каскада/повторного открытия/независимости прогноза перенесена в SubscriptionTransactionsTest на subscriptions+ledger; физическое сохранение старой схемы проверяет StorageOpeningTest.
- `subscriptionCascadeAlsoRetiresOperationIds` — Retired operation IDs и таблицы финансовых операций удалены из v5; предотвращать replay удалённой платёжной команды больше нечему. Каскад ledger/подтверждение удаления сохранены.
- `subscriptionDeletionCascadeRequiresConfirmationAndPreservesOtherFinancialFixtures` — Финансовые fixtures больше невозможны в v5. Применимая проверка транзакций/каскада/повторного открытия/независимости прогноза перенесена в SubscriptionTransactionsTest на subscriptions+ledger; физическое сохранение старой схемы проверяет StorageOpeningTest.

### Android (NOT RUN) — data/room/StorageOpeningTest.kt

- `changedHistoricalDefaultIsRejectedWithoutMigration` — Перенесено на v5 либо заменено безопасным отказом v1–v4 до writable/migration. Byte preservation, старые записи, настройки/изображения/маркер, callback fencing и WAL/повреждения сохраняются.
- `changedV4WithOriginalIdentityIsRejectedBeforeWritableCallbacksAndPreservesBytes` — Перенесено на v5 либо заменено безопасным отказом v1–v4 до writable/migration. Byte preservation, старые записи, настройки/изображения/маркер, callback fencing и WAL/повреждения сохраняются.
- `legacyV4WithoutMarkerGetsReadyOnlyAfterPreservingExistingRows` — Перенесено на v5 либо заменено безопасным отказом v1–v4 до writable/migration. Byte preservation, старые записи, настройки/изображения/маркер, callback fencing и WAL/повреждения сохраняются.
- `supportedMigrationFailureRollsBackSchemaVersionAndRows` — Перенесено на v5 либо заменено безопасным отказом v1–v4 до writable/migration. Byte preservation, старые записи, настройки/изображения/маркер, callback fencing и WAL/повреждения сохраняются.
- `versionsOneThroughFourPreserveRowsAndMigrateToV4` — Перенесено на v5 либо заменено безопасным отказом v1–v4 до writable/migration. Byte preservation, старые записи, настройки/изображения/маркер, callback fencing и WAL/повреждения сохраняются.

### Android (NOT RUN) — data/room/TrackerDatabaseTest.kt

- `dbT04OperationIdUniqueAcrossSubscriptionsAndUpdateConflictsAbort` — Финансовый operationId/override keys удалены. Замена проверяет logical key ledger, update conflict и независимость generation; FK/каскад/SQL rollback/reopen сохраняются.
- `reminderAndDeliveryKeysAreUnique` — Финансовый operationId/override keys удалены. Замена проверяет logical key ledger, update conflict и независимость generation; FK/каскад/SQL rollback/reopen сохраняются.

### Android (NOT RUN) — notifications/ReminderSystemRegressionTest.kt

- `concurrentThemeAndGlobalOffsetsIgnoreButPreserveLegacyOverrides` — Перенесён на global offsets и согласованные date+price updates, без таблицы overrides. Native publication/claim/gate и отсутствие partial/stale notification остаются; новые имена отражают целевой контракт.
- `emptyLegacyOverridesDoNotSuppressGlobalRenewals` — Перенесён на global offsets и согласованные date+price updates, без таблицы overrides. Native publication/claim/gate и отсутствие partial/stale notification остаются; новые имена отражают целевой контракт.
- `transactionalDateAndOverrideEditCannotPublishPartialOrOldSnapshot` — Перенесён на global offsets и согласованные date+price updates, без таблицы overrides. Native publication/claim/gate и отсутствие partial/stale notification остаются; новые имена отражают целевой контракт.

### Android (NOT RUN) — ui/DetailsEditorRedesignUiTest.kt

- `futureSeriesChoiceAndRequiredConfirmationRemainExplicit` — Отменённые recovery/mode/NextOnly-toggle заменены contextual cancelled EditEnd/resume, пояснением всей серии и валидацией anchor. Доступность, форма/сохранение и блокировка операции сохраняются.
- `legacyIssueHasOnlyNonFinancialRecoveryAndLegacyModesRemainRenderable` — Отменённые recovery/mode/NextOnly-toggle заменены contextual cancelled EditEnd/resume, пояснением всей серии и валидацией anchor. Доступность, форма/сохранение и блокировка операции сохраняются.

### Android (NOT RUN) — ui/DetailsUiTest.kt

- `unknownAccessEndNeverInventsCountdownOrProgress` — Отмена теперь по умолчанию имеет известный ближайший exclusive end. Новый сценарий проверяет этот default, скрытие fraction в end day и EditEnd; неизвестный end сохраняет отдельные renderer/domain тесты.

### Android (NOT RUN) — ui/SubscriptionsRedesignUiTest.kt

- `decisionsExplainProblemsAndAwaitingHasAnEmptyTrack` — Проблемные/просроченные секции и foreign fixtures заменены ACTIVE/CANCELLED, порядком/ID/точными RUB. Доступность, loading/error, Back/restore и деньги сохраняются.
- `forecastSeparatesCurrenciesAndKeepsZeroTwoAndThreeDigits` — Проблемные/просроченные секции и foreign fixtures заменены ACTIVE/CANCELLED, порядком/ID/точными RUB. Доступность, loading/error, Back/restore и деньги сохраняются.

### Android (NOT RUN) — ui/SubscriptionsUiTest.kt

- `decisionsAreSeparateAndNotDuplicatedInActiveList` — Проблемные/просроченные секции и foreign fixtures заменены ACTIVE/CANCELLED, порядком/ID/точными RUB. Доступность, loading/error, Back/restore и деньги сохраняются.

### Android (NOT RUN) — ui/finance/FinanceIntegrationTest.kt

- `fixedRubTwelveMonthsPreserveLegacyCurrenciesAndSurviveReopeningStores` — Финансовые/foreign/MANUAL fixtures удалены, сохранены reopen settings/Room и reactive price/date/lifecycle для ACTIVE. Добавлен coherent concurrent snapshot test; алгоритм окон не менялся.
- `liveEditsDateAndLifecycleUpdateForecastWhileHistoryAndRefundsDoNot` — Финансовые/foreign/MANUAL fixtures удалены, сохранены reopen settings/Room и reactive price/date/lifecycle для ACTIVE. Добавлен coherent concurrent snapshot test; алгоритм окон не менялся.

### JVM — data/integration/ReminderInputsTest.kt

- `legacyCancellationLeadCannotAffectGlobalRenewalPlan` — Legacy cancellation lead удалён физически; target cases проверяют один renewal event и global offsets. Старые v4 данные блокируются целиком; отключение/дедупликация/retry/generation сохранены.

### JVM — data/room/StorageConvertersTest.kt

- `offsetsHaveCanonicalRepresentationIncludingDisabled` — Room converter offsets удалён вместе с индивидуальными правилами; 16 сочетаний глобальных дней и empty/missing по-прежнему проверяет SettingsTest/SettingsIntegrationTest.

### JVM — domain/MoneyTest.kt

- `moneyT02HandlesThreeAndFourFractionDigits` — Тест валют с 0/3/4 знаками и смешанных валют больше не применим. RUB exact parsing/Long boundary/checked arithmetic/ошибки сохранены в MoneyTest и RubPrice SubscriptionDomainTest.
- `moneyT04KeepsTotalsInSeparateCurrencies` — Тест валют с 0/3/4 знаками и смешанных валют больше не применим. RUB exact parsing/Long boundary/checked arithmetic/ошибки сохранены в MoneyTest и RubPrice SubscriptionDomainTest.

### JVM — domain/calendar/RenewalCalendarTest.kt

- `calT11OverrideAffectsOnlyOneIndexedCycle` — Отменён API одноразового/индексированного переноса и adjacent-cycle conflict. Полная новая серия проверяется SubscriptionDomainTest.dateEditReanchorsWholeSeries/frequencyEditStartsNewSeriesAndClearsPeriodOverride; регулярная математика и bounded lookup сохранены.
- `calT12WholeFutureSeriesCreatesNewSegmentAndLeavesOldSegmentIntact` — Отменён API одноразового/индексированного переноса и adjacent-cycle conflict. Полная новая серия проверяется SubscriptionDomainTest.dateEditReanchorsWholeSeries/frequencyEditStartsNewSeriesAndClearsPeriodOverride; регулярная математика и bounded lookup сохранены.
- `calT13DirectConstructionCannotBypassConflictValidation` — Отменён API одноразового/индексированного переноса и adjacent-cycle conflict. Полная новая серия проверяется SubscriptionDomainTest.dateEditReanchorsWholeSeries/frequencyEditStartsNewSeriesAndClearsPeriodOverride; регулярная математика и bounded lookup сохранены.
- `calT13RejectsEqualityAndCrossingOnBothSides` — Отменён API одноразового/индексированного переноса и adjacent-cycle conflict. Полная новая серия проверяется SubscriptionDomainTest.dateEditReanchorsWholeSeries/frequencyEditStartsNewSeriesAndClearsPeriodOverride; регулярная математика и bounded lookup сохранены.
- `searchFindsMovedDateOnEitherSideOfOriginalDate` — Отменён API одноразового/индексированного переноса и adjacent-cycle conflict. Полная новая серия проверяется SubscriptionDomainTest.dateEditReanchorsWholeSeries/frequencyEditStartsNewSeriesAndClearsPeriodOverride; регулярная математика и bounded lookup сохранены.

### JVM — domain/forecast/ForecastEngineTest.kt

- `fcT05AndMoneyT04CurrenciesNeverMix` — MANUAL/one-off/многовалютность официально удалены. ForecastEngineTest сохраняет активные регулярные серии, точные RUB totals, overflow, интервалы, leap и порядок; target exclusion/price/date проверяются актуальными тестами.
- `fcT07ManualProducesExactlyOneUntilExplicitScheduleEdit` — MANUAL/one-off/многовалютность официально удалены. ForecastEngineTest сохраняет активные регулярные серии, точные RUB totals, overflow, интервалы, leap и порядок; target exclusion/price/date проверяются актуальными тестами.
- `fcT12AvailableCurrenciesRecomputedAfterRemoval` — MANUAL/one-off/многовалютность официально удалены. ForecastEngineTest сохраняет активные регулярные серии, точные RUB totals, overflow, интервалы, leap и порядок; target exclusion/price/date проверяются актуальными тестами.
- `oneOffMovePreservesIndexesWithoutDuplicatesAndRestoresAnchorDay` — MANUAL/one-off/многовалютность официально удалены. ForecastEngineTest сохраняет активные регулярные серии, точные RUB totals, overflow, интервалы, leap и порядок; target exclusion/price/date проверяются актуальными тестами.

### JVM — domain/integration/SubscriptionViewsTest.kt

- `explicitDayChangesManualClassificationWithoutInventingPaymentIssue` — Заменён целевым SubscriptionViewsTest: три группы, автоматическая дата без мутации, reanchor всей серии и restore с ID/ценой. Legacy modes/issues/валюта/one-off не восстанавливаются.
- `issueCancelledAndArchiveGroupsAgreeWithForecastExclusions` — Заменён целевым SubscriptionViewsTest: три группы, автоматическая дата без мутации, reanchor всей серии и restore с ID/ценой. Legacy modes/issues/валюта/one-off не восстанавливаются.
- `monthlyProjectionRestores31AndOneOffDoesNotShiftSeries` — Заменён целевым SubscriptionViewsTest: три группы, автоматическая дата без мутации, reanchor всей серии и restore с ID/ценой. Legacy modes/issues/валюта/one-off не восстанавливаются.
- `yearlyLeapAndReactivatedCurrencyUseOnlyFutureSnapshot` — Заменён целевым SubscriptionViewsTest: три группы, автоматическая дата без мутации, reanchor всей серии и restore с ID/ценой. Legacy modes/issues/валюта/one-off не восстанавливаются.

### JVM — domain/lifecycle/LifecycleCommandsTest.kt

- `existingIssueIsExcludedFromForecastWithoutCreatingAnotherIssue` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `lifeT08T09DateOrArchiveResolvesIssueWithoutPaymentCommand` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `lifeT10T11CancellationPreservesKnownEndAndNeverInventsUnknown` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `lifeT13ReactivationUsesExplicitNewPriceModeAndDate` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.

### JVM — domain/lifecycle/LifecycleTest.kt

- `archiveAndReactivateAreExplicit` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `calT14AutoDueIsInclusive` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `calT18ProgressIsClampedAndOverdueManualIsEmpty` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `calT19StartOverrideChangesOnlyProgress` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `cancellationInfersPriorCalendarBoundaryIncludingOneOff` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `cancellationKeepsKnownOrUnknownAccessAndNeverAutoArchives` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `cancellationProgressUsesExplicitStartAndClampsAtBoundaries` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `cancellationRejectsNonpositiveProgressIntervals` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `cancellationWithoutEndHasNoProgressEvenWithKnownStart` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `cancellationWithoutPriorBoundaryDoesNotInventStart` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `invalidTransitionsFail` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `issueIsExplicitAndResolvedByDateWithoutPayment` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `lifeT01AutoAdvancesExpectedDateWithoutPaymentOrMutation` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.
- `lifeT02ManualTomorrowTodayYesterday` — Старый lifecycle service/modes/issues/inclusive-end удалён. Применимая часть заменена 27 целевыми R1a SubscriptionDomainTest и target LifecycleRepositoryTest/SubscriptionUseCasesTest; ручные подтверждения, one-off и проблемные переходы больше не применимы.

### JVM — domain/reminders/ReminderRulesTest.kt

- `automaticMonthlyAnchorReturnsFromFebruaryToMarchAndManualDoesNotAdvance` — Регулярные month/expiry части сохранены под новыми именами; MANUAL и NextOnly официально отменены. Scope/global offsets/DST/recovery/handled keys/границы сохранены.
- `expiredManualAndRolledAutoTargetsCannotResurrectOldWarnings` — Регулярные month/expiry части сохранены под новыми именами; MANUAL и NextOnly официально отменены. Scope/global offsets/DST/recovery/handled keys/границы сохранены.
- `nextOnlyOverrideUsesExistingCalendarAndDoesNotMutateSchedule` — Регулярные month/expiry части сохранены под новыми именами; MANUAL и NextOnly официально отменены. Scope/global offsets/DST/recovery/handled keys/границы сохранены.

### JVM — notifications/NotificationRulesTest.kt

- `moneyRetainsJpyZeroPrecision` — Синтетические JPY/KWD price fixtures заменены точными RUB kopecks и Long.MAX_VALUE; копия уведомления, permission/channel и failure contracts сохранены.
- `moneyRetainsKwdThreePrecisionAndLargeValues` — Синтетические JPY/KWD price fixtures заменены точными RUB kopecks и Long.MAX_VALUE; копия уведомления, permission/channel и failure contracts сохранены.

### JVM — ui/details/DetailsViewModelTest.kt

- `legacyModesAndStatesLoadAndOnlyExistingIssueCanBeResolved` — Legacy modes/issues/currency branch заменены тремя целевыми статусами, EditEnd и отбрасыванием несовместимого полного snapshot. Повторная отправка/unknown outcome/conflict/generation/cancellation остаются.
- `savedReactivationCurrencyChangeIsDiscardedWithoutExecuting` — Legacy modes/issues/currency branch заменены тремя целевыми статусами, EditEnd и отбрасыванием несовместимого полного snapshot. Повторная отправка/unknown outcome/conflict/generation/cancellation остаются.

### JVM — ui/subscriptions/SubscriptionCardModelTest.kt

- `overdueManualAndPaymentIssueDoNotInventFutureDates` — Просроченного MANUAL/issue нет; новый сценарий проверяет автоматическое продвижение даты ACTIVE без записи/проблемного статуса. Unknown/end/fraction/sort/ID проверяются отдельно.

## Существенно адаптированные и добавленные методы

Для каждого класса общая причина применяется к перечисленным методам. Изменения общих fixture builders также затрагивают методы с неизменёнными телами.

### Android (NOT RUN) — app/DatasetCompositionTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `pointerAndBothStoresSurviveClosingAndReopening`, `projectionIgnoresHistoryAndUpdatesFuturePriceAndSchedule`, `startupAndResumeRefreshCalendarWithoutCreatingPayments`.

### Android (NOT RUN) — background/ReminderInfrastructureTest.kt

Legacy cancellation lead удалён физически; target cases проверяют один renewal event и global offsets. Старые v4 данные блокируются целиком; отключение/дедупликация/retry/generation сохранены.

Адаптированы: `archiveInvalidatesClaimImmediatelyBeforePublication`, `autumnClockTransitionKeepsNineAndDoesNotPublishAgain`, `cancellationReleasesClaimAndClosesBackgroundSession`, `disablingThenReenablingGlobalDaysReschedulesPendingKeysWithoutClearingLedger`, `noActivitySessionRequiredAndExistingDataStoreLeaseRemainsUsable`, `obsoleteIndividualOffsetIsRetainedButCannotCreateNotification`, `productionUseCasesSignalLifecycleChangesButNotThemeOrChartSettings`, `springClockTransitionKeepsNineAndOriginalLogicalDate`.

Новые/заменяющие имена: `onlyRenewalReasonsEnterCoveredKeys`.

### Android (NOT RUN) — background/ReminderWorkManagerTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `createThroughUseCasesEnqueuesRealControlWorkerWithoutActivity`.

### Android (NOT RUN) — data/assets/AssetStoreTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `discardingAfterAmbiguousSuccessfulSaveDoesNotDeleteCommittedImage`.

### Android (NOT RUN) — data/assets/OfflineIconCatalogIntegrationTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `confirmedDeletionPreservesSharedImageAndOtherHistoryUntilLastOwner`, `everyApprovedVectorLoadsFromAndroidResources`.

### Android (NOT RUN) — data/integration/SubscriptionUseCasesTest.kt

Заменено target use-case сценарием в том же классе: coherent details, дубликаты/копейки, Keep/Reanchor/PeriodStart, exclusive cancellation, archive/restore, snapshot conflict, rollback, ledger cascade, settings/reopen и mutation gate. Старые modes/issues/financial rows/индивидуальные rules/валюта исключены из fixtures; применимые гарантии дополнены SubscriptionTransactionsTest.

Адаптированы: `concurrentDateEditOrArchiveUsesFullExpectedSnapshot`, `staleSnapshotsCannotDeleteOrArchiveAfterReactivation`.

Новые/заменяющие имена: `cancelledExclusiveEndCanChangeWithoutChangingFrozenStartAndNeverAutoArchives`, `confirmedDeletionCascadesOnlySelectedLedger`, `duplicateNamesHaveIndependentIdsAndKopecks`, `explicit31InFebruaryRestoresMarchAndScopedStartExpires`, `explicitDateReplacesWholeSeriesAndResetsPeriodOverride`, `fullSnapshotRejectsStalePriceEvenAfterReturningToSameScheduleAndState`, `injectedSqlFailureRollsBackAllSubscriptionFields`, `manualArchiveAndRestoreKeepIdentityAndAppearance`, `priceAndNameEditsPreserveExactCalendarAnchor`, `priceEditKeepsScopedPeriodStartAndExplicitClearDoesNotShiftAnchor`, `realRoomFlowsUpdateDetailsWithoutReadingFinancialTables`, `settingsAndSubscriptionsReopenUsingReadyWithoutFreshAuthorization`, `startupAndResumeDateRefreshKeepMutationGuardAndReconciliationWithoutRoomWrites`.

### Android (NOT RUN) — data/lifecycle/LifecycleRepositoryTest.kt

Заменено target LifecycleRepositoryTest на Cancel/EditEnd/Archive/Reactivate с explicit today. Concurrent winner/rollback/ID/appearance/подтверждение/каскад и полный expected snapshot сохранены; PAYMENT_ISSUE/финансовые fixtures удалены.

Адаптированы: `lifeT14T15CascadeDeletionRequiresConfirmationAndRetainsOtherRecords`, `lifeT17ConcurrentCommandsAndStaleDeletionCannotOverwrite`.

Новые/заменяющие имена: `archiveRestorePreservesIdAppearanceAndValidatesNewContract`, `cancellationFreezesStartAndEditingEndNeverMovesIt`, `concurrentCommandsCommitOneExpectedSnapshot`, `injectedUpdateFailureRollsBackFullSnapshot`.

### Android (NOT RUN) — data/room/StorageOpeningTest.kt

Перенесено на v5 либо заменено безопасным отказом v1–v4 до writable/migration. Byte preservation, старые записи, настройки/изображения/маркер, callback fencing и WAL/повреждения сохраняются.

Адаптированы: `changedHistoricalIndexIsRejectedWithoutMigration`, `committedWalSchemaIsInspectedAndNotIgnoredOrCheckpointedOnRejection`, `compatibleWalCommittedRowsSurvivePreflightAndRoomOpen`, `crashAfterValidCreationBeforeReadyRecoversByValidationNotRecreation`, `damagedMarkerIsNotReplacedEvenWithCompatibleDatabase`, `failedBackgroundOpenDoesNotAcquireSettingsMutateLedgerOrPublish`, `failedCompositionDoesNotPublishUseCasesSweepImagesOrReconcile`, `freshInstallReturnsActuallyOpenValidatedRoom`, `hotRollbackJournalIsNotRecoveredByReadOnlyDiagnostics`, `matchingIdentityHashDoesNotHideInvalidSchema`, `partialIndexWithOriginalColumnsIsRejectedWithoutMigration`, `staleCompositionCannotPerformAgainstNewGeneration`, `twoConcurrentOpenersConsumeOneCreationPermission`, `unsupportedVersionNeverReplacesDataOrChangesOtherStores`, `wrongIdentityHashIsRejectedWithoutRewritingMetadata`.

Новые/заменяющие имена: `changedCurrentDefaultIsRejectedBeforeWrite`, `changedV5WithOriginalIdentityIsRejectedBeforeWritableCallbacksAndPreservesBytes`, `existingV5WithoutMarkerGetsReadyOnlyAfterPreservingExistingRows`, `missingForeignKeyWithMatchingHashIsRejectedBeforeWrite`, `oldSchemaCannotInvokeEvenAnExplicitlySuppliedMigration`, `versionsOneThroughFourAreRejectedBeforeWritableOpenWithoutChangingAnyFile`.

### Android (NOT RUN) — data/room/SubscriptionTransactionsTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Новые/заменяющие имена: `concurrentLifecycleCommandsCommitOneSnapshotAndPreserveLedger`, `confirmationAndCascadeAffectOnlySelectedSubscription`, `deliveryAcknowledgementCannotChangeSubscriptionOrForecast`, `failedLedgerCascadeRollsBackParentAndDependentRows`, `injectedLifecycleFailureRollsBackSubscriptionAndPreservesLedger`, `reopenPreservesCompleteSubscriptionAndDeliveryMetadataUsingReady`.

### Android (NOT RUN) — data/room/TrackerDatabaseTest.kt

Финансовый operationId/override keys удалены. Замена проверяет logical key ledger, update conflict и независимость generation; FK/каскад/SQL rollback/reopen сохраняются.

Адаптированы: `allTablesRoundTripAndReopenPreserveExactValuesAndDatasetIsolation`, `dbT02AllForeignKeysRejectMissingParents`, `dbT03CascadeLeavesOtherSubscriptionAndSharedAssetIntact`, `dbT05FailureRollsBackWholeTransaction`, `invalidValuesRejectedBeforeStorage`.

Новые/заменяющие имена: `dbT04DeliveryLogicalKeyUniqueAndUpdateConflictsAbort`, `freshV5HasOnlyTargetTablesAndGeneratedSchemaColumns`, `renewalDeliveryKeysRemainIndependentAcrossGenerations`.

### Android (NOT RUN) — data/settings/SettingsIntegrationTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `dbT06GlobalChangesDoNotRewriteRoomAndSubscriptionsAreObservable`, `dbT07ReopenAndDatasetIsolationAndSharedLease`, `explicitEmptyGlobalSetStaysDisabledAfterFinalLeaseReopen`, `legacyStoredValuesAreIgnoredAndRemainIntactAfterCurrentWriteAndReopen`, `reactiveFlowAndInvalidUpdateIsAtomic`.

### Android (NOT RUN) — notifications/NotificationNavigationUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `archivedSubscriptionStillOpensItsExistingDetails`, `unknownOldNotificationActionCannotMutateSubscriptionOnLaunchOrRecreation`.

### Android (NOT RUN) — notifications/NotificationPermissionUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `zSystemBlockRetainsPendingReasonsAndGrantReconciles`.

### Android (NOT RUN) — notifications/NotificationPublisherTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `archiveCancelsAlreadyPostedNotification`, `changingInertLegacyDeadlineDoesNotCancelCurrentRenewal`, `disabledRenewalCancelsNotification`, `legacyCancellationLeadStillUsesOnlyRenewalChannelAndCopy`, `preUpdateCancellationNotificationIsRemovedWithoutAcknowledgingDelivery`, `replayUpdatesSameSystemIdentity`, `systemChannelBlockIsRespectedWithoutFallbackOrFalseDelivery`.

### Android (NOT RUN) — notifications/ReminderSystemRegressionTest.kt

Перенесён на global offsets и согласованные date+price updates, без таблицы overrides. Native publication/claim/gate и отсутствие partial/stale notification остаются; новые имена отражают целевой контракт.

Адаптированы: `delayedMixedReasonsAreOneNativeMessageAndAllCoveredKeysAreAcknowledged`, `disablingGlobalDaysRemovesStaleNativeCopyWithoutResettingDeliveredKeys`, `explicitDateEditWhileWorkerWaitsInvalidatesOldReminderWithoutPayment`, `lifecycleWritesRemoveAndRestoreOnlyEligiblePendingReasons`.

Новые/заменяющие имена: `concurrentThemeAndGlobalOffsetsRemainIndependent`, `globalRenewalsReconcileIdempotentlyAndEmptyDisablesThem`, `transactionalDateAndPriceEditCannotPublishPartialOrOldSnapshot`.

### Android (NOT RUN) — ui/AccessibilityUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable`.

### Android (NOT RUN) — ui/ArchiveSettingsRedesignUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `archiveShowsOnlyKnownAccessAndNoForecastOrRowReactivation`, `duplicateArchiveNamesKeepLabelsCurrenciesAndOpenCorrectUuid`, `oneGlobalOffsetDialogAllowsArbitrarySelectionAndEmptySet`.

### Android (NOT RUN) — ui/ArchiveSettingsUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `archiveReactivationPreservesUuidAndUpdatesActiveForecast`.

### Android (NOT RUN) — ui/DetailsEditorRedesignUiTest.kt

Отменённые recovery/mode/NextOnly-toggle заменены contextual cancelled EditEnd/resume, пояснением всей серии и валидацией anchor. Доступность, форма/сохранение и блокировка операции сохраняются.

Адаптированы: `archiveKeepsReactivationAndHidesPayment`, `cancelledAccessIsRenderedExactlyOnce`, `captureDarkDetailsAndEditor`, `managementIsSeparateAndDispatchesExistingActions`, `unknownAccessDoesNotInventDateOrProgress`.

Новые/заменяющие имена: `cancelledDetailsOfferEndEditAndResumeWithoutLegacyRecovery`, `cancelledEditorKeepsNonScheduleFieldsEditableAndFrozenPeriodReadOnly`, `dateEditExplainsWholeSeriesAndAnchorValidationRemainsVisible`.

### Android (NOT RUN) — ui/DetailsUiTest.kt

Отмена теперь по умолчанию имеет известный ближайший exclusive end. Новый сценарий проверяет этот default, скрытие fraction в end day и EditEnd; неизвестный end сохраняет отдельные renderer/domain тесты.

Адаптированы: `knownAccessUsesDomainRemainingFraction`.

Новые/заменяющие имена: `cancellationDefaultsToNearestExclusiveEndAndHidesProgressOnEndDay`.

### Android (NOT RUN) — ui/SubscriptionsRedesignUiTest.kt

Проблемные/просроченные секции и foreign fixtures заменены ACTIVE/CANCELLED, порядком/ID/точными RUB. Доступность, loading/error, Back/restore и деньги сохраняются.

Адаптированы: `duplicateNamesKeepLabelsDividersAndCorrectUuid`, `knownAccessIsShownOnceAndUnknownAccessHasNoProgress`, `largeFontKeepsLongNamesAmountsAndToolbarActionsAvailable`, `suppliedDomainFractionIsRenderedWithThinProgressAndTextStatus`.

Новые/заменяющие имена: `cancelledRowsHaveNoProblemOrAwaitingStatus`, `forecastRendersExactRubEstimateWithoutForeignCurrencyControls`.

### Android (NOT RUN) — ui/SubscriptionsUiTest.kt

Проблемные/просроченные секции и foreign fixtures заменены ACTIVE/CANCELLED, порядком/ID/точными RUB. Доступность, loading/error, Back/restore и деньги сохраняются.

Адаптированы: `cancelledUnknownAccessNeverShowsImaginedDate`, `cleanLaunchAndFirstAddNavigateToRealEditor`, `useCaseInsertionIsReactiveAndDuplicateServicesRetainLabels`.

Новые/заменяющие имена: `cancelledRowsAreBelowActiveWithoutDuplicatesOrProblemStates`.

### Android (NOT RUN) — ui/details/DetailsOperationsIntegrationTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `completeSavedSubscriptionSnapshotRejectsConcurrentEditAfterReopen`, `liveLifecycleDetailsPreserveOtherSubscriptionAndLegacyRows`, `lostArchiveResponseRestoresExpectedSnapshotWithoutAutomaticResubmission`.

### Android (NOT RUN) — ui/finance/FinanceIntegrationTest.kt

Финансовые/foreign/MANUAL fixtures удалены, сохранены reopen settings/Room и reactive price/date/lifecycle для ACTIVE. Добавлен coherent concurrent snapshot test; алгоритм окон не менялся.

Новые/заменяющие имена: `concurrentEditsCannotMixPriceLabelsEligibilityAndForecastSnapshots`, `fixedRubTwelveMonthsAndIndependentSubscriptionsSurviveReopen`, `livePriceDateAndLifecycleChangesRecomputeOnlyActiveForecasts`.

### Android (NOT RUN) — ui/finance/FinanceUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `monthsSelectExactExpectedEventsWithoutCurrencyOrHorizonControls`, `zeroMonthIsSelectableAndLargeTextRemainsScrollableInBothThemes`.

### Android (NOT RUN) — ui/finance/LargeFontChartUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `nativeFontTwoLandscapeKeepsAllMonthsAndExactLargeMoneyAccessibleInBothThemes`.

### Android (NOT RUN) — ui/finance/MonthlyChartUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `reactivePriceAndCurrencySnapshotsUpdateBarsWithoutStaleCachedSums`.

### Android (NOT RUN) — ui/review/CrossScreenDesignUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `galleryAllCurrentScreensDark`, `longAmountsKeepZeroTwoAndThreeFractionDigitsAcrossWidthsAndFonts`.

### Android (NOT RUN) — ui/review/LargeFontWindowUiTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `catalogRemainsSelectableInLandscapeAtNativeDoubleFontScale`, `landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont`.

### Android (NOT RUN) — ui/settings/ArchiveSettingsIntegrationTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `archiveProjectionReactivationPreservesHistoryAndUuidAndRecomputesForecast`, `failedReadAndLostWriteResponsePreservePreferencesAndRestoreWithoutAutomaticWrite`, `globalSettingsPersistAcrossReopenWithoutReadingOrOverwritingLegacyRules`, `productionSessionRetryKeepsSameDatasetAndReconnectsArchiveAndSettingsBeforeFinalRelease`.

### JVM — data/integration/ReminderInputsTest.kt

Legacy cancellation lead удалён физически; target cases проверяют один renewal event и global offsets. Старые v4 данные блокируются целиком; отключение/дедупликация/retry/generation сохранены.

Адаптированы: `emptyGlobalSetDisablesEverySubscription`, `globalDefaultsMapToPureRenewalPlansAndSameNamesRemainIndependent`.

### JVM — data/mapping/SubscriptionMappingTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Новые/заменяющие имена: `everyTargetStateAndScopedPeriodMapsLosslesslyWithStorageTimestamps`, `exactKopecksAndOriginalAnchorAreNotCalculatedStorageState`, `invalidRoomValuesAreRejectedRatherThanConvertedOrPartiallyMapped`, `runtimeEntityHasNoRetiredProductFieldsAndOnlyThreeStates`.

### JVM — data/room/StorageConvertersTest.kt

Room converter offsets удалён вместе с индивидуальными правилами; 16 сочетаний глобальных дней и empty/missing по-прежнему проверяет SettingsTest/SettingsIntegrationTest.

Адаптированы: `invalidStoredValuesAreNotSilentlyNormalized`, `uuidAndCurrenciesRoundTrip`.

### JVM — data/settings/SettingsTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `absentAndExplicitlyEmptyAreDistinct`, `allArbitraryAllowedGlobalSetsRoundTripIncludingZero`, `defaultsAndCodecRoundTrip`, `invalidCurrentContractFailsAndLegacyMembersAreSafelyFiltered`, `retiredKeysAreInertAndPreservedAcrossCurrentPreferenceWrites`.

### JVM — domain/MoneyTest.kt

Тест валют с 0/3/4 знаками и смешанных валют больше не применим. RUB exact parsing/Long boundary/checked arithmetic/ошибки сохранены в MoneyTest и RubPrice SubscriptionDomainTest.

Адаптированы: `currencyCodesAreValidatedAndHaveValueEquality`, `largestLongAmountIsAcceptedExactly`, `moneyT01ParsesCommaAndFormatsWithoutLoss`, `moneyT03RejectsExcessPrecisionEvenWhenExtraDigitsAreZero`, `overflowInputIsRejectedInsteadOfRoundedOrWrapped`, `parseFormatRoundTripsMinorUnitsAcrossScales`.

### JVM — domain/calendar/RenewalCalendarTest.kt

Отменён API одноразового/индексированного переноса и adjacent-cycle conflict. Полная новая серия проверяется SubscriptionDomainTest.dateEditReanchorsWholeSeries/frequencyEditStartsNewSeriesAndClearsPeriodOverride; регулярная математика и bounded lookup сохранены.

Адаптированы: `boundedSearchAgreesWithEnumeratedShortSeries`, `calT10ExplicitDayIsIndependentOfShortInitialMonth`, `calT14DateIsCurrentOnDueDayAndNextCycleAfterwards`, `calT16OverflowAndDateExhaustionAreControlled`, `calT16RejectsInvalidModelsAndNegativeIndices`, `calT17SearchDoesNotWalkBillionYearHistory`, `hugeRangeIsLazyAndOversizedMaterializationFailsExplicitly`, `rangeExhaustionNearMaxDateDoesNotWrapOrInventEvents`, `rangeIsHalfOpenAndRespectsNextCycleIndex`.

### JVM — domain/forecast/ForecastEngineTest.kt

MANUAL/one-off/многовалютность официально удалены. ForecastEngineTest сохраняет активные регулярные серии, точные RUB totals, overflow, интервалы, leap и порядок; target exclusion/price/date проверяются актуальными тестами.

Адаптированы: `calT17AncientDailyScheduleUsesEfficientLookup`, `calT19DisplayPeriodStartDoesNotChangeForecast`, `emptyInvalidAndDateBoundaryInputsFailExplicitly`, `fcT01ThirtyDaysAreHalfOpenAndIncludeToday`, `fcT04SixAndTwelveMonthsCrossYear`, `fcT06AutoEveryFourteenDays`, `fcT08ExcludedStatesRegardlessOfDatesOrPaidAccess`, `fcT09ForecastLookupNeverMutatesScheduleOrState`, `fcT10RecalculatesPriceScheduleDateStateAndMode`, `fcT11MonthlyDetailMatchesTotalsIncludingEmptyMonths`, `fcT14ExactLongBoundaryAndOverflowAcrossMonths`.

### JVM — domain/integration/SubscriptionViewsTest.kt

Заменён целевым SubscriptionViewsTest: три группы, автоматическая дата без мутации, reanchor всей серии и restore с ID/ценой. Legacy modes/issues/валюта/one-off не восстанавливаются.

Новые/заменяющие имена: `cancelledAndArchiveGroupsAgreeWithForecastExclusions`, `changedDateStartsWholeNewSeries`, `dayChangesNeverChangeActiveStateOrRequireConfirmation`, `restoreRetainsIdentityAndUsesOnlyNewScheduleAndPrice`.

### JVM — domain/reminders/ReminderRulesTest.kt

Регулярные month/expiry части сохранены под новыми именами; MANUAL и NextOnly официально отменены. Scope/global offsets/DST/recovery/handled keys/границы сохранены.

Адаптированы: `amsterdamAutumnOverlapChoosesEarlierOffsetAndOneLogicalOccurrence`, `amsterdamSpringGapUsesFirstValidTimeWithoutChangingLogicalDateOrTime`, `calendarUnderflowAndOutOfRangeInstantDoNotReturnPartialPlans`, `changingDateAndTimeInvalidatesOldPayloadButTimeKeepsLogicalKey`, `delayedReasonsCrossLocalMidnightAndYearWithoutResurrectingHandledKeys`, `deterministicOrderingStableIdsAndIndependentUuidsExcludeLogicalDuplicates`, `emptyOffsetsDisableRenewalsAndNoCancellationEventExists`, `excludedLifecycleStatesAndDeletedSubscriptionsHaveNoReminders`, `invalidOffsetsLeadConflictingSnapshotsAndMismatchedDatesFailExplicitly`, `leapAnchorIsPreservedForFutureLeapYears`, `onlyRenewalDatesUseTheGlobalOffsets`.

Новые/заменяющие имена: `monthlyAnchorReturnsFromFebruaryToMarch`, `rolledTargetsCannotResurrectOldWarnings`.

### JVM — domain/subscription/SubscriptionDomainTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `calculatedBoundaryRestores31AndLeapDayBackwards`, `dueTodayThenAutomaticNextPeriodWithoutMutation`, `foreignAmountIsRejectedNeverRelabelled`, `invalidIntervalsAndDatesRejectExplicitly`.

### JVM — notifications/NotificationRulesTest.kt

Синтетические JPY/KWD price fixtures заменены точными RUB kopecks и Long.MAX_VALUE; копия уведомления, permission/channel и failure contracts сохранены.

Новые/заменяющие имена: `moneyRetainsExactLargestRubValue`, `moneyRetainsRubKopecks`.

### JVM — notifications/NotificationViewModelTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `restoringAChannelReconcilesAndDisablingDoesNotPublish`.

### JVM — ui/archive/ArchiveViewModelTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `nameAndUuidSortKeepsDuplicateNamesIndependentWithoutArchiveTimestamp`.

### JVM — ui/details/DetailsViewModelTest.kt

Legacy modes/issues/currency branch заменены тремя целевыми статусами, EditEnd и отбрасыванием несовместимого полного snapshot. Повторная отправка/unknown outcome/conflict/generation/cancellation остаются.

Адаптированы: `cancellationDoesNotBecomeUnknownOutcomeErrorOrTriggerRetry`, `removedAndUnknownSavedActionsAreDiscardedWithoutExecutionOrClearingOtherState`.

Новые/заменяющие имена: `onlyTargetStatesLoadAndCancelledEndEditingIsContextual`.

### JVM — ui/details/SubscriptionSnapshotTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `snapshotRoundTripPreservesEveryColumnIncludingNullableValues`.

### JVM — ui/editor/EditorRetryTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `initialReadErrorRetainsSavedDraft`.

### JVM — ui/editor/EditorStateTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `compatibleRubDraftRestoresAndRetiredFieldChangesAreIgnored`, `failedSaveRetainsUserInput`, `legacyDraftDropsRetiredRulesAndRequiresForeignAmountReentry`.

Новые/заменяющие имена: `editingExpiredPeriodStartScopesNewDraftToDisplayedCurrentDueWithoutReanchor`, `selectingUnchangedDateOrFrequencyPreservesAnchorAndPeriodDraft`.

### JVM — ui/editor/EditorTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `februaryCanRetain31`, `leapAnchorReturnsInLeapYear`, `oldAnchorCanBeLoadedWithoutResettingToCycleZero`, `precisionZeroTwoThreeNeverRounds`.

### JVM — ui/finance/FinanceViewModelTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `overflowIsAnErrorWithNoPartialOrZeroTotal`, `removingRubDataDoesNotFallbackToForeignAmountsOrResetValidMonth`, `retiredCurrencyAndHorizonSavedStateCannotWriteOrChangeRubProjection`.

### JVM — ui/settings/SettingsViewModelTest.kt

Общий fixture/API адаптирован к SubscriptionRecord/RubPrice/трём состояниям/новой схеме, без восстановления отменённых продуктовых возможностей; сохраняемые проверки перечислены в новых сценариях того же компонента.

Адаптированы: `preferencePatchesAreIndependentIdempotentAndPreserveOtherFields`.

### JVM — ui/subscriptions/SubscriptionCardModelTest.kt

Просроченного MANUAL/issue нет; новый сценарий проверяет автоматическое продвижение даты ACTIVE без записи/проблемного статуса. Unknown/end/fraction/sort/ID проверяются отдельно.

Адаптированы: `automaticSortUsesDatesThenUuidAndUnknownLast`, `knownAccessEndUsesStoredDateAndDomainProgress`, `namesLabelsAndCurrencyPrecisionArePreserved`, `unknownCancellationDateHasNoCountdownOrProgress`.

Новые/заменяющие имена: `activeDateAdvancesWithoutMutationOrProblemStatus`.

## Не выполнено, риски и следующие этапы

Все Android-тесты в этом отчёте — NOT RUN. В частности, не выполнены реальные fresh/reopen/upgrade-block,
побайтовые fixture сравнения на SQLite, generated preflight на устройстве, WAL/SHM/hot journal,
power-loss/fsync, конкуренция нескольких соединений, CASCADE/trigger rollback, bitmap/private images/sweep,
WorkManager native queue/claim/publication/generation, permission/channel/reboot/timezone,
rotation/process recreation и restored navigation, темы/TalkBack/large font/Photo Picker.
Их компиляция и статическая проверка не доказывают безопасность поведения Android SQLite/NMS/WorkManager.
APK DEX/schema audit — статический результат.

Read-only preflight опирается на service API закреплённых Room 2.8.3/SQLite; обновление зависимостей требует
отдельной повторной проверки. Нельзя обещать побайтовую неизменность SHM lock bytes при активных соединениях.
Чистый разрыв v1–v4 преднамерен: установка поверх экспериментальной базы блокируется, пока владелец
не выполнит явный ручной сброс. Ни одной старой суммы не присвоен RUB и переносов данных не выполнялось.

До R2 остаются прежний half-open plusDays(30) вместо целевого today…today+30 включительно,
окончательные 12 корзин/rolling horizon/пересечения коротких циклов/retention ledger и системная приёмка напоминаний.
Нормализация month/year — R6a, animations/comparison settings — соответствующие будущие этапы.
R3–R6 также завершат согласованный визуальный дизайн; текущая главная сводка/компоновка не переделывались.
H/backup отсутствует, Auto Backup/D2D не включались.

R1b.5 — отдельная итоговая регрессия и аудит полного R1b с владельцем; не начат.
Для независимой приёмки R1b.4 доступны code diff, generated v5 schema, JVM результаты и скомпилированные
device fixtures. До реального запуска Android-сценариев инструментальная приёмка остаётся открытой.
После подготовки Draft PR работа останавливается, merge не выполняется.

## Изменённые файлы

Пути относительно корня репозитория; список включает удалённые файлы и новые exports/fixtures/отчёт:

```text

app/schemas/com.glebskorobogatov.subscriptiontracker.data.room.TrackerDatabase/5.json
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/app/DatasetCompositionTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderInfrastructureTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderMigrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderWorkManagerTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/assets/AssetStoreTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/assets/OfflineIconCatalogIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionMigrationIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCasesTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/lifecycle/LifecycleRepositoryTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/payment/LegacyPaymentStorageTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/payment/PaymentMigrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/payment/RefundMigrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/refund/LegacyRefundStorageTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/room/StorageOpeningTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/room/SubscriptionTransactionsTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/room/TrackerDatabaseTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/fixtures/LegacyFinancialRows.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationNavigationUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationPermissionUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationPublisherTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/ReminderSystemRegressionTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/AccessibilityUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsEditorRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsOperationsIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/LargeFontChartUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/MonthlyChartUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/CrossScreenDesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/LargeFontWindowUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/settings/ArchiveSettingsIntegrationTest.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/app/ReminderSystemProbeReceiver.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/ArchiveSettingsPreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/DetailsEditorPreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/FinancePreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/review/DesignReviewGallery.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsPreviews.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/ReminderInputs.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionEditor.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCases.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/lifecycle/LifecycleRepository.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/mapping/SubscriptionMapping.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/mapping/SubscriptionSnapshot.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/reminders/ReminderChanges.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/reminders/ReminderLedger.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/Daos.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/Entities.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/RetiredPaymentOperation.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/SchemaPreflight.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/StorageConverters.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/TrackerDatabase.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/settings/Settings.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsRepository.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/calendar/RenewalAnchor.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/calendar/RenewalCalendar.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/calendar/RenewalSchedule.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/forecast/ForecastEngine.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/integration/SubscriptionViews.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleCommands.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/SubscriptionLifecycle.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/model/CurrencyCode.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/reminders/ReminderRules.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/subscription/SubscriptionDomain.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/subscription/SubscriptionRecord.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/notifications/AndroidReminderPublisher.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/archive/ArchiveScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/archive/ArchiveViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionRow.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsUiState.kt
app/src/main/res/values/strings.xml
app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/integration/ReminderInputsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/mapping/SubscriptionMappingTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/reminders/ReminderSchedulingTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/room/StorageConvertersTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/MoneyTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/calendar/RenewalCalendarTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/forecast/ForecastEngineTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/integration/SubscriptionViewsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleCommandsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/reminders/ReminderRulesTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/subscription/SubscriptionDomainTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationRulesTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/archive/ArchiveViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/SubscriptionSnapshotTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorRetryTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorStateTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionCardModelTest.kt
docs/reports/R1b.4.md
```


## Дополнение R1b.4-fix — неизменённое расписание редактора

Дата: 17.11.2026. Исходный SHA `df6fd2a3a8c62177ac549bc4a1484d2bbc76faeb`, существующая ветка
`codex/r1b-04-room-domain-integration`, существующий Draft PR #5. Перед работой HEAD совпал,
рабочее дерево было чистым. Новая ветка/PR не создавались.

Итоговый SHA проверенного исправления: `b3af153596647a6948557a551fad74951cc46ae1`.
Следующий коммит изменяет только этот отчёт; SHA вершины ветки с отчётом приводится в PR #5 и итоговом сообщении.
Это дополнение заменяет предыдущие сведения об актуальном SHA/количестве тестов, сохраняя исходный отчёт R1b.4 как историю.

### Первопричина и воспроизведение

fromRow() помещал в форму вычисленный ближайший due и исходный anchorDay серии.
validate() и save() безусловно вызывали schedule(fields) до определения, менял ли пользователь расписание.
Для WEEK с опорой 01.11.2026 и today=08.11.2026 форма содержит date=08.11.2026 и anchorDay=1.
Это корректные представления существующей серии, но не согласованные параметры нового первого события.
RecurringSchedule.starting() правильно отклоняет эту пару; ошибка появлялась у anchorDay даже при правке только цены.
То же затрагивало DAY, переход месяца, отменённые/архивные записи и сохранённые черновики.

Дефект воспроизведён на исходном production-коде шестью новыми JVM-тестами EditorScheduleValidationTest:
**6 выполнено, 5 упали** (weekly, daily, month rollover, inactive и stale draft).
После исправления эти же **6 тестов прошли**. Месячная/годовая контрольная проверка проходила и до исправления.
Это проверка настоящих fromRow()/validate() EditorViewModel; JVM fixture через reflection устанавливает
уже загруженный ожидаемый row, чтобы не вводить тестовый обход в production API хранилища.
Полная загрузка и запись проверяются отдельными Android-сценариями ниже, которые скомпилированы, но NOT RUN.

### Исправление

Общий scheduleEdit(fields) используется и в validate(), и в save(). Он различает:

- Создание: всегда формирует и полностью проверяет новую серию.
- Редактирование без изменения date/frequency/опорного уточнения: возвращает null до вызова
  RecurringSchedule.starting(); исходные year/month/day/частота остаются без изменений.
- Явное изменение: проверяет доступность изменения по состоянию, date>=today и полную согласованность
  нового RecurringSchedule.starting(). Невалидные поля блокируют сохранение.

Для сравнения используется первоначальный displayedDue из черновика, а не заново вычисленный due сегодня.
Поэтому пересоздание ViewModel и переход времени не превращают изменение имени/цены/оформления в reanchor.
Устаревшая неизменённая дата не проверяется как начало новой серии.

Существующий change() сбрасывает унаследованный anchorDay при явной смене date/unit/count.
Новые DAY/WEEK серии получают день выбранной первой даты, а исходный день старой серии не переносится.
Явно введённое несовместимое уточнение по-прежнему отклоняется строгим RecurringSchedule.starting().
MONTH/YEAR сохраняют возможность явного уточнения 31/29, включая короткий февраль и високосные годы.
Если дата/период возвращены к исходным значениям, отсутствие дополнительного уточнения сохраняет старую опору.
Отменённые и архивные записи разрешают обычные правки без построения новой серии; изменение их расписания остаётся недоступным.

Сохранены полный expected row, fingerprint восстановленного черновика, проверка generation внутри owner.perform(),
mutation gate и optimistic проверка репозитория. CancellationException не заменяется ошибкой поля.
Ни RecurringSchedule, ни Domain, ни схема/защита Room не изменены.

### Изменённые файлы

- app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorViewModel.kt.
- app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorScheduleValidationTest.kt — новый файл.
- app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorScheduleIntegrationTest.kt — новый файл.
- docs/reports/R1b.4.md — это дополнение.

Иных production-изменений нет. Схема v5 и все snapshots, безопасное открытие/журнал/gates,
forecast/reminder algorithms, настройки, зависимости/Gradle/CI и продуктовые требования побайтово сохранены относительно базы исправления.
UI и дизайн не менялись. Прежние тесты не удалялись, не ослаблялись и не адаптировались ради PASS.

### Новые тесты

6 JVM-методов проверяют реальную валидацию EditorViewModel с данными fromRow(), а не только доменные функции.
Сценарии: weekly price; daily name; DAY/WEEK после перехода месяца; CANCELLED/ARCHIVED;
устаревший неизменённый displayedDue; месячный 31/годовой leap29 и отклонение явного anchor32.

18 Android-методов используют настоящие EditorViewModel, SubscriptionUseCases, домен и защищённо открытую Room v5
для отдельного синтетического поколения. Авторизация создания вызывается один раз в setup; при восстановлении
черновика база/READY/UUID не сбрасываются. Каждый успешный save проверяет фактическую сохранённую строку
и всю исходную опору; при отказе — неизменность строки/отсутствие мутации.

| Требуемая регрессия | Android сценарий (скомпилирован, NOT RUN) |
|---|---|
| Weekly, несколько циклов, только цена | weeklyPriceEditAfterSeveralCyclesSavesWithoutReanchor |
| DAY, несколько циклов, только название | dailyNameEditAfterSeveralCyclesSavesWithoutReanchor |
| DAY/WEEK после смены месяца | dailyAndWeeklyPriceEditsAcrossMonthPreserveWholeOriginalAnchor |
| Только оформление | appearanceOnlyEditSavesWithoutRebuildingWeeklySchedule |
| Явная дата → новая серия | explicitDateEditCreatesWholeWeeklySeriesWithoutInheritedDay |
| Явный период → новая серия | frequencyEditCreatesNewDailySeriesWithoutInheritedWeekAnchor |
| Месячный 31 при due=28 февраля | monthly31AtFebruary28IsPreservedByUnrelatedSave |
| Високосная годовая опора | leapYearAnchorIsPreservedByUnrelatedSave |
| Некорректное явное уточнение | explicitInvalidRefinementIsRejectedBeforeMutation |
| CANCELLED/ARCHIVED редактируются без нового графика | cancelledAndArchivedDailyAndWeeklyRecordsSaveOtherFields |
| Восстановленный черновик | restoredDraftUsesOriginalDisplayedDueEvenAfterTodayAdvances |
| Устаревшая дата открытой формы | staleUnchangedDateInOpenDraftDoesNotBlockPriceEdit |
| Новое создание и повторный Save | newSubscriptionStillRequiresValidNewSeriesAndOnlyOneSave |
| Прошлая дата новой серии отклоняется | newSeriesWithPastDateIsRejectedWhileUnchangedDraftMayBeOld |
| Явные MONTH/YEAR уточнения | explicitMonthlyAndAnnualRefinementsRemainAvailable |
| Конкурентный optimistic snapshot | concurrentRowChangeStillRejectsOptimisticSave |
| Смена generation | changedGenerationStillRejectsSaveBeforeMutation |
| Восстановление устаревшего snapshot | restoredSnapshotStillDetectsConcurrentEdit |

### Фактические результаты исправления

Среда: JDK21 / SDK36 / Build Tools35.0.0. Проверки проведены для исходников исправляющего коммита,
а не заимствованы из предыдущего R1b.4.

| Проверка | Результат |
|---|---|
| Исходное воспроизведение новым JVM-классом | 6 выполнено, 5 FAIL до исправления; ожидаемое подтверждение дефекта |
| Повтор того же JVM-класса после исправления | PASS: 6/6 |
| git diff --check | PASS |
| :app:testDebugUnitTest | PASS: **254 выполнено**, 0 failures/errors/skipped; 248 прежних + 6 новых |
| :app:assembleDebug | PASS |
| :app:lintDebug | PASS: **0 ошибок, 8 прежних предупреждений** |
| :app:compileDebugAndroidTestKotlin | PASS: **337 скомпилированных @Test-методов**, 319 прежних + 18 новых; подтверждено аннотациями class-файлов |
| Инструментальное выполнение | **NOT RUN**: ADB недоступен (exit -6), доступное устройство/эмулятор не подтверждено; ограничений не обходили |
| Проверка scope/diff/конфиденциальности | PASS: production diff только EditorViewModel, остальные перечисленные изменения — тесты и отчёт; только синтетические fixtures |

Полная обязательная команда завершилась BUILD SUCCESSFUL:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

### Ограничения и повторная приёмка

Все 18 новых Android-сценариев полного EditorViewModel → запись остаются NOT RUN, как и прежняя инструментальная регрессия.
JVM-проверка воспроизводит и проверяет исправление валидации; её нельзя считать доказательством выполнения Room/Activity
или успешного полного сохранения на устройстве. Требуется запуск подготовленных сценариев на доступном устройстве,
включая восстановление черновика, конфликт снимка и смену поколения.

Исправление готово к повторной независимой статической приёмке с этими ограничениями.
Автор и коммитер используют GitHub noreply. Merge и ручной запуск GitHub Actions не выполнялись.
R1b.5 и R2 не начинались. После обновления существующего Draft PR #5 работа останавливается.
