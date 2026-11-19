# R5b — детали подписки, отмена, архив и восстановление

## База и границы

Принятая база: ветка `codex/r5a-editor`, SHA `eff559876febbab87c2b1ead8d42dda5e3542db4`, Draft PR #12.
Ветка `codex/r5b-details-archive` создана от этого SHA. SHA реализации: **9b5006131db3c10869f3bcc5a0ee8e00d06cb959**.
SHA коммита с этим отчётом записан в описании Draft PR. Самоссылка в файле не используется.

Изучены AGENTS, PRD, ARCHITECTURE, UI_DESIGN_SPEC §3.3, §3.5 и §3.7, IMPLEMENTATION_PLAN, TEST_PLAN, DECISIONS №29, №31, №44, №45, №69, №70, №77, №84, №85, №107, №108, №109, №112, №124 и отчёты R3, R4a, R4b, R5a, REG-01, REG-02.
Затронуты R-LIFE-01…06, R-UI-01/04/05/06/07/09, R-SUB-06/07, R-DATA-01/02 и проверки T-LIFE-01…05, T-UI-01/03, T-DATA-02.

R6a, R6b, R7, R8 и H не начаты. Редактор R5a, Room v5, денежная и календарная арифметика, ForecastEngine, ReminderRules, WorkManager, ledger, AssetStore, DataStore, `PreferencesSettingsRepository`, `ExitBinding`, Manifest, applicationId, Gradle, зависимости, Backup/D2D и продуктовые требования не менялись. PR #12 и более ранние PR не менялись. Merge, force push и ручной запуск Actions не выполнялись. PERSONAL-READY не объявляется.

## Экран деталей

Детали остаются вложенным экраном без навигационного островка. Шапка: стрелка «Назад», заголовок «Подписка», меню из трёх точек. Прежняя кнопка редактирования в шапке и раскрывающийся блок управления убраны.

Основной блок выровнен влево (решение №124). Значок деталей — `TrackerDimensions.detailsLogoSize`, 72 dp. Строки списка и редактора остаются 54 dp. Рядом с значком — название и подпись, ниже сумма через `TrackerMoney` и периодичность. При `fontScale` от 1.5 или ширине меньше 360 dp значок и текст складываются вертикально. Длинная сумма не обрезается многоточием и не теряет копейки.

Дальше идут состояние, ближайшая дата или дата окончания доступа, оставшееся время и `TrackerPeriodIndicator`, когда доля периода определена. В день окончания доля пустая, индикатор не рисуется, плашка «Отменена» остаётся. Архивная запись без проекции показывает сохранённый `cancelledAccessEndExclusive`, иначе `archivedAt`. Если обеих дат нет, остаётся прежняя строка о неизвестной дате. Дата не выдумывается.

История платежей, ручная оплата, история цен, выбор валюты и режимы подписок не возвращались.

## Матрица действий

Одна широкая кнопка `details_primary`. То же действие в меню не дублируется.

| Состояние | Основная кнопка | Меню |
|---|---|---|
| ACTIVE | Редактировать, существующий редактор R5a | Отметить отмену, Архивировать, Удалить подписку |
| CANCELLED | Возобновить, форма проверки параметров | Редактировать, Изменить окончание доступа, Архивировать, Удалить подписку |
| ARCHIVED | Восстановить, та же форма | Редактировать, Удалить подписку |

Красный цвет только у «Удалить подписку» и у ошибок. Меню закрывается выбором, Back и нажатием вне него. Повторный запуск операции не проходит: `submit` выходит, пока фаза `OperationInProgress`, действие пустое или есть конфликт.

## Отмена и окончание доступа

Отмена по-прежнему `LifecycleCommand.Cancel`. В поле `details_field_end` подставляется ближайшая дата продления как первый день без доступа. Её можно заменить календарём редактора `EditorDateSheet`. Второй календарный движок не создавался. Пояснение дословно: «Отметь отмену у поставщика. Приложение не отменяет подписку за тебя. Будущие ожидаемые платежи и напоминания будут исключены».

