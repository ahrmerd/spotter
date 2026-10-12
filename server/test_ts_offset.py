"""Run: python3 -m unittest server/test_ts_offset.py"""
import os, sys, unittest
sys.path.insert(0, os.path.dirname(__file__))
from engine import ts_offset


def ts_packets(n=6):
    return b"".join(b"\x47" + bytes(187) for _ in range(n))


class TsOffset(unittest.TestCase):
    def test_png_header_before_ts_is_found(self):
        png = b"\x89PNG\r\n\x1a\n" + bytes(112)  # 120 bytes, like the real disguised segments
        self.assertEqual(ts_offset(png + ts_packets()), 120)

    def test_plain_ts_starts_at_zero(self):
        self.assertEqual(ts_offset(ts_packets()), 0)

    def test_no_ts_returns_zero(self):
        self.assertEqual(ts_offset(b"abc" * 1000), 0)

    def test_single_0x47_is_not_enough(self):
        self.assertEqual(ts_offset(b"\x47" + bytes(1500)), 0)


if __name__ == "__main__":
    unittest.main()
