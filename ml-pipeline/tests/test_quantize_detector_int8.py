import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import numpy as np
try:
    import onnx
    from onnx import TensorProto, helper
except ImportError:
    onnx = None
from PIL import Image


SOURCE = Path(__file__).resolve().parents[1] / "gpu_kit/alternatives/quantize_detector_int8.py"
spec = importlib.util.spec_from_file_location("quantize_detector_int8", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class QuantizeDetectorTest(unittest.TestCase):
    def test_letterbox_keeps_one_pixel_for_extreme_aspect_ratio(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "thin.png"
            Image.new("RGB", (1, 2000), (255, 0, 0)).save(path)
            batch = module.letterbox(path)
        self.assertEqual(batch.shape, (1, 3, 416, 416))
        self.assertEqual(batch.dtype, np.float32)
        np.testing.assert_array_equal(batch[0, :, 0, 207], [1, 0, 0])
        np.testing.assert_allclose(batch[0, :, 0, 206], [114 / 255] * 3)

    def test_rejects_nonpositive_calibration_before_creating_output(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "int8"
            for count in (0, -1):
                with self.subTest(count=count):
                    result = subprocess.run(
                        [sys.executable, "-X", "utf8", str(SOURCE),
                         "--onnx", str(Path(folder) / "missing.onnx"),
                         "--config", folder, "--out", str(output),
                         "--calibration", str(count)],
                        capture_output=True, text=True, encoding="utf-8",
                    )
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertIn("--calibration must be positive", result.stderr)
                    self.assertFalse(output.exists())


@unittest.skipIf(onnx is None, "ONNX graph tests require optional onnx dependency")
class QuantizeDetectorGraphTest(unittest.TestCase):
    def save_graph(self, folder, input_shape=(1, 3, 416, 416),
                   output_shape=(1, 53, 3549), prefix="/model.23/"):
        # Only graph metadata/names are under test; no inference is performed.
        nodes = [helper.make_node("Conv", ["features", "weights"], ["features2"],
                                  name=prefix + "cv3.0/cv3.0.0/conv/Conv")]
        nodes.extend(helper.make_node("Conv", ["features2", "weights"], [f"{branch}_{level}"],
                                      name=f"{prefix}{branch}.{level}/{branch}.{level}.2/Conv")
                     for branch in ("cv2", "cv3") for level in range(3))
        nodes.append(helper.make_node("Concat", ["boxes", "scores"], ["output0"],
                                      name=prefix + "Concat_3", axis=1))
        graph = helper.make_graph(
            nodes, "detector_metadata",
            [helper.make_tensor_value_info("images", TensorProto.FLOAT, input_shape)],
            [helper.make_tensor_value_info("output0", TensorProto.FLOAT, output_shape)],
        )
        path = Path(folder) / "detector.onnx"
        onnx.save(helper.make_model(graph), path)
        return path

    def test_head_preserves_final_predictions_but_quantizes_hidden_convolutions(self):
        with tempfile.TemporaryDirectory() as folder:
            excluded = module.float_head(self.save_graph(folder))
        self.assertIn("/model.23/Concat_3", excluded)
        for branch in ("cv2", "cv3"):
            for level in range(3):
                self.assertIn(f"/model.23/{branch}.{level}/{branch}.{level}.2/Conv", excluded)
        self.assertNotIn("/model.23/cv3.0/cv3.0.0/conv/Conv", excluded)

    def test_rejects_one_to_one_and_transposed_output(self):
        with tempfile.TemporaryDirectory() as folder:
            for shape in ((1, 300, 6), (1, 3549, 53)):
                with self.subTest(shape=shape):
                    path = self.save_graph(folder, output_shape=shape)
                    with self.assertRaisesRegex(ValueError, "output"):
                        module.float_head(path)

    def test_rejects_input_that_calibration_cannot_feed(self):
        with tempfile.TemporaryDirectory() as folder:
            for shape in ((1, 3, 640, 640), (1, 416, 416, 3), ("batch", 3, 416, 416)):
                with self.subTest(shape=shape):
                    path = self.save_graph(folder, input_shape=shape)
                    with self.assertRaisesRegex(ValueError, "input"):
                        module.float_head(path)

    def test_rejects_unknown_head_names_instead_of_silently_quantizing_scores(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.save_graph(folder, prefix="renamed/")
            with self.assertRaisesRegex(ValueError, "head"):
                module.float_head(path)

    def test_rejects_head_when_a_final_convolution_cannot_be_identified(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.save_graph(folder)
            model = onnx.load(path)
            model.graph.node[1].name = "/model.23/renamed_prediction/Conv"
            onnx.save(model, path)
            with self.assertRaisesRegex(ValueError, "head"):
                module.float_head(path)


if __name__ == "__main__":
    unittest.main()
