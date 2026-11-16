# R1b.2 — удаление учёта фактических платежей и возвратов

Реализован только R1b.2: R-BASE-04, R-STAT-06 в согласованных границах подэтапа.
База: `e727a64507b9555e5ef1d40247c7be5e5a1da5c0`, ветка `codex/r1b-02-remove-payment-history`.
R1b.3–R1b.5 не реализованы; целевая модель R1a ещё не полностью подключена к runtime.

## Поведение и компоненты

Удалены `PaymentRepository`, `RefundRepository`, `PaymentCommand`/`PaymentRules`, `RefundRules`,
`PaymentIntent`, платёжная часть `PaymentAttempt`, `PaymentId`/`RefundId`/`OperationId`.
Из `SubscriptionLifecycleService` удалены подтверждения продления, исторические платежи,
предложение даты по фактической оплате, создание проблемного состояния и разрешение проблемы платежом.
Из `SubscriptionUseCases` удалены все команды создания, редактирования, удаления платежей/возвратов,
подтверждения и платёжные подсказки. Скрытых замен, заглушек или автоматической генерации фактов нет.

Удалены `HistoryScreen`, `HistoryViewModel`, `HistorySnapshots`, `SubscriptionHistory`,
`PaymentWithRefunds`, `PaymentHistoryItem`, агрегаты фактических расходов, диалоги и previews истории.
Маршрут `PaymentHistory` не зарегистрирован. Удалены переходы, предпросмотр истории, кнопка оплаты,
выбор будущей цены через оплату и действие создания проблемы. Редактирование остаётся в шапке деталей;
отмена, архив, возобновление и подтверждённое удаление доступны в существующем управлении подпиской.
Строки отменённых сценариев удалены; сохраняемые финансовые показатели подписаны как прогнозы.
Алгоритмы прогнозирования, календаря и напоминаний не изменены.

Общие `toDomain()`/`withDomain()` перенесены без изменения семантики в
`data/mapping/SubscriptionMapping.kt`. Полный снимок всех 22 полей строки вынесен в
`data/mapping/SubscriptionSnapshot.kt`; формат сохранён, добавлена проверка размера и тегов null.

Детали используют `SubscriptionDao.observeDetails()` и `SubscriptionWithReminders`:
одна транзакционная Room relation подписки с напоминаниями. Платежи/возвраты не загружаются
и не являются таблицами инвалидации этого наблюдателя. Сохраняются реактивные edit/state/delete.
Подготовлены инструментальные проверки SQL и согласованности частей relation при конкурентных транзакциях.

`DetailsViewModel` восстанавливает только допустимые действия и полный ожидаемый снимок.
Старые Payment/MarkIssue и неизвестные действия, повреждённый либо чужой снимок очищают только
несовместимое временное состояние формы. Другие ключи SavedStateHandle не удаляются.
Восстановление само не отправляет команду. Повторная отправка во время выполнения блокируется;
при неизвестном исходе снимок и поля заморожены, повтор использует тот же снимок, конфликт требует
явного обновления. Удалённая квитанция/платёжный ключ не восстанавливаются и не создаются.
Проверяется сохранённое поколение до отправки и внутри `owner.perform`; остаются mutation/generation gate
и оптимистическая транзакционная проверка полной строки. CancellationException пробрасывается.

`RestoredNavigation` до `super.onCreate` очищает только записи удалённого маршрута в активном
стеке и сохранённые стеки, содержащие его. Возврат — к существующим деталям/корню. Совместимые
маршруты и черновики сохраняются. Используется точный формат Bundle и serializer ID закреплённой
Navigation 2.9.5, без регистрации скрытого экрана. Подготовлены тесты реального save/restore
NavHostController и сохранённых стеков. Их выполнение на устройстве ещё требуется.

## Хранение, удаление и переходные зависимости

