"""Unit tests for the pure helpers in phonecam_client (no camera/network needed).

Run:  python -m unittest test_phonecam_client -v
"""

import struct
import unittest

import numpy as np

from phonecam_client import (
    MAX_NAL,
    PACKET_MAGIC,
    apply_orientation,
    letterbox,
    parse_packet_header,
)


def make_header(payload_len: int, rotation: int, front: bool) -> bytes:
    """Build a packet header exactly like the Android app does."""
    return (
        PACKET_MAGIC
        + struct.pack(">I", payload_len)
        + struct.pack(">I", rotation)
        + bytes([1 if front else 0])
    )


class TestParsePacketHeader(unittest.TestCase):
    def test_valid_header(self):
        head = make_header(12345, 90, True)
        self.assertEqual(parse_packet_header(head), (12345, 90, 1))

    def test_zero_rotation_back_camera(self):
        head = make_header(100, 0, False)
        self.assertEqual(parse_packet_header(head), (100, 0, 0))

    def test_bad_magic(self):
        with self.assertRaises(ValueError):
            parse_packet_header(b"BAD!" + b"\x00" * 9)

    def test_short_header(self):
        with self.assertRaises(ValueError):
            parse_packet_header(b"PCAM")

    def test_zero_length(self):
        with self.assertRaises(ValueError):
            parse_packet_header(make_header(0, 0, False))

    def test_oversize_length(self):
        with self.assertRaises(ValueError):
            parse_packet_header(make_header(MAX_NAL + 1, 0, False))

    def test_bad_rotation(self):
        with self.assertRaises(ValueError):
            parse_packet_header(make_header(100, 45, False))


class TestApplyOrientation(unittest.TestCase):
    def test_identity(self):
        img = np.zeros((1080, 1920, 3), dtype=np.uint8)
        out = apply_orientation(img, 0, False)
        self.assertEqual(out.shape, (1080, 1920, 3))

    def test_rotate90_swaps_axes(self):
        img = np.zeros((1080, 1920, 3), dtype=np.uint8)
        out = apply_orientation(img, 90, False)
        self.assertEqual(out.shape, (1920, 1080, 3))

    def test_rotate180_keeps_shape(self):
        img = np.zeros((1080, 1920, 3), dtype=np.uint8)
        out = apply_orientation(img, 180, False)
        self.assertEqual(out.shape, (1080, 1920, 3))

    def test_front_mirror_flips_pixels(self):
        img = np.zeros((4, 6, 3), dtype=np.uint8)
        img[:, :3] = 255  # left half white
        out = apply_orientation(img, 0, True)
        self.assertTrue(np.all(out[:, 3:] == 255))  # white moved right
        self.assertTrue(np.all(out[:, :3] == 0))


class TestLetterbox(unittest.TestCase):
    def test_native_size_untouched_shape(self):
        img = np.zeros((720, 1280, 3), dtype=np.uint8)
        out = letterbox(img, 1280, 720)
        self.assertEqual(out.shape, (720, 1280, 3))

    def test_portrait_into_landscape_gets_pillars(self):
        img = np.full((1920, 1080, 3), 200, dtype=np.uint8)
        out = letterbox(img, 1920, 1080)
        self.assertEqual(out.shape, (1080, 1920, 3))
        # Content width = 1080 * (1080/1920) = 607; bars on left/right.
        self.assertTrue(np.all(out[:, :400] == 0))
        self.assertTrue(np.all(out[:, -400:] == 0))
        self.assertTrue(np.all(out[:, 800:1120] == 200))

    def test_never_stretches(self):
        # 4:3 frame into 16:9 canvas must not fill the full width*height.
        img = np.full((1200, 1600, 3), 100, dtype=np.uint8)
        out = letterbox(img, 1920, 1080)
        self.assertEqual(out.shape, (1080, 1920, 3))
        self.assertEqual(out[0, 0, 0], 0)  # top-left corner is bar, not content


if __name__ == "__main__":
    unittest.main()
