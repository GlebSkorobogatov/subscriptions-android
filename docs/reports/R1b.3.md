# R1b.3 — упрощение настроек и напоминаний

## Состояние и база

- Исходная ветка: `codex/r1b-02-remove-payment-history`, принятый SHA `7de4e6c4a15f8a334c5ec7dfca58fa527330b3e4`; перед работой HEAD совпал, рабочее дерево было чистым.
- Ветка: `codex/r1b-03-settings-reminders`, создана непосредственно от принятого коммита.
- Итоговый SHA проверяемого кода: `a4b3faa432858994c7b84eccf192bc4844e75d6b`. Отчёт добавляется отдельным завершающим коммитом; HEAD с отчётом закреплён в stacked Draft PR и итоговом сообщении.
- Base PR: `codex/r1b-02-remove-payment-history`. Предыдущие Draft PR #1–#3 остаются открытыми, merge не выполняется.
- Реализован только R1b.3. Код готов к независимому статическому обзору и инструментальной проверке; полноценная инструментальная приёмка не заявляется.

Изучены AGENTS, PRD, ARCHITECTURE, IMPLEMENTATION_PLAN, TEST_PLAN, UI_DESIGN_SPEC, DECISIONS,
отчёты принятых PR #1/#2/#3, `R1b.2.md` и актуальные consumers/тесты.
Существенное расхождение: UI_DESIGN_SPEC §7 и T-UI-06 сохраняют глобальный time picker,
а R-NOT-01 указывает 09:00. Владелец явно уточнил в чате: **«Фиксированное 09:00, убрать time picker»**.
Выполнено это решение; утверждённые файлы требований не переписывались.
Остальная целевая архитектура реализуется поэтапно, без преждевременного R1b.4/R2.

## Предварительная инвентаризация и результат

| Область / компоненты | Зависимость до R1b.3 | Результат |
|---|---|---|
| `Settings`, `SettingsCodec`, `SettingsRepository` | Тема, изменяемое время, два набора дней, валюта/горизонт; leases DataStore | Тема и один набор дней. Время вычисляется как 09:00. Retired keys не читаются и не удаляются. Factory/lease lifecycle дословно сохранены. |
| `SettingsViewModel` / `SettingsScreen` | Time dialog, payment/cancellation selectors и сохранённые intents | Один диалог глобальных дней; статическое время. Сохраняются loading/error/theme, pending/retry, idempotent preference patches. Старые time/cancellation/currency/horizon intents не выполняются. |
| `SubscriptionDraft` / `SubscriptionEdit` / `SubscriptionExtras`, `SubscriptionEditor` | Запись и optimistic проверка reminder overrides, cancellation lead | Удалены параметры/команды этих правил. Редактор не читает и не пишет legacy rules. Сохраняет существующий lead и остальные не редактируемые поля строки. Полный optimistic snapshot, транзакции, ассеты сохранены. |
| Editor / Details / их ViewModel | Индивидуальные дни, наследование, lead, выбор валюты и сохранённые поля | Все эти элементы и обработчики удалены. Совместимые черновики остаются; несовместимые временные поля очищаются без записи пользовательских данных. |
| `SubscriptionUseCases.details`, DAO read model | Транзакционная relation с reminder overrides | Теперь наблюдается одна строка подписки, без чтения правил. Согласованность самого snapshot и реактивные edit/state/delete сохранены. Старый relation DTO удалён, схема не менялась. |
| `reminderInputs`, `ReminderRules` | Индивидуальные overrides, два вида событий, lead | Только глобальные дни и один доменный тип продления. Тот же календарь/nearest-cycle алгоритм. Даты, DST conversion, recovery и dedup contract не заменены новым движком. |
| `ReminderLedger` | Оба вида событий, чтение overrides, удаление неактуальных pending rows | Нет чтения overrides. События отмены завершаются SKIPPED без удаления строк. Неактуальные pending продления сохраняются, но не планируются и не публикуются вне текущих глобальных правил. |
| `ReminderRuntime`, `ReminderWorker`, scheduler/factory | Единый worker для delivery/control/wake | Сохранены единая защищённая фабрика, gate, generation, claim/lease и финальная проверка. Добавлена политика work payload 2 и безопасный путь завершения старых заданий. |
| Publisher / текст / permission ViewModel / PendingIntent | Два канала и тексты; OPEN и dismiss | Только действующий канал продлений. Старые cancellation payload в показанных уведомлениях отклоняются при reconciliation. OPEN/dismiss сохранены, операционных действий оплаты/отмены/архива в уведомлениях нет. |
| `autoArchiveExpired`, repository и date refresh | Автоматическое изменение статуса при запуске/resume | Функция, repository API и все вызовы удалены. Refresh обновляет дату и запрашивает reconciliation через прежний mutation gate, без Room-записей статуса. |
| `FinanceSource`, `FinanceViewModel`, `FinanceScreen`, `selectedChart` | Выбор/сохранение валюты и 3/6/12 месяцев, automatic currency fallback | Команды, selectors, записи настроек, pending/save state и fallback удалены. Существующий engine получает фиксированные 12 месяцев и RUB. Выбор месяца остаётся и корректно восстанавливается. |
| WorkManager/receivers | Сохранённые jobs, clock/timezone receiver, library reboot receiver | Старые jobs нейтрализуются, отмена очереди ограничена принадлежащими приложению устаревшими reminders. Общая очередь не очищается. Системные точки восстановления сохранены. |
| Тесты, debug previews/probe, строки | Контролы и fixtures удаляемых возможностей | Адаптированы к новым правилам; убраны отменённые строки/scenes. Debug probe использует синтетическое пропущенное продление в 09:00, без изменения глобального времени. |

## Настройки и сохранённые состояния

Единственная пользовательская настройка напоминаний — произвольное сочетание {7,3,1,0}.
Для отсутствующего `payment_offsets` используется {3,1}; явно сохранённый пустой набор остаётся пустым
и выключает напоминания. JVM-проверка перебирает все 16 сочетаний в одном тестовом методе.
Допустимые ранее выбранные дни и тема сохраняются между запусками, а не сбрасываются при чтении.

Безопасная обработка старых значений:

