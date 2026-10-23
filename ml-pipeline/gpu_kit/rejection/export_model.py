"""Export the actual MobileNet verifier graph and check CPU numerical parity."""
import hashlib
import numpy as np
import torch
import onnxruntime as ort


def export_mobile(encoder, head, path, threads):
    model = torch.nn.Sequential(encoder.cpu(), head.cpu()).eval()
    sample = torch.zeros(1, 3, 224, 224)
    torch.onnx.export(model, sample, str(path), input_names=['images'], output_names=['logits'],
                      opset_version=17, dynamo=False)
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
    actual = session.run(None, {'images': sample.numpy()})[0]
    with torch.inference_mode():
        expected = model(sample).numpy()
    if actual.shape != (1, head.out_features) or not np.allclose(actual, expected, atol=1e-4, rtol=1e-4):
        raise RuntimeError('Export parity failed')
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return dict(file=path.name, sha256=digest, shape=list(actual.shape),
                parity_max_abs_diff=float(np.max(np.abs(actual - expected))),
                input='RGB float32 NCHW224 normalized by ImageNet mean/std; prepared pad geometry')
