import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('team_qr', Path(__file__).parents[1] / 'bobbot-team' / 'qr.py')
qr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qr)

ZBARIMG = '/usr/bin/zbarimg'
QUIET_ZONE = 4
SCALE = 8

PAIRING_URL = 'bobbot://pair?url=https://hermes.rjhome.top'
LONG_PAYLOAD = 'bobbot://pair?url=https://hermes.rjhome.top&token=' + 'a1b2c3d4' * 18 + '&v=2'

FINDER = [
    [1, 1, 1, 1, 1, 1, 1],
    [1, 0, 0, 0, 0, 0, 1],
    [1, 0, 1, 1, 1, 0, 1],
    [1, 0, 1, 1, 1, 0, 1],
    [1, 0, 1, 1, 1, 0, 1],
    [1, 0, 0, 0, 0, 0, 1],
    [1, 1, 1, 1, 1, 1, 1],
]


def write_pbm(matrix, path):
    """Plain PBM (1 means black), quiet zone and scaling included."""
    span = len(matrix) + 2 * QUIET_ZONE
    lines = ['P1', '%d %d' % (span * SCALE, span * SCALE)]
    for y in range(span):
        pixels = []
        for x in range(span):
            inside = QUIET_ZONE <= y < QUIET_ZONE + len(matrix) and QUIET_ZONE <= x < QUIET_ZONE + len(matrix)
            dark = inside and matrix[y - QUIET_ZONE][x - QUIET_ZONE]
            pixels.extend(['1' if dark else '0'] * SCALE)
        lines.extend([' '.join(pixels)] * SCALE)
    Path(path).write_text('\n'.join(lines) + '\n')


def decode(matrix, directory, name):
    path = os.path.join(directory, name + '.pbm')
    write_pbm(matrix, path)
    result = subprocess.run(
        [ZBARIMG, '--raw', '-q', '-Sdisable', '-Sqrcode.enable', path],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise AssertionError('zbarimg found no QR code in %s: %s' % (name, result.stderr.strip()))
    return result.stdout.rstrip('\n')


class MatrixShapeTest(unittest.TestCase):
    def test_matrix_is_square_and_sized_for_its_version(self):
        for text, version in (('hi', 1), (PAIRING_URL, 4), (LONG_PAYLOAD, 10)):
            matrix = qr.encode(text)
            self.assertEqual(len(matrix), 4 * version + 17, text[:20])
            for row in matrix:
                self.assertEqual(len(row), len(matrix))

    def test_finder_patterns_sit_in_three_corners(self):
        matrix = qr.encode(PAIRING_URL)
        size = len(matrix)
        for top, left in ((0, 0), (0, size - 7), (size - 7, 0)):
            block = [[int(matrix[top + y][left + x]) for x in range(7)] for y in range(7)]
            self.assertEqual(block, FINDER, 'finder at %d,%d' % (top, left))
        # The fourth corner must not look like one.
        fourth = [[int(matrix[size - 7 + y][size - 7 + x]) for x in range(7)] for y in range(7)]
        self.assertNotEqual(fourth, FINDER)

    def test_dark_module_is_always_set(self):
        for text in ('hi', LONG_PAYLOAD):
            matrix = qr.encode(text)
            version = (len(matrix) - 17) // 4
            self.assertTrue(matrix[4 * version + 9][8])

    def test_overlong_payload_is_rejected(self):
        self.assertEqual(len(qr.encode('A' * 213)), 4 * 10 + 17)
        with self.assertRaises(ValueError):
            qr.encode('A' * 214)


@unittest.skipUnless(os.path.exists(ZBARIMG), 'zbarimg is not installed')
class RoundTripTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def test_pairing_url(self):
        self.assertEqual(decode(qr.encode(PAIRING_URL), self.temp.name, 'pair'), PAIRING_URL)

    def test_short_payload(self):
        self.assertEqual(decode(qr.encode('hi'), self.temp.name, 'short'), 'hi')

    def test_long_payload_uses_a_higher_version(self):
        matrix = qr.encode(LONG_PAYLOAD)
        self.assertGreater(len(LONG_PAYLOAD), 190)
        self.assertGreater(len(matrix), len(qr.encode(PAIRING_URL)))
        self.assertEqual(decode(matrix, self.temp.name, 'long'), LONG_PAYLOAD)

    def test_every_version_round_trips(self):
        # One payload per version, sized to the last byte that still fits.
        for version, capacity in enumerate((14, 26, 42, 62, 84, 106, 122, 152, 180, 213), start=1):
            text = ('v%d-' % version).ljust(capacity, 'Z')
            matrix = qr.encode(text)
            self.assertEqual(len(matrix), 4 * version + 17, text[:8])
            self.assertEqual(decode(matrix, self.temp.name, 'v%d' % version), text)


if __name__ == '__main__':
    unittest.main()