Room остаётся **v4**. Entities, таблицы payments/refunds/retired, поля, ключи, индексы, FK,
конвертеры, миграции 1→2→3→4 и исторические schema snapshots неизменны.
Старые Entity/DAO и enum `PaymentSource` нужны только для совместимости v4, fixtures
и каскадного удаления выбранной подписки; удалить в R1b.4.
Пользовательские сценарии не создают и не редактируют финансовую историю через эти DAO.
Единственный production-доступ к историческим строкам за пределами Room — чтение идентификаторов
в транзакции явного удаления конкретной подписки (`LifecycleRepository.delete`).
Debug-only probe сохраняет счётчик старых строк для регрессии «не создавать платежи».

При подтверждённом удалении сохранены optimistic snapshot, транзакция, каскады напоминаний,
ledger и финансовых строк, сигнал reconciliation, очистка только неиспользуемого изображения.
Общее изображение остаётся до последнего владельца. Как прежде, операция сохраняет технические
`retired_payment_operations` только для удаляемой подписки; это идентификаторы старых операций,
не финансовые факты. Старые `retired_refund_ids` сохраняются, новых пользовательских квитанций нет.
Таблицы и этот совместимый код удаления уходят в R1b.4. Массовой очистки истории нет.

MANUAL, PAYMENT_ISSUE, CANCELLATION_SCHEDULED и разовые даты остаются без массового преобразования
до R1b.4. Существующую PAYMENT_ISSUE можно разрешить только датой (`ResolveWithDate`),
отредактировать, отменить или архивировать, без создания факта оплаты. Создавать новую проблему нельзя.
Просроченная MANUAL может явно задать будущую серию через редактор; до этого её прежнее исключение
из прогноза сохранено. Возобновление, ID, оформление и независимые записи сохраняются.
`autoArchiveExpired` и включённая семантика legacy-конца доступа остаются до R1b.3.
Индивидуальные напоминания и глобальные настройки не менялись.

Защита R1b.1 не изменена: InitializationJournal/одноразовое разрешение создания,
предварительная read-only валидация схемы, безопасное открытие, WAL/SHM, состояния блокировки,
generation/mutation gate, ресурсные leases, background runtime/worker и AssetStore.sweep.
Версия, entities, schema snapshots, миграции, safety-файлы и зависимости проверены по diff к базе.
R-DATA-01/02, сохранение R-LIFE-03/05 и R-SUB-01/05 проверяются применимыми существующими
и адаптированными тестами; полноценная инструментальная приёмка не заявляется.

## Проверки

Среда: JDK 21 / Android SDK 36. Только изолированные синтетические fixtures.

| Проверка | Результат |
|---|---|
| `git diff --check` | PASS |
| `:app:testDebugUnitTest` | PASS: 281 выполнен, 0 failures/errors/skipped |
| `:app:assembleDebug` | PASS |
| `:app:lintDebug` | PASS: 8 warnings, 0 errors |
| `:app:compileDebugAndroidTestKotlin` | PASS |
| `connectedDebugAndroidTest` | NOT RUN: нет подключённого устройства/эмулятора; ADB/KVM не обходились |
| T-SCOPE-01 | Частично: удалённые команды, payload истории, runtime маршрут, строки и ссылки проверены; полная целевая схема/режимы/настройки — R1b.5 |

Предупреждения lint: 6 NewerVersionAvailable, 1 ObsoleteSdkInt, 1 UnusedResources
(welcome_message). Зависимости не обновлялись. GitHub Actions вручную не запускались.

Ни один инструментальный тест не выполнен: **все 318 NOT RUN**, включая миграции, SQL-наблюдение,
конкурентные транзакции, сохранность истории v4, удаление с общими ассетами, уведомления,
восстановление старого маршрута и UI legacy-состояний. Скомпилированные тесты не доказывают
безопасность этих сценариев. Остаются ручная проверка TalkBack, обеих тем, большого шрифта,
реального process death/старого сохранённого стека и переходов из уведомлений на устройстве.
Особый риск — зависимость очистки временной навигации от формата Navigation 2.9.5:
при обновлении библиотеки повторно проверить тесты восстановления.

## Учёт тестов

Счётная единица — метод `@Test`, не класс и не строка тестового плана. Сравнение с исходной базой:
«без изменения» означает неизменное тело/объявление теста (перенос импорта не считается адаптацией);
переименованные и перенесённые проверки сопоставлены с исходными, а не посчитаны добавлением и удалением.

