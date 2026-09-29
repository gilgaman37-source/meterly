import argparse
import json
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from train_digit_cells import DigitCnn


def reading_text(digits: str, fraction_digits: int) -> str:
    integer = str(int(digits[:-fraction_digits] or "0"))
    fraction = digits[-fraction_digits:]
    return f"{integer}.{fraction}".rstrip("0").rstrip(".")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--image", required=True, type=Path)
    args = parser.parse_args()

    checkpoint = torch.load(args.model, map_location="cpu")
    model = DigitCnn()
    model.load_state_dict(checkpoint["model_state"])
    model.eval()

    digits_per_reading = int(checkpoint.get("digits_per_reading", 8))
    fraction_digits = int(checkpoint.get("fraction_digits", 3))
    cell_size = (int(checkpoint.get("cell_width", 40)), int(checkpoint.get("cell_height", 64)))

    image = Image.open(args.image).convert("L")
    predicted = []
    confidences = []
    with torch.no_grad():
        for index in range(digits_per_reading):
            cell_width = image.width / digits_per_reading
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
    print(json.dumps({
        "digits": digits,
        "value": reading_text(digits, fraction_digits),
        "confidence": round(sum(confidences) / len(confidences), 4),
    }))


if __name__ == "__main__":
    main()
