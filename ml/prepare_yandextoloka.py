import argparse
import ast
import csv
from pathlib import Path

import cv2
import numpy as np


def normalize_value(value: str) -> str:
    number = float(value)
    text = f"{number:.3f}".rstrip("0").rstrip(".")
    return text


def polygon_points(location: str, width: int, height: int) -> np.ndarray:
    parsed = ast.literal_eval(location)
    points = parsed["data"]
    return np.array([[p["x"] * width, p["y"] * height] for p in points], dtype=np.float32)


def order_quad(points: np.ndarray) -> np.ndarray:
    rect = cv2.minAreaRect(points)
    box = cv2.boxPoints(rect).astype(np.float32)
    sums = box.sum(axis=1)
    diffs = np.diff(box, axis=1).reshape(-1)
    return np.array([
        box[np.argmin(sums)],
        box[np.argmin(diffs)],
        box[np.argmax(sums)],
        box[np.argmax(diffs)],
    ], dtype=np.float32)


def rectify(image: np.ndarray, points: np.ndarray, target_width: int, target_height: int) -> np.ndarray:
    quad = order_quad(points)
    destination = np.array([
        [0, 0],
        [target_width - 1, 0],
        [target_width - 1, target_height - 1],
        [0, target_height - 1],
    ], dtype=np.float32)
    transform = cv2.getPerspectiveTransform(quad, destination)
    crop = cv2.warpPerspective(image, transform, (target_width, target_height))
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    gray = cv2.equalizeHist(gray)
    return gray


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--width", default=256, type=int)
    parser.add_argument("--height", default=64, type=int)
    args = parser.parse_args()

    images_dir = args.dataset_root / "images"
    data_csv = args.dataset_root / "data.csv"
    crops_dir = args.output_dir / "crops"
    crops_dir.mkdir(parents=True, exist_ok=True)

    rows = []
    with data_csv.open(newline="", encoding="utf-8-sig") as source:
        reader = csv.DictReader(source)
        for item in reader:
            image_path = images_dir / item["photo_name"]
            image = cv2.imread(str(image_path))
            if image is None:
                continue
            points = polygon_points(item["location"], image.shape[1], image.shape[0])
            crop = rectify(image, points, args.width, args.height)
            label = normalize_value(item["value"])
            crop_name = Path(item["photo_name"]).with_suffix(".png").name
            crop_path = crops_dir / crop_name
            cv2.imwrite(str(crop_path), crop)
            rows.append({"image": str(crop_path), "label": label})

    manifest = args.output_dir / "manifest.csv"
    with manifest.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=["image", "label"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"prepared={len(rows)} manifest={manifest}")


if __name__ == "__main__":
    main()
