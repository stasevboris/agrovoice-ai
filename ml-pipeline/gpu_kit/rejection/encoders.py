"""Frozen visual encoders; loading never downloads weights or starts training."""
from pathlib import Path
import numpy as np
from PIL import Image
import torch
from torchvision import models

MEAN = torch.tensor([.485, .456, .406])[:, None, None]
STD = torch.tensor([.229, .224, .225])[:, None, None]


def load_encoder(name, weights, device):
    if name == 'mobilenet':
        base = models.mobilenet_v3_small(weights=None)
        base.load_state_dict(torch.load(weights, map_location='cpu', weights_only=True))
        encoder = torch.nn.Sequential(base.features, base.avgpool, torch.nn.Flatten())
        dimensions = 576
    elif name == 'dinov2':
        from transformers import Dinov2Model

        class DinoEncoder(torch.nn.Module):
            def __init__(self, folder):
                super().__init__()
                self.model = Dinov2Model.from_pretrained(folder, local_files_only=True)

            def forward(self, images):
                return self.model(pixel_values=images).last_hidden_state[:, 0]

        encoder = DinoEncoder(str(weights))
        dimensions = encoder.model.config.hidden_size
    else:
        raise ValueError('Unsupported encoder')
    encoder.eval().to(device)
    for parameter in encoder.parameters():
        parameter.requires_grad_(False)
    return encoder, dimensions


class Crops:
    def __init__(self, rows, root):
        self.rows = rows
        self.root = Path(root)

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        with Image.open(self.root / self.rows[index]['image']) as image:
            if image.size != (224, 224):
                raise ValueError('Prepared crop must be exactly 224x224')
            array = np.asarray(image.convert('RGB'), dtype=np.float32).transpose(2, 0, 1).copy() / 255
        return (torch.from_numpy(array) - MEAN) / STD


def extract_features(encoder, rows, data, device, batch, workers, check_stop):
    loader = torch.utils.data.DataLoader(Crops(rows, data), batch_size=batch,
                                        num_workers=workers, shuffle=False)
    result = []
    with torch.inference_mode():
        for index, images in enumerate(loader):
            check_stop()
            features = encoder(images.to(device)).cpu()
            if not torch.isfinite(features).all():
                raise RuntimeError('Non-finite encoder features')
            result.append(features)
            if index % 50 == 0:
                print(f'features {index + 1}/{len(loader)}', flush=True)
    if not result:
        raise ValueError('No samples')
    return torch.cat(result)
