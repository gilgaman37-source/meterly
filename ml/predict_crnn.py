import argparse
import json
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from train_crnn import Crnn


def decode(logits: torch.Tensor, chars: str) -> str:
    indexes = logits.permute(1, 0, 2).argmax(2)[0].cpu().tolist()
    result = []
    previous = 0
    for index in indexes:
        if index != 0 and index != previous:
            result.append(chars[index - 1])
        previous = index
    return "".join(result)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--image", required=True, type=Path)
    args = parser.parse_args()

    checkpoint = torch.load(args.model, map_location="cpu")
    chars = checkpoint["chars"]
    width = checkpoint.get("width", 256)
    height = checkpoint.get("height", 64)

    model = Crnn(num_classes=len(chars) + 1)
    model.load_state_dict(checkpoint["model_state"])
    model.eval()

    image = Image.open(args.image).convert("L").resize((width, height))
    array = np.array(image, dtype=np.float32) / 255.0
    tensor = torch.from_numpy((array - 0.5) / 0.5).unsqueeze(0).unsqueeze(0)
    with torch.no_grad():
        logits = model(tensor)
        probabilities = logits.exp().max(2).values.mean().item()
    print(json.dumps({"value": decode(logits, chars), "confidence": round(probabilities, 4)}))


if __name__ == "__main__":
    main()
