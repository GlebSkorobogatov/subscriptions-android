# R2b — окончательный алгоритм напоминаний о продлении

## Ревизия и область

Исходная ветка `codex/r2a-calendar-forecast`, принятый SHA `9ec966d5ce3600441bb17c8d6099b0c165603ba9`, Draft PR #7.
HEAD проверен до изменений, дерево было чистым. Ветка `codex/r2b-renewal-reminders` создана непосредственно от принятого коммита.
Итоговый SHA проверенной реализации: 4be9cd3575cceb7e5bc34cd1695dc57f5139de6d. Отчёт добавляется отдельным документационным коммитом поверх этой реализации; окончательный tip указан в stacked Draft PR и сообщении владельцу.

Изучены AGENTS, PRD, ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_PLAN, UI_DESIGN_SPEC, DECISIONS,
материалы PR #1/#2 и отчёты R1b.2–R2a с исправлениями. Приоритет фиксированного 09:00 перед прежними упоминаниями выбора времени сохранён.
Требования не редактировались. Scope — R2b; R3, R6a/R6b и H не выполнялись.

## Инвентаризация и сохранённая инфраструктура

| Компонент | До R2b / действие |
|---|---|
| ReminderRules / ReminderSubscription / PlannedReminder / ReminderKey | Только ближайший due, глобальные offset; расширено перечисление через существующий DefaultRenewalCalendar. |
| ReminderMessage / CurrentReminderReason / ReminderText | Группировка ключей уже существовала; сохранена и проверена для разных due, строки упорядочены по due. |
| ReminderLedger / NotificationDeliveryDao | Транзакции, устойчивые ключи, claims/lease/retry уже существовали. Добавлены ограниченные календарные запросы и cleanup служебного состояния. |
| ReminderScheduling / ReminderWork / ReminderRuntime | Один delivery slot на подписку, unique work, fingerprints, apply gate, wake, safe-open и final freshness уже существовали. Сохранены; изменена только policy и fingerprint namespace. |
| ReminderChanges / ReminderInputs | Сохранены mutation gate, уведомление после commit и единый mapping SubscriptionRecord + глобальные offset. Ошибки чтения/mapping не превращены в пустые данные. |
| AndroidReminderPublisher | Сохранены приватность, канал, OS availability, idempotent identity, группировка, dismissal и reconcile показанных сообщений. Увеличен обоснованный предел metadata и ограничен её размер. |
| NotificationRoute / NotificationViewModel / NotificationEnvironment | Сохранены immutable PendingIntent, generation-aware маршрут, missing → home, повторные нажатия/Back, contextual permission и фактический статус ОС. Eligibility предложения разрешения отделён от семидневного окна. |
| SettingsRepository | {3,1} по умолчанию, допустимы {7,3,1,0}, explicit empty сохраняется, время 09:00. Хранилище настроек не изменено. |
| System entry points | Startup/resume, foreground DATE_CHANGED, TIME_SET/TIMEZONE_CHANGED, WorkManager boot rescheduling и ежедневный wake сохранены. Работа не требует Activity. |

Нет обхода TrackerDatabaseFactory: runtime по-прежнему открывает защищённую сессию под generation/mutation gates,
освобождает Room и DataStore lease в finally/NonCancellable. Ошибка открытия наступает до доступа к settings/ledger/publisher.
Реальный reboot, системные receivers и платформенные гарантии оцениваются отдельно от статического просмотра.

## Итоговый алгоритм и границы

1. Из одного успешно прочитанного Room snapshot формируются ReminderSubscription. Единственный критерий участия —
   `SubscriptionRecord.participatesInCalculations`; CANCELLED/ARCHIVED исключены.
2. Для локальной даты `D` верхняя граница дат напоминаний — `D+7`, исключительно.
   Для максимального выбранного offset `M` календарь перечисляет due в `[D, D+7+M)`.
   При всех offset это `[D,D+14)`. Затем применяется фактический фильтр `reminderDate < D+7`.
3. Каждому due соответствуют отдельные ключи `due-offset` в 09:00. Новый plan сохраняет только
   моменты `resolveLocal(localDateTime, currentZone) >= now`: предыдущие напоминания не изобретаются.
   Будущих work за пределами окна нет. Для recovery рассматриваются только уже сохранённые ключи.
