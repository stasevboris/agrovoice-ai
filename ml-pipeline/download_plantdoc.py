import os
import re
import zipfile

ZIP_PATH = r"F:\agrovoice-ai\ml-pipeline\plantdoc_yolo.zip"
EXTRACT_DIR = r"\\?\F:\agrovoice-ai\ml-pipeline\extracted"

def sanitize_filename(filename):
    # Оставляем структуру папок, но очищаем имя файла от запрещенных символов Windows (?, *, :, <, >, ", |)
    parts = filename.split('/')
    clean_parts = []
    for part in parts:
        # Убираем ?itok=... и прочий веб-мусор
        clean = re.sub(r'[\\?*:"<>|]', '_', part)
        clean_parts.append(clean)
    return os.path.join(*clean_parts)

def safe_extract():
    print("=== 🌿 Распаковка PlantDoc с санитизацией имен Windows ===")
    os.makedirs(EXTRACT_DIR, exist_ok=True)
    
    with zipfile.ZipFile(ZIP_PATH, 'r') as zf:
        members = zf.infolist()
        total = len(members)
        print(f"Всего объектов в архиве: {total}")
        
        extracted_count = 0
        for idx, member in enumerate(members):
            clean_rel_path = sanitize_filename(member.filename)
            target_path = os.path.join(EXTRACT_DIR, clean_rel_path)
            
            if not target_path.startswith("\\\\?\\"):
                target_path = "\\\\?\\" + target_path

            if member.is_dir():
                os.makedirs(target_path, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(target_path), exist_ok=True)
                with zf.open(member) as src, open(target_path, "wb") as dst:
                    dst.write(src.read())
                extracted_count += 1

            if idx % 500 == 0 or idx == total - 1:
                print(f"\rОбработано: {idx + 1} / {total}", end="", flush=True)

    print(f"\n✓ Успешно распаковано {extracted_count} файлов в папку extracted!")

if __name__ == '__main__':
    safe_extract()