| Набор | До | Без изменения | Адаптировано | Удалено | Добавлено | После |
|---|---:|---:|---:|---:|---:|---:|
| JVM | 323 | 261 | 12 | 50 | 8 | 281 |
| Инструментальные | 350 | 266 | 46 | 38 | 6 | 318 |

Таким образом, сохранено из прежнего набора 273 JVM и 312 инструментальных тестов, включая адаптированные.
Тесты безопасного хранения, миграций, календаря R1a, ассетов и уведомлений не удалялись.
Их финансовые fixtures переведены на прямое заполнение старых таблиц исключительно в тестах.

### Удалённые проверки и замены

#### JVM

- `domain/lifecycle/LifecycleCommandsTest.kt` — 1 удалённых тестов. Подтверждение оплаты с потреблением цикла больше не существует. Остальные переходы, legacy-date recovery, исключение из прогноза, ID и автоархив остаются в LifecycleCommandsTest.
  Удалены: `lifeT07PaymentRestoresActiveAndConsumesOneCycle`.
- `domain/lifecycle/LifecycleTest.kt` — 4 удалённых тестов. Дата от фактической оплаты, платёжный intent и историческое добавление отменены. Календарь/индикатор и неплатёжные lifecycle-переходы сохранены; редактор будущего графика проверяет ForecastEngineTest и SubscriptionUseCasesTest.
  Удалены: `issueCanResolveWithPaymentIntent`, `lifeT03LatePaymentSuggestsActualDatePlusInterval`, `lifeT04SelectedNextDateIsIndependentOfActualPayment`, `lifeT05HistoricalPaymentDoesNotResolveOverdue`.
- `domain/payment/PaymentRulesTest.kt` — 8 удалённых тестов. Полностью отменённые платёжные команды, сериализация их payload, выбор будущей цены при оплате. MoneyTest/денежные проверки R1a, ForecastEngineTest и редактор проверяют точность, переполнение, будущую цену и график.
- `domain/refund/RefundRulesTest.kt` — 9 удалённых тестов. Полностью отменённый сервис возвратов: лимиты/валюта/даты, net expense и переполнение суммы возвратов. Общая денежная валидация/переполнение остаются; SQL rollback, FK/cascade и сохранность старых строк — LegacyRefundStorageTest и TrackerDatabaseTest.
- `ui/details/PaymentAttemptTest.kt` — 9 удалённых тестов. Удалён сбор платёжного payload, платёжные даты, ключ и выбор цены/серии при оплате. Единственный общий round-trip всех полей перенесён в SubscriptionSnapshotTest; добавлена валидация повреждённого снимка. Отправка/восстановление общих действий — DetailsViewModelTest.
  Удалены: `actualFutureDateIsRejected`, `changedAmountRequiresExplicitPriceDecision`, `explicitFutureSeriesAllowsLongOverdueManualPayment`, `foreignCurrencyIsRejected`, `legacyPayloadRemainsByteCompatibleAndSeriesChoiceChangesIdentity`, `manualRequiresNextAfterActualDate`, `ordinaryAutoNeverPassesSelectedNext`, `paymentIssueRequiresNextEvenForAuto`, `restoredSnapshotProducesIdenticalRequestPayload`.
- `ui/history/HistorySnapshotsTest.kt` — 4 удалённых тестов. Снимки и сортировки исключительно финансовой истории, экран которой удалён. Полный снимок самой подписки — SubscriptionSnapshotTest; оптимистические конфликты — DetailsViewModelTest и DetailsOperationsIntegrationTest.
- `ui/history/HistoryTotalsTest.kt` — 4 удалённых тестов. Итоги фактических расходов, возвраты и overflow исторических итогов больше не рассчитываются. Прогноз и его overflow/пустые/ошибочные состояния продолжают проверяться ForecastEngineTest и тестами finance.
- `ui/history/HistoryViewModelTest.kt` — 11 удалённых тестов. ViewModel удалённого экрана: его формы и CRUD, чтение/валидация платежей/возвратов и квитанции повторной отправки не применимы. Общие Loading/Error/NotFound, отмена, двойная отправка, неизвестный исход, сохранённые черновики и stale snapshot сохраняются в DetailsViewModelTest; настройки/редактор имеют свои действующие наборы.

