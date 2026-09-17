"""Generate a tiny uncompressed RAR4 containing our own 1x1 PNG, using the file format."""
from pathlib import Path
import struct
import zlib

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00')) + chunk(b'IEND', b'')

def header(kind, flags, body):
    data = struct.pack('<BHH', kind, flags, len(body) + 7) + body
    return struct.pack('<H', zlib.crc32(data) & 0xffff) + data

rar = b'Rar!\x1a\x07\x00' + header(0x73, 0, b'\x00' * 6)
for name in [b'chapter/page10.png', b'chapter/page2.png']:
    body = struct.pack('<IIBIIBBHI', len(png), len(png), 2, zlib.crc32(png), 0, 20, 0x30, len(name), 0x20) + name
    rar += header(0x74, 0x8000, body) + png
rar += header(0x7b, 0x4000, b'')
path = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/stored.rar'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(rar)
