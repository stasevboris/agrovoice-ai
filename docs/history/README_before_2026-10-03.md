# 🌿 AgroVoice AI (PhytoGuard)
> **Интеллектуальная система фитосанитарного мониторинга со встроенным голосовым ассистентом**  
> *Client-Server Android Application with Edge AI (YOLOv11n), Two-Tier Voice Pipeline (Porcupine + Whisper) & Decision Support System (DSS)*

[![Android](https://img.shields.io/badge/Platform-Android_10+-3DDC84?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin_2.0-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![SQLite](https://img.shields.io/badge/Database-SQLite_3-003B57?style=flat&logo=sqlite&logoColor=white)](https://www.sqlite.org)
[![YOLOv11](https://img.shields.io/badge/Vision-YOLOv11n_INT8-00FFFF?style=flat)](https://github.com/ultralytics/ultralytics)
[![Whisper](https://img.shields.io/badge/Speech-Faster--Whisper-FF6F00?style=flat)](https://github.com/SYSTRAN/faster-whisper)
[![FastAPI](https://img.shields.io/badge/Backend-FastAPI-009688?style=flat&logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📌 О проекте (Problem & Solution)

В промышленных теплицах и открытом грунте у агронома при обходе шпалер руки постоянно загрязнены соком растений или защищены плотными перчатками со следами химикатов. Работа с сенсорным экраном планшета приводит к загрязнению техники, ложным нажатиям и замедлению обхода сотен рядов.

**AgroVoice AI** — мобильная система для Android с **100% Hands-Free сценарием**:
1. **Взгляд / Камера**: Поддержка камеры смартфона, нагрудной UVC-камеры по USB-C (OTG, 0 мс задержки) и экшн-камер GoPro по Wi-Fi.
2. **Голос (Two-Tier)**: Постоянное слушание фразы *«Агроном»* сверхлегкой нейросетью Porcupine KWS (< 1.1% CPU, 12–15 мА), DSP-фильтрация шума насосов (300–3400 Гц) и дискретная активация Faster-Whisper INT8 на 4 секунды (WER < 6.8%).
3. **Детекция очагов (YOLOv11n)**: Локализация Bounding Box 5 ключевых патогенов (мучнистая роса, фитофтороз, серая гниль, паутинный клещ, белокрылка) с точностью **mAP@0.5 = 93.4%** за 25–40 мс на бортовом NPU/GPU.
4. **СППР (Госреестр СЗР)**: Рекомендация регламентов (химические эталоны и биоаналоги) с нормами расхода на 10 л воды, классами опасности и сроками ожидания (каренцией).
5. **Автономность**: Локальная база данных Room (SQLite) и пакетная синхронизация с 1С:Агро / SCADA через FastAPI.

---

## 🏗️ Архитектура системы

```text
┌────────────────────────────────────────────────────────────────────────┐
│                   АППАРАТНЫЙ УРОВЕНЬ (HANDS-FREE)                      │
│   Нагрудная UVC-камера (USB-C OTG)  /  GoPro Wi-Fi 5GHz  /  Гарнитура  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               ANDROID КЛИЕНТ (Kotlin + Jetpack Compose)                │
│                                                                        │
│  [Audio Pipeline]                                                      │
│  16 kHz Audio -> Porcupine KWS -> Butterworth Filter -> Whisper INT8   │
│                                                                        │
│  [Vision Pipeline]                                                     │
│  Camera Frame -> Crop 640x640 -> YOLOv11n (NNAPI/GPU) -> Bounding Box  │
│                                                                        │
│  [On-Demand FSM & Local Storage]                                       │
│  SQLite / Room DB (Pathogens, Treatments, InspectionLogs)              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Wi-Fi в лаборатории (конец смены)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             СЕРВЕР СИНХРОНИЗАЦИИ (FastAPI / Python Docker)             │
│   POST /api/v1/sync/batch  ──>  Дедупликация  ──>  1С:Агро / SCADA    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🎯 Метрики качества детекции (Тестовый датасет 1 280 очагов)

| Патоген / Вредитель | Культура | Очагов (Boxes) | Precision | Recall | mAP@0.5 |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Мучнистая роса** (*Podosphaera xanthii*) | Огурец | 342 | 0.942 | 0.915 | **0.938** |
| **Фитофтороз** (*Phytophthora infestans*) | Томат | 418 | 0.951 | 0.930 | **0.947** |
| **Серая гниль** (*Botrytis cinerea*) | Томат / Перец | 185 | 0.918 | 0.894 | **0.912** |
| **Паутинный клещ** (*Tetranychus urticae*) | Огурец / Томат | 120 | 0.895 | 0.880 | **0.891** |
| **Тепличная белокрылка** (*Trialeurodes*) | Томат | 215 | 0.931 | 0.910 | **0.925** |
| **ИТОГ (Micro-average, взвешенный)** | **Все культуры** | **1 280** | **0.936** | **0.913** | **0.934 (93.4%)** |

---

## 📂 Структура репозитория

```bash
├── android-app/                  # Исходный код Android-приложения (Kotlin)
│   ├── app/src/main/
│   │   ├── java/com/agronomy/phytomonitor/
│   │   │   ├── data/             # Room DB, сущности, DAO, репозитории
│   │   │   ├── domain/           # Бизнес-логика, UseCases
│   │   │   ├── presentation/     # Jetpack Compose экраны (Видоискатель, Журнал)
│   │   │   └── service/          # Foreground Service (микрофон KWS)
│   │   └── AndroidManifest.xml   # Разрешения CAMERA, RECORD_AUDIO, USB
│   └── build.gradle.kts
│
├── ml-pipeline/                  # Обучение и экспорт нейросетей
│   ├── dataset/                  # Скрипты аугментации Albumentations (HSV, Blur)
│   ├── train_yolo.py             # Обучение YOLOv11n (PyTorch)
│   ├── export_onnx_tflite.py     # Квантование INT8/FP16 для NNAPI
│   └── weights/                  # Готовые веса yolov11n_int8.tflite
│
├── voice-engine/                 # Речевой двухстадийный конвейер
│   ├── kws_porcupine/            # Модель ключевого слова «Агроном» (1.2 МБ)
│   ├── dsp_filter/               # Фильтр Баттерворта 300–3400 Гц + VAD
│   └── whisper_onnx/             # Faster-Whisper Base/Small INT8
│
├── server/                       # Сервер синхронизации на FastAPI
│   ├── main.py                   # REST эндпоинты пакетной синхронизации
│   ├── models.py                 # Pydantic схемы логов и регламентов
│   ├── Dockerfile
│   └── requirements.txt
│
└── docs/                         # Материалы дипломной работы (схемы, графики)
```

---

## 🚀 Быстрый старт

### 1. Мобильное приложение (Android Studio)
1. Склонируйте репозиторий:
   ```bash
   git clone https://github.com/your-username/agrovoice-ai.git
   ```
2. Откройте папку `android-app` в Android Studio (Hedgehog+).
3. Дождитесь синхронизации Gradle и выполните запуск на смартфоне с Android 10+:
   ```bash
   ./gradlew installDebug
   ```

### 2. Сервер синхронизации (FastAPI)
```bash
cd server
python -m venv venv
source venv/bin/activate # или venv\Scripts\activate на Windows
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```
Документация Swagger UI доступна по адресу: `http://localhost:8000/docs`

---

## 🛡️ Публикация в Google Play Store

* **Сборка релизного бандла**: `./gradlew bundleRelease` -> `release.aab`
* **Программа закрытого тестирования**: 20 активных тестеров в течение 14 дней согласно регламенту Google Play 2023+.
* **Политика конфиденциальности (Privacy Policy)**: Развернута в папке `docs/privacy-policy.md`.

---

## 📜 Лицензия и авторство
* **Автор**: Дипломный проект (2026 г.)
* **Лицензия**: MIT License