#### Инструментальные

- `data/integration/SubscriptionUseCasesTest.kt` — 3 удалённых тестов. Устранены финансовые use cases, replay платёжной квитанции и ограничения суммы возвратов. Остальные тесты класса сохранены/адаптированы: read-model, optimistic concurrency, SQL rollback, даты, удаление и настройки.
  Удалены: `concurrentDuplicateConfirmationReplaysReceiptAndAdvancesOnlyOnce`, `concurrentPaymentEditAndRefundOrTwoRefundsPreserveLimit`, `restoredPaymentAttemptReplaysOneReceiptAfterDatabaseReopen`.
- `data/refund/RefundRepositoryTest.kt` — 6 удалённых тестов. Отменены CRUD/лимиты/даты/валюты и idempotency пользовательского сервиса возвратов/платежей. Пять проверок сохранности хранения, reopen, каскадов, отката транзакции и старых идентификаторов адаптированы в LegacyRefundStorageTest; не удалены.
  Удалены: `competingRefundsCannotExceedPayment`, `deletedOperationCannotRenewAgainAfterReopenEvenIfScheduleRestored`, `invalidDatesAndFailedEditLeaveStoredHistoryIntact`, `moneyT08T10PartialFullAndExtraUnitAtomic`, `moneyT11T13EditDeleteAndStaleSnapshots`, `uuidRetryConflictMissingParentAndWrongCurrency`.
- `ui/DetailsUiTest.kt` — 5 удалённых тестов. Диалог и подтверждение фактической оплаты, смена будущей цены через оплату, создание новой проблемы и повторная платёжная отправка отменены. Редактор, архив/восстановление, подтверждённое удаление и индикатор доступа сохранены. Recovery существующей проблемы проверяют DetailsEditorRedesignUiTest, DetailsViewModelTest и интеграционные lifecycle/use-case проверки.
  Удалены: `changedActualAmountCanUpdateFuturePrice`, `doubleConfirmationCreatesOnePayment`, `lateManualPaymentCanKeepFuturePrice`, `markIssueThenResolveDateDoesNotCreatePayment`, `paymentOperationAndFieldsSurviveRecreation`.
- `ui/HistoryUiTest.kt` — 8 удалённых тестов. Все пользовательские сценарии удалённого экрана истории и финансовые формы отменены. Сохранность v4/foreign keys/каскады/reopen остаются в Room и LegacyFinancial storage-тестах; старый маршрут и общие черновики — NavigationRestoreTest/DetailsViewModelTest.
- `ui/PaymentsRedesignUiTest.kt` — 16 удалённых тестов. Весь дизайн и поведение удалённых платёжных/возвратных экранов, фактические итоги и их скриншоты больше не являются требованиями. Текущие неплатёжные экраны продолжают проверяться DetailsEditorRedesignUiTest, AccessibilityUiTest и design review; storage/транзакции проверяются отдельно.

### Адаптации и добавления

Адаптированные проверки оставляют применимую часть контракта: финансовые команды заменены
редактированием/архивом/разрешением существующей проблемы датой, старые строки заданы fixtures.
PaymentRepositoryTest → LegacyPaymentStorageTest (3), RefundRepositoryTest → LegacyRefundStorageTest (5),
HistoryIntegrationTest → DetailsOperationsIntegrationTest (3): сохранились конкуренция снимков,
откат, reopen, независимость истории от прогноза, полный снимок после пересоздания и потеря ответа.
Платёжный replay больше не обещается: повтор после уже завершённого неплатёжного изменения
получает конфликт старого снимка, затем требуется явное обновление.

Добавлены следующие проверки (инструментальные только скомпилированы):