- Допустимые члены набора сохраняются; нечисловые/недопустимые отбрасываются. Если сохранённый набор целиком невалиден — напоминания выключены, новые дни автоматически не включаются.
- Неизвестная тема отображается как SYSTEM. Неизвестная будущая версия настроек остаётся ошибкой.
- `reminder_time`, `cancellation_offsets`, `chart_months`, `chart_currency` остаются в Preferences как инертные данные, не читаются и не копируются в runtime Settings. Даже некорректные retired значения не включают удалённую функцию.
- Запись текущей темы/дней сохраняет посторонние Preferences. Настоящие ошибки чтения/IO/повреждение DataStore не заменяются успешными пустыми настройками и не стирают файл.
- SettingsViewModel удаляет только несовместимые временные intents и поля time dialog. Валидные pending theme/global-days patches сохраняются без автоматической повторной отправки.
- Диалог дней имеет новый безопасный saved-state формат: прежний Boolean выбора payment/cancellation не восстанавливает отменённый диалог; новое глобальное окно восстанавливается. Явный saver всего MutableState отбрасывает также старый nullable контейнер: null/false/true проверены отдельным JVM-тестом, не могут стать null в новом Boolean.
- FinanceViewModel не восстанавливает currency/horizon state и ничего не записывает ради fallback. Допустимый выбранный месяц сохраняется; вышедший из окна — возвращается к первому текущему месяцу.
- Старые поля индивидуальных правил/lead удаляются из черновика редактора. Неизменившийся ожидаемый legacy snapshot с суффиксом правил принимается без чтения правил; изменение самой строки по-прежнему даёт конфликт.
- Черновик с другой валютой не переименовывает введённую сумму в RUB: сумма очищается с просьбой повторно ввести стоимость. При редактировании существующей иностранной записи валюта берётся из ожидаемой строки и показана без возможности выбора.
- Несовместимое сохранённое действие Reactivate с изменением валюты отбрасывается без выполнения; остальные безопасные действия/снимки R1b.2 продолжают работать.

Новые подписки создаются только в RUB. При edit/reactivate менять валюту старой записи нельзя;
исходная валюта и точность сохраняются. Иностранные суммы не конвертируются и не суммируются как RUB.
В RUB-график они не входят. Прежний список/детали/напоминания могут показывать исходную валюту
старой записи до R1b.4; это переходная совместимость, не пользовательский выбор валюты.

## Старые фоновые задачи, журнал и защита

| Work payload | Поведение |
|---|---|
| Прежний version=1 без policy | Worker не открывает Room/DataStore, не резервирует ledger и не публикует уведомление. Запрашивает одно актуальное reconciliation и завершается success; ошибка enqueue имеет прежний ограниченный retry. |
| policy=2 | Прежние проверки generation, fingerprint и финального расписания; защищённая фабрика открытия, общий mutation gate, транзакционный claim/lease, bounded retry и закрытие ресурсов. |
| Неизвестная будущая policy / некорректная версия | Постоянный отказ без открытия данных; бесконечного retry нет. |

Новые delivery/control/wake получают policy tag. `requestLocked` отменяет только незавершённые
старые jobs с прежним ROOT tag и без нового policy tag; RUNNING не прерываются этой очисткой,
их нейтрализует doWork. `apply` сохраняет поколение и точную сверку wanted tasks.
Нет production-вызова `cancelAllWork`. В тестовом teardown очистка изолированной WorkManager DB сохранена.
Отдельных production-классов worker/receiver автоархивирования в принятой базе не было:
оно выполнялось из refreshToday. Этот путь полностью удалён, ранее архивированные записи не восстанавливаются.

Логические ключи `subscription + PAYMENT + due + offset + generation` и их `reminder:v1` identity сохранены;
версия ledger payload остаётся 1. Новая policy меняет work fingerprint/метаданные очереди, не Room.
DELIVERED/SKIPPED ключи не сбрасываются. Отменённые cancellation записи сохраняются и становятся SKIPPED
(незавершённые), не декодируются как действующее событие и не блокируют глобальное продление.
Неактуальные pending продления остаются в журнале, но проходят текущий global schedule filter на
планировании/claim и повторно перед публикацией. При off→on ещё не доставленный допустимый ключ
снова планируется; доставленный — не отправляется повторно. Старое pending время заменяется на 09:00
только для актуального ещё не завершённого глобального ключа, без замены его ID.
Резервация, lease recovery, cancellation корутин и обработка потери ответа сохранены.

После успешного открытия reconciliation также проверяет ранее показанные нативные уведомления:
устаревшее событие отмены больше не проходит доменный parser/current-schedule проверку и удаляется
из NotificationManager. Старый Android channel может пассивно остаться в ОС, но приложение его
не создаёт и не использует. Из уведомлений доступны только открытие актуальных деталей и закрытие.
Неизвестные действия не запускают изменение подписки; ошибочный/старый route сохраняет безопасный fallback.

При недоступном/несовместимом хранилище новый worker не получает рабочую сессию. Read-only preflight,
InitializationJournal, AUTHORIZED→CREATING→READY, pointer/generation guards, WAL/SHM,
PreservingOpenHelperFactory и блокирующий UI не изменены. Room и DataStore lease закрываются как прежде.
Ассеты/иконки/sweep не перерабатывались. История платежей и финансовые операции не возвращены.
Файлы Room v4, entities, keys/FK/indices, миграции и schema snapshots побайтово неизменны по diff.

## Точки перепланирования и границы R2

- Создание/edit/state/delete подписки и изменение глобальных дней — прежний ReminderChanges.write/request.
- Запуск/resume — observeSession → refreshToday через прежний gate → request; обновление даты больше не архивирует.
- Изменение времени/пояса — прежний ReminderTimeReceiver и rescheduleAfterClockChange, с заменой устаревшего monotonic delay.
- Reboot — сохранён library RescheduleReceiver WorkManager (BOOT_COMPLETED/RECEIVE_BOOT_COMPLETED подтверждены в merged manifest) и durable wake/control queue. Реальный reboot не проверялся.
- Восстановление данных H в принятом приложении не реализовано: новый механизм не добавлен. Защита переключения поколения и запрос актуального reconciliation сохранены.
- Финальный календарь напоминаний/rolling horizon/короткие интервалы и полная системная проверка — R2; существующий nearest-cycle engine и WorkManager best effort сохранены, exact alarms не добавлены.