После успешного сохранения состояние CANCELLED, UUID, цена, расписание и оформление те же. Будущие платежи и напоминания исключаются сразу. Автоархива нет. Отказ от диалога данные не меняет.

Изменение окончания доступно только для CANCELLED и идёт через `LifecycleCommand.EditEnd`. Подписка не возобновляется, опора расписания не переписывается. Тег `details_field_end` сохранён.

Формы отмены, окончания и возобновления — поверхность внутри окна, а не вложенный `ModalBottomSheet`, чтобы лист календаря открывался поверх. Удаление и архив — отдельные центрированные диалоги.

## Архив, возобновление, удаление

Архив только ручной, `LifecycleCommand.Archive`. Для активной записи диалог объясняет исчезновение из списка, статистики и напоминаний. Для отменённой — подтверждает перенос. UUID, цена, частота, название, подпись, каталожный значок или своё изображение и архивные метаданные сохраняются. Переход назад выполняется только после успешного commit: `popBackStack`, а если стек пуст — главный список. Ошибка оставляет повтор той же операции или reload.

Возобновление и восстановление — `LifecycleCommand.Reactivate` в той же форме, что редактор R5a: цена, капсулы Неделя / Месяц / Квартал / Год / Другая, ближайшая дата, необязательная опора. Шесть месяцев задаются через «Другая». Флаг `customFrequency` хранится в SavedStateHandle как `frequencyCustom` и не схлопывает произвольный режим, когда unit и count совпали с пресетом. Новая запись и новый корневой маршрут не создаются. Выбор валюты не возвращался. Только явное «Сохранить» ставит ACTIVE, сохраняет UUID и оформление, снимает окончание доступа и пересчитывает статистику и напоминания. «Отмена» оставляет CANCELLED или ARCHIVED.

Удаление — диалог с предупреждением о необратимости. Кнопка подтверждения красная и называется «Удалить». Пункт меню остаётся «Удалить подписку». Удаляется выбранная запись, её уведомления и задачи снимаются прежним контрактом, ledger чистится безопасно, файл изображения удаляется только когда других ссылок нет. Отказ ничего не меняет. Назад переход только после успеха. Ошибка не объявляется успехом.

Список архива открывается из настроек. Back и заголовок сохранены. Строка приглушённая по вторичному тексту, но контрастная: значок 54 dp, название, подпись, сумма через `TrackerMoney`, периодичность, дата окончания или архивирования, если она есть. Индикатора периода нет. Длинная сумма складывается по правилу REG-01: ширина меньше 240 dp, шрифт от 1.5 или измеренная цена шире 45% строки. Порядок сортировки `ArchiveViewModel` не менялся. Пустой архив — короткое пояснение. Loading и Error этим текстом не подменяются. Открытие строки ведёт в обычные детали. Back возвращает архив: запись деталей лежит поверх той же записи стека, позиция списка сохраняется.

## Навигация и гарантии

`DetailsViewModel` сохраняет snapshot, поколение, frozen operation, повтор той же операции при неопределённом исходе, явный reload конфликта и запрет ложного успеха. `archived` ставится только после успешного Archive и в SavedStateHandle не пишется. Меню — `rememberSaveable`. Поле открытого календаря тоже. Back сначала закрывает меню, затем лист даты, затем форму. Пока операция идёт или результат frozen, экран не покидается.

`exitRequest` деталей по-прежнему передаётся как 0. `ExitBinding` и `PreferencesSettingsRepository` не менялись. Уведомление на уже открытые детали остаётся `AlreadyVisible` и не закрывает диалог. Черновик редактора и идущая запись не прерываются.

## Первоначальные падения

До исправления, на API 31:

