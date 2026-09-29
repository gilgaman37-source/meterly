import ast
import csv
import hashlib
import json
import shutil
from pathlib import Path


ROOT = Path("work/datasets/yandextoloka-water-meters/WaterMeters")
GOLDEN = Path("ml/golden-kaggle-validation-digit-cells.csv")
OUTPUT = Path("examples/ocr-full-upload-samples")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    data = {}
    with (ROOT / "data.csv").open(newline="", encoding="utf-8-sig") as source:
        for row in csv.DictReader(source):
            data[row["photo_name"]] = row

    rows = []
    with GOLDEN.open(newline="", encoding="utf-8") as source:
        for index, row in enumerate(csv.DictReader(source), start=1):
            crop_name = Path(row["image"]).with_suffix(".jpg").name
            source_image = ROOT / "images" / crop_name
            annotation = data[crop_name]
            expected = row["expected_value"]
            target_name = f"{index:02d}_full_expected_{expected.replace('.', '_')}.jpg"
            target = OUTPUT / target_name
            shutil.copyfile(source_image, target)
            rows.append({
                "file": target_name,
                "source_file": crop_name,
                "sha256": sha256(target),
                "expected_value": expected,
                "location": ast.literal_eval(annotation["location"]),
            })

    with (OUTPUT / "manifest.json").open("w", encoding="utf-8") as target:
        json.dump({"samples": rows}, target, ensure_ascii=False, indent=2)
    print(f"created={len(rows)} dir={OUTPUT}")


if __name__ == "__main__":
    main()
