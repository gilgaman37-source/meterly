import argparse
import csv
import json
import random
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageEnhance
from sklearn.model_selection import train_test_split
from torch import nn
from torch.utils.data import DataLoader, Dataset


CHARS = "0123456789."


@dataclass(frozen=True)
class Sample:
    image: Path
    label: str


class MeterDataset(Dataset):
    def __init__(self, samples: list[Sample], augment: bool):
        self.samples = samples
        self.augment = augment

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, index: int):
        sample = self.samples[index]
        image = Image.open(sample.image).convert("L")
        if self.augment:
            if random.random() < 0.45:
                image = ImageEnhance.Contrast(image).enhance(random.uniform(0.75, 1.35))
            if random.random() < 0.25:
                image = ImageEnhance.Brightness(image).enhance(random.uniform(0.85, 1.20))
        array = np.array(image, dtype=np.float32) / 255.0
        array = (array - 0.5) / 0.5
        return torch.from_numpy(array).unsqueeze(0), sample.label


class Crnn(nn.Module):
    def __init__(self, num_classes: int):
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(1, 32, 3, padding=1),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2, 2),
            nn.Conv2d(32, 64, 3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2, 2),
            nn.Conv2d(64, 128, 3, padding=1),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.MaxPool2d((2, 1)),
            nn.Conv2d(128, 128, 3, padding=1),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.MaxPool2d((2, 1)),
        )
        self.sequence = nn.LSTM(128 * 4, 128, num_layers=2, bidirectional=True, batch_first=True, dropout=0.15)
        self.classifier = nn.Linear(256, num_classes)

    def forward(self, images: torch.Tensor) -> torch.Tensor:
        features = self.features(images)
        batch, channels, height, width = features.shape
        features = features.permute(0, 3, 1, 2).reshape(batch, width, channels * height)
        sequence, _ = self.sequence(features)
        logits = self.classifier(sequence)
        return logits.log_softmax(2).permute(1, 0, 2)


def collate(batch):
    images = torch.stack([item[0] for item in batch])
    labels = [item[1] for item in batch]
    return images, labels


def encode(labels: list[str], char_to_index: dict[str, int]):
    lengths = torch.tensor([len(label) for label in labels], dtype=torch.long)
    encoded = torch.tensor([char_to_index[ch] for label in labels for ch in label], dtype=torch.long)
    return encoded, lengths


def decode(logits: torch.Tensor, index_to_char: dict[int, str]) -> list[str]:
    best = logits.permute(1, 0, 2).argmax(2).cpu().tolist()
    values = []
    for row in best:
        previous = 0
        chars = []
        for index in row:
            if index != 0 and index != previous:
                chars.append(index_to_char[index])
            previous = index
        values.append("".join(chars))
    return values


def read_manifest(path: Path) -> list[Sample]:
    with path.open(newline="", encoding="utf-8") as source:
        return [Sample(Path(row["image"]), row["label"]) for row in csv.DictReader(source)]


def evaluate(model, loader, loss_fn, char_to_index, index_to_char, device):
    model.eval()
    total_loss = 0.0
    total = 0
    exact = 0
    with torch.no_grad():
        for images, labels in loader:
            images = images.to(device)
            logits = model(images)
            targets, target_lengths = encode(labels, char_to_index)
            targets = targets.to(device)
            target_lengths = target_lengths.to(device)
            input_lengths = torch.full((images.size(0),), logits.size(0), dtype=torch.long, device=device)
            loss = loss_fn(logits, targets, input_lengths, target_lengths)
            total_loss += loss.item() * images.size(0)
            predictions = decode(logits, index_to_char)
            exact += sum(pred == label for pred, label in zip(predictions, labels))
            total += images.size(0)
    return {"loss": total_loss / max(1, total), "exact_accuracy": exact / max(1, total)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--epochs", default=12, type=int)
    parser.add_argument("--batch-size", default=32, type=int)
    parser.add_argument("--learning-rate", default=0.001, type=float)
    parser.add_argument("--seed", default=42, type=int)
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    samples = read_manifest(args.manifest)
    train_samples, valid_samples = train_test_split(samples, test_size=0.18, random_state=args.seed)
    train_loader = DataLoader(MeterDataset(train_samples, augment=True), batch_size=args.batch_size,
                              shuffle=True, collate_fn=collate)
    valid_loader = DataLoader(MeterDataset(valid_samples, augment=False), batch_size=args.batch_size,
                              shuffle=False, collate_fn=collate)

    char_to_index = {char: index + 1 for index, char in enumerate(CHARS)}
    index_to_char = {index + 1: char for index, char in enumerate(CHARS)}
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = Crnn(num_classes=len(CHARS) + 1).to(device)
    loss_fn = nn.CTCLoss(blank=0, zero_infinity=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=0.0001)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    best_accuracy = -1.0
    history = []
    for epoch in range(1, args.epochs + 1):
        model.train()
        total_loss = 0.0
        total = 0
        for images, labels in train_loader:
            images = images.to(device)
            logits = model(images)
            targets, target_lengths = encode(labels, char_to_index)
            targets = targets.to(device)
            target_lengths = target_lengths.to(device)
            input_lengths = torch.full((images.size(0),), logits.size(0), dtype=torch.long, device=device)
            loss = loss_fn(logits, targets, input_lengths, target_lengths)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimizer.step()
            total_loss += loss.item() * images.size(0)
            total += images.size(0)

        metrics = evaluate(model, valid_loader, loss_fn, char_to_index, index_to_char, device)
        row = {
            "epoch": epoch,
            "train_loss": total_loss / max(1, total),
            "valid_loss": metrics["loss"],
            "valid_exact_accuracy": metrics["exact_accuracy"],
        }
        history.append(row)
        print(json.dumps(row, ensure_ascii=False))
        if metrics["exact_accuracy"] >= best_accuracy:
            best_accuracy = metrics["exact_accuracy"]
            torch.save({
                "model_state": model.state_dict(),
                "chars": CHARS,
                "width": 256,
                "height": 64,
                "metrics": row,
            }, args.output_dir / "best_model.pt")

    with (args.output_dir / "metrics.json").open("w", encoding="utf-8") as target:
        json.dump({"device": str(device), "history": history, "best_accuracy": best_accuracy},
                  target, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
