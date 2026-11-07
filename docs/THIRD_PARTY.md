# Источники и воспроизводимость

- sherpa-onnx 2.0.1: https://github.com/k2-fsa/sherpa-onnx — Android AAR, Kotlin API, диаризация, Whisper. Лицензия проекта Apache-2.0; копия в `LICENSE-sherpa-onnx.txt`.
- llama.cpp: https://github.com/ggml-org/llama.cpp — точный commit в `config/llama-commit.txt`, лицензия MIT в исходном коде.
- Whisper small ONNX INT8: https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small — commit `8f3c18b358db4d1f2fc1eae49d75cd20989e4309`; исходная модель https://github.com/openai/whisper.
- pyannote segmentation 3.0, ONNX-конверсия: https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models; первоисточник https://huggingface.co/pyannote/segmentation-3.0.
- Историческая модель версии 0.1, больше не используется для инференса: CAMPPlus / 3D-Speaker: https://github.com/modelscope/3D-Speaker; ONNX-веса https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models.
- Qwen2.5-0.5B-Instruct GGUF: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF — commit `9217f5db79a29953eb74d5343926648285ec7e67`, Q4_K_M.

- NVIDIA NeMo TitaNet-Small: [официальная карточка](https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small), [ONNX-релиз sherpa](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx), SHA-256 `ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e`, 40 257 283 байта. NVIDIA Corporation; конверсия sherpa-onnx, веса не изменены. Карточка относит модель к лицензии NeMo Toolkit (Apache-2.0); текст `LICENSE-NVIDIA-NeMo.txt` и атрибуция входят в APK.

Файлы, размеры, URL и доступные исходные SHA-256 перечислены в `config/models.lock.json`. После загрузки скрипт создаёт `config/packaged-models.json` с SHA-256 каждого фактически упакованного файла. Приложение сверяет эти хеши при подготовке.

Копии лицензий движков и моделей включены в `app/src/main/assets/licenses/` и попадут в APK. Для pyannote использован файл LICENSE из официального ONNX-архива sherpa-onnx.

При распространении сохраняйте уведомления и условия лицензий библиотек и **каждой** модели; лицензия движка не распространяется автоматически на веса. Файлы весов не являются разработкой команды. Собственный вклад — Android-сценарий, обработка аудио, интеграция этапов, контракт результатов, JNI-мост, защита текста и тесты.

## Тестовые данные

- Русские короткие примеры синтезированы системными голосами macOS; они не заменяют естественную речь.
- Контрольные WAV с двумя английскими и четырьмя китайскими голосами взяты из официальных примеров sherpa-onnx; ссылки и хеши в `config/test-fixtures.json`.
- Два естественных разговора: DataHiveAI, [multilingual-conversations-sample](https://huggingface.co/datasets/datahiveai/multilingual-conversations-sample), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), commit `c29728a0c9792ba486807fa1ea9ca64e292cec01`, файлы `ru_000002.mp3`, `ru_000014.mp3`. Аудио включено только в тестовые исходники/тестовый APK, не в пользовательский APK. Исходники сохранены без изменения; для отдельных экспериментов применялось преобразование в моно 16 кГц. У набора нет эталонных расшифровок, поэтому WER/DER по нему не заявлены.
- Во время исследования сравнивались CAMPPlus, TitaNet и WeSpeaker из официальных релизов sherpa-onnx. Простая замена модели в старом алгоритме не давала устойчивого улучшения. В версии 0.2 TitaNet-Small используется вместе с новым алгоритмом группировки; CAMPPlus и WeSpeaker в пользовательский APK не входят.

Дополнительные тесты: DataHive `ru_000013`, `en_000015`, `ru_000004`, `en_000020`, `ru_000017`, `en_000001`, тот же commit и CC BY 4.0. Последние два добавлены на позднем этапе, затем включены в итоговую регрессию. Независимой слепой выборкой они не объявляются. `three-speakers.wav` — первые 26 секунд китайского примера: отредактированный тестовый фрагмент, а не новая естественная запись. Все файлы/хеши/разделение разработки и итоговой проверки перечислены в `config/test-fixtures.json`.

Тестовый набор шумной речи (не входит в рабочий APK): LibriSpeech, Vassil Panayotov и соавторы, https://www.openslr.org/12, CC BY 4.0. Четыре фрагмента одного диктора из закреплённой копии `hf-internal-testing/librispeech_asr_dummy`, revision `5be91486e11a2d616f4ec5db8d3fd248585ac07a`. В тестах звук преобразован в WAV, обрезаны первые 40 мс, добавлены описанные шумы; тексты исходного набора сохранены. Полные источники, SHA-256 и преобразования — `config/noise-fixtures.json`. Остальные пять исходных записей получены локально macOS `say` для контрольных фраз.

## Интерфейс 0.6

Служебные иконки: [Google Material Icons](https://github.com/google/material-design-icons), Apache License 2.0. Файлы-источники перечислены в `artifacts/design-v06/material-icons.json`; копия лицензии — `app/src/main/assets/licenses/material-icons-LICENSE.txt`. Новый фирменный знак создан Imagegen, промпт и исходный PNG сохранены в `artifacts/design-v06/`.
