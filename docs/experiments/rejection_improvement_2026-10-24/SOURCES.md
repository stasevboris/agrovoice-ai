# Проверенные источники: отказ и открытое множество

Проверено 24.10.2026. Это источники гипотез и реализации, не метрики AgroVoice.
Код сторонних проектов не устанавливался и не запускался; локальная реализация
score/поиска соседей написана отдельно. Снимки прочитанного кода и ответы API:
`artifacts/verification/rejection_improvement_20261024/sources/`.

| Источник | Что прочитано | Польза / решение |
|---|---|---|
| [OpenOOD](https://github.com/Jingkang50/OpenOOD), MIT | README, [KNNPostprocessor](https://github.com/Jingkang50/OpenOOD/blob/main/openood/postprocessors/knn_postprocessor.py) | Единый протокол сравнения OOD, train-банк нормализованных признаков и расстояние до k-го соседа. KNN включён как контроль; библиотека и её benchmark-данные не загружались. |
| [Sun et al., KNN-OOD, ICML 2022](https://github.com/deeplearning-wisc/knn-ood), [статья](https://arxiv.org/abs/2204.06507) | README и `run_imagenet_vit.py` | Авторский код нормализует признаки перед поиском. Наш k=10/5 и contrast known-vs-outlier — адаптация к нашим данным, не точное воспроизведение статьи. Веса/код из этого репозитория не заимствовались; blanket-лицензия не предполагалась. |
| [DINOv2 linear evaluation](https://github.com/facebookresearch/dinov2/blob/main/dinov2/eval/linear.py) | `create_linear_input`, linear head, LR search и SGD | Авторы сравнивают CLS нескольких блоков и усреднение patch-токенов. Наш опыт остаётся CLS-only и не выдаётся за авторский полный рецепт. Проверка pooling — отдельная будущая абляция, в финалиста после test не добавлялась. |
| [Hendrycks et al., Outlier Exposure, ICLR 2019](https://github.com/hendrycks/outlier-exposure), Apache-2.0 | README, идея дополнительного outlier-обучения | Поддерживает отдельное обучение неизвестности. В текущем опыте бинарная голова — контроль; общая reject-категория не означает «листа нет». Старый Tiny Images и случайные интернет-данные не скачивались. |
| [Stack Overflow: My CNN classifier gives wrong prediction on random images](https://stackoverflow.com/questions/52831038/my-cnn-classifier-gives-wrong-prediction-on-random-images) | Все четыре ответа получены через [публичный API Stack Exchange](https://api.stackexchange.com/2.3/questions/52831038/answers?order=desc&sort=votes&site=stackoverflow&filter=withbody) | Обсуждаются open-set, binary gate и ограничение softmax. Рекомендация брать произвольный порог 0,7 не принята: у нас порог выбирается по calibration при явных ограничениях. Ответ с `math.isclose` не имеет обоснования OOD и не использован. Код ответов не копировался. |
| [Stack Overflow: How to implement an unknown class…](https://stackoverflow.com/questions/60426468/how-to-implement-an-unknown-class-in-deep-learning-neural-nets) | Страница в браузере показывала challenge; публичный API вернул 0 ответов | Источник найден, но полезного готового решения не дал. Отсутствие ответа не трактуется как отсутствие метода. |
| [Олег Седухин / boygenius, «Проблемы современного машинного обучения», Хабр, 14.02.2022](https://habr.com/ru/companies/ods/articles/651103/) | Разделы про утечки, shortcut learning и стресс-тесты; ссылки на Geirhos и D’Amour | Обосновывает проверки по источникам, свету/фону и сдвигу распределения. Советы «просто больше данных» не заменяют отдельный тест; статья не доказывает готовность конкретной модели. |

Поиск выполнен встроенным браузером; Stack Overflow защищал обычную страницу,
поэтому для публичных ответов использован официальный API. Desktop interaction
и обход через управление экраном не использовались.

Главный локальный вывод: жёсткое совпадение argmax культуры и score неизвестности
решают разные задачи. Их замена на парное сравнение logits культуры S/reject
проверена экспериментом; культурные метки или диагнозы S модуль не подменяет.
Финальный вариант выбран по calibration, затем один раз проверен на audit/test.
