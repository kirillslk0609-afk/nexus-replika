# Материалы для сдачи — NEXUS / Реплика

Задача: Нейро-стенографист (Hex.Team). Структура адаптирована из [шаблона организаторов](https://github.com/grigorevmp/hacathon_template) под Android и локальную расшифровку аудио.

| Требование | Материал |
|---|---|
| 1. APK | [neuroscribe-debug.apk](https://github.com/kirillslk0609-afk/nexus-replika/releases/download/v0.6.1/neuroscribe-debug.apk), 949 695 740 байт |
| 2. Исходники | [Android-модуль](../app/src/), [сборочные скрипты](../scripts/), [ZIP](https://github.com/kirillslk0609-afk/nexus-replika/releases/download/v0.6.1/neuroscribe-source.zip) |
| 3. Установка и использование | [README](../README.md#установка-и-проверка-на-телефоне) |
| 4. Архитектура и модели | [ARCHITECTURE](../docs/ARCHITECTURE.md), [THIRD_PARTY](../docs/THIRD_PARTY.md) |
| 5. Ограничения | [LIMITATIONS](../docs/LIMITATIONS.md) |
| 6. Собственные тесты | [TEST_RESULTS](../docs/TEST_RESULTS.md), [машиночитаемые результаты](test-results/), [видеопроверка телефона](../docs/PHONE_TEST.md) |
| 7. Видеодемонстрация | [Два актуальных видео](demo/README.md) |

APK и большие видео хранятся в [GitHub Release v0.6.1](https://github.com/kirillslk0609-afk/nexus-replika/releases/tag/v0.6.1) этого же публичного репозитория. Размеры и SHA-256 шести исходных файлов релиза сверены с оригиналами; скачивание проверено без авторизации. [Контрольные суммы](https://github.com/kirillslk0609-afk/nexus-replika/releases/download/v0.6.1/SHA256SUMS).

Папки `docs/`, `config/`, `artifacts/`, `src/` сохранены. Android-код находится в стандартном `app/src/`; [src/README.md](../src/README.md) объясняет расположение. Примеры акустической передачи из общего шаблона заменены [контрактом импорта и экспорта](../docs/PROTOCOL.md) и метриками ASR/диаризации.

Архив исходников и Git bundle соответствуют снимку поставки 0.6.1; последующие изменения навигационных README не меняют APK и исходники приложения.

[Презентация к защите и текст для двух участников](presentation/README.md) добавлены отдельно в этот же релиз. Ссылка на репозиторий должна быть отправлена в форму сдачи до 09:00. После 09:00 изменения решения запрещены; исключение организаторов — презентация до 13:30.
