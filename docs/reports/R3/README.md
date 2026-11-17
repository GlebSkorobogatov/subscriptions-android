# Визуальные материалы R3

**Рендеринг: NOT RUN. Реальных PNG в этой папке пока нет.**
Android-устройство/эмулятор недоступны (adb devices завершился −6). В настроенной среде нет готового
Compose preview renderer; наличие tooling/аннотаций @Preview и SDK layoutlib-api не означает рендеринг.
Нарисованные имитации и подмена скриншотов не создавались.

Подготовлены debug-only `DesignSystemGallery`, `ReviewScene.DesignSystem`, четыре foundation previews
(светлая/тёмная 390×844, узкая 320×720 при fontScale=2, landscape 640×360 при fontScale=2),
а также два preview подтверждения. Они не входят в пользовательскую release-навигацию и не открывают базу.

Для реального Android подготовлен `R3GalleryUiTest.lightDarkAndNarrowLargeFontFoundationArtifacts`:
4 варианта (LIGHT/DARK × 390dp/обычный шрифт и 320dp/fontScale=2), по 8 областей каждого.
Существующий ReviewArtifacts сохраняет реальные captureToImage PNG, manifest и HTML с отметкой ручного просмотра NOT RUN.
После фактического выполнения файлы нужно проверить на синтетические данные и положить сюда, сохранив метаданные.
Компиляция этого теста не является получением 32 изображений.

Ожидаемые материалы для визуальной приёмки:

- Палитра и поверхности обеих тем, точные фоны #FFFFFF/#212121.
- Типографика и суммы 1 990 ₽, 299,50 ₽, максимальная сумма без обрезания.
- Primary/secondary/text/icon/destructive, disabled и loading.
- Поле unfocused/focused/error/read-only/disabled; ошибки читаются без опоры только на цвет.
- Single/multiple selected capsules, disabled и галочка выбора.
- 12 офлайн-значков и fallback.
- Нейтральный, янтарный, отменённый индикаторы; границы 0% и 100%, отсутствие линии при неизвестном начале.
- Loading/Empty/Error/Retry/Success.
- Центрированное подтверждение и прокручиваемый bottom sheet, включая landscape, fontScale=2 и IME.

Отдельно на физическом устройстве: TalkBack, реальные системные панели, gesture/3-button navigation,
системный reduced motion, изменение темы без потери черновика. Результаты этих проверок пока **NOT RUN**.
