"""Minimal QR encoder: byte mode, error correction level M, versions 1-10.

The Hermes dashboard renders a pairing QR code, and this plugin is copied into
user profiles where no third-party package is guaranteed to be installed, so
the encoder is vendored here instead of taking a dependency.
"""

# Level M, per version: (EC codewords per block, [(block count, data codewords per block), ...]).
_EC_BLOCKS = {
    1: (10, [(1, 16)]),
    2: (16, [(1, 28)]),
    3: (26, [(1, 44)]),
    4: (18, [(2, 32)]),
    5: (24, [(2, 43)]),
    6: (16, [(4, 27)]),
    7: (18, [(4, 31)]),
    8: (22, [(2, 38), (2, 39)]),
    9: (22, [(3, 36), (2, 37)]),
    10: (26, [(4, 43), (1, 44)]),
}

# Alignment pattern centre coordinates; every pair of centres carries a pattern
# except the three that would land on a finder pattern.
_ALIGNMENT = {
    1: [],
    2: [6, 18],
    3: [6, 22],
    4: [6, 26],
    5: [6, 30],
    6: [6, 34],
    7: [6, 22, 38],
    8: [6, 24, 42],
    9: [6, 26, 46],
    10: [6, 28, 50],
}

# Modules left over after the codeword stream; they stay light.
_REMAINDER_BITS = {1: 0, 2: 7, 3: 7, 4: 7, 5: 7, 6: 7, 7: 0, 8: 0, 9: 0, 10: 0}

_MAX_VERSION = 10
_MODE_BYTE = 0b0100
_EC_LEVEL_M = 0b00
_FORMAT_GENERATOR = 0x537
_FORMAT_MASK = 0x5412
_VERSION_GENERATOR = 0x1F25
_PAD_BYTES = (0xEC, 0x11)

_PENALTY_N1 = 3
_PENALTY_N2 = 3
_PENALTY_N3 = 40
_PENALTY_N4 = 10

# 1:1:3:1:1 dark/light run with four light modules on one side.
_FINDER_LIKE = (
    [True, False, True, True, True, False, True, False, False, False, False],
    [False, False, False, False, True, False, True, True, True, False, True],
)


# --- GF(256), primitive polynomial 0x11D ---------------------------------

_EXP = [0] * 512
_LOG = [0] * 256


def _build_tables():
    x = 1
    for i in range(255):
        _EXP[i] = x
        _LOG[x] = i
        x <<= 1
        if x & 0x100:
            x ^= 0x11D
    for i in range(255, 512):
        _EXP[i] = _EXP[i - 255]


_build_tables()


def _gf_mul(a, b):
    if a == 0 or b == 0:
        return 0
    return _EXP[_LOG[a] + _LOG[b]]


def _generator_poly(degree):
    """Product of (x - a^i) for i in 0..degree-1, highest-order coefficient first."""
    poly = [1]
    for i in range(degree):
        shifted = [0] * (len(poly) + 1)
        for j, coef in enumerate(poly):
            shifted[j] ^= coef
            shifted[j + 1] ^= _gf_mul(coef, _EXP[i])
        poly = shifted
    return poly


def _ec_codewords(data, count):
    gen = _generator_poly(count)
    rem = list(data) + [0] * count
    for i in range(len(data)):
        factor = rem[i]
        if factor:
            for j, coef in enumerate(gen):
                rem[i + j] ^= _gf_mul(coef, factor)
    return rem[len(data):]


# --- Bit stream ----------------------------------------------------------

def _count_bits(version):
    return 8 if version < 10 else 16


def _data_capacity(version):
    return sum(count * size for count, size in _EC_BLOCKS[version][1])


def _pick_version(payload_len):
    for version in range(1, _MAX_VERSION + 1):
        needed = 4 + _count_bits(version) + 8 * payload_len
        if needed <= _data_capacity(version) * 8:
            return version
    raise ValueError('payload too long for version 10')


def _push(bits, value, width):
    for shift in range(width - 1, -1, -1):
        bits.append((value >> shift) & 1 == 1)


def _data_codewords(payload, version):
    total = _data_capacity(version)
    bits = []
    _push(bits, _MODE_BYTE, 4)
    _push(bits, len(payload), _count_bits(version))
    for byte in payload:
        _push(bits, byte, 8)
    bits.extend([False] * min(4, total * 8 - len(bits)))
    bits.extend([False] * (-len(bits) % 8))
    words = [
        sum(1 << (7 - i) for i, bit in enumerate(bits[o:o + 8]) if bit)
        for o in range(0, len(bits), 8)
    ]
    for i in range(total - len(words)):
        words.append(_PAD_BYTES[i % 2])
    return words


def _interleave(payload, version):
    """Split into blocks, append EC codewords, then interleave both halves."""
    ec_count, groups = _EC_BLOCKS[version]
    words = _data_codewords(payload, version)
    blocks, ec_blocks, offset = [], [], 0
    for count, size in groups:
        for _ in range(count):
            block = words[offset:offset + size]
            offset += size
            blocks.append(block)
            ec_blocks.append(_ec_codewords(block, ec_count))
    result = []
    for i in range(max(len(block) for block in blocks)):
        for block in blocks:
            if i < len(block):
                result.append(block[i])
    for i in range(ec_count):
        for block in ec_blocks:
            result.append(block[i])
    return result


# --- Function patterns ---------------------------------------------------

def _format_positions(size):
    """(x, y) for format bits 0..14, as the two redundant copies."""
    first = ([(8, i) for i in range(6)]
             + [(8, 7), (8, 8), (7, 8)]
             + [(14 - i, 8) for i in range(9, 15)])
    second = ([(size - 1 - i, 8) for i in range(8)]
              + [(8, size - 15 + i) for i in range(8, 15)])
    return first, second


