# Проверки и история Android

Текущий код — [карта компонентов](../PROJECT_MAP.md), фактическая версия —
[STATUS](../STATUS.md) и [build.gradle.kts](../../android-app/app/build.gradle.kts).
Файлы здесь фиксируют конкретные версии APK и сценарии, преимущественно ранние.

| Материалы | Что подтверждают |
|---|---|
| [Серии, встроенный голос и WorkManager](series_voice_sync_2026-10-20/report.json) | Room v5, перенос кадров, UI/Worker/Keystore на Redmi, встроенный Denis и маршрутизация микрофона; границы указаны в отчёте |
| [FIELD_DEMO](FIELD_DEMO_2026-10-03.md), [MODEL_LAB](MODEL_LAB.md) | Устройство раннего демо/лабораторной сборки |
| [VOICE_COMMANDS](VOICE_COMMANDS_2026-10-04.md), [VOICE_GALLERY](VOICE_GALLERY_2026-10-04.md), [VOICE_CONTROLS](VOICE_CONTROLS_2026-10-04.md) | Эволюцию команд, съёмки и галереи; не свободный LLM-диалог |
| [FIELD_TOOLS 04.10](FIELD_TOOLS_2026-10-04.md), [FIELD_TOOLS 05.10](FIELD_TOOLS_2026-10-05.md) | Журнал, заметки, пользовательские оценки и ранние проверки |
| [PHONE_TEST_INSTALL](PHONE_TEST_INSTALL_2026-10-04.md) | Историческую установку и ограничения проверки |
| `accuracy_v1/`, `phone_validation/`, `voice_v*/`, `field_tools_v*/`, `yolo26s_final_v15_phone/` | Первичные JSON, скриншоты и локальные тяжёлые доказательства; версия указана внутри |

Старый benchmark не измеряет новую версию и весь путь от камеры до сохранения.
Сборка тестового APK не означает запуск инструментальных тестов. Действия на
телефоне требуют явного поручения Бориса. После него тестировать через
`adb install -r` и `adb shell am instrument`; `connectedDebugAndroidTest` не использовать
из-за риска удаления приложения с данными.