- `performScrollTo` у закреплённых «Сохранить» редактора и формы деталей требовал прокручиваемого предка. Кнопки вне прокрутки переведены на `performClick`.
- Второй `create` в том же тесте ждал пустой текст «Добавить подписку». После первой записи остаётся описание кнопки в шапке.
- У колонки `details_price` в слитой семантике нет `Text`. Проверка суммы идёт по `formatTrackerRub`.
- `settings put system font_scale` из тела Compose-теста ждал смены конфигурации на главном потоке, на котором уже шёл тест. Вызов убран из теста и из `@After`. Масштаб 2 проверяет `LargeFontReviewActivity`. Отдельный кадр системного масштаба снимается до запуска Activity.
- Поворот после `setContent` терял иерархию, повторный `setContent` запрещён. Landscape-проверка масштаба 2 поворачивает окно до единственного `setContent`.
- Удаление неиспользуемых строк деталей подняло UnusedResources с 1 до 6: `editor_year`, `editor_count`, `editor_days`, `editor_weeks`, `editor_months` больше не читаются. Они удалены. Капсула года по-прежнему берёт `editor_unit_year`. `welcome_message` оставлен.
- Архивная карточка показывала «Дата следующего события неизвестна» при сохранённом окончании доступа. Теперь показывается эта дата.
- `captureToImage` у семантики `dialog()` без отдельного окна падал в галерее и landscape-диалоге. Снимок берёт `ui06_viewport`. Проверки доступности кнопок в том тесте выполнялись до снимка и в первом прогоне уже проходили.

## JVM и сборка