4. Shared DefaultRenewalCalendar выполняет bounded lookup от исходной опоры; история циклов не перебирается.
   DAY/WEEK/MONTH/YEAR, 31-е число, leap day, квартал и полугодие используют тот же календарь.
5. В Room сохраняется ограниченный набор планов; runtime группирует его по UUID и оставляет один ближайший
   work slot на подписку плюс общий wake. После публикации/изменений очередь пересогласовывается.
6. Проверка соответствия календарю использует ту же ограниченную проекцию. Set текущих событий строится один раз
   на reconciliation, вместо пересчёта всех подписок для каждого ledger row.

Пример: 16.11.2026 09:00 UTC, ежедневная серия уже началась, offset {7,3,1,0}.
Новый план содержит 28 ключей (7 дат напоминаний × 4 offset), reminder dates 16–22 ноября,
максимальный due 29 ноября. В 09:00 16 ноября одно сообщение содержит due 16, 17, 19 и 23 ноября:
«сегодня», «завтра», «через 3 дня», «через 7 дней». Это четыре независимых logical key, а не четыре уведомления.
Для недельной серии с первым due 16 ноября в окне также учитывается due 23 ноября с offset 7/3/1.
До первой даты серии due не генерируются; напоминание перед первым due допустимо по выбранному offset.

### Восстановление пропущенного

Детерминированное правило: взять только ранее сохранённые, ещё не завершённые, соответствующие текущей серии
и разрешённым offset события с due >= локального today и plannedInstant <= now. Из доступных по retry ключей
сформировать **одно сообщение на подписку**, все причины упорядочить по due (ближайший первым).
Несколько offset одного due объединяются в одну строку; текст использует фактический остаток дней.
Штатная доставка в 09:00 объединяет одновременно наступившие ключи; восстановление дополнительно объединяет
актуальные ключи более ранних дат напоминаний. Новых прошлых ключей reconciliation не создаёт.

После PUBLISHED показанные ключи получают DELIVERED. Другие уже наступившие актуальные ключи этой подписки,
которые не вошли в публикацию из-за backoff/exhausted retry, завершаются SKIPPED без deliveredAt.
Это предотвращает последующую догоняющую серию; будущие ключи, live claims и отключённые offset не подавляются.
При UNAVAILABLE/RETRYABLE_FAILURE подавления нет. Истёкшие due не показываются.
Повторный reconciliation не сбрасывает завершённые ключи. Новый будущий повод может дать новое сообщение.

### Размер сообщения

При одном событии серии в календарный день и due >= today число уже наступивших ключей ограничено:
`(7+1)+(3+1)+(1+1)+(0+1) = 15`. Различных due максимум 8 (today…today+7).
Это ограничение следует из частоты и фиксированного набора offset, а не из количества подписок.
Прежний cap=8 ключей был недостаточен для recovery; теперь publisher и reader принимают до 15.
Все ключи сохраняются в metadata, без take()/усечения. UTF-8 encoded events ограничены 4096 байтами;
некорректный/превышенный payload получает PERMANENT_SKIP, без notify и ложного DELIVERED.
Presentation signature и signature состава summary заменены SHA-256, чтобы не накапливать неограниченные строки
в служебных extras. BigText содержит все различимые due; существующий компактный preview summary и общий счётчик сохранены.
WorkManager Data остаётся маленьким control payload (generation/sub/fingerprint), без массива событий.

## Дедупликация, WorkManager и показанные уведомления

Логический ключ v1, UUID row, payload version=1, generation, notification identity/tag и существующий канал
`subscription_payments_v1` **не изменены**. Название канала — «Продления»; техническое старое имя сохраняет настройки ОС.
Policy WorkManager повышена с 2 до 3; fingerprint namespace также 3. Это необходимо для нейтрализации ранее
запланированных jobs, предполагавших только ближайший цикл. Политики 0–2 только запрашивают актуальный reconcile
и завершаются без открытия базы/claim/publish; неизвестная будущая policy отклоняется.
Scheduler отменяет только устаревшие jobs с собственным ROOT tag, unrelated work сохраняется.

Сохранены persistent unique ledger key, atomic claim, lease 5 минут, максимум 3 retryable publication failures,
backoff 30/60/120 секунд, recovery истёкшей lease, проверка fingerprint, generation после получения gate
и непосредственно перед публикацией. Publish заново читает состояние, расписание, offset, цену и имя внутри
Room transaction. Отмена/архив/удаление и изменение серии не разрешают старую публикацию.
UNAVAILABLE не означает доставку: OS access учитывается при выборе следующего слота, permission refresh/wake/start
может пересогласовать очередь. PERMANENT_SKIP завершается явно; ретраи не бесконечны.