## Временные compatibility-компоненты и финальный поиск

Поиск выполнен по production/debug/test исходникам и ресурсам, затем по APK и полному diff к принятому SHA.
Имена отменённых commands/settings отсутствуют в APK; production consumers reminder override DAO отсутствуют.
Каждый оставшийся тип остатка классифицирован ниже.

| Остаток | Зачем сохранён / может ли выполняться | Следующий этап |
|---|---|---|
| Room `reminder_overrides`, `ReminderMode`, room `ReminderEventType.CANCELLATION`, DAO; `cancellationLeadDays` | Строго схема v4, старые rows и fixtures/cascade. UI, editor и reminders не читают правила и не могут их создать/изменить. Старый lead переносится как неизменяемое поле полного snapshot. | Физическая схема/модель — R1b.4, отсутствие отменённого runtime — R1b.5. |
| Cancellation rows в delivery ledger | Сохранены для целостности/аудита/dedup. Незавершённые нейтрализуются, не публикуются; завершённые не меняются. | Разрыв старой схемы — R1b.4; новая безопасная политика ledger cleanup — R2. |
| Старые Preferences keys времени/cancellation/валюты/горизонта | Инертные данные, не сбрасываются и не управляют приложением. | Обновление формата настроек можно оценить в R1b.4/R1b.5; массовой очистки нет. |
| Старый work payload без policy в ReminderWorker | Минимальная выполняемая compatibility-ветка: success + актуальный request, без Room/ledger/публикации. Нельзя удалить, пока существуют старые persisted WorkRequests. | Проверить дальнейшее сохранение в R1b.5; удалять лишь после явного вывода поддержки старых очередей. |
| Android channel `subscription_payments_v1`, доменный PAYMENT, `payment_offsets`, `reminder:v1` | Действующие стабильные идентификаторы продлений. Сохраняют настройки ОС/пользователя и dedup, не означают учёт фактов оплаты. | Не требуют удаления/переименования ради названия; совместимость учитывать в R1b.4/R2. |
| Старый cancellation channel в ОС | Может пассивно существовать после обновления; нет production-кода создания/публикации в него. | Автоматическая очистка пользовательской конфигурации канала не входит в этап. |
| Legacy AUTO/MANUAL, PAYMENT_ISSUE, CANCELLATION_SCHEDULED, Money/CurrencyCode, oneOffDueDate/overrideCycleIndex | Ещё выполняется прежний календарь/отображение старых записей. Нельзя заменить без новой схемы/полного связывания R1a. Одноразовая дата — поле календаря подписки; отдельного snooze/переноса уведомления в принятом коде нет. | R1b.4, финальный scope audit R1b.5. |
| `forecastMonths(months,currency)`, generic engine, read-only FinanceSnapshot constants | Технический существующий read-wrapper/fixtures. Пользовательских команд выбора/сохранения нет; runtime график строго 12/RUB. | Пересмотреть адаптеры в R1b.4/R2; алгоритм прогноза здесь не менялся. |
| `refreshToday(today,changedAt)` | Совместимая сигнатура оставляет неиспользуемый changedAt, но не архивирует/не пишет Room; выполняет date refresh и безопасный request. | Снятие остаточных legacy API — R1b.4/R1b.5. |
| Sanitizers временного Settings/Finance/Editor state и saver global dialog | Выполняются только при восстановлении; удаляют несовместимые временные поля/окна, не Preferences/Room. Защищают старый nullable Boolean и отменённые intents от восстановления/выполнения. | R1b.5: пересмотреть совместимость при выводе поддержки старых черновиков. |
| Снимок SubscriptionSnapshot и старый nav sanitizer R1b.2 | Выполняются для optimistic checks/совместимых черновиков и безопасного восстановления маршрута истории; не возвращают историю. | Сохраняются; при изменении схемы/Navigation проверить в R1b.4/R1b.5. |
| Legacy financial Entity/DAO/PaymentSource/tombstones | Результат R1b.2: схема/fixtures и явное каскадное удаление одной подписки. Новых пользовательских финансовых операций нет. | R1b.4. |
| Pure ReminderRules time parameter и DST helpers | Технический аргумент прежнего алгоритма и регрессионных DST-тестов. Production передаёт только вычисленное 09:00, time command отсутствует. | Существующий pure механизм сохраняется для R2. |

## Проверки и ограничения доказательств

Среда: JDK 21, Android SDK 36, Build Tools 35.0.0. Только синтетические fixtures.