def _version_positions(size):
    """((x, y), (x, y)) for version bits 0..17: bottom-left and top-right blocks."""
    return [((size - 11 + i % 3, i // 3), (i // 3, size - 11 + i % 3)) for i in range(18)]


def _format_value(mask):
    data = (_EC_LEVEL_M << 3) | mask
    rem = data
    for _ in range(10):
        rem = (rem << 1) ^ ((rem >> 9) * _FORMAT_GENERATOR)
    return ((data << 10) | rem) ^ _FORMAT_MASK


def _version_value(version):
    rem = version
    for _ in range(12):
        rem = (rem << 1) ^ ((rem >> 11) * _VERSION_GENERATOR)
    return (version << 12) | rem


def _base_matrix(version):
    size = version * 4 + 17
    modules = [[False] * size for _ in range(size)]
    function = [[False] * size for _ in range(size)]

    def put(x, y, dark):
        modules[y][x] = dark
        function[y][x] = True

    # Finders plus their separators; the ring at distance 2 is the only light one.
    for cx, cy in ((3, 3), (size - 4, 3), (3, size - 4)):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                x, y = cx + dx, cy + dy
                if 0 <= x < size and 0 <= y < size:
                    d = max(abs(dx), abs(dy))
                    put(x, y, d <= 3 and d != 2)

    for i in range(8, size - 8):
        put(6, i, i % 2 == 0)
        put(i, 6, i % 2 == 0)

    centres = _ALIGNMENT[version]
    last = len(centres) - 1
    for i, cy in enumerate(centres):
        for j, cx in enumerate(centres):
            if (i, j) in ((0, 0), (0, last), (last, 0)):
                continue
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    put(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)

    put(8, size - 8, True)  # dark module: row 4*version+9, column 8

    for copy in _format_positions(size):
        for x, y in copy:
            function[y][x] = True

    if version >= 7:
        value = _version_value(version)
        for i, (a, b) in enumerate(_version_positions(size)):
            bit = (value >> i) & 1 == 1
            put(a[0], a[1], bit)
            put(b[0], b[1], bit)

    return modules, function


def _draw_format(modules, mask):
    value = _format_value(mask)
    for copy in _format_positions(len(modules)):
        for i, (x, y) in enumerate(copy):
            modules[y][x] = (value >> i) & 1 == 1


# --- Placement, masking, scoring ----------------------------------------

def _place_data(modules, function, bits):
    size = len(modules)
    i = 0
    right = size - 1
    while right >= 1:
        if right == 6:  # the vertical timing column is never half of a data pair,
            right = 5   # so the remaining pairs shift one column left
        upward = ((right + 1) & 2) == 0
        for vert in range(size):
            for j in range(2):
                x = right - j
                y = (size - 1 - vert) if upward else vert
                if not function[y][x] and i < len(bits):
                    modules[y][x] = bits[i]
                    i += 1
        right -= 2
    if i != len(bits):
        raise RuntimeError('codeword stream did not fill the symbol')


def _mask_at(mask, y, x):
    if mask == 0:
        return (x + y) % 2 == 0
    if mask == 1:
        return y % 2 == 0
    if mask == 2:
        return x % 3 == 0
    if mask == 3:
        return (x + y) % 3 == 0
    if mask == 4:
        return (y // 2 + x // 3) % 2 == 0
    if mask == 5:
        return (x * y) % 2 + (x * y) % 3 == 0
    if mask == 6:
        return ((x * y) % 2 + (x * y) % 3) % 2 == 0
    return ((x + y) % 2 + (x * y) % 3) % 2 == 0


def _apply_mask(modules, function, mask):
    for y, row in enumerate(modules):
        for x in range(len(row)):
            if not function[y][x] and _mask_at(mask, y, x):
                row[x] = not row[x]


def _penalty(modules):
    size = len(modules)
    score = 0
    lines = [row[:] for row in modules]
    lines += [[modules[y][x] for y in range(size)] for x in range(size)]

    for line in lines:
        run = 1
        for i in range(1, size):
            if line[i] == line[i - 1]:
                run += 1
            else:
                if run >= 5:
                    score += _PENALTY_N1 + run - 5
                run = 1
        if run >= 5:
            score += _PENALTY_N1 + run - 5
        for i in range(size - 10):
            if line[i:i + 11] in _FINDER_LIKE:
                score += _PENALTY_N3

    for y in range(size - 1):
        for x in range(size - 1):
            corner = modules[y][x]
            if corner == modules[y][x + 1] == modules[y + 1][x] == modules[y + 1][x + 1]:
                score += _PENALTY_N2

    dark = sum(sum(row) for row in modules)
    total = size * size
    score += _PENALTY_N4 * int(abs(dark * 100.0 / total - 50) / 5)
    return score


# --- Public API ----------------------------------------------------------

def encode(text):
    """Return the QR matrix as rows of booleans; True means a dark module."""
    payload = text.encode('utf-8')
    version = _pick_version(len(payload))
    codewords = _interleave(payload, version)

    bits = []
    for word in codewords:
        _push(bits, word, 8)
    bits.extend([False] * _REMAINDER_BITS[version])

    base, function = _base_matrix(version)
    _place_data(base, function, bits)

    best = None
    for mask in range(8):
        candidate = [row[:] for row in base]
        _apply_mask(candidate, function, mask)
        _draw_format(candidate, mask)
        score = _penalty(candidate)
        if best is None or score < best[0]:
            best = (score, candidate)
    return best[1]