- I: `data/assets/OfflineIconCatalogIntegrationTest.kt` — `confirmedDeletionPreservesSharedImageAndOtherHistoryUntilLastOwner`.
- I: `data/integration/SubscriptionUseCasesTest.kt` — `detailsRelationNeverCombinesSubscriptionAndRemindersFromDifferentTransactions`.
- I: `ui/DetailsEditorRedesignUiTest.kt` — `legacyIssueHasOnlyNonFinancialRecoveryAndLegacyModesRemainRenderable`.
- I: `ui/navigation/NavigationRestoreTest.kt` — `cachedRetiredStackIsDroppedWhileOtherStacksAndFormStateRemain`.
- I: `ui/navigation/NavigationRestoreTest.kt` — `compatibleEditorRouteAndDraftRestoreWithoutBeingDiscarded`.
- I: `ui/navigation/NavigationRestoreTest.kt` — `oldActiveHistoryRouteRestoresDetailsAndItsDraftWithNoHistoryDestination`.
- JVM: `ui/details/DetailsViewModelTest.kt` — `cancellationDoesNotBecomeUnknownOutcomeErrorOrTriggerRetry`.
- JVM: `ui/details/DetailsViewModelTest.kt` — `compatibleDraftRetainsCompleteExpectedSnapshotAndDoesNotSubmitOnRestore`.
- JVM: `ui/details/DetailsViewModelTest.kt` — `legacyModesAndStatesLoadAndOnlyExistingIssueCanBeResolved`.
- JVM: `ui/details/DetailsViewModelTest.kt` — `malformedOrWrongSubscriptionSnapshotCannotRestoreAnAction`.
- JVM: `ui/details/DetailsViewModelTest.kt` — `removedAndUnknownSavedActionsAreDiscardedWithoutExecutionOrClearingOtherState`.
- JVM: `ui/details/DetailsViewModelTest.kt` — `restoredGenerationMismatchBlocksMutationBeforePerform`.
- JVM: `ui/details/NonFinancialContractTest.kt` — `applicationContractHasNoFinancialCommandsOrHistoryPayload`.
- JVM: `ui/details/SubscriptionSnapshotTest.kt` — `malformedSnapshotsAreRejectedInsteadOfDroppingColumnsOrGuessingNulls`.

### Перечень всех адаптированных тестов

