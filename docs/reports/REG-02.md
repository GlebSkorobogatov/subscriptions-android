# REG-02 — закрытие оставшихся регрессий

## База и границы

Ветка: `codex/r5a-editor`. Исходный SHA: `a7956a908f42aa749a4867ef62d7804425aa363d`. Draft PR #12, база `codex/r4b-settings`.
Среда ENV-01 не менялась. PR #11 не менялся. Новый PR не создавался. Merge, force push и ручной запуск Actions не выполнялись. R5b не начат. PERSONAL-READY не объявляется.

Восемь регрессий REG-01 и три исправления REG-01-fix не переоткрывались. Room, денежная и календарная арифметика, AssetStore, Gradle и зависимости не менялись.

## 1. FinanceIntegrationTest

`livePriceDateAndLifecycleChangesRecomputeOnlyActiveForecasts` заканчивался значением `await`, поэтому `runBlocking` возвращал `FinanceSnapshot`. JUnit отвергал весь класс: 9 методов не запускались, вместо них был `initializationError`.

Метод объявлен как `Unit`. Проверки 24 начислений, суммы 3600, отмены, архива и пустого прогноза сохранены. В полном прогоне API 31 у класса 9 методов, все успешны.

## 2. StorageOpeningTest

`PRAGMA wal_autocheckpoint=0` возвращает прежний порог. Android SQLite не выполняет такой оператор через `execSQL` (`SQLITE_OK`: запрос только через `query` или `rawQuery`). Падение было до `TrackerDatabaseFactory.open`.

В тесте порог читается через `rawQuery` на соединении открытой транзакции. Производственное хранилище не менялось. WAL-фикстуры, отказ несовместимой схемы и неизменность байтов при отказе сохранены. `committedWalSchemaIsInspectedAndNotIgnoredOrCheckpointedOnRejection` и `compatibleWalCommittedRowsSurvivePreflightAndRoomOpen` успешны.

## 3. Напоминание без catch-up

Тест: `ReminderSystemRegressionTest.expiredManualPaymentAndDeadlineNeverCreateCatchUpNotification`, строка 86.

`process` вернул `null`, системного уведомления не было: иначе выполнение остановилось бы раньше этих `assertTrue`. Дата продления 5 января 2027 уже прошла к 6 января. R-NOT-03 запрещает отправлять истёкшее событие. `ReminderLedger.cleanup` оставляет по таким строкам надгробие `SKIPPED`, чтобы поздний проход не собрал из них catch-up. Это действующий контракт ledger, не дефект доставки. Устаревшим было ожидание `PENDING`.

Следующее продление лежит за горизонтом 7 дней, поэтому планировщик после сверки пуст и ложная задача не ставится. Проверка теперь требует непустое множество только из `SKIPPED`, отсутствие уведомления и пустой список задач. Просроченное, повторное или ложное уведомление не создаётся.

## 4. StorageBlockedUiTest

Тело `incompatibleFailureDrawsDespiteSettingsLoadingAndRemainsBlockedAfterRestoreAndNotification` доходило до экрана блокировки. `ActivityScenario.close` не видел `DESTROYED` и останавливался на `PAUSED`.

`onNewIntent` вызывает `setIntent`. Наблюдатель ActivityScenario после этого отбрасывает события, у которых intent больше не совпадает со стартовым. `onPause` одиночного `SINGLE_TOP` ещё совпадает и записывается. Последующие `onResume` и уничтожение игнорируются. Экран блокировки при этом остаётся на месте: активность не зависает из-за производственного жизненного цикла.

После доставки уведомления тест вызывает уже существующий `followActivityIntent`, как навигационные тесты. Проверки несовместимой базы сохранены: экран блокировки, текст несовместимости, отсутствие повтора, нижней навигации и редактора до и после `recreate` и после intent. Синтетический источник по-прежнему не открывает пользовательское хранилище. Оба метода класса успешны.

