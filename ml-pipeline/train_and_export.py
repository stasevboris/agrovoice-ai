from ultralytics import YOLO

def main():
    print("=== 🌿 AgroVoice AI: Пайплайн YOLOv11n на Windows ===")

    # 1. Загружаем официальную базовую ультралегкую модель nano
    print(">>> 1. Загрузка архитектуры YOLOv11n...")
    model = YOLO("yolo11n.pt")

    # 2. Проверочный прогон обучения (скачает тестовый датасет coco8)
    print(">>> 2. Запуск тестового обучения (3 эпохи)...")
    model.train(
        data="coco8.yaml",
        epochs=3,
        imgsz=640,
        batch=16,
        name="agrovoice_quick"
    )

    # 3. Экспорт весов в формат ONNX и TFLite для мобильного процессора Android
    print(">>> 3. Экспорт в формат ONNX (для NPU/CPU)...")
    onnx_file = model.export(format="onnx", imgsz=640, dynamic=False)
    print(f"✓ ONNX экспортирован: {onnx_file}")

    print(">>> 4. Экспорт в формат TFLite для Android...")
    try:
        tflite_file = model.export(format="tflite", imgsz=640, half=True)
        print(f"🎉 TFLite модель успешно создана: {tflite_file}")
    except Exception as e:
        print(f"TFLite export note: {e}")
        print("ONNX модель готова к конвертации и работе!")

if __name__ == '__main__':
    main()