- I: `app/DatasetCompositionTest.kt:projectionIgnoresHistoryAndUpdatesFuturePriceAndSchedule`.
- I: `app/DatasetCompositionTest.kt:startupAndResumeRefreshCalendarWithoutCreatingPayments`.
- I: `background/ReminderInfrastructureTest.kt:productionUseCasesSignalLifecycleChangesButNotThemeOrChartSettings`.
- I: `data/integration/SubscriptionMigrationIntegrationTest.kt:legacyChainsKeepHistoryAndNewRetiredKeysSurviveReopen`.
- I: `data/integration/SubscriptionUseCasesTest.kt:allPaymentAndRefundUseCasesPreserveRetiredKeysAcrossReopen` → `data/integration/SubscriptionUseCasesTest.kt:legacyRowsAndSettingsSurviveReopeningWithoutFinancialUseCases`.
- I: `data/integration/SubscriptionUseCasesTest.kt:cancellationLastDayReactivationAndDeletionPreserveIndependentSubscription`.
- I: `data/integration/SubscriptionUseCasesTest.kt:competingDeleteAndReplayOrStaleReactivationNeverCreateSecondCycle` → `data/integration/SubscriptionUseCasesTest.kt:staleSnapshotsCannotDeleteOrArchiveAfterReactivation`.
- I: `data/integration/SubscriptionUseCasesTest.kt:concurrentConfirmationWithDateEditOrArchiveIsControlled` → `data/integration/SubscriptionUseCasesTest.kt:concurrentDateEditOrArchiveUsesFullExpectedSnapshot`.
- I: `data/integration/SubscriptionUseCasesTest.kt:dateRefreshManualIssueCancellationAndReactivation`.
- I: `data/integration/SubscriptionUseCasesTest.kt:explicitPriceChoicesAffectOnlySelectedFuturePrice` → `data/integration/SubscriptionUseCasesTest.kt:editorPriceEditLeavesOtherPlanAndLegacyHistoryUntouched`.
- I: `data/integration/SubscriptionUseCasesTest.kt:fullSnapshotRejectsStalePriceAndSameScheduleAfterReactivation`.
- I: `data/integration/SubscriptionUseCasesTest.kt:issueDateAndIssuePaymentHaveDistinctFinancialEffects` → `data/integration/SubscriptionUseCasesTest.kt:existingIssueCanResolveByDateWithoutCreatingPaymentOrChangingPrice`.
- I: `data/integration/SubscriptionUseCasesTest.kt:issuePaymentAndSubscriptionDeletionCannotBypassReceipts` → `data/integration/SubscriptionUseCasesTest.kt:confirmedSubscriptionDeletionRetiresOnlyItsExistingOperations`.
- I: `data/integration/SubscriptionUseCasesTest.kt:longOverdueExplicitSeriesHasOneReceiptAndGuardedPayload` → `data/integration/SubscriptionUseCasesTest.kt:longOverdueManualCanUseExplicitEditorSeriesWithoutPayment`.
- I: `data/integration/SubscriptionUseCasesTest.kt:realRoomFlowsUpdateDetailsButHistoryDoesNotEmitForecast` → `data/integration/SubscriptionUseCasesTest.kt:realRoomFlowsUpdateDetailsWithoutReadingOrObservingFinancialRows`.
- I: `data/integration/SubscriptionUseCasesTest.kt:sqlFailureThroughUseCaseRollsBackPaymentAndLifecycle` → `data/integration/SubscriptionUseCasesTest.kt:sqlFailureThroughUseCaseRollsBackLifecycleAndRetainsLegacyRows`.
- I: `data/lifecycle/LifecycleRepositoryTest.kt:failedPaymentRecoveryRollsBackStateAndHistory` → `data/lifecycle/LifecycleRepositoryTest.kt:failedLegacyDateResolutionRollsBackStateAndHistory`.
- I: `data/lifecycle/LifecycleRepositoryTest.kt:lifeT06T07IssuePaymentAtomicIdempotentAndConflictingRepeatRejected` → `data/lifecycle/LifecycleRepositoryTest.kt:existingIssueResolutionIsAtomicAndConcurrentStaleCommandsAreRejected`.
- I: `data/lifecycle/LifecycleRepositoryTest.kt:lifeT08T09ThreeResolutionPathsKeepHistoryForNonPaymentActions`.
- I: `data/lifecycle/LifecycleRepositoryTest.kt:lifeT17ConcurrentCommandsAndStaleDeletionCannotOverwrite`.
- I: `data/payment/PaymentRepositoryTest.kt:dbT05InjectedSqlFailureRollsBackPaymentAndSubscription` → `data/payment/LegacyPaymentStorageTest.kt:injectedLifecycleFailureRollsBackSubscriptionAndPreservesExistingPayment`.
- I: `data/payment/PaymentRepositoryTest.kt:historyReopensAndDoesNotChangeScheduleOrCurrency` → `data/payment/LegacyPaymentStorageTest.kt:historyReopensAndDoesNotChangeScheduleOrCurrency`.
- I: `data/payment/PaymentRepositoryTest.kt:moneyT15DbT04ConcurrentRepeatReturnsSameReceiptAndConflictingRetryFails` → `data/payment/LegacyPaymentStorageTest.kt:concurrentLifecycleCommandsCommitOneExpectedSnapshotAndPreserveLegacyPayment`.
- I: `data/refund/RefundRepositoryTest.kt:historyChangesNeverChangeSubscriptionOrForecast` → `data/refund/LegacyRefundStorageTest.kt:historyChangesNeverChangeSubscriptionOrForecast`.
- I: `data/refund/RefundRepositoryTest.kt:monetaryHistorySurvivesReopen` → `data/refund/LegacyRefundStorageTest.kt:monetaryHistorySurvivesReopen`.
- I: `data/refund/RefundRepositoryTest.kt:moneyT14DeleteCascadeConfirmationAndOtherRecords` → `data/refund/LegacyRefundStorageTest.kt:subscriptionDeletionCascadeRequiresConfirmationAndPreservesOtherFinancialFixtures`.
- I: `data/refund/RefundRepositoryTest.kt:sqlFailureRollsBackDeletionAndTombstone` → `data/refund/LegacyRefundStorageTest.kt:sqlFailureRollsBackSubscriptionDeletionCascadeAndTombstone`.
- I: `data/refund/RefundRepositoryTest.kt:subscriptionCascadeAlsoRetiresOperationIds` → `data/refund/LegacyRefundStorageTest.kt:subscriptionCascadeAlsoRetiresOperationIds`.
- I: `notifications/ReminderSystemRegressionTest.kt:confirmedPaymentWhileWorkerWaitsInvalidatesOldReminderWithoutDoublePayment` → `notifications/ReminderSystemRegressionTest.kt:explicitDateEditWhileWorkerWaitsInvalidatesOldReminderWithoutPayment`.
- I: `notifications/ReminderSystemRegressionTest.kt:lifecycleWritesRemoveAndRestoreOnlyEligiblePendingReasons`.
- I: `ui/AccessibilityUiTest.kt:enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable`.
- I: `ui/ArchiveSettingsRedesignUiTest.kt:aboutShowsVersionAndDataInfoWithoutFakeBackupActionOrIsland`.
- I: `ui/ArchiveSettingsUiTest.kt:archiveReactivationPreservesUuidHistoryAndUpdatesActiveForecast` → `ui/ArchiveSettingsUiTest.kt:archiveReactivationPreservesUuidAndUpdatesActiveForecast`.
- I: `ui/DetailsEditorRedesignUiTest.kt:managementIsSeparateAndDispatchesExistingActions`.
- I: `ui/DetailsEditorRedesignUiTest.kt:progressOperationBlocksBackEditAndPayment`.
- I: `ui/DetailsUiTest.kt:archiveProvidesMinimalPathBackToDetailsAndReactivation`.
- I: `ui/DetailsUiTest.kt:detailsShowRealFieldsAndPaymentRequiresExplicitConfirmation` → `ui/DetailsUiTest.kt:detailsShowRealFieldsWithEditAndWithoutPaymentOrHistoryActions`.
- I: `ui/SubscriptionsRedesignUiTest.kt:decisionsExplainProblemsAndAwaitingHasAnEmptyTrack`.
- I: `ui/SubscriptionsUiTest.kt:decisionsAreSeparateAndNotDuplicatedInActiveList`.
- I: `ui/finance/FinanceIntegrationTest.kt:liveEditsDateAndLifecycleUpdateForecastWhileHistoryAndRefundsDoNot`.
- I: `ui/history/HistoryIntegrationTest.kt:committedPaymentAndRefundWithLostResponseReplayOnceAfterRestoringViewModel` → `ui/details/DetailsOperationsIntegrationTest.kt:lostArchiveResponseRestoresExpectedSnapshotWithoutAutomaticResubmission`.
- I: `ui/history/HistoryIntegrationTest.kt:completeSavedSnapshotsRejectConcurrentEditsAndRetiredKeysSurviveReopen` → `ui/details/DetailsOperationsIntegrationTest.kt:completeSavedSubscriptionSnapshotRejectsConcurrentEditAfterReopen`.
- I: `ui/history/HistoryIntegrationTest.kt:historyCrudUpdatesLiveDetailsWithoutChangingForecastStatusOrOtherSubscription` → `ui/details/DetailsOperationsIntegrationTest.kt:liveLifecycleDetailsPreserveOtherSubscriptionAndLegacyRows`.
- I: `ui/review/CrossScreenDesignUiTest.kt:galleryAllCurrentScreensDark`.
- I: `ui/review/LargeFontWindowUiTest.kt:landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont`.
- I: `ui/settings/ArchiveSettingsIntegrationTest.kt:archiveProjectionReactivationPreservesHistoryAndUuidAndRecomputesForecast`.
- JVM: `domain/CalendarTypesTest.kt:identifiersRejectMalformedAndAbbreviatedUuids`.
- JVM: `domain/CalendarTypesTest.kt:identifiersRoundTripCanonicalUuidsWithSeparateTypes`.
- JVM: `domain/forecast/ForecastEngineTest.kt:fcT07ManualProducesExactlyOneUntilExplicitConfirmation` → `domain/forecast/ForecastEngineTest.kt:fcT07ManualProducesExactlyOneUntilExplicitScheduleEdit`.
- JVM: `domain/forecast/ForecastEngineTest.kt:fcT09HistoryIsNotAnInputAndAutoDoesNotCreatePayments` → `domain/forecast/ForecastEngineTest.kt:fcT09ForecastLookupNeverMutatesScheduleOrState`.
- JVM: `domain/lifecycle/LifecycleCommandsTest.kt:lifeT06MarkIssueRequiresAutoAndExcludesForecast` → `domain/lifecycle/LifecycleCommandsTest.kt:existingIssueIsExcludedFromForecastWithoutCreatingAnotherIssue`.
- JVM: `domain/lifecycle/LifecycleCommandsTest.kt:lifeT08T09DateOrArchiveResolvesIssueWithoutPaymentCommand`.
- JVM: `domain/lifecycle/LifecycleTest.kt:invalidTransitionsFail`.
- JVM: `domain/lifecycle/LifecycleTest.kt:issueIsExplicitAndResolvedByDateWithoutPayment`.
- JVM: `domain/lifecycle/LifecycleTest.kt:lifeT01AutoAdvancesExpectedDateWithoutPaymentOrMutation`.
- JVM: `ui/details/DetailsViewModelTest.kt:repeatedSubmitWhileBusyStartsOneOperation`.
- JVM: `ui/details/DetailsViewModelTest.kt:uncertainOutcomePreservesKeyPayloadAndBlocksMutationAcrossRecreation`.
- JVM: `ui/details/PaymentAttemptTest.kt:snapshotRoundTripPreservesEveryColumnIncludingNullableValues` → `ui/details/SubscriptionSnapshotTest.kt:snapshotRoundTripPreservesEveryColumnIncludingNullableValues`.