## 5. Публикация настроек

`SettingsPublication` держал одно ожидаемое значение и отбрасывал любой другой снимок хранилища, пока не придёт точно такое же. Подтверждение, пришедшее раньше публикации, занимало слот и дальше глушило более новые значения. Пропущенное подтверждение оставляло фильтр навсегда. Отдельный replay мог оказаться рядом с ошибкой чтения.

Механизм заменён. `PreferencesSettingsRepository` после `edit` увеличивает поколение и заново читает DataStore. Запись сериализована. Увеличение поколения выполняется и при отмене после commit. DataStore остаётся единственным источником: пропущенное подтверждение перечитывается, более позднее значение не фильтруется, ошибка чтения не подменяется запомненным значением. Повторное чтение идёт в хранилище. Тема, анимации и offsets по-прежнему меняются одним `edit`.

JVM: `PreferencesSettingsRepositoryTest`, 9 методов. Инструментальный `settingsReadErrorRetainsThemeAndRetryUsesStoredData` успешен в полном прогоне.

## 6. Привязка выхода и recreation

`confirm` диалога хранится через `rememberSaveable`, а `ExitBinding` хранился через `remember`. После recreation диалог оставался, а идентификатор терялся. Отмена не вызывала `consumed`, поэтому единственное уведомление не снималось. Сохранение закрывало редактор и оставляло привязку следующему редактору.

`ExitBinding` теперь сохраняется тем же механизмом, что и диалог. Уже вооружённый диалог не принимает новый id. Диалог, открытый обычным Back без уведомления, после recreation остаётся без id и не перенимает позднее уведомление. Успешное сохранение снимает привязку и не потребляет запрос: после выхода решение R4a по-прежнему открывает цель. `consumed(sequence)` по-прежнему ничего не делает для чужого id.

Инструментальные проверки, все успешны:

- `recreationKeepsTheOlderExitIdAndCancelDoesNotConsumeTheNewerNotification`
- `userBackDialogSurvivesRecreationWithoutAdoptingANotification`
- `continuingTheOnlyNotificationAfterRecreationConsumesIt`
- `savingClearsTheExitBindingBeforeTheNextEditor`

## Проверки

`git diff --check`: пустой вывод.

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain`: BUILD SUCCESSFUL in 47s. JVM: 403 теста, 47 файлов, 0 failures, 0 errors, 0 skipped. К прежним 394 добавлены 9 методов репозитория и один метод восстановления привязки; прежний тест `SettingsPublication` удалён вместе с механизмом. Lint пересобран в этом запуске: 0 ошибок, 19 прежних предупреждений (`AndroidGradlePluginVersion` 2, `GradleDependency` 9, `NewerVersionAvailable` 6, `ObsoleteSdkInt` 1, `UnusedResources` 1).

Полный Android-набор на API 31, `emulator-5554`. В исходниках 454 метода `@Test`: прежние 450 плюс четыре проверки recreation. Прогон 11m 25s, в отчёте раннера 671,51 с. Зависаний не было: прогресс не останавливался на три минуты, дамп потоков не снимался.

| | Количество |
| --- | --- |
| Методов `@Test` в исходниках и в раннере | 454 |
| Успешно | 454 |
| Упало | 0 |
| Пропущено | 0 |
| Не запускалось | 0 |
| Ошибок инициализации класса | 0 |

Первый прогон исправленных методов до полного набора тоже был успешным: 20 методов, 0 падений. Повтором падение не скрывалось.

API 33, TalkBack и process death: NOT RUN.

## Что сохранено

Сохранены контракт `consumed(sequence)`, вертикальный доскролл, финансовые проверки `FinanceIntegrationTest`, байты WAL при отказе открытия и запрет обхода несовместимой базы. Восемь регрессий REG-01 не переоткрывались.

Остановиться до независимой приёмки ChatGPT. R5b не начинать. PERSONAL-READY не объявлять.