```bash
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

| Проверка | Фактический итог |
|---|---|
| Diff check, включая полный diff к принятому SHA | PASS |
| testDebugUnitTest | PASS, 275 реально выполненных тестов, 0 failures/errors/skipped |
| assembleDebug | PASS |
| lintDebug | PASS: 8 прежних warnings, 0 errors |
| compileDebugAndroidTestKotlin | PASS: 328 методов @Test скомпилированы |
| connectedDebugAndroidTest | NOT RUN: нет доступного устройства/эмулятора, KVM недоступен; обходов ADB/KVM не было |
| Проверка protected diff | PASS: schema/миграции/защита открытия/поколений, leases/gates, ассеты, R1a calendar/record и алгоритм ForecastEngine не изменены |
| Поиск отменённых runtime API / проверка APK | PASS в границах R1b.3; T-SCOPE-01 целиком относится к R1b.5 |

Lint: 6 NewerVersionAvailable, 1 ObsoleteSdkInt, 1 UnusedResources (welcome_message), как в базе.
Промежуточная ошибка отступа исправлена, итоговый lint не содержит ошибок; проверки не отключались.
Зависимости, Gradle и Actions не менялись. Actions вручную не запускались.

Все **328 инструментальных тестов NOT RUN**, включая новые worker/ledger/нативные notification/IO/UI сценарии.
Компиляция не доказывает корректность реального WorkManager, повторного открытия DataStore,
сохранности v4/WAL, публикации, отмены резерваций, reboot/timezone и UI restoration на устройстве.
Обязательны независимый запуск применимых I/UI тестов, TalkBack/крупный шрифт/обе темы,
реальные permissions/reboot/смена системного времени и повторная проверка старой очереди после обновления.

Известные ограничения/риски:

- Подключение SubscriptionRecord, три целевых состояния и исключительная граница конца доступа остаются R1b.4. Legacy-модель сейчас не объявляется целевой R1a.
- MANUAL overdue и one-off календарь сохраняют прежнюю семантику. Новой логики циклов/истории нет.
- Существующий next30Days использует прежнюю границу plusDays(30); её исправление и конечные календарные правила — R2, здесь они не изменялись.
- Неактивные pending и завершённые ledger rows сохраняются; безопасная ограниченная очистка с сохранением dedup остаётся предметом R2.
- Старые нативные уведомления очищаются после успешной проверки хранилища, не посредством обхода ошибки открытия.
- В Settings ещё нет целевого toggle анимаций и финальной компоновки настроек/архива: их не было в принятой базе, визуальный этап R4b не выполнялся.
- H отсутствует; новый backup/restore не добавлен. Общие ограничения локального хранения без копии сохраняются.
- Compatibility saved state привязан к принятой версии Navigation/форматам скаляров; реальный process death и обновление с сохранённым состоянием требуют устройства.

Обнаруженное при аудите тестовых fixtures: ReminderWorkManagerTest не давал явного разрешения
первичного создания для worker-only сценариев. Теперь одноразовый authorizeFreshFixture вызывается
единожды в @Before нового синтетического поколения; повторный вызов из отдельного теста убран.
Это настройка fixture, не изменение production-защиты и не обход маркера READY.
Принятое исправление reopen в DetailsOperationsIntegrationTest/FinanceIntegrationTest сохранено.

## Учёт тестов

Счётная единица — метод @Test. Перенесённые/переименованные методы сопоставлены с исходными.
«Без изменения» означает неизменное объявление/тело; изменение общих fixtures отмечено отдельно.

| Набор | До | Без изменения тела | Адаптировано | Удалено | Добавлено | После |
|---|---:|---:|---:|---:|---:|---:|
| JVM | 281 | 226 | 45 | 10 | 4 | 275 |
| Инструментальные | 318 | 257 | 57 | 4 | 14 | 328 |

### Удалённые тесты — причины и сохранённые проверки

- `data/lifecycle/LifecycleRepositoryTest.kt:failedBulkArchiveRollsBackAllRows` — Удалена автоматическая bulk archive операция и её транзакция. Неплатёжный lifecycle SQL rollback, optimistic concurrency, подтверждённый FK cascade/delete, reopen и ассеты остаются; новый refresh не пишет статусы вообще.
- `data/lifecycle/LifecycleRepositoryTest.kt:lifeT10T12KnownUnknownAndRepeatAutoArchive` — Удалена автоматическая bulk archive операция и её транзакция. Неплатёжный lifecycle SQL rollback, optimistic concurrency, подтверждённый FK cascade/delete, reopen и ассеты остаются; новый refresh не пишет статусы вообще.
- `ui/ArchiveSettingsRedesignUiTest.kt:timeDialogPreservesFieldErrorsAndBusyBlocksConfirmation` — Time picker удалён по явному уточнению владельца. Read-only 09:00, отсутствие диалога, theme/global-days, busy/error/retry и доступность остались.
- `ui/ArchiveSettingsUiTest.kt:cancellingInvalidTimeDraftDoesNotChangeStoredPreference` — У отменённого time picker больше нет invalid draft. Фиксированное время, отсутствие диалога, старые intents/codec values и сохранение глобального пустого набора покрыты другими проверками.
- `domain/lifecycle/LifecycleCommandsTest.kt:autoArchiveDoesNotTouchOtherStatesOrManualOverdue` — Автоматический архив полностью удалён. Вместо его правил новые прикладные проверки многократного date refresh охватывают все состояния, отсутствие изменения строк и сохранение ручного archive/restore.
- `domain/lifecycle/LifecycleCommandsTest.kt:lifeT12InclusiveLastDayAndIdempotentArchive` — Автоматический архив полностью удалён. Вместо его правил новые прикладные проверки многократного date refresh охватывают все состояния, отсутствие изменения строк и сохранение ручного archive/restore.
- `domain/reminders/ReminderRulesTest.kt:changingLeadInvalidatesOnlyCancellationReasons` — Отменены сроки/изменение lead напоминаний об отмене. Будущие/истёкшие продления, DST, anchors, recovery и логические ключи остаются; игнорирование legacy lead проверяется отдельно.
- `domain/reminders/ReminderRulesTest.kt:expiredDeadlineIsRemovedWhileFuturePaymentRemainsEligible` — Отменены сроки/изменение lead напоминаний об отмене. Будущие/истёкшие продления, DST, anchors, recovery и логические ключи остаются; игнорирование legacy lead проверяется отдельно.
- `notifications/NotificationRulesTest.kt:cancellationTodayNamesDeadlineAndProvider` — Отменены cancellation copy и отдельный канал отмены. Продления, тексты, permission/channel denial, URI/generation и bounded failure checks сохранены; старое cancellation notification проверяется инструментальным fixture.
- `notifications/NotificationRulesTest.kt:cancellationTomorrowHasRealDate` — Отменены cancellation copy и отдельный канал отмены. Продления, тексты, permission/channel denial, URI/generation и bounded failure checks сохранены; старое cancellation notification проверяется инструментальным fixture.
- `notifications/NotificationRulesTest.kt:mixedReasonsCannotBypassDisabledCancellationChannel` — Отменены cancellation copy и отдельный канал отмены. Продления, тексты, permission/channel denial, URI/generation и bounded failure checks сохранены; старое cancellation notification проверяется инструментальным fixture.
- `ui/editor/EditorTest.kt:independentDisabledAndCustomRules` — Удалён API создания/наследования индивидуальных правил. Сохранены календарь и денежная валидация; добавлены проверки отбрасывания старых полей и неизменности legacy rules.
- `ui/editor/EditorTest.kt:inheritIsSeparateFromDisabled` — Удалён API создания/наследования индивидуальных правил. Сохранены календарь и денежная валидация; добавлены проверки отбрасывания старых полей и неизменности legacy rules.
- `ui/finance/FinanceViewModelTest.kt:selectionsUseRepositoryAndDoubleWriteIsBlockedWithoutDiscardingDataOnFailure` — Команды записи currency/horizon отменены. Чтение/error/retry, overflow, восстановление месяца и отсутствие fallback/записей по старым selections продолжают тестироваться.

### Адаптированные проверки — полный перечень

Причины по областям: cancellation/индивидуальные controls заменены единым global-only контрактом;
time picker — неизменяемым 09:00; selectors/fallback — фиксированной RUB/12 проекцией;
автоархив — отсутствием автоматического перехода; финансовые иностранные записи задаются как legacy fixtures,
а не новыми пользовательскими currency choices. Сохраняемые требования сохранности, транзакций,
конкуренции, ошибок, дедупликации, leases/generation и UI restoration не исключены.

- **I `background/ReminderInfrastructureTest.kt`**: Legacy cancellation не добавляет сообщения, время фиксировано, ledger не очищается при потере актуальности; concurrency/DST/claim/cancellation/rollback сохранены.
  - `archiveInvalidatesClaimImmediatelyBeforePublication`.
  - `autumnDstOverlapDoesNotPublishAgainAtSecondOffset → autumnClockTransitionKeepsNineAndDoesNotPublishAgain`.
  - `cancellationReleasesClaimAndClosesBackgroundSession`.
  - `coincidentPaymentAndCancellationAreOneMessageWithAllCoveredKeys → legacyCancellationLeadDoesNotAddReasonsOrCoveredKeys`.
  - `expiredEventIsNotRecoveredAsOldWarning`.
  - `productionUseCasesSignalLifecycleChangesButNotThemeOrChartSettings`.
  - `springDstGapUsesFirstValidMomentWithOriginalLogicalDate → springClockTransitionKeepsNineAndOriginalLogicalDate`.
  - `timeAndTimezoneChangesPreserveKeysButReplaceScheduling → timezoneChangesPreserveKeysAndFixedNineButReplaceScheduling`.
- **I `background/ReminderWorkManagerTest.kt`**: Явная одноразовая fixture-авторизация перемещена в setup, новые WorkRequest metadata policy не обходят безопасность.
  - `createThroughUseCasesEnqueuesRealControlWorkerWithoutActivity`.
- **I `data/integration/SubscriptionUseCasesTest.kt`**: Нет rules read/write и автоархива; fixed chart, optimistic edits и rollback/cascade/history preservation сохраняются.
  - `cancellationLastDayReactivationAndDeletionPreserveIndependentSubscription`.
  - `dateRefreshManualIssueCancellationAndReactivation`.
  - `detailsRelationNeverCombinesSubscriptionAndRemindersFromDifferentTransactions → detailsNeverReadOrExposeLegacyRulesAndRemainReactiveOnDelete`.
  - `editorExtrasAndIndependentRulesCommitTogetherAndConflictOnRules → editorExtrasPreserveInertLegacyRulesAndConflictOnFullRow`.
  - `invalidReminderBatchRollsBackSubscriptionAndUuidIsPreservedOnValidEdit → invalidCurrencyEditRollsBackAndValidEditPreservesUuid`.
  - `realRoomFlowsUpdateDetailsWithoutReadingOrObservingFinancialRows`.
  - `settingsFlowUpdatesWithoutOverwritingCustomReminders → globalSettingsUpdateWithoutReadingOrOverwritingLegacyRules`.
- **I `data/lifecycle/LifecycleRepositoryTest.kt`**: Восстановление сохраняет валюту записи; ручные переходы, FK и независимые записи сохранены.
  - `lifeT13T16ReactivationPreservesOldPaymentAndRefundCurrency`.
- **I `data/settings/SettingsIntegrationTest.kt`**: Работающие global days вместо отменённых time/horizon/cancellation; leases, reopen и атомарность валидации сохранены.
  - `dbT06GlobalChangesDoNotRewriteRoomAndSubscriptionsAreObservable`.
  - `dbT07ReopenAndDatasetIsolationAndSharedLease`.
  - `reactiveFlowAndInvalidUpdateIsAtomic`.
- **I `notifications/NotificationPublisherTest.kt`**: Нативный renewal-only канал, legacy lead инертен; system identity/deny/cleanup остаются.
  - `changedDeadlineCancelsMixedNotification → changingInertLegacyDeadlineDoesNotCancelCurrentRenewal`.
  - `mixedReasonsUseOneCancellationChannelNotification → legacyCancellationLeadStillUsesOnlyRenewalChannelAndCopy`.
  - `replayUpdatesSameSystemIdentity`.
  - `stableChannelsHaveStandardImportance`.
- **I `notifications/ReminderSystemRegressionTest.kt`**: Global-only reason count вместо mixed cancellation, legacy overrides инертны, inactive ledger сохраняется; gate/claim/generation и native freshness остаются.
  - `changingGlobalTimeRemovesStaleNativeCopyWithoutResettingDeliveredKeys → disablingGlobalDaysRemovesStaleNativeCopyWithoutResettingDeliveredKeys`.
  - `concurrentSettingsUpdatesKeepIndependentRulesAndCustomOverrides → concurrentThemeAndGlobalOffsetsIgnoreButPreserveLegacyOverrides`.
  - `delayedMixedReasonsAreOneNativeMessageAndAllCoveredKeysAreAcknowledged`.
  - `emptyCustomSetsSuppressBothKindsWithoutChangingGlobalDefaults → emptyLegacyOverridesDoNotSuppressGlobalRenewals`.
  - `expiredManualPaymentAndDeadlineNeverCreateCatchUpNotification`.
  - `explicitDateEditWhileWorkerWaitsInvalidatesOldReminderWithoutPayment`.
  - `lifecycleWritesRemoveAndRestoreOnlyEligiblePendingReasons`.
  - `transactionalDateAndOverrideEditCannotPublishPartialOrOldSnapshot`.
- **I `ui/AccessibilityUiTest.kt`**: Отменённые controls удалены из font/rotation workflow; действующие формы, dialogs, archive и theme продолжают проверяться.
  - `enlargedFontKeepsFormsDialogsHistoryArchiveAndReminderActionsReachable`.
- **I `ui/ArchiveSettingsRedesignUiTest.kt`**: Один global days dialog, read-only время, прежние layout/theme/busy/retry/restoration проверки.
  - `independentOffsetDialogsUseExistingCommandsAndAllowEmptySet → oneGlobalOffsetDialogAllowsArbitrarySelectionAndEmptySet`.
  - `normalTimeRowKeepsValueToRightAndAnnouncesCurrentTime → fixedTimeRowKeepsValueAndHasNoEditingAction`.
  - `offsetDialogRestoresWithoutAutomaticallySendingAnotherChange`.
  - `savingAndUncertainWriteBlockFurtherChangesAndOfferExplicitRetry`.
- **I `ui/ArchiveSettingsUiTest.kt`**: Нет изменения валюты/time/cancellation; сохранены manual archive/restore, глобальные дни и recreation.
  - `archiveReactivationPreservesUuidAndUpdatesActiveForecast`.
  - `offsetsAreIndependentSupportMultipleSelectionAndEmptySet → globalOffsetsSupportMultipleSelectionEmptySetAndRecreation`.
  - `timeDefaultsToNineAndDraftAndConfirmedValueSurviveRecreation → fixedNineSurvivesRecreationWithoutTimeDialog`.
- **I `ui/DetailsEditorRedesignUiTest.kt`**: Вместо управления override проверяется отсутствие controls, сохранены label/anchor/edit и независимые действия.
  - `accountLabelVisibleAndReminderOverridesStayIndependent`.
- **I `ui/finance/FinanceIntegrationTest.kt`**: RUB/12 вместо выбора, иностранный fixture сохранён; reactive editing/archive/reopen/history independence остаются.
  - `liveEditsDateAndLifecycleUpdateForecastWhileHistoryAndRefundsDoNot`.
  - `selectedPeriodsCurrenciesAndNamesMatchEngineAndSurviveReopeningStores → fixedRubTwelveMonthsPreserveLegacyCurrenciesAndSurviveReopeningStores`.
- **I `ui/finance/FinanceNavigationTest.kt`**: Месяц/последний столбец, tabs/settings/rotation сохраняются без horizon/currency selectors.
  - `createdSubscriptionAppearsInForecastAndSelectionSurvivesRecreationAndSettingsBack`.
  - `lastChartMonthSurvivesThemeTabReturnRecreationAndActualRotation`.
- **I `ui/finance/FinanceUiTest.kt`**: Те же календарные события/отображение без выбора валюты/горизонта; renderer legacy precision и large font остаются.
  - `monthsSelectExactExpectedEventsAndCurrenciesNeverMix → monthsSelectExactExpectedEventsWithoutCurrencyOrHorizonControls`.
  - `zeroMonthIsSelectableAndLargeTextRemainsScrollableInBothThemes`.
- **I `ui/finance/LargeFontChartUiTest.kt`**: Только DTO signature; extreme legacy amounts, размеры и ориентация сохранены.
  - `nativeFontTwoLandscapeKeepsAllMonthsAndExactLargeMoneyAccessibleInBothThemes`.
  - `nativeFontTwoNarrowKeepsAllMonthsAndExactLargeMoneyAccessibleInBothThemes`.
- **I `ui/finance/MonthlyChartUiTest.kt`**: Нет currency action, monthly totals/events/geometry/selected month остаются.
  - `reactivePriceAndCurrencySnapshotsUpdateBarsWithoutStaleCachedSums`.
  - `tapSelectsExactMonthTotalAndEventsAndCurrencyChangeCannotMixAmounts`.
- **I `ui/review/CrossScreenDesignUiTest.kt`**: Персональные controls отсутствуют; date picker и layout/font/theme matrix сохранены.
  - `independentOverrideControlsAnnounceEventAndDayWithoutChangingOtherSet`.
- **I `ui/review/KeyboardRotationUiTest.kt`**: Отменённый time draft заменён восстановлением действующего global offsets dialog в landscape; editor/IME сохранены.
  - `landscapeTimeDraftRecreationAndCancelPreserveOriginTabAndStoredTime → landscapeGlobalOffsetsRecreationPreservesOriginTabAndFixedTime`.
- **I `ui/review/LargeFontWindowUiTest.kt`**: Из gallery удалена только отменённая time scene; действующие lifecycle dialogs/global checkbox и размеры сохранены.
  - `landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont`.
- **I `ui/settings/ArchiveSettingsIntegrationTest.kt`**: Global settings/09:00 вместо time/cancellation, legacy rules не читаются/не переписываются, archive/theme/reopen/unknown write outcomes сохранены.
  - `archiveProjectionReactivationPreservesHistoryAndUuidAndRecomputesForecast`.
  - `failedReadAndLostWriteResponsePreservePreferencesAndRestoreWithoutAutomaticWrite`.
  - `settingsCommandsPersistAcrossReopenWithoutOverwritingCustomOrOtherFields → globalSettingsPersistAcrossReopenWithoutReadingOrOverwritingLegacyRules`.
- **JVM `data/integration/ReminderInputsTest.kt`**: Адаптер теперь принимает только глобальные дни; legacy lead игнорируется, same-name ID и дубликаты строк проверяются.
  - `actualC02DefaultsMapToPurePlansAndSameNamesRemainIndependent → globalDefaultsMapToPureRenewalPlansAndSameNamesRemainIndependent`.
  - `duplicateRowsDuplicateOverridesAndForeignOverridesFailBeforePlanning → duplicateRowsFailBeforePlanningAndNoOverrideInputExists`.
  - `emptyCustomDisablesOnlyItsKindAndGlobalEmptyDoesNotOverrideCustom → emptyGlobalSetDisablesEverySubscription`.
  - `inheritanceUsesExistingEffectiveOffsetsIndependentlyAndNeverMutatesCustom → legacyCancellationLeadCannotAffectGlobalRenewalPlan`.
- **JVM `data/settings/SettingsTest.kt`**: Новая форма Settings: 16 глобальных сочетаний, missing/empty, retired keys и безопасная валидация.
  - `defaultsAndCodecRoundTrip`.
  - `emptyOffsetsAndNullableCurrencyPersist → retiredKeysAreInertAndPreservedAcrossCurrentPreferenceWrites`.
  - `invalidValuesFailExplicitlyWithoutSilentReset → invalidCurrentContractFailsAndLegacyMembersAreSafelyFiltered`.
  - `remT01T03T04AndDbT06IndependentInheritanceAndCustom → allArbitraryAllowedGlobalSetsRoundTripIncludingZero`.
  - `remT05T06EmptyCustomDisablesOnlyOneTypeAndZeroIsSupported → absentAndExplicitlyEmptyAreDistinct`.
- **JVM `domain/lifecycle/LifecycleCommandsTest.kt`**: Удалена только ссылка на автоархив; известный/неизвестный end и исключение из прогноза сохранены.
  - `lifeT10T11CancellationPreservesKnownEndAndNeverInventsUnknown`.
- **JVM `domain/reminders/ReminderRulesTest.kt`**: Один тип события вместо отмены, прежние календарь, DST, boundary/recovery и stable keys.
  - `amsterdamAutumnOverlapChoosesEarlierOffsetAndOneLogicalOccurrence`.
  - `amsterdamSpringGapUsesFirstValidTimeWithoutChangingLogicalDateOrTime`.
  - `automaticMonthlyAnchorReturnsFromFebruaryToMarchAndManualDoesNotAdvance`.
  - `calendarUnderflowAndOutOfRangeInstantDoNotReturnPartialPlans`.
  - `coincidentReasonsRetainBothTypesDatesAndLogicalKeysInOneMessage → duplicateOccurrencesRetainOneRenewalReasonInOneMessage`.
  - `customDayWeekAndCalendarMonthIntervalsUseDomainCalendar`.
  - `delayedReasonsCrossLocalMidnightAndYearWithoutResurrectingHandledKeys`.
  - `deterministicOrderingStableIdsAndIndependentUuidsExcludeLogicalDuplicates`.
  - `futureOccurrencesAreNotRecoveryCandidates`.
  - `independentPaymentAndDeadlineDatesUseTheirOwnOffsets → onlyRenewalDatesUseTheGlobalOffsets`.
  - `initialSetupNeverInventsBackdatedWarningsOrRecovery`.
  - `invalidOffsetsLeadConflictingSnapshotsAndMismatchedDatesFailExplicitly`.
  - `leapAnchorIsPreservedForFutureLeapYears`.
  - `longShutdownCollapsesThreeMissedOffsetsIntoOneCurrentReason`.
  - `nextOnlyOverrideUsesExistingCalendarAndDoesNotMutateSchedule`.
  - `noDeadlineMeansNoCancellationRegardlessOfOffsets → emptyOffsetsDisableRenewalsAndNoCancellationEventExists`.
  - `zeroIsInclusiveAtNowButNeverRescheduledAfterConfiguredTime`.
- **JVM `notifications/NotificationRulesTest.kt`**: Один канал/тип, прежние expected-copy, валюта старых записей, deny и failure contracts.
  - `blockedAppPreventsAnyReason`.
  - `blockedPermissionPreventsAnyReason`.
  - `enabledChannelsAllowBothReasons → enabledChannelAllowsRenewal`.
  - `mixedReasonsCannotBypassDisabledPaymentChannel → renewalCannotBypassDisabledChannel`.
  - `mixedReasonsHaveOneTitleAndBothBodies → renewalTitleNamesSubscriptionWithoutCancellationCopy`.
- **JVM `notifications/NotificationViewModelTest.kt`**: Механическая форма NotificationAccess, проверки разрешения и маршрутов сохранены.
  - `grantingPermissionReconcilesOnceWithoutRepeatedRefreshRequests`.
  - `restoringAChannelReconcilesAndDisablingDoesNotPublish`.
- **JVM `ui/editor/EditorStateTest.kt`**: Совместимые черновики сохраняются, старые rules/валютный выбор не восстанавливаются.
  - `savedDraftRestoresAllValues → legacyDraftDropsRetiredRulesAndRequiresForeignAmountReentry`.
- **JVM `ui/finance/FinanceViewModelTest.kt`**: Нет валютного fallback/horizon writes; fixed projection, error/overflow/month state проверяются.
  - `delayedFirstReadIsLoadingAndEmptyNeedsSuccessfulRead`.
  - `monthRestoresAndClampsOnlyWhenItLeavesWindow`.
  - `overflowIsAnErrorWithNoPartialOrZeroTotal`.
  - `removingLastSelectedCurrencyReactsWithoutResettingValidMonth → removingRubDataDoesNotFallbackToForeignAmountsOrResetValidMonth`.
  - `unavailableCurrencyFallsBackDeterministicallyAndIsSaved → retiredCurrencyAndHorizonSavedStateCannotWriteOrChangeRubProjection`.
- **JVM `ui/settings/SettingsViewModelTest.kt`**: Retired time/cancellation intents заменены проверками безопасного отбрасывания; idempotent patches/read/pending/retry сохранены.
  - `failedReadIsErrorAndRetryDoesNotResetStoredPreferences`.
  - `invalidTimeDoesNotStartWrite → retiredCancellationIntentCannotBeRetried`.
  - `preferencePatchesAreIndependentIdempotentAndPreserveOtherFields`.
  - `rawTimeDraftSurvivesRestoreAndInvalidInputNeverWrites → malformedOrUnknownPendingPreferenceNeverWritesOnRestoreOrRetry`.
  - `timeDialogAndDraftRestoreWithoutSavingAutomatically → retiredTimeDialogAndIntentAreDiscardedWithoutSaving`.

Дополнительно изменены общие fixtures/preview adapters (при неизменных телах некоторых тестов):
SubscriptionsUiTest создаёт иностранные записи напрямую как синтетическую legacy fixture, а новые записи приложения — только RUB;
SettingsTestNavigation открывает единственный global dialog; debug preview DTO больше не несут rules/time controls;
ReminderWorkManagerTest авторизует новый тестовый UUID ровно один раз в @Before.
Ни одна применимая проверка хранения/миграций/сохранности ассетов/generation/preflight не удалялась из-за старого financial fixture.

### Добавленные методы

- I (скомпилирован, NOT RUN) `background/ReminderInfrastructureTest.kt:disablingThenReenablingGlobalDaysReschedulesPendingKeysWithoutClearingLedger`.
- I (скомпилирован, NOT RUN) `background/ReminderInfrastructureTest.kt:legacyCancellationRowsAreRetiredWithoutDeletingLedgerOrBlockingRenewals`.
- I (скомпилирован, NOT RUN) `background/ReminderInfrastructureTest.kt:obsoleteIndividualOffsetIsRetainedButCannotCreateNotification`.
- I (скомпилирован, NOT RUN) `background/ReminderInfrastructureTest.kt:oldPendingTimeMovesToNineWithoutReplacingLogicalKeysOrDuplicatingDelivery`.
- I (скомпилирован, NOT RUN) `background/ReminderWorkManagerTest.kt:futureUnknownPolicyFailsWithoutOpeningOrRequestingWork`.
- I (скомпилирован, NOT RUN) `background/ReminderWorkManagerTest.kt:legacyDeliveryControlAndWakeFinishWithoutOpeningStorage`.
- I (скомпилирован, NOT RUN) `background/ReminderWorkManagerTest.kt:retireOnlyLegacyReminderQueueAndKeepUnrelatedWork`.
- I (скомпилирован, NOT RUN) `data/integration/SubscriptionUseCasesTest.kt:foreignLegacyPriceRetainsCurrencyAcrossEditArchiveRestoreAndReopen`.
- I (скомпилирован, NOT RUN) `data/integration/SubscriptionUseCasesTest.kt:repeatedDateRefreshNeverArchivesOrRestoresAnyExistingState`.
- I (скомпилирован, NOT RUN) `data/integration/SubscriptionUseCasesTest.kt:startupAndResumeDateRefreshKeepMutationGuardAndRequestReconciliationWithoutRoomWrites`.
- I (скомпилирован, NOT RUN) `data/settings/SettingsIntegrationTest.kt:explicitEmptyGlobalSetStaysDisabledAfterFinalLeaseReopen`.
- I (скомпилирован, NOT RUN) `data/settings/SettingsIntegrationTest.kt:legacyStoredValuesAreIgnoredAndRemainIntactAfterCurrentWriteAndReopen`.
- I (скомпилирован, NOT RUN) `notifications/NotificationNavigationUiTest.kt:unknownOldNotificationActionCannotMutateSubscriptionOnLaunchOrRecreation`.
- I (скомпилирован, NOT RUN) `notifications/NotificationPublisherTest.kt:preUpdateCancellationNotificationIsRemovedWithoutAcknowledgingDelivery`.
- JVM `ui/details/DetailsViewModelTest.kt:savedReactivationCurrencyChangeIsDiscardedWithoutExecuting`.
- JVM `ui/details/NonFinancialContractTest.kt:retiredSettingsAndPerSubscriptionReminderCommandsHaveNoRuntimeContract`.
- JVM `ui/editor/EditorStateTest.kt:compatibleRubDraftRestoresAndRetiredFieldChangesAreIgnored`.
- JVM `ui/settings/SettingsDialogStateTest.kt:oldNullableBooleanDialogStateIsDiscardedAndCurrentGlobalDialogRoundTrips`.

## Изменённые файлы

Пути относительно корня репозитория (включая удалённый read-model DTO):

```text
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderInfrastructureTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/background/ReminderWorkManagerTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCasesTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/lifecycle/LifecycleRepositoryTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationNavigationUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationPublisherTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/notifications/ReminderSystemRegressionTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/AccessibilityUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/ArchiveSettingsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/DetailsEditorRedesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SettingsTestNavigation.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/SubscriptionsUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceIntegrationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceNavigationTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/LargeFontChartUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/finance/MonthlyChartUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/CrossScreenDesignUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/KeyboardRotationUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/review/LargeFontWindowUiTest.kt
app/src/androidTest/java/com/glebskorobogatov/subscriptiontracker/ui/settings/ArchiveSettingsIntegrationTest.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/app/ReminderSystemProbeReceiver.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/app/StorageFailureTestActivity.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/ArchiveSettingsPreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/DetailsEditorPreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/preview/FinancePreviews.kt
app/src/debug/java/com/glebskorobogatov/subscriptiontracker/ui/review/DesignReviewGallery.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/background/ReminderRuntime.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/background/ReminderWork.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/ReminderInputs.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionEditor.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/integration/SubscriptionUseCases.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/lifecycle/LifecycleRepository.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/reminders/ReminderLedger.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/reminders/ReminderScheduling.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/Daos.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/room/SubscriptionRelations.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/settings/Settings.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsRepository.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleCommands.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/domain/reminders/ReminderRules.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/notifications/AndroidReminderPublisher.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/notifications/ReminderText.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceViewModel.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsScreen.kt
app/src/main/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsViewModel.kt
app/src/main/res/values/notifications.xml
app/src/main/res/values/strings.xml
app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/integration/ReminderInputsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/data/settings/SettingsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/lifecycle/LifecycleCommandsTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/domain/reminders/ReminderRulesTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationRulesTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/notifications/NotificationViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/DetailsViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/details/NonFinancialContractTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorStateTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/editor/EditorTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/finance/FinanceViewModelTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsDialogStateTest.kt
app/src/test/java/com/glebskorobogatov/subscriptiontracker/ui/settings/SettingsViewModelTest.kt
docs/reports/R1b.3.md
```

## Следующие этапы и независимая приёмка

R1b.4 — новая схема и полный runtime mapping R1a, снятие legacy tables/моделей и окончательная семантика состояний.
R1b.5 — итоговый поиск отменённых API/маршрутов/ресурсов, проверка необходимости work compatibility guards.
R2 — конечная календарная модель прогнозов/напоминаний, границы окна/короткие интервалы, системная приёмка очереди/reboot/recovery.
Ни один из этих этапов не начат. R1b.3 подготовлен для независимой приёмки с перечисленными NOT RUN;
без запуска инструментальных сценариев не следует считать доказанными новые гарантии WorkManager/ledger/IO/UI на устройстве.
Merge не выполнять, дальнейшая работа — только после приёмки владельцем.
