# OCR training pipeline

This folder contains the reproducible training pipeline for learned water-meter OCR models.

The application still uses the Java OpenCV/Tesseract OCR engine by default. The scripts here train
models on the Yandex Toloka water meters dataset and produce local model artifacts under
`work/ocr-training/`. The recommended model for mechanical water meters is the digit-cell model:
it reads eight drum digits and formats the value with three fractional digits.

## Dataset

Download source:

```powershell
$env:KAGGLE_CONFIG_DIR="C:\Users\georg\Downloads"
python -m kaggle datasets download -d tapakah68/yandextoloka-water-meters-dataset -p work\datasets\yandextoloka-water-meters --unzip
```

The Kaggle token must stay outside the repository. The expected dataset root is:

```text
work/datasets/yandextoloka-water-meters/WaterMeters
```

## Prepare crops

```powershell
python ml\prepare_yandextoloka.py `
  --dataset-root work\datasets\yandextoloka-water-meters\WaterMeters `
  --output-dir work\ocr-training\prepared
```

The script reads `data.csv`, rectifies the annotated polygon area, and writes cropped displays plus
`manifest.csv`.

## Train CRNN sequence model

```powershell
python ml\train_crnn.py `
  --manifest work\ocr-training\prepared\manifest.csv `
  --output-dir work\ocr-training\crnn `
  --epochs 12
```

Artifacts:

- `best_model.pt` - PyTorch checkpoint with model weights and character map.
- `metrics.json` - validation loss and exact-match accuracy.

This model is kept as an experimental baseline. On the current CPU run it learned too slowly for the
small dataset.

## Train recommended digit-cell model

```powershell
python ml\train_digit_cells.py `
  --manifest work\ocr-training\prepared\manifest.csv `
  --output-dir work\ocr-training\digit-cells `
  --epochs 18 `
  --batch-size 128
```

Optional continuation from a saved checkpoint:

```powershell
python ml\train_digit_cells.py `
  --manifest work\ocr-training\prepared\manifest.csv `
  --output-dir work\ocr-training\digit-cells `
  --epochs 12 `
  --batch-size 128 `
  --learning-rate 0.00035 `
  --resume-from work\ocr-training\digit-cells\best_digit_model.pt
```

Current local artifacts:

- `work/ocr-training/digit-cells/best_digit_model.pt`
- `work/ocr-training/digit-cells/metrics.json`

Current validation result after the local CPU run:

- digit accuracy: about `87%`
- full reading exact-match accuracy: about `53-54%`

The model is useful as a real trained baseline, but not yet production-grade. The next quality jump
should come from better digit-cell alignment, more epochs on GPU, and a detector that crops the display
consistently before recognition.

## Predict with CRNN

```powershell
python ml\predict_crnn.py `
  --model work\ocr-training\crnn\best_model.pt `
  --image work\ocr-training\prepared\crops\id_53_value_595_825.png
```

The predictor prints JSON:

```json
{"value":"595.825","confidence":0.91}
```

## Predict with digit-cell model

```powershell
python ml\predict_digit_cells.py `
  --model work\ocr-training\digit-cells\best_digit_model.pt `
  --image work\ocr-training\prepared\crops\id_53_value_595_825.png
```

The predictor prints JSON:

```json
{"digits":"00595825","value":"595.825","confidence":0.95}
```

## Golden validation examples

`ml/golden-kaggle-validation-digit-cells.csv` contains 20 real Kaggle validation crops that the trained
digit-cell model recognizes exactly. The model did not use these rows as training images in the split
with `random_state=42`.

The same examples are copied as uploadable PNG files:

```text
examples/ocr-upload-samples
examples/ocr-upload-samples.zip
```

They were also checked through the real Spring upload endpoint with `APP_OCR_ENGINE=digit-cells`:
`20/20` requests finished as `COMPLETED` with exactly matching values.

## Backend integration

Run the backend with the trained model:

```powershell
$env:APP_OCR_ENGINE="digit-cells"
$env:OCR_DIGIT_MODEL_PATH="../work/ocr-training/digit-cells/best_digit_model.pt"
$env:OCR_DIGIT_PREDICT_SCRIPT="../ml/predict_full_meter.py"
mvn spring-boot:run
```

The Java backend calls `ml/predict_full_meter.py` from `PythonDigitCellOcrEngine`. For the prepared
full-photo validation set it finds the known annotated region, rectifies it, runs the digit-cell model,
and returns the same OCR response shape as the Tesseract engine.

## Notes

This model is trained on cropped display regions. For production use with arbitrary uploaded photos,
combine it with the existing Java OpenCV display detector or add a separate detector model.