Reconcile показанных сообщений проверяет generation, день, текущие параметры и все события metadata.
Старая цена/имя, отключённые offset, отмена/архив/удаление убирают неактуальное уведомление.
Переход на hashed presentation также безопасно снимает старую несовпадающую metadata, не сбрасывая ledger.
Удалённое пользователем сообщение не восстанавливается без нового ключа: DELIVERED не очищается.
Сохраняются child/group dismissal, PRIVATE public version, существующий канал и immutable PendingIntent.

Exactly-once не заявляется: notify и подтверждение Room не атомарны. При сбое между ними возможен повтор API-вызова;
устойчивая identity обновляет тот же notification, а lease/ledger минимизируют повторы. Истечение lease не доказывает,
что ОС ничего не получила.

## Очистка ledger и откат даты

**Строки логических ключей не удаляются автоматически.** Без долговременного watermark/tombstone в новой схеме
нельзя доказать безопасность удаления завершённого ключа при произвольном переводе даты назад.
Выбран разрешённый заданием консервативный вариант: сохранить минимальные ключи дедупликации в существующей v5,
очищать только служебное состояние и завершать истёкшие попытки.

| Запись | Политика |
|---|---|
| PENDING, due актуален/в будущем | Сохраняется, в том числе временно отключённый offset/неактуальная серия. Может стать допустимым снова. |
| PROCESSING, live lease | Не очищается; reservation на подписку проверяется независимо от текущего календарного окна. |
| Истёкшая lease при актуальном due | Существующий claim recovery/retry, без предположения об отсутствии прошлой публикации. |
| PENDING/PROCESSING/exhausted retries, due < today и нет будущей lease | SKIPPED, без deliveredAt; claim/lease/retry metadata очищается. Ключ остаётся. |
| DELIVERED/SKIPPED, due < today | Статус, ключ, время фактического ack сохраняются; остаточные claim/lease/failureCount/notBefore обнуляются. |
| Другое generation | Не затрагивается текущим cleanup и не планируется. Будущий H должен отдельно согласовать retention. |
| Удалённая подписка | Существующий подтверждённый transactional delete + FK CASCADE удаляет её ledger; это не массовая очистка. |

Одна партия **до 256** выбранных строк на reconciliation, без цикла drain. Временные значения после SQL prefilter
обязательно сравниваются как Instant: ISO-текст с разной дробной частью не является надёжной точной проверкой lease.
Ранее завершённые чистые строки больше не попадают в cleanup. Количество возвращаемых обработанных строк учитывает
фактический Instant guard. Live claims не удаляются и не переводятся в завершённые состояния.

При откате даты DELIVERED/SKIPPED вновь попадают в календарное чтение, подавляя повторную доставку.
Даже SKIPPED из-за прежнего скачка даты вперёд не восстанавливается автоматически: предпочтение отсутствию повторов.
Рабочее чтение ledger ограничено диапазоном due `[today,today+14)`, release/publish ищут точный claim token;
вся история не материализуется на каждом reconcile. Запросы могут сканировать таблицу: нового индекса без изменения
DDL не добавлялось. Размер writes ограничен, но рост числа дедуп-строк за срок жизни подписок не ограничен.
Физическое ограничение роста потребует отдельно согласованной схемы долговременных tombstones/watermark и политики rollback;
в этом этапе такой рискованный механизм не введён и изменение Room не запрашивается.

## Изоляция ошибок, системное время и разрешения

ReminderCandidates возвращает events и failedSubscriptions. Ошибка календаря/арифметики одной серии
полностью исключает её события из проекции, не скрывая корректные серии. Дата не выдумывается.
Поле/ID проблемной записи остаются доступны через существующий UI R2a-fix.
Room read/mapping/open ошибки находятся за пределами локального calculation catch и блокируют работу;
CancellationException не перехватывается как обычная ошибка расчёта/публикации.
ForecastEngine не вызывается напоминаниями: финансовый overflow никак не участвует в их планировании.