`git diff --check`: PASS, вывод пустой.

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain`

| Проверка | Результат |
|---|---|
| :app:testDebugUnitTest | PASS, 405 тестов, 0 failures, 0 errors, 0 skipped. База R5a была 403. Добавлены 2 теста `DetailsViewModelTest` |
| :app:assembleDebug | PASS |
| :app:lintDebug | PASS, 0 ошибок, 19 предупреждений |
| :app:compileDebugAndroidTestKotlin | PASS |
| Общая команда | BUILD SUCCESSFUL in 1m 9s |

Предупреждения те же: GradleDependency 9, NewerVersionAvailable 6, AndroidGradlePluginVersion 2, ObsoleteSdkInt 1, UnusedResources 1 (`welcome_message`).

Повтор той же команды на дереве `9b50061`: **PASS**, BUILD SUCCESSFUL in 33s. Unit-тесты, сборка APK, разбор lint и компиляция androidTest были UP-TO-DATE: исходники main не менялись после прогона 1m 9s, а запасной снимок галереи уже был скомпилирован полным набором. Lint снова 0 ошибок и 19 прежних предупреждений.

Два новых JVM-теста: произвольная частота переживает новый `DetailsViewModel` на том же SavedStateHandle и не схлопывается в пресет; отказ от операции не ставит `archived`, frozen блокирует повторную запись.

## Android, API 31

Среда ENV-01: JDK 21.0.13, Android SDK `/opt/android-sdk`, Build Tools 35.0.0, Gradle Wrapper 8.13, эмулятор `emulator-5554`, Google APIs x86_64, API 31. `--tests` не используется. Системный `font_scale` после отдельных кадров возвращён к 1.0.

Первый полный набор на коде до запасного снимка: **465 тестов, 462 PASS, 3 FAIL, 0 skipped**, 12m 46s. Все три — `Could not find a dialog window provider to capture its bitmap`:

- `CrossScreenDesignUiTest.galleryAllCurrentScreensLight`
- `CrossScreenDesignUiTest.galleryAllCurrentScreensDark`
- `LargeFontWindowUiTest.landscapeDialogsKeepConfirmationAndCancelReachableAtLargeFont`

Повтор этих двух классов после запасного снимка: **14/14 PASS**, 1m 29s.

Повтор полного набора на итоговом дереве: **465/465 PASS, 0 skipped, 0 failed**, 12m 39s. База R5a была 454. Добавлены 11 тестов: 8 в `R5bLifecycleUiTest`, 1 в `R5bSharedAssetUiTest`, 2 в `R5bFontScaleUiTest`.

Покрыты матрица трёх состояний, выравнивание и 72 dp, отмена с сегодняшней датой, календарь окончания, исчезновение индикатора в день окончания, архив активной и отменённой записи, возобновление и восстановление с новой ценой и периодом, MONTH/1 внутри «Другая», сохранение UUID и каталожного значка, отказ от операций, удаление одной из двух записей с общим изображением, конфликт и frozen, двойное подтверждение архива, пересоздание формы отмены, Back календаря раньше формы, возврат на список после архива и удаления, пустая статистика после отмены и сумма `640,00 RUB` после восстановления, меню и форма при landscape и font scale 2.

`captureWhenSystemFontIsEnlarged` в обычном наборе сразу выходит, если `fontScale` меньше 1.5, и системную настройку не меняет. Кадры системного масштаба 2 сняты отдельным запуском: настройка выставлена до старта Activity и после снимка возвращена к 1.0.

API 33: **NOT RUN**. TalkBack на устройстве: **NOT RUN**. Photo Picker: **NOT RUN**. Process death: **NOT RUN**. Семантика Compose не считается физической проверкой TalkBack.

## Визуальные кадры

Синтетические данные, в Git не входят. Каталог кадров: `r5b-details-active`, `r5b-details-cancelled`, `r5b-details-cancelled-ended`, `r5b-details-archived`, `r5b-details-dark`, `r5b-details-landscape`, `r5b-menu-active`, `r5b-menu-cancelled`, `r5b-menu-archived`, `r5b-menu-landscape`, `r5b-cancel-dialog`, `r5b-end-calendar`, `r5b-resume-form`, `r5b-restore-form`, `r5b-archive-confirm`, `r5b-archive-filled`, `r5b-archive-empty`, `r5b-delete-confirm`, `r5b-details-font-2`, `r5b-menu-font-2`, `r5b-cancel-font-2`, `r5b-cancel-landscape-font-2`, `r5b-details-system-font-2`, `r5b-menu-system-font-2`, `r5b-details-system-font-2-landscape`.

Тёмные архивные детали показывают «Доступ до 2026-11-28», а не неизвестную дату. Пустой архив — «В архиве пока пусто». Подтверждение удаления — красная «Удалить».

## Изменённые файлы

`DetailsScreen.kt`, `DetailsViewModel.kt`, `ArchiveScreen.kt`, `TrackerApp.kt`, `TrackerTokens.kt`, `strings.xml`, `ic_more_vert.xml`, `ic_edit.xml`, `ic_event.xml`, `ic_delete.xml`, `DetailsEditorPreviews.kt`, `DetailsViewModelTest.kt`, `DetailsUiTest.kt`, `DetailsEditorRedesignUiTest.kt`, `DetailsTestNavigation.kt`, `ArchiveSettingsUiTest.kt`, `ArchiveSettingsRedesignUiTest.kt`, `R5aEditorUiTest.kt`, `EditorUiTest.kt`, `SubscriptionsUiTest.kt`, `AccessibilityUiTest.kt`, `NotificationNavigationUiTest.kt`, `NotificationNavigationRaceUiTest.kt`, `CrossScreenDesignUiTest.kt`, `LargeFontWindowUiTest.kt`, `ReviewArtifacts.kt`, `R5bLifecycleUiTest.kt`, `docs/reports/R5b.md`.

Остановиться до независимой приёмки. Следующий этап не начат.

## R5b-fix — завершение приёмки деталей

Исходный SHA `dd7300f70e78cc3b42b76c89d9a582296692ad1f`, ветка `codex/r5b-details-archive`, база `codex/r5a-editor`. Проверены чистое рабочее дерево, совпадение HEAD с Draft PR #13 и область изменений. Итоговый SHA реализации `33e965829ab0a8e6f110f59e3b871c09e4f8c5d5`. Коммит с результатами этой проверки находится поверх него в том же [Draft PR #13](https://github.com/GlebSkorobogatov/subscriptions-android/pull/13).

### Замечания и исправления

- **Восстановление после удаления (R-LIFE-05, T-LIFE-05).** У диалога удаления отсутствовала кнопка reload, хотя ViewModel уже поддерживала сброс конфликтного или неопределённого черновика и повторное чтение. «Перезагрузить данные» теперь вызывает `refresh` для обоих случаев. Во время записи повтор, закрытие и reload заблокированы. При конфликте следующий запуск требует нового явного подтверждения со свежим снимком. При потерянном ответе доступен повтор с прежним снимком или reload. Если запись отсутствует, после reload отображается NotFound и действие безопасного возврата без вызова callback успешного удаления. Ошибка наблюдения и отмена корутины обработаны так, чтобы первая не разблокировала ещё идущую запись, а вторая оставляла проверяемый frozen-черновик.
- **Гонка навигации (R-UI-04, R-LIFE-03/05, T-UI-03, T-NOT-06).** Раньше уведомление могло открыть B во время операции A, а завершение экрана A вызывало обычный `popBackStack()` над B. Теперь callback закрывает только владеющий `NavBackStackEntry`. Запрос уведомления остаётся в Activity-scoped ViewModel, пока операция текущих деталей выполняется. После завершения подтверждённо архивированные/удалённые детали закрываются и цель перечитывается. Если цель ещё существует, открывается её актуальная запись. При отсутствующей цели открывается список. При ошибке операции с доступной записью можно открыть B, затем вернуться к ошибке A. Повторный запрос использует видимую или уже существующую запись маршрута. Повторное уведомление для A во время операции проходит эту проверку вместо раннего подавления дубля. Возврат редактора продолжает использовать ExitBinding из REG-02.
- **Модальное окно формы (R-UI-04/09, T-UI-03/08).** Семантика `dialog()` в окне Activity оставляла фон частью той же иерархии фокуса. Форма стала отдельным Compose Dialog с прежней нижней поверхностью, формой и цветами. Календарь открывается поверх неё. Back закрывает верхнее окно первым, обычный Back в форме её закрывает. Для busy и frozen отключены уход по Back и касанию фона. Поля прокручиваются, в обычном окне кнопки закреплены. При высоте формы менее 220 dp с учётом insets и IME они прокручиваются вместе с полями. Если скрытая опора не согласуется с датой и периодичностью, используется проверка действующего контракта `RecurringSchedule.starting`, поле раскрывается, прокручивается в видимую область и получает пояснение ошибки.

### Изменённые файлы и регрессии

- `ui/details/DetailsScreen.kt`, `DetailsViewModel.kt`, `res/values/strings.xml` — reload, NotFound, модальная форма, индикация ошибочной опоры.
- `ui/navigation/TrackerApp.kt`, `notifications/NotificationViewModel.kt` — ожидание операции, проверка владельца, повторное разрешение цели. Существующие NotificationNavigationResolver, NotificationNavigationSession и ExitBinding используются без правок.
- `debug/.../app/IsolatedMainActivity.kt` — пауза только в тестовой среде перед реальной записью, подтверждение прочитанного запроса уведомления и моделирование потерянного ответа после записи.
- `DetailsViewModelTest.kt` — четыре новых JVM-теста для конфликта, неопределённого результата с пропавшей записью, ошибки чтения во время записи и исправления опорного дня. Проверка CancellationException дополнена ожиданием выхода из busy без подтверждения результата.
- `DetailsOperationNavigationUiTest.kt` — семь Android-сценариев с управляемой паузой. Архивирование и удаление с уведомлением B, удаление с пересозданием Activity, уведомление A при архивировании и при удалении, конфликт с reload, потеря ответа с NotFound, блокировка закрытия формы в busy/frozen. После возврата проверяются стек и сохранённая строка Room.
- `R5bLifecycleUiTest.kt` (классы `R5bLifecycleUiTest` и `R5bFontScaleUiTest`) — три Android-сценария для фонового Back/касания, исправления скрытой опоры и работы поля и действий при landscape и font scale 2. Прежний сценарий закрытия календаря перед формой сохранён.

Хранилище, схема Room, календарная и денежная арифметика, прогноз, reminders/WorkManager/ledger, ассеты, настройки, конфигурация сборки и Manifest проверены по diff. Изменения ограничены слоем деталей, навигацией и синтетическими тестами.

### Среда и результаты

Codex Cloud, Temurin JDK 21.0.9, Android SDK 36, Build Tools 35.0.0, Gradle Wrapper 8.13. ENV-01 из Cursor в этой среде недоступен. В SDK отсутствует бинарник эмулятора, /dev/kvm отсутствует, ADB завершился до получения списка устройств из-за недоступного каталога настроек.

```sh
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

