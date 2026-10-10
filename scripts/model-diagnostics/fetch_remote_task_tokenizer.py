"""Fetch only the TOKENIZER_MODEL entry of a remote .task (zip) bundle via HTTP range requests.

Lets candidate artifacts be compared for tokenizer defects without downloading the
full model weights. Usage:
  python fetch_remote_task_tokenizer.py <https-url-to-.task> <out-path.task>
The output is a small zip that contains only TOKENIZER_MODEL (and METADATA when present),
which inspect_task_tokenizer.py accepts directly.
"""
import io
import struct
import sys
import urllib.request
import zipfile


def ranged(url, start, end):
    req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}", "User-Agent": "mini-diagnostics"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read(), r.headers.get("Content-Range")


def main():
    url, out = sys.argv[1], sys.argv[2]
    _, content_range = ranged(url, 0, 0)
    total = int(content_range.split("/")[-1])
    tail_len = min(total, 65536 + 22)
    tail, _ = ranged(url, total - tail_len, total - 1)
    eocd = tail.rfind(b"PK\x05\x06")
    cd_size, cd_offset = struct.unpack("<II", tail[eocd + 12:eocd + 20])
    cd, _ = ranged(url, cd_offset, cd_offset + cd_size - 1)
    entries = {}
    pos = 0
    while pos < len(cd) and cd[pos:pos + 4] == b"PK\x01\x02":
        method, = struct.unpack("<H", cd[pos + 10:pos + 12])
        csize, usize = struct.unpack("<II", cd[pos + 20:pos + 28])
        nlen, elen, clen = struct.unpack("<HHH", cd[pos + 28:pos + 34])
        local_off, = struct.unpack("<I", cd[pos + 42:pos + 46])
        name = cd[pos + 46:pos + 46 + nlen].decode()
        entries[name] = (method, csize, usize, local_off)
        pos += 46 + nlen + elen + clen
    print({k: v[2] for k, v in entries.items()})
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name in ("TOKENIZER_MODEL", "METADATA"):
            if name not in entries:
                continue
            method, csize, usize, off = entries[name]
            hdr, _ = ranged(url, off, off + 29)
            nlen, elen = struct.unpack("<HH", hdr[26:30])
            data_start = off + 30 + nlen + elen
            data, _ = ranged(url, data_start, data_start + csize - 1)
            if method == 8:
                import zlib
                data = zlib.decompress(data, -15)
            elif method != 0:
                raise SystemExit(f"unsupported compression {method}")
            assert len(data) == usize
            z.writestr(name, data)
    with open(out, "wb") as f:
        f.write(buf.getvalue())
    print("wrote", out, "totalArtifactBytes", total)


if __name__ == "__main__":
    main()
