"""Original stored RAR5 and two-frame GIF fixtures; no third-party book content.
RAR5 header specification: https://www.rarlab.com/technote.htm
Encrypted/split 7z fixtures use the same PNG and test password 'parity'.
"""
from pathlib import Path
import struct, zlib, runpy
root=Path(__file__).resolve().parents[1]
png=runpy.run_path(str(root/'tests/create_rar_fixture.py'))['png']
out=root/'app/src/androidTest/assets'
def vint(n):
    data=bytearray()
    while n>127: data.append((n&127)|128); n >>= 7
    data.append(n)
    return bytes(data)
def header(data):
    data=vint(len(data))+data
    return struct.pack('<I',zlib.crc32(data))+data
def file_header(data,flags,crc):
    name=b'page.png'
    return header(bytes([2,flags])+vint(len(data))+b'\x04'+vint(len(png))+b'\x20'+struct.pack('<I',crc)+b'\x00\x00'+vint(len(name))+name)
signature=b'Rar!\x1a\x07\x01\x00'
(out/'stored-rar5.rar').write_bytes(signature+header(b'\x01\x00\x00')+file_header(png,2,zlib.crc32(png))+png+header(b'\x05\x00\x00'))
first,second=png[:35],png[35:]
(out/'split.part1.rar').write_bytes(signature+header(b'\x01\x00\x01')+file_header(first,0x12,zlib.crc32(first))+first+header(b'\x05\x00\x01'))
(out/'split.part2.rar').write_bytes(signature+header(b'\x01\x00\x03\x01')+file_header(second,0x0a,zlib.crc32(png))+second+header(b'\x05\x00\x00'))
gif=b'GIF89a'+b'\x01\x00\x01\x00\x80\x00\x00'+b'\xff\x00\x00\x00\x00\xff'+b'\x21\xff\x0bNETSCAPE2.0\x03\x01\x00\x00\x00'
for pixel in [0,1]:
    gif+=b'\x21\xf9\x04\x00\x0a\x00\x00\x00'+b'\x2c\x00\x00\x00\x00\x01\x00\x01\x00\x00'+bytes([2,2,0x44+(pixel<<3),1,0])
(out/'animated.gif').write_bytes(gif+b'\x3b')
