import sys
from pathlib import Path
import unittest
import numpy as np
from PIL import Image
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_supplemented import mask_box, group_split, leaf_group


class SupplementTests(unittest.TestCase):
    def test_mask_leaf_and_background_rejection(self):
        rgb = np.zeros((100, 100, 3), dtype=np.uint8)
        rgb[20:80, 30:70] = [40, 130, 30]
        box, error = mask_box(Image.fromarray(rgb))
        self.assertIsNone(error)
        self.assertAlmostEqual(box[0], .5)
        self.assertAlmostEqual(box[2], .42)
        self.assertIsNone(mask_box(Image.fromarray(np.full_like(rgb, 150)))[0])

    def test_physical_leaf_group_excludes_category_collision(self):
        mapping = {'rs_hl 42': ['Potato___healthy:::7.0', 'Tomato___healthy:::99.0']}
        a = leaf_group('uuid___RS_HL 42.JPG', 'Potato___healthy', mapping)
        b = leaf_group('other___RS_HL 42.JPG', 'Potato___healthy', mapping)
        self.assertEqual(a, b)
        self.assertNotEqual(a, leaf_group('uuid___RS_HL 42.JPG', 'Tomato___healthy', mapping))

    def test_grouped_holdout_keeps_all_views_together(self):
        records = [dict(group_id=f'leaf_{i}', boxes=[[11, .5, .5, .4, .4]])
                   for i in range(10) for _ in range(3)]
        group_split(records)
        assignments = {}
        for r in records:
            assignments.setdefault(r['group_id'], set()).add(r['split'])
        self.assertTrue(all(len(v) == 1 for v in assignments.values()))
        self.assertEqual(sum(v == {'val_aux'} for v in assignments.values()), 2)


if __name__ == '__main__':
    unittest.main()