## Изменённые файлы

Пути относительно корня репозитория, включая удалённые файлы:

```text
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/app/DatasetCompositionTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderInfrastructureTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/assets/OfflineIconCatalogIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionMigrationIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCasesTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/lifecycle/LifecycleRepositoryTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/payment/LegacyPaymentStorageTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/payment/PaymentRepositoryTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/refund/LegacyRefundStorageTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/refund/RefundRepositoryTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/fixtures/LegacyFinancialRows.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/ReminderSystemRegressionTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/AccessibilityUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsEditorRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/HistoryUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/PaymentsRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsOperationsIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/LargeFontChartUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/MonthlyChartUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/history/HistoryIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/navigation/NavigationRestoreTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/CrossScreenDesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/LargeFontWindowUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/settings/ArchiveSettingsIntegrationTest.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/app/ReminderSystemProbeReceiver.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/design/TrackerDesignPreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/DetailsEditorPreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/FinancePreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/PaymentsPreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/review/DesignReviewGallery.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/app/MainActivity.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/ReminderInputs.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionEditor.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCases.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/lifecycle/LifecycleRepository.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/mapping/SubscriptionMapping.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/mapping/SubscriptionSnapshot.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/payment/PaymentRepository.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/refund/RefundRepository.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/Daos.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/SubscriptionHistory.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/SubscriptionRelations.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleCommands.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/SubscriptionLifecycle.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/model/Identifiers.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/payment/PaymentCommand.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/refund/RefundRules.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/archive/ArchiveViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/details/PaymentAttempt.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/history/HistoryScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/history/HistorySnapshots.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/history/HistoryViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/navigation/RestoredNavigation.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/navigation/TrackerApp.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/subscriptions/SubscriptionsUiState.kt
app/src/main/res/values/strings.xml
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/CalendarTypesTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/forecast/ForecastEngineTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleCommandsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/payment/PaymentRulesTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/refund/RefundRulesTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/NonFinancialContractTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/PaymentAttemptTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/SubscriptionSnapshotTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/history/HistorySnapshotsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/history/HistoryTotalsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/history/HistoryViewModelTest.kt
docs/reports/R1b.2.md
```