| Проверка | Фактический результат |
|---|---|
| git diff --check | PASS |
| :app:testDebugUnitTest | PASS, 409 выполнено, 0 failures/errors/skipped |
| :app:assembleDebug | PASS |
| :app:lintDebug | PASS, 0 ошибок, 8 предупреждений в отчёте Codex |
| :app:compileDebugAndroidTestKotlin | PASS, 475 аннотаций @Test в скомпилированных классах |
| Обязательная Gradle-команда | BUILD SUCCESSFUL, 2m 44s |

Первый прогон обязательной команды тоже завершился успешно за 2m 51s. Затем уточнено поведение при отмене операции и добавлена проверка закрытия locked-формы. Первоначальных падений Gradle-задач и JVM-тестов в R5b-fix не было. У пробного запуска ADB был отказ из-за недоступного каталога, устройство проверить не удалось.

В отчёте Codex шесть предупреждений NewerVersionAvailable, одно ObsoleteSdkInt и одно UnusedResources. Прежние 19 предупреждений относятся к окружению Cursor. Для разницы в lint не вносились изменения Gradle или зависимостей. Room schema exports сверены побайтово с базой.

Android-прогоны новых методов, классов деталей/архива/навигации и полного набора — **NOT RUN**. Результат прежнего прогона R5b на API 31 относится к исходной версии. `captureWhenSystemFontIsEnlarged` даже при зелёном общем прогоне со штатным шрифтом не проверяет системный увеличенный шрифт. Для повторной проверки требуется выставить масштаб до запуска Activity. API 33+, физический TalkBack, Photo Picker, process death, реальное поведение IME и новые скриншоты — **NOT RUN**. Состояние готово для независимой статической приёмки, фактическое поведение окон и гонки ещё требуют Android-прогона.

