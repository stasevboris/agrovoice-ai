"""Produce a comparison from saved measurements; retain validation and test separately."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]


def read(name):
    return json.loads((ROOT/'docs/experiments'/name).read_text(encoding='utf-8'))


def main():
    baseline = read('plantdoc_clean_v1_yolo11n_416.json')
    candidate = read('plantdoc_clean_v1_yolo26n_416.json')
    e2e = read('plantdoc_clean_v1_yolo26n_416_nms_free.json')
    large = read('plantdoc_clean_v1_yolo26n_416_512.json')
    phone = read('redmi_note_7_benchmark.json')
    rows = []
    for title, metrics, benchmark in zip(['YOLO11n', 'YOLO26n + NMS', 'YOLO26n без NMS'],
                                        [baseline, candidate, e2e], phone['models']):
        val = metrics['validation']['overall']
        test = metrics.get('test', {}).get('overall', {})
        test_score = f"{100*test['metrics/mAP50-95(B)']:.2f}" if test else '—'
        rows.append(f"| {title} | {100*val['metrics/mAP50(B)']:.2f} | {100*val['metrics/mAP50-95(B)']:.2f} | {test_score} | "
                    f"{benchmark['model_bytes']/1e6:.2f} | {benchmark['inference_and_postprocess']['median_ms']:.1f} | "
                    f"{benchmark['memory']['peak_process_rss_bytes']/1e6:.1f} |")
    class_rows = []
    selected_classes = ['Potato leaf early blight', 'Potato leaf late blight',
                        'Tomato Early blight leaf', 'Tomato leaf bacterial spot', 'Tomato leaf late blight']
    for name in selected_classes:
        scores = []
        for report, split in [(baseline, 'validation'), (candidate, 'validation'),
                              (baseline, 'test'), (candidate, 'test')]:
            metrics = next(item for item in report[split]['classes'] if item['name'] == name)['metrics']
            scores.append(f"{metrics['map50']*100:.1f}")
        class_rows.append('| '+name+' | '+' | '.join(scores)+' |')
    speed_gain = 100*(1-phone['models'][1]['inference_and_postprocess']['median_ms']/phone['models'][0]['inference_and_postprocess']['median_ms'])
    text = f'''# YOLO11n и YOLO26n: сравнение для AgroVoice AI

Дата: 3 октября 2026. Сервер нужен в итоговой архитектуре, его реализация отложена.

Для текущего прототипа выбран **YOLO26n Detect, 29 классов PlantDoc, 416×416, обычная голова с внешним NMS**. YOLO11n сохранена как исходная модель для сравнения. Это детекция целого листа и категории «культура + состояние», не сегментация пятен и не измерение процента поражения.

## Измеренные результаты

Обе модели обучены от весов COCO на одной dataset_clean_v1: 1977 train / 349 val / 236 test. 60 эпох, batch 16, AdamW lr0=0.001, seed=20261003, одинаковые аугментации, FP32, AMD RX 9060 XT через WSL/ROCm. Проверены отпечаток выборки и порядок всех 29 классов. Это один запуск каждой архитектуры; повторов по разным seed и доверительных интервалов нет. Полный рекомендованный рецепт YOLO26 с MuSGD отдельно не исследовался.

| Модель, вход 416 | val mAP50 | val mAP50–95 | test mAP50–95 | ONNX, МБ | Note 7, мс | Пик RSS процесса, МБ |
|---|---:|---:|---:|---:|---:|---:|
{chr(10).join(rows)}

МБ = 1 000 000 байт. Время — медиана запуска графа и обработки выходов на Redmi Note 7 6/64, Android 13: CPU FP32, 4 потока, 10 прогревов и 40 измерений, циклически пять одинаковых подготовленных изображений. Декодирование/масштабирование фотографии, камера и отрисовка не включены. RAM — весь процесс проверочного приложения, включая Android/Java/ONNX Runtime и тестовые тензоры; это не размер весов и не минимальное требование к телефону. Это короткий тест, без оценки длительного нагрева и автономности. Samsung A56 и Redmi 9T в этом эксперименте не проверены.

YOLO26 с NMS сократила измеренное время на {speed_gain:.1f}% и размер графа примерно на 7,7%. Рост требований к памяти в этой проверке не обнаружен. Режим без NMS экономит около 27 МБ RSS относительно обычной YOLO26, но выигрывает лишь около 3 мс и теряет 1,62 пункта val mAP50–95. При текущем соотношении выбираем обычную голову.

На val YOLO26 лучше YOLO11 на 2,66 пункта mAP50–95. **На test общая метрика немного хуже: 46,19 против 47,34.** Поэтому утверждение «замена архитектуры доказанно улучшила точность в поле» не подтверждено. Модель выбрана по val и скорости до просмотра её test; test затем проверен один раз, повторные запуски читают сохранённый результат. Выборка test уже использовалась для исходной YOLO11, поэтому она не является новым независимым полевым набором. Следующие эксперименты оцениваем на val, итоговую внешнюю проверку нужно собрать отдельно.

Те же веса YOLO26 проверены при входе 512×512 без повторного обучения: val mAP50–95 = {100*large['validation']['overall']['metrics/mAP50-95(B)']:.2f}. Общее качество ниже, объём вычислений от увеличения стороны возрастает примерно в 1,51 раза. Этот вариант не выбран. Тестовая выборка для него не использовалась.

## Где качество пока слабое

По отдельным условиям приоритетных культур результаты неоднородны. Ниже mAP50; это метрика детекции, а не процент правильных диагнозов.

| Класс | YOLO11 val | YOLO26 val | YOLO11 test | YOLO26 test |
|---|---:|---:|---:|---:|
{chr(10).join(class_rows)}

Здоровый картофель: всего четыре рамки в train, семь в val на одном снимке, ноль в test. Tomato two spotted spider mites: одна рамка train и одна val; качество не доказано. Огурцов в этих 29 классах нет. Архив огурцов сохранён отдельно, но его фрагменты листьев нельзя без проверки выдавать за рамки целого листа. Для полноценной первой полевой версии нужно дополнить и проверить данные, сохранив исходные классы.

## Что известно из официальной документации

На COCO при 640×640 Ultralytics публикует для YOLO11n 2,6 млн параметров / 6,5 GFLOPs / 39,5 mAP50–95, для YOLO26n 2,4 млн / 5,5 GFLOPs / 40,9 (обычная голова) или 40,1 (без NMS). Это около −8% параметров, −15% вычислений и +0,6…1,4 пункта COCO AP. Параметры YOLO26 относятся к слитой модели с удалённой неиспользуемой головой; исходные веса обучения хранят обе. Официальные CPU-тайминги выбирают голову без NMS и измерены на серверном CPU; они не являются тестом Redmi или Samsung. Формулировка «лучшая модель для телефона в 2026» не устанавливает универсального победителя для всех устройств и датасетов.

Источники: [YOLO26](https://docs.ultralytics.com/models/yolo26/), [YOLO11](https://docs.ultralytics.com/models/yolo11/), [ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html).

## Проверки и файлы

- PyTorch → ONNX: три изображения на каждый проверенный вариант; совпали выходы обычной головы. Для головы без NMS сопоставлены детекции с confidence ≥0.001, поскольку порядок TopK при близких оценках может отличаться.
- Android → настольный ONNX: для каждой из трёх моделей совпал сырой выход первого одинакового входа. Это проверка исполнения графа; отдельная оценка качества на личных полевых снимках ещё нужна.
- Обработка NMS для настольного сравнения совпала с Ultralytics на пяти изображениях.
- Проверочный debug APK собран, установлен и выполнен на Note 7. Экран ModelBenchmarkActivity включён только в debug; основная пользовательская работа с фотографиями к приложению ещё не подключена.
- Пакет: ml-pipeline/exports/plantdoc_clean_v1_yolo26n_416 — best.pt, model.onnx, classes.json, отчёты, inference_contract.json и SHA-256.
- Offline-пример на компьютере: predict_photo.py сохраняет рамки и JSON. Для явно выбранного огурца возвращает unsupported_crop. Confidence не является вероятностью болезни или процентом поражения.

Измерения сохранены в plantdoc_clean_v1_yolo26n_416.json, plantdoc_clean_v1_yolo26n_416_nms_free.json, plantdoc_clean_v1_yolo26n_416_512.json, redmi_note_7_benchmark.json и onnx_desktop_benchmark.json в этой папке.

## Повторить

```powershell
.\\ml-pipeline\\Start-Training.ps1 -Backend wsl -Model F:\\agrovoice-ai\\ml-pipeline\\yolo26n.pt -Name plantdoc_clean_v1_yolo26n_416 -Epochs 60 -ImageSize 416 -Batch 16
.\\ml-pipeline\\venv\\Scripts\\python.exe -X utf8 ml-pipeline\\evaluate_clean.py --run runs\\detect\\plantdoc_clean_v1_yolo26n_416
.\\ml-pipeline\\venv\\Scripts\\python.exe -X utf8 ml-pipeline\\predict_photo.py 'C:\\путь\\фотография.jpg' --crop potato
```

Повторное обучение создаёт следующий каталог запуска; нужно передать его в --run. evaluate_clean.py по умолчанию проверяет только val; --test использовать после выбора модели. Пути телефона в collect_phone_benchmark.py относятся к отдельному проверочному экрану и исходным тензорам prepare_phone_benchmark.py.
'''
    (ROOT/'docs/experiments/YOLO_COMPARISON_2026-10-03.md').write_text(text, encoding='utf-8')
    print(ROOT/'docs/experiments/YOLO_COMPARISON_2026-10-03.md')


if __name__ == '__main__':
    main()
