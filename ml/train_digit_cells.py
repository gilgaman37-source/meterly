import argparse
import csv
import json
import random
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageEnhance, ImageFilter
from sklearn.model_selection import train_test_split
from torch import nn
from torch.utils.data import DataLoader, Dataset


DIGITS_PER_READING = 8
FRACTION_DIGITS = 3


@dataclass(frozen=True)
class ReadingSample:
    image: Path
    label: str


@dataclass(frozen=True)
class DigitSample:
    image: Path
    digit: int
    index: int


class DigitDataset(Dataset):
    def __init__(self, samples: list[DigitSample], augment: bool):
        self.samples = samples
        self.augment = augment

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, index: int):
        sample = self.samples[index]
        image = Image.open(sample.image).convert("L")
        cell_width = image.width / DIGITS_PER_READING
        left = int(round(sample.index * cell_width))
        right = int(round((sample.index + 1) * cell_width))
        pad = max(1, int(cell_width * 0.08))
        left = max(0, left - pad)
        right = min(image.width, right + pad)
        cell = image.crop((left, 0, right, image.height)).resize((40, 64), Image.Resampling.BICUBIC)
        if self.augment:
            if random.random() < 0.35:
                cell = ImageEnhance.Contrast(cell).enhance(random.uniform(0.75, 1.45))
            if random.random() < 0.25:
                cell = ImageEnhance.Brightness(cell).enhance(random.uniform(0.85, 1.20))
            if random.random() < 0.15:
                cell = cell.filter(ImageFilter.GaussianBlur(radius=random.uniform(0.2, 0.55)))
        array = np.array(cell, dtype=np.float32) / 255.0
        array = (array - 0.5) / 0.5
        return torch.from_numpy(array).unsqueeze(0), torch.tensor(sample.digit, dtype=torch.long)


class DigitCnn(nn.Module):
    def __init__(self):
        super().__init__()
        self.network = nn.Sequential(
            nn.Conv2d(1, 32, 3, padding=1),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),
            nn.Conv2d(32, 64, 3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),
            nn.Conv2d(64, 128, 3, padding=1),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),
            nn.Conv2d(128, 192, 3, padding=1),
            nn.BatchNorm2d(192),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool2d((1, 1)),
            nn.Flatten(),
            nn.Dropout(0.2),
            nn.Linear(192, 10),
        )

    def forward(self, images: torch.Tensor) -> torch.Tensor:
        return self.network(images)


def digits_for_label(label: str) -> str:
    scaled = int(round(float(label) * (10 ** FRACTION_DIGITS)))
    return str(scaled).zfill(DIGITS_PER_READING)[-DIGITS_PER_READING:]


def reading_text(digits: str) -> str:
    integer = str(int(digits[:-FRACTION_DIGITS] or "0"))
    fraction = digits[-FRACTION_DIGITS:]
    return f"{integer}.{fraction}".rstrip("0").rstrip(".")


def read_manifest(path: Path) -> list[ReadingSample]:
    with path.open(newline="", encoding="utf-8") as source:
        return [ReadingSample(Path(row["image"]), row["label"]) for row in csv.DictReader(source)]


def expand(samples: list[ReadingSample]) -> list[DigitSample]:
    result = []
    for sample in samples:
        digits = digits_for_label(sample.label)
        for index, digit in enumerate(digits):
            result.append(DigitSample(sample.image, int(digit), index))
    return result