## R5b-verify — инструментальная приёмка

Исходный SHA `1c32a47399c0dbed018f2e19cb15143239bc3669`, ветка `codex/r5b-details-archive`, база `codex/r5a-editor`, Draft PR #13. Исправление навигации: `977b5b60857e7b59f9b576431ea5ccffa45fca4a`. Коммит с этим разделом лежит поверх него в том же PR. PR #12 не менялся. Merge, force push и ручной запуск Actions не выполнялись. R6a не начат. PERSONAL-READY не объявляется.

Среда ENV-01: Temurin/OpenJDK 21.0.13, Android SDK `/opt/android-sdk` платформа 36, Build Tools 35.0.0, Gradle Wrapper 8.13, `/dev/kvm` доступен. Эмулятор `api31`, `emulator-5554`, Google APIs x86_64, API 31. На старте рабочее дерево чистое, HEAD совпал с указанным SHA.

### Первые падения и повторы

- `DetailsOperationNavigationUiTest.popBackStackToExistingRouteKeepsUnresolvedFrozenDraft`, код до исправления: **FAIL**, 3.68 с. `IllegalStateException: Method setCurrentState must be called on the main thread` в `TrackerApp` на `navigate` после `popBackStack(route)`. `details()` собирается через `flowOn(Dispatchers.Default)`. Когда уведомление приходит уже после потерянного ответа, продолжение корутины оказывается вне главного потока, а `NavController` меняет lifecycle записи только на нём. До правки отдельные прогоны `archiveWaitsForItsOwnExitBeforeOpeningTheOtherDetails` и `conflictedDeleteKeepsItsErrorAfterTheOtherDetailsAndReloadsWithoutDeleting`: **PASS**. После `withContext(Dispatchers.Main.immediate)` тот же новый метод: **PASS**.
- Запрошенные классы на исправленном коде, первый проход: **71 тест, 70 PASS, 1 FAIL**, 4 мин 36 с. Единственное падение — `R5bLifecycleUiTest.modalFormBlocksTheBackgroundBackButtonAndNativeBackClosesOnlyTheForm`: сразу после `pressBack` семантика ещё содержала `details_dialog`. Касание фона уже вызывало `dismiss`. Повтор без ожидания кадра: снова **FAIL**. С `waitForIdle` после касания и после Back: **PASS**. Отдельный обработчик клавиш в форму не добавлялся: после кадра диалог закрыт, запись деталей на месте. Повтор всех восьми классов: **71/71 PASS**, 6 мин 39 с.
- Полный набор: **476 тестов, 475 PASS, 1 FAIL, 0 skipped**, 16 мин 52 с. Падение — `R5aEditorUiTest.presetsCustomCalendarAndValidation`, строка выбора `frequency_unit_MONTH`: после `performClick` узел остался `Selected = false`. Изолированный повтор этого метода: **PASS**, 32 с. Редактор и капсулы частоты в этом проходе не менялись. Это не регрессия R5b-fix.

