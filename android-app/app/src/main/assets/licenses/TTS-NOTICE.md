# Built-in Russian speech

The application uses the upstream **vits-piper-ru_RU-denis-medium-int8**
archive and **sherpa-onnx static ONNX Runtime Android 1.13.8**. Exact upstream
URLs, archive/runtime SHA-256 values and extracted file hashes are in
`tts/manifest.json`. `python android-app/fetch_assets.py --tts-only` retrieves
these originals; the project does not publish these weights in its releases.

- sherpa-onnx: Apache-2.0, k2-fsa contributors.
  Source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8
  License: `SHERPA-ONNX-APACHE-2.0.txt`.
- ONNX Runtime: MIT, Microsoft Corporation.
  Source and license: https://github.com/microsoft/onnxruntime
- The Piper phonemizer included by sherpa uses eSpeak NG, GPL-3.0.
  Source corresponding to the sherpa build:
  https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1
  License: `ESPEAK-NG-GPL-3.0.txt`. This dependency is not Apache-2.0.
- The Piper voices repository declares MIT at revision
  `e9ef9dd26adb6a40a9b47198817cd8e96d01e7cb`:
  https://huggingface.co/rhasspy/piper-voices/blob/e9ef9dd26adb6a40a9b47198817cd8e96d01e7cb/README.md
  Its Denis model card states CC0 for the **source recordings** and describes
  fine-tuning from the English lessac medium voice. CC0 for recordings is not
  a claim that every runtime or weight dependency is CC0.
  Card: `DENIS-MODEL-CARD.md` and
  https://huggingface.co/rhasspy/piper-voices/blob/e9ef9dd26adb6a40a9b47198817cd8e96d01e7cb/ru/ru_RU/denis/medium/MODEL_CARD
  Recordings: https://github.com/OHF-Voice/voice-datasets

No new training or publication is performed by the asset fetcher. A public
model release must still use the project's `prepare_model_release.py` process;
shipping an APK also requires retaining these notices and satisfying each
dependency's redistribution/source obligations.