def evaluate(model, reading_samples: list[ReadingSample], device: torch.device):
    model.eval()
    exact = 0
    digit_total = 0
    digit_ok = 0
    with torch.no_grad():
        for sample in reading_samples:
            image = Image.open(sample.image).convert("L")
            expected_digits = digits_for_label(sample.label)
            predicted = []
            confidences = []
            for index in range(DIGITS_PER_READING):
                cell_width = image.width / DIGITS_PER_READING
                left = int(round(index * cell_width))
                right = int(round((index + 1) * cell_width))
                pad = max(1, int(cell_width * 0.08))
                cell = image.crop((max(0, left - pad), 0, min(image.width, right + pad), image.height))
                cell = cell.resize((40, 64), Image.Resampling.BICUBIC)
                array = np.array(cell, dtype=np.float32) / 255.0
                tensor = torch.from_numpy((array - 0.5) / 0.5).unsqueeze(0).unsqueeze(0).to(device)
                probabilities = model(tensor).softmax(1)[0]
                digit = int(probabilities.argmax().item())
                predicted.append(str(digit))
                confidences.append(float(probabilities.max().item()))
            predicted_digits = "".join(predicted)
            exact += predicted_digits == expected_digits
            digit_total += DIGITS_PER_READING
            digit_ok += sum(a == b for a, b in zip(predicted_digits, expected_digits))
    return {
        "reading_exact_accuracy": exact / max(1, len(reading_samples)),
        "digit_accuracy": digit_ok / max(1, digit_total),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--epochs", default=18, type=int)
    parser.add_argument("--batch-size", default=128, type=int)
    parser.add_argument("--learning-rate", default=0.001, type=float)
    parser.add_argument("--resume-from", type=Path)
    parser.add_argument("--seed", default=42, type=int)
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    readings = read_manifest(args.manifest)
    train_readings, valid_readings = train_test_split(readings, test_size=0.18, random_state=args.seed)
    train_loader = DataLoader(DigitDataset(expand(train_readings), augment=True),
                              batch_size=args.batch_size, shuffle=True)
    valid_loader = DataLoader(DigitDataset(expand(valid_readings), augment=False),
                              batch_size=args.batch_size, shuffle=False)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = DigitCnn().to(device)
    if args.resume_from:
        checkpoint = torch.load(args.resume_from, map_location=device)
        model.load_state_dict(checkpoint["model_state"])
    loss_fn = nn.CrossEntropyLoss()
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=0.0001)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, args.epochs))

    args.output_dir.mkdir(parents=True, exist_ok=True)
    best_exact = -1.0
    history = []
    for epoch in range(1, args.epochs + 1):
        model.train()
        train_loss = 0.0
        train_total = 0
        for images, targets in train_loader:
            images = images.to(device)
            targets = targets.to(device)
            logits = model(images)
            loss = loss_fn(logits, targets)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
            train_loss += loss.item() * images.size(0)
            train_total += images.size(0)
        scheduler.step()

        model.eval()
        valid_loss = 0.0
        valid_total = 0
        digit_ok = 0
        with torch.no_grad():
            for images, targets in valid_loader:
                images = images.to(device)
                targets = targets.to(device)
                logits = model(images)
                loss = loss_fn(logits, targets)
                valid_loss += loss.item() * images.size(0)
                valid_total += images.size(0)
                digit_ok += (logits.argmax(1) == targets).sum().item()
        reading_metrics = evaluate(model, valid_readings, device)
        row = {
            "epoch": epoch,
            "train_loss": train_loss / max(1, train_total),
            "valid_loss": valid_loss / max(1, valid_total),
            "valid_digit_accuracy": digit_ok / max(1, valid_total),
            **reading_metrics,
        }
        history.append(row)
        print(json.dumps(row, ensure_ascii=False))
        if row["reading_exact_accuracy"] >= best_exact:
            best_exact = row["reading_exact_accuracy"]
            torch.save({
                "model_state": model.state_dict(),
                "digits_per_reading": DIGITS_PER_READING,
                "fraction_digits": FRACTION_DIGITS,
                "cell_width": 40,
                "cell_height": 64,
                "metrics": row,
            }, args.output_dir / "best_digit_model.pt")

    with (args.output_dir / "metrics.json").open("w", encoding="utf-8") as target:
        json.dump({"device": str(device), "history": history, "best_exact_accuracy": best_exact},
                  target, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
