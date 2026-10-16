import os
import subprocess
from ultralytics import YOLO

DATA_YAML = r"F:\agrovoice-ai\ml-pipeline\dataset\data.yaml"
PROJECT_DIR = r"F:\agrovoice-ai\runs\detect"
EXP_NAME = "agrovoice_plantdoc_yolo11n"

def main():
    print("=== 🌿 AgroVoice AI: Старт обучения YOLOv11n на PlantDoc (29 классов) ===")
    
    # Загружаем базовые веса nano
    model = YOLO("yolo11n.pt")

    print("\n>>> 1. Запуск обучения (30 эпох)...")
    results = model.train(
        data=DATA_YAML,
        epochs=30,
        imgsz=640,
        batch=16,
        project=PROJECT_DIR,
        name=EXP_NAME,
        optimizer="AdamW",
        lr0=0.001,
        hsv_h=0.015,
        hsv_s=0.7,
        hsv_v=0.4,
        degrees=10.0,
        fliplr=0.5,
        save=True,
        plots=True
    )

    best_pt = os.path.join(PROJECT_DIR, EXP_NAME, "weights", "best.pt")
    print(f"\n✓ Обучение завершено! Лучшие веса сохранены в: {best_pt}")

    # Экспорт в ONNX
    print("\n>>> 2. Экспорт обученной модели в ONNX...")
    onnx_file = model.export(format="onnx", imgsz=640, dynamic=False)
    print(f"✓ ONNX файл готов: {onnx_file}")

    # Экспорт в TFLite через onnx2tf
    print("\n>>> 3. Экспорт в TFLite для мобильного приложения Android...")
    tflite_out_dir = os.path.join(PROJECT_DIR, EXP_NAME, "weights", "tflite_model")
    cmd = f'onnx2tf -i "{onnx_file}" -o "{tflite_out_dir}"'
    subprocess.run(cmd, shell=True)

    print(f"\n🎉 ВСЁ ГОТОВО! Мобильная TFLite модель лежит в: {tflite_out_dir}")

if __name__ == '__main__':
    main()
