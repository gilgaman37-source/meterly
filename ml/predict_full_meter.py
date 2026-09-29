import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image

from predict_digit_cells import reading_text
from train_digit_cells import DIGITS_PER_READING, DigitCnn


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> dict:
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {sample["sha256"]: sample for sample in data.get("samples", [])}


def order_quad(points: np.ndarray) -> np.ndarray:
    rect = cv2.minAreaRect(points.astype(np.float32))
    box = cv2.boxPoints(rect).astype(np.float32)
    sums = box.sum(axis=1)
    diffs = np.diff(box, axis=1).reshape(-1)
    return np.array([
        box[np.argmin(sums)],
        box[np.argmin(diffs)],
        box[np.argmax(sums)],
        box[np.argmax(diffs)],
    ], dtype=np.float32)


def rectify_known_region(image_path: Path, sample: dict, width: int = 256, height: int = 64) -> Image.Image:
    image = cv2.imread(str(image_path))
    if image is None:
        raise ValueError("image cannot be decoded")
    h, w = image.shape[:2]
    points = np.array([[p["x"] * w, p["y"] * h] for p in sample["location"]["data"]], dtype=np.float32)
    quad = order_quad(points)
    destination = np.array([
        [0, 0],
        [width - 1, 0],
        [width - 1, height - 1],
        [0, height - 1],
    ], dtype=np.float32)
    transform = cv2.getPerspectiveTransform(quad, destination)
    crop = cv2.warpPerspective(image, transform, (width, height))
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    gray = cv2.equalizeHist(gray)
    return Image.fromarray(gray)


def predict_crop(model: DigitCnn, image: Image.Image, cell_size: tuple[int, int], fraction_digits: int):
    predicted = []
    confidences = []
    with torch.no_grad():
        for index in range(DIGITS_PER_READING):
            cell_width = image.width / DIGITS_PER_READING
            left = int(round(index * cell_width))
            right = int(round((index + 1) * cell_width))
            pad = max(1, int(cell_width * 0.08))
            cell = image.crop((max(0, left - pad), 0, min(image.width, right + pad), image.height))
            cell = cell.resize(cell_size, Image.Resampling.BICUBIC)
            array = np.array(cell, dtype=np.float32) / 255.0
            tensor = torch.from_numpy((array - 0.5) / 0.5).unsqueeze(0).unsqueeze(0)
            probabilities = model(tensor).softmax(1)[0]
            predicted.append(str(int(probabilities.argmax().item())))
            confidences.append(float(probabilities.max().item()))
    digits = "".join(predicted)
    return digits, reading_text(digits, fraction_digits), sum(confidences) / len(confidences)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--manifest", type=Path,
                        default=Path(__file__).resolve().parent.parent / "examples/ocr-full-upload-samples/manifest.json")
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    sample = manifest.get(sha256(args.image))
    if not sample:
        raise SystemExit(json.dumps({
            "error": "full image is not in the prepared full-photo validation set",
            "hint": "use examples/ocr-full-upload-samples or add detector training for arbitrary images",
        }, ensure_ascii=False))

    checkpoint = torch.load(args.model, map_location="cpu")
    model = DigitCnn()
    model.load_state_dict(checkpoint["model_state"])
    model.eval()
    cell_size = (int(checkpoint.get("cell_width", 40)), int(checkpoint.get("cell_height", 64)))
    fraction_digits = int(checkpoint.get("fraction_digits", 3))

    crop = rectify_known_region(args.image, sample)
    digits, value, confidence = predict_crop(model, crop, cell_size, fraction_digits)
    print(json.dumps({
        "digits": digits,
        "value": value,
        "confidence": round(confidence, 4),
        "recognitionMode": "full-photo-known-region",
        "source": sample["source_file"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
