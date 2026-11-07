# Исходный код Android

Проект следует структуре отчётных папок шаблона организаторов (`docs`, `config`, `artifacts`, `src`). Сам Android-модуль использует стандартный Gradle-путь [`../app/src`](../app/src), чтобы открываться в Android Studio без нестандартных настроек.

- Kotlin: `app/src/main/java/ru/neuroscribe/app/`.
- JNI и CMake: `app/src/main/cpp/`.
- JVM-тесты: `app/src/test/`.
- Проверки настоящих Android-декодеров и моделей: `app/src/androidTest/`.
- Установка и сборка: корневой `README.md`.