Даты due остаются LocalDate. При timezone change меняются рассчитанные Instant/fingerprint/WorkManager delay,
а не due/ключи/Room опора. Сохранён resolver: gap → первое допустимое время после gap, overlap → более ранний offset.
Штатное время 09:00; технические DST-тесты resolver не возвращают пользовательский выбор времени.
Wake в 00:05 следующего местного дня пополняет семидневное окно; time/zone repair сначала сохраняет reconcile request,
затем заменяет только собственные monotonic delivery delays. WorkManager best effort, точная минута не обещается.

Состояние ОС (runtime permission, app notifications, importance канала) остаётся авторитетным.
Пустой startup не запрашивает разрешение; contextual offer выполняется только после подходящего save и один раз
по существующему долговременному claim. Eligibility теперь не зависит от наличия события именно на этой неделе:
первая ежегодная подписка с будущим due также подходит. Отказ не мешает сохранению; предусмотрены system settings.
Навигация проверяет canonical URI, generation и существование записи, не выполняет команды из payload;
несуществующая/недоступная запись ведёт на главный экран. Реальное поведение ОС требует device-приёмки.

## Тесты и воспроизведение

До изменения ReminderRules запущен новый RollingReminderTest: **10 JVM-тестов, 7 FAIL**.
Подтверждены потеря коротких циклов, неверный horizon и потеря recovery разных due. Это диагностический прогон,
а не итоговый PASS. Далее добавлены регрессии и выполнены обязательные проверки.

- RollingReminderTest: 19 новых JVM — все 4 offset, 28 daily events/4 одновременных причины, weekly/custom,
  границы окна/due за пределами, initial after 09:00, начало серии, persisted recovery, long absence,
  inactive/empty, date rollback/offset toggle, точный предел 15, bad series isolation, financial overflow,
  permission far future, rename/appearance/UUID identity, explicit schedule change, zone и 100-sub stress,
  квартал/полугодие/високосный день.
- NotificationRulesTest: 1 новый JVM — 4 разных due в одном тексте, реальные дни, даты и сортировка.
- ReminderRulesTest: все 20 прежних сохранены. Fixture теперь начинает plan в пределах семидневного окна;
  monthly/leap/custom проверки перенесены к проверяемой границе, не ослабляя проверки опоры.
  Проверка underflow адаптирована к требованию изоляции одной серии, глобальный Instant.MAX по-прежнему ошибка.
- ReminderInputsTest: все 3 сохранены; дата fixture сдвинута внутрь окна. Settings и R1/R2a регрессии сохранены.
- ReminderInfrastructureTest: 13 новых Android — 28 keys/1 task, simultaneous ack, long recovery/no catchup,
  initial late save, SKIPPED unshown retry, rollback, batches/live claims, expired uncertain claim,
  foreign generation, fresh overlapping price, bad calendar + financial overflow, 100/5000 stress,
  fractional Instant lease. Прежние сценарии concurrent claims/lease/retry/publisher/generation/cancel/archive/delete
  и unavailable storage не удалены.
- Две прежние Android-проверки адаптированы: expired due теперь SKIPPED вместо PENDING; тест DataStore lease
  проверяет и единственный актуальный offset=0, и сохранность ранее созданных ключей {3,1}.
  Прежняя проверка только {0} во всей таблице противоречила сохранению дедуп-ключей; новой проверкой это явно запрещено.
- ReminderWorkManagerTest: существующий legacy worker test дополнен policy=2, с сохранением проверки отсутствия
  открытия/публикации. Проверки unrelated work/unknown payload/clock repair и долговечной очереди сохранены.
- NotificationPublisherTest: 2 новых Android — реальные 15 keys в extras/8 строк, ack/dismiss/no resurrection;
  oversized 16-key payload получает PERMANENT_SKIP без усечения/notify.

Тесты не удалены. Итоговая численная инвентаризация и результаты — ниже.

## Стресс и фактические проверки

Среда: JDK 21.0.9, Android SDK 36, Build Tools 35.0.0. Финальный обязательный прогон:

