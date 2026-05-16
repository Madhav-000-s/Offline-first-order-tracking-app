import random

# A handful of precomputed waypoint routes so the courier simulator never
# depends on an external routing API -- the demo works with no network at
# all (DESIGN.md §14.4: "the demo works on a plane too").
FIXTURE_ROUTES: list[list[tuple[float, float]]] = [
    [(12.9716, 77.5946), (12.9750, 77.6010), (12.9790, 77.6080), (12.9830, 77.6150)],
    [(12.9350, 77.6100), (12.9400, 77.6180), (12.9460, 77.6250), (12.9520, 77.6320)],
    [(12.9550, 77.5800), (12.9600, 77.5850), (12.9650, 77.5920), (12.9700, 77.5990)],
    [(12.9900, 77.5700), (12.9930, 77.5780), (12.9960, 77.5860), (13.0000, 77.5940)],
]


def pick_fixture_route() -> list[tuple[float, float]]:
    return random.choice(FIXTURE_ROUTES)


def encode_polyline(points: list[tuple[float, float]]) -> str:
    """Google's polyline algorithm, precision 5."""
    chars: list[str] = []
    prev_lat = prev_lng = 0
    for lat, lng in points:
        lat_i, lng_i = round(lat * 1e5), round(lng * 1e5)
        for value, prev in ((lat_i, prev_lat), (lng_i, prev_lng)):
            delta = value - prev
            shifted = ~(delta << 1) if delta < 0 else (delta << 1)
            while shifted >= 0x20:
                chars.append(chr((0x20 | (shifted & 0x1F)) + 63))
                shifted >>= 5
            chars.append(chr(shifted + 63))
        prev_lat, prev_lng = lat_i, lng_i
    return "".join(chars)


def decode_polyline(encoded: str) -> list[tuple[float, float]]:
    points: list[tuple[float, float]] = []
    index = lat = lng = 0
    length = len(encoded)
    while index < length:
        for is_lat in (True, False):
            shift = result = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else (result >> 1)
            if is_lat:
                lat += delta
            else:
                lng += delta
        points.append((lat / 1e5, lng / 1e5))
    return points
