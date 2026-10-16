"""Require a GPU forward/backward pass before launching training."""
import json
import time
import torch
from torch import nn

if not torch.cuda.is_available():
    raise SystemExit("GPU unavailable. Do not launch a GPU training job.")
device = torch.device("cuda:0")
model = nn.Sequential(nn.Conv2d(3, 16, 3, padding=1), nn.BatchNorm2d(16), nn.SiLU(),
                      nn.Conv2d(16, 32, 3, stride=2, padding=1)).to(device)
optimizer = torch.optim.AdamW(model.parameters(), lr=0.001)
start = time.monotonic()
for _ in range(3):
    optimizer.zero_grad()
    prediction = model(torch.randn(4, 3, 128, 128, device=device))
    loss = prediction.square().mean()
    loss.backward()
    optimizer.step()
torch.cuda.synchronize()
print(json.dumps(dict(gpu=torch.cuda.get_device_name(0), torch=torch.__version__, hip=torch.version.hip,
                      forward_backward="passed", seconds=time.monotonic()-start, loss=float(loss.detach().cpu())), indent=2))
