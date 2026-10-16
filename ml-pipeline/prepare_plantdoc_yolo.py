import os
import shutil
import pandas as pd
from PIL import Image

BASE_DIR = r"\\?\F:\agrovoice-ai\ml-pipeline\extracted\PlantDoc-Object-Detection-Dataset-master"
OUT_DIR = r"\\?\F:\agrovoice-ai\ml-pipeline\dataset"

def convert():
    print("=== 🌿 Конвертация PlantDoc в нативный формат YOLOv11 ===")
    
    train_csv = os.path.join(BASE_DIR, "train_labels.csv")
    test_csv = os.path.join(BASE_DIR, "test_labels.csv")
    
    df_train = pd.read_csv(train_csv)
    df_test = pd.read_csv(test_csv)
    
    # 1. Извлекаем уникальные классы и сортируем для детерминированного порядка
    classes = sorted(list(set(df_train['class'].unique()).union(set(df_test['class'].unique()))))
    class_to_id = {cls_name: idx for idx, cls_name in enumerate(classes)}
    
    print(f"✓ Найдено классов: {len(classes)}")
    for idx, c in enumerate(classes):
        print(f"  [{idx:02d}] {c}")

    # Создаем структуру папок YOLO
    for split in ['train', 'val']:
        os.makedirs(os.path.join(OUT_DIR, "images", split), exist_ok=True)
        os.makedirs(os.path.join(OUT_DIR, "labels", split), exist_ok=True)

    def process_split(df, split_name, src_folder):
        print(f"\n>>> Обработка сплита {split_name}...")
        grouped = df.groupby('filename')
        total = len(grouped)
        processed = 0

        for filename, group in grouped:
            # Исходный путь картинки
            src_img = os.path.join(BASE_DIR, src_folder, filename)
            
            # Проверяем, существует ли файл (мог быть санитизирован)
            if not os.path.exists(src_img):
                # Пробуем найти санитизированный
                import re
                clean_name = re.sub(r'[\\?*:"<>|]', '_', filename)
                src_img = os.path.join(BASE_DIR, src_folder, clean_name)
                if not os.path.exists(src_img):
                    continue

            # Получаем размеры изображения
            try:
                with Image.open(src_img) as img:
                    img_w, img_h = img.size
            except Exception:
                continue

            # Имя для YOLO
            base_stem = os.path.splitext(os.path.basename(src_img))[0]
            dst_img = os.path.join(OUT_DIR, "images", split_name, f"{base_stem}.jpg")
            dst_txt = os.path.join(OUT_DIR, "labels", split_name, f"{base_stem}.txt")

            # Копируем картинку
            shutil.copyfile(src_img, dst_img)

            # Записываем YOLO-разметку
            lines = []
            for _, row in group.iterrows():
                cls_name = row['class']
                if cls_name not in class_to_id:
                    continue
                cls_id = class_to_id[cls_name]
                
                xmin = float(row['xmin'])
                ymin = float(row['ymin'])
                xmax = float(row['xmax'])
                ymax = float(row['ymax'])

                # Ограничиваем в рамках картинки
                xmin = max(0.0, min(xmin, img_w))
                xmax = max(0.0, min(xmax, img_w))
                ymin = max(0.0, min(ymin, img_h))
                ymax = max(0.0, min(ymax, img_h))

                box_w = xmax - xmin
                box_h = ymax - ymin
                if box_w <= 0 or box_h <= 0:
                    continue

                x_center = (xmin + box_w / 2.0) / img_w
                y_center = (ymin + box_h / 2.0) / img_h
                norm_w = box_w / img_w
                norm_h = box_h / img_h

                lines.append(f"{cls_id} {x_center:.6f} {y_center:.6f} {norm_w:.6f} {norm_h:.6f}")

            with open(dst_txt, "w", encoding="utf-8") as f:
                f.write("\n".join(lines))

            processed += 1
            if processed % 300 == 0 or processed == total:
                print(f"\r  Прогресс: {processed} / {total} картинок", end="", flush=True)

    process_split(df_train, "train", "TRAIN")
    process_split(df_test, "val", "TEST")

    # Генерируем data.yaml
    yaml_content = f"""# PlantDoc Dataset config for YOLOv11 AgroVoice AI
path: F:/agrovoice-ai/ml-pipeline/dataset
train: images/train
val: images/val

names:
"""
    for idx, c in enumerate(classes):
        yaml_content += f"  {idx}: \"{c}\"\n"

    yaml_path = r"F:\agrovoice-ai\ml-pipeline\dataset\data.yaml"
    with open(yaml_path, "w", encoding="utf-8") as f:
        f.write(yaml_content)

    print(f"\n\n🎉 ГОТОВО! Конфиг сохранен в: {yaml_path}")

if __name__ == '__main__':
    convert()
