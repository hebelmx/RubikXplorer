"""AC3: the exported model's size limit and card, and the seeded synthetic side."""

from __future__ import annotations

import json
import unittest

import numpy as np

import synth_faces
import train_sticker_model as trainer
from sticker_common import COLOR_ORDER, INPUT_SIZE, MODEL_CARD, MODEL_OUTPUT, MODEL_SIZE_LIMIT, rotate_labels, sha256_bytes


class ModelTest(unittest.TestCase):
    def test_the_size_limit_rejects_500_kb_or_more(self) -> None:
        trainer.check_size(MODEL_SIZE_LIMIT - 1)
        with self.assertRaises(SystemExit):
            trainer.check_size(MODEL_SIZE_LIMIT)
        with self.assertRaises(SystemExit):
            trainer.check_size(MODEL_SIZE_LIMIT + 1)

    def test_the_committed_model_is_the_carded_one_and_under_the_limit(self) -> None:
        card = json.loads(MODEL_CARD.read_text())
        data = MODEL_OUTPUT.read_bytes()
        self.assertLess(len(data), MODEL_SIZE_LIMIT)
        self.assertEqual(card["sha256"], sha256_bytes(data))
        self.assertEqual(card["bytes"], len(data))
        self.assertEqual(list(COLOR_ORDER), card["colorOrder"])
        self.assertEqual([1, INPUT_SIZE, INPUT_SIZE, 3], card["input"])
        self.assertEqual([1, 9, 6], card["output"])
        self.assertTrue(0.5 <= card["tau"] <= 0.99)

    def test_the_model_reads_one_face_into_nine_softmaxes(self) -> None:
        image = np.zeros((1, INPUT_SIZE, INPUT_SIZE, 3), dtype=np.uint8)
        probs = trainer.tflite_probs(MODEL_OUTPUT.read_bytes(), image)
        self.assertEqual((1, 9, 6), probs.shape)
        np.testing.assert_allclose(probs.sum(axis=-1), 1.0, atol=1e-3)

    def test_tau_is_the_smallest_grid_value_with_no_confidently_wrong(self) -> None:
        probs = np.zeros((1, 9, 6), dtype=np.float32)
        probs[0, :, 0] = 1.0
        labels = np.zeros((1, 9), dtype=np.int64)
        labels[0, 0] = 1  # one sticker wrong at probability 1.0: no tau avoids it
        self.assertEqual((0.99, {"zeroConfidentlyWrongReached": False}), trainer.choose_tau(probs, labels))
        probs[0, 0] = [0.3, 0.7, 0, 0, 0, 0]  # wrong at 0.3? top-1 is now right
        probs[0, 1] = [0.62, 0.38, 0, 0, 0, 0]
        labels[0, 1] = 1  # wrong at 0.62: tau must exceed it
        self.assertEqual(0.63, trainer.choose_tau(probs, labels)[0])

    def test_synthetic_faces_are_seeded_and_labelled(self) -> None:
        a, la = synth_faces.generate(6, 7, synth_faces.default_palettes())
        b, lb = synth_faces.generate(6, 7, synth_faces.default_palettes())
        np.testing.assert_array_equal(a, b)
        np.testing.assert_array_equal(la, lb)
        self.assertEqual((6, INPUT_SIZE, INPUT_SIZE, 3), a.shape)
        self.assertTrue(((la >= 0) & (la < len(COLOR_ORDER))).all())

    def test_quarter_turn_augmentation_turns_the_labels_with_the_image(self) -> None:
        labels = list(range(9))
        self.assertEqual([2, 5, 8, 1, 4, 7, 0, 3, 6], rotate_labels(labels, 1))
        self.assertEqual(labels, rotate_labels(labels, 4))


if __name__ == "__main__":
    unittest.main()