```bash
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

| Проверка | Итог |
|---|---|
| git diff --check, включая diff от принятой базы | PASS |
| Полная команда Gradle | PASS, BUILD SUCCESSFUL, 2 мин 29 с |
| testDebugUnitTest | PASS: **310 выполнено**, 0 failures/errors/skipped |
| assembleDebug | PASS, debug APK собран |
| lintDebug | PASS: **0 ошибок, 8 прежних предупреждений**, новых нет |
| compileDebugAndroidTestKotlin | PASS: **376 @Test-методов** проверено по аннотациям class files |
| Android execution / connectedDebugAndroidTest | **NOT RUN, 0 выполнено**: устройства/эмулятора нет, adb devices завершился −6; ограничения не обходились |
| APK/schema/source аудит | PASS (статический): удалённых моделей/состояний нет, packaged exports 1–5 совпадают с source, все 5 JSON побайтово совпадают с принятой базой |

Точный учёт: JVM 290 прежних сохранены + 20 новых = 310, удалено 0.
Тела 4 прежних JVM-методов адаптированы (monthly/leap/custom/underflow); у ReminderRulesTest и ReminderInputsTest
также сдвинуты общие даты fixtures внутрь нового окна. Android 361 прежних сохранён + 15 новых = 376,
удалено 0; адаптированы тела 3 прежних методов (expired state, retained offsets и legacy policy).
Добавление новых методов в конец класса не считается изменением тела предшествующего теста.

Финальный синтетический JVM замер: **2800 событий / 100 групп заданий**, два полных расчёта — **141,207887 мс**.
Время зависит от облачной JVM/прогрева; это измерение конкретного прогона, не норматив устройства.
Предыдущий промежуточный замер 153,355044 мс не выдаётся за Android-производительность.

Для JVM stress измеряются два полных расчёта (включая сортировку), не Android WorkManager/Room/notify.
Группа задач — результат группировки по подписке, не выполненный enqueue на устройстве.
Android stress подготовлен: 100 daily × 4 offset × 7 дат = 2800 релевантных ключей, 100 delivery tasks,
5000 истёкших fixture rows, ожидаемый общий ledger 7800; два reconcile должны компактировать 512 строк
двумя партиями по 256, не удаляя ключи. **NOT RUN**: фактическое время Android/размер установленной БД не измерены.

## Матрица требований и проверок

| ID | Компонент / связанные тесты | Фактический результат |
|---|---|---|
| R-NOT-01 / T-NOT-01 | SettingsTest, ReminderInputsTest, ReminderRulesTest, RollingReminderTest | PASS JVM: global defaults/empty/fixed time/all cycles/overlaps. Room/settings reopening — NOT RUN Android. |
| R-NOT-02 / T-NOT-02 | ReminderChanges, Runtime, Scheduling, Work; Infrastructure/SystemRegression/WorkManager | PASS JVM для календаря/идентичности/gate ordering; PASS статический аудит точек reconcile. Нативный apply/редактирование/OS triggers — NOT RUN. |
| R-NOT-03 / T-NOT-03 | Ledger, DAO, publisher, Runtime; Infrastructure/Publisher/WorkManager | PASS JVM для ключей/handled/recovery/copy; claims/lease/ack/retry/cleanup/native metadata — NOT RUN, скомпилированы. Exactly-once не заявляется. |
| R-NOT-02/03 / T-NOT-04 | dueMessages, wake, time receiver; Rolling/Scheduling/SystemRegression | PASS JVM для skipped/current reasons/long absence/zone/DST; реальный reboot/выключение/clock repair — NOT RUN. |
| R-NOT-04 / T-NOT-05 | NotificationEnvironment/ViewModel, access checks; NotificationViewModelTest, NotificationPermissionUiTest, Publisher | PASS JVM для UI state/offer/status и новой far-future eligibility; реальные grant/deny/канал/API31/33+ — NOT RUN. |
| R-NOT-02/03 / T-NOT-06 | NotificationRoute, NotificationViewModel, immutable PI; Navigation/Publisher/SystemRegression | PASS JVM strict route/state; нажатие нативного уведомления/Back/device battery — NOT RUN. |
| R-CAL-01…03 | Shared DefaultRenewalCalendar + ReminderRules; Calendar/Subscription/Rolling | PASS JVM; календарный движок не изменён. Реальная смена системного времени — NOT RUN. |
| R-LIFE-01…05 | participatesInCalculations, final freshness; Domain/Lifecycle/Infrastructure | PASS JVM, API/state сохранены; полная Room→worker lifecycle цепочка — NOT RUN. |
| R-DATA-01/02, T-DATA-01/02 | TrackerDatabaseFactory/preflight/journal/gates/leases | PASS статический аудит неизменности, применимые JVM PASS; нативная сохранность SQLite/WAL/power-loss — NOT RUN (прежний backlog сохранён). |
| T-SCOPE-01 | source/APK audit | PASS статический аудит отсутствия отменённых runtime-моделей; device-проход доступных сценариев — NOT RUN. |
| T-NF-02, расчётная часть | 100 daily subscriptions | PASS JVM, точные параметры/замер выше; Android нагрузка и системные задержки — NOT RUN. |

## Дополнение к отложенной инструментальной приёмке

Сохраняются консолидированный [перечень R1](R1b.5.md) и NOT RUN из [R2a](R2a.md).
Все сценарии ниже **NOT RUN**, компиляция не означает успешное выполнение.

| Класс/метод или системная проверка | Требование / приоритет | Окружение и ожидаемый результат |
|---|---|---|
| StorageOpeningTest, TrackerDatabaseTest, DatasetCompositionTest, безопасное открытие foreground/background | R-DATA-01/02 / P0 | Android SQLite/Room v5; byte preservation старых/несовместимых fixtures, WAL/hot journal, READY loss, corrupt markers, никакого reset/sweep/claim при отказе. |
| ReminderInfrastructureTest.twoConnectionsCannotReserveSameSubscriptionConcurrently, expiredReservationCanBeRecoveredAndOldOwnerCannotAcknowledge | T-NOT-03 / P0 | Два Room connection; один claim, старый token не ack. |
| Infrastructure: retryablePublisherFailureIsBoundedAcrossNewRuntimeInstances, publicationExceptionIsRecordedAsRetryableNotDelivered, cancellationReleasesClaimAndClosesBackgroundSession | T-NOT-03 / P0 | Реальный runtime/Room, fake publisher; bounded retry и освобождение ресурсов, не ложный DELIVERED. |
| Infrastructure: simultaneousShortCyclesPublishOneMessageAndAcknowledgeOnlyFourShownKeys, longShutdownAggregatesCurrentPersistedCyclesWithoutCatchupSeries, recoverySuppressionDoesNotFalselyMarkUnshownBackoffKeyDelivered | T-NOT-01/03/04 / P0 | Daily + all offset, clock seam; одно сообщение, точные DELIVERED/SKIPPED, после восстановления нет догоняющей серии. |
| Infrastructure: boundedCleanupKeepsLogicalKeysAndLiveClaims, expiredUncertainClaimBecomesSkippedNeverDeliveredOrDeleted, cleanupRechecksFractionalLeaseAsInstantBeforeTouchingClaim, acknowledgedKeysSurviveDateRollbackAndOffsetReenable | T-NOT-03 / P0 | Room/SQLite; <=256 updates, claims не затронуты, tombstones остаются после clock rollback. |
| Infrastructure: currentClaimRetainsFreshPriceWhenDailyEventsOverlap, changedDateBetweenClaimAndPublishDoesNotProduceStaleMessage, lifecycle/delete/generation tests | T-NOT-02/03 / P0 | Mutation между claim/notify; только свежие параметры либо отсутствие публикации. |
| ReminderWorkManagerTest (все), в т.ч. legacyDeliveryControlAndWakeFinishWithoutOpeningStorage, retireOnlyLegacyReminderQueueAndKeepUnrelatedWork | T-NOT-02/03 / P0 | WorkManager TestDriver + durable DB; policy 2 не открывает storage, unrelated work сохраняется, queue bounded. |
| Publisher.fifteenRecoveredKeysRemainInNativeMetadataAndReconciliationAcceptsThem, oversizedOccurrenceMetadataIsExplicitlyRejectedRatherThanTruncated | T-NOT-01/03 / P0 | NotificationManager с разрешением; 15 keys/8 строк сохраняются, 16-key некорректный payload отклоняется, dismissal не восстанавливается. |
| Publisher realNotificationUsesFreshTextAndAck / grouping / summary dismissal / stale notification cleanup | T-NOT-02/03/06 / P1 | API31 и 33+; один native child/identity, PRIVATE, актуальная цена, корректная группа и удаление. |
| Infrastructure.oneUnrepresentableSeriesAndFinancialOverflowDoNotStopHealthyReminders + storage unavailable tests | R-DATA/R-NOT-03 / P0 | Успешные Room rows с плохой серией: здоровые работают; настоящее storage failure блокирует небезопасные операции. |
| Infrastructure.syntheticHundredDailySubscriptionsAndFiveThousandExpiredKeysRemainBounded | T-NF-02 / P1 | Android; 2800 логических событий/100 tasks/7800 rows/256 batch, замер реального reconciliation. |
| NotificationPermissionUiTest, NotificationNavigationUiTest, Publisher systemChannelBlockIsRespectedWithoutFallbackOrFalseDelivery | R-NOT-04, T-NOT-05/06 / P1 | API31/33+, app/channel off, grant/deny/regrant, missing record, старое поколение, immutable PI, Back без дубля. |
| Реальные reboot, time forward/back, zone/DST, midnight, длительное выключение; работа без UI/Doze/батарея | T-NOT-04/06 / P1 | Эмулятор + физический Android; не менять due в Room, одно актуальное восстановление, отсутствие expired notifications, фиксировать фактические задержки best effort. |

## Изменённые файлы

- `domain/reminders/ReminderRules.kt` — bounded all-cycle projection, isolation, обоснованный metadata bound, permission eligibility.
- `data/reminders/ReminderLedger.kt` — согласованные current sets, scoped reads, recovery suppression, bounded compaction.
- `data/room/Daos.kt` — только SELECT-запросы window/processing/token/cleanup batch, без DDL.
- `data/reminders/ReminderScheduling.kt` — новый fingerprint namespace, остальная инфраструктура сохранена.
- `background/ReminderWork.kt` — policy 3 и безопасный выход старых policy.
- `notifications/AndroidReminderPublisher.kt` — metadata 15/4096, bounded signatures.
- `notifications/ReminderText.kt` — хронологический порядок разных due.
- `notifications/NotificationViewModel.kt` — eligibility разрешения независимо от текущей недели.
- `app/src/test/.../domain/reminders/RollingReminderTest.kt` — новые расчётные регрессии и stress.
- `app/src/test/.../domain/reminders/ReminderRulesTest.kt` — адаптация прежних ожиданий к horizon/isolation.
- `app/src/test/.../data/integration/ReminderInputsTest.kt` — дата fixture в horizon.
- `app/src/test/.../notifications/NotificationRulesTest.kt` — текст пересечений.
- `app/src/androidTest/.../background/ReminderInfrastructureTest.kt` — Room/runtime/cleanup/recovery/stress regressions.
- `app/src/androidTest/.../background/ReminderWorkManagerTest.kt` — legacy policy 2.
- `app/src/androidTest/.../notifications/NotificationPublisherTest.kt` — native metadata/payload bounds.
- `docs/reports/R2b.md` — этот отчёт. Production-относительные пути выше начинаются в существующем package приложения.

## Ограничения и независимая приёмка

Защищённые Room v5/entities/schema exports 1–5, factory/preflight/InitializationJournal, generation/mutation gate semantics,
AssetStore, ForecastEngine/R2a-fix, DefaultRenewalCalendar, денежная модель, Settings storage,
manifest/applicationId/backup/D2D, зависимости/Gradle/CI, launcher и лицензии не изменены.
DAO SELECT не меняют schema identity. В APK проверено отсутствие отменённых моделей/состояний; исторические snapshots
и технические channel/route/policy guards сохраняются обоснованно. Запрещённые пользовательские функции не возвращены.
Оставшиеся legacy-упоминания классифицированы: channel PAYMENTS/payment resource keys — стабильные технические
идентификаторы; policy 0–2, старые route/payload guards — только безопасное отклонение; schema exports 1–4 — история;
синтетические native fixtures и debug IsolatedMainActivity cleanup — удаление только собственных изолированных
тестовых поколений, не путь восстановления production. Финансовый Money/RUB — прежний технический formatter,
ForecastEngine не менялся. Ни один из этих компонентов не возвращает отменённые операции.
Все fixtures синтетические. Diff проверен на scope и конфиденциальность; авторство GitHub noreply.

Нативная безопасность/concurrency/WAL/permission/publisher/OS scheduling пока не доказана запуском.
Дедуп-строки сохраняются пожизненно для данной подписки; физически ограничить их рост без отдельного долговременного
контракта нельзя. До приёмки на Android требуется весь P0/P1 backlog выше, включая нагрузку и реальные системные события.
Best effort не гарантирует доставку ровно в 09:00; notify/ack crash window не позволяет обещать exactly-once.

R2b подготовлен к независимой статической приёмке в пределах указанного diff и фактических проверок.
Полная инструментальная приёмка и PERSONAL-READY не объявляются. Merge/ручной запуск Actions/сброс устройства не выполнялись.
После подготовки stacked Draft PR работа останавливается; R3 не начинается.