### Что исправлено

`TrackerApp` выполняет чтение стека, `leaveDetails`, `popBackStack(route)` и `navigate` внутри `withContext(Dispatchers.Main.immediate)`. Решение уведомления и контракт владельца записи не переписывались. Новый сценарий архивирует запись с потерянным ответом, открывает другую карточку и возвращается к первой через `popBackStack(route)`. Диалог неподтверждённого черновика остаётся, второй экземпляр деталей не создаётся. После reload Back открывает список, а не чужую карточку. В Room строка остаётся `ARCHIVED`.

`modalFormBlocksTheBackgroundBackButtonAndNativeBackClosesOnlyTheForm` ждёт кадр Compose после системного касания и Back. Условие проверки то же: диалог закрыт, экран деталей и состояние «Активна» на месте.

### Команды

`git diff --check`: **PASS**.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2 --console=plain
```

Команда на итоговом дереве с исправлением навигации: **BUILD SUCCESSFUL**, 1 мин 2 с. Отчёт lint этой команды остался прежним файлом (`lintReportDebug` UP-TO-DATE). Отдельный `:app:lintDebug --rerun-tasks` пересобрал отчёт за 1 мин 30 с.

| Проверка | Результат |
|---|---|
| :app:testDebugUnitTest | PASS, 409 выполнено, 0 failures/errors/skipped |
| :app:assembleDebug | PASS |
| :app:lintDebug | PASS, 0 ошибок, 19 предупреждений |
| :app:compileDebugAndroidTestKotlin | PASS, 476 аннотаций @Test |
| Запрошенные Android-классы, повтор | PASS, 71/71, 0 skipped |
| Полный Android-набор, первый проход | 476 тестов, 475 PASS, 1 FAIL, 0 skipped, 16 мин 52 с |
| Повтор `presetsCustomCalendarAndValidation` | PASS |

19 предупреждений ENV-01 те же, что в первом прогоне R5b на Cursor: GradleDependency 9, NewerVersionAvailable 6, AndroidGradlePluginVersion 2, ObsoleteSdkInt 1, UnusedResources 1 (`welcome_message`). Восемь предупреждений из отчёта Codex относятся к другой среде. Gradle и зависимости не менялись.

Запрошенные классы: `DetailsOperationNavigationUiTest`, `R5bLifecycleUiTest`, `R5bFontScaleUiTest`, `DetailsUiTest`, `NotificationNavigationUiTest`, `NotificationNavigationRaceUiTest`, `LargeFontWindowUiTest`, `CrossScreenDesignUiTest`.

### Системный font scale 2

`captureWhenSystemFontIsEnlarged` в обычном наборе по-прежнему выходит сразу, если масштаб меньше 1.5. Отдельный запуск: `settings put system font_scale 2` до старта Activity, затем только этот метод. **PASS**, 8.648 с. Снимки `r5b-details-system-font-2`, `r5b-menu-system-font-2` и `r5b-details-system-font-2-landscape` записаны, раннего выхода не было. На портретных кадрах крупный текст и меню с переносом «Удалить подписку». Исходное значение настройки было не задано. После проверки `settings delete system font_scale`, чтение снова `null`.

### Ограничения

API 33+, физический TalkBack, Photo Picker, process death и отдельная проверка реальной клавиатуры вне уже прошедших тестов — **NOT RUN**. Остановиться до независимой приёмки. Следующий этап не начат.
