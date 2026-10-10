"""Offline diagnostic for the MediaPipe .task tokenizer used by MINI local drafting.

The stock sentencepiece runtime refuses this tokenizer ("piece must not include
null character"). The script analyses the proto directly, then loads a copy in
which only that single NUL piece is renamed, so encode/decode behaviour can be
observed without changing any other piece. Only synthetic strings are used.

Usage:  python inspect_task_tokenizer.py <path-to-.task> [out.json]
Needs:  pip install sentencepiece protobuf
"""
import json
import sys
import zipfile
from collections import Counter

import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2 as pb

SAMPLES = {
    "english": "Can we meet tomorrow at 10?",
    "price": "The quoted total is \u20b9500 including delivery.",
    "hindi": "\u0915\u0932 \u092c\u093e\u0924 \u0915\u0930 \u0938\u0915\u0924\u0947 \u0939\u0948\u0902?",
    "telugu": "\u0c30\u0c47\u0c2a\u0c41 \u0c2e\u0c3e\u0c1f\u0c4d\u0c32\u0c3e\u0c21\u0c17\u0c32\u0c2e\u0c3e?",
}


def gpt2_bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(0xA1, 0xAC + 1)) + list(range(0xAE, 0xFF + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return {b: chr(c) for b, c in zip(bs, cs)}


BYTE_TO_CHAR = gpt2_bytes_to_unicode()
CHAR_TO_BYTE = {v: k for k, v in BYTE_TO_CHAR.items()}


def is_byte_form_fragment(piece):
    """True when a piece is still GPT-2 byte-encoded and is not valid UTF-8 on its own."""
    if not piece or not all(c in CHAR_TO_BYTE for c in piece):
        return False
    if not any(ord(c) >= 0xC0 for c in piece):
        return False
    try:
        bytes(CHAR_TO_BYTE[c] for c in piece).decode("utf-8")
        return False
    except UnicodeDecodeError:
        return True


def has_artifacts(text):
    if "\u0120" in text or "\ufffd" in text:
        return True
    return any("\u00c0" <= a <= "\u00ff" and "\u0080" <= b <= "\u00bf" for a, b in zip(text, text[1:]))


def in_range(s, lo, hi):
    return any(lo <= c <= hi for c in s)


def main():
    path = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else None
    raw = zipfile.ZipFile(path).read("TOKENIZER_MODEL")
    proto = pb.ModelProto()
    proto.ParseFromString(raw)
    pieces = [p.piece for p in proto.pieces]
    report = {
        "tokenizerBytes": len(raw),
        "vocabSize": len(pieces),
        "modelType": pb.TrainerSpec.ModelType.Name(proto.trainer_spec.model_type),
        "byteFallback": proto.trainer_spec.byte_fallback,
        "pieceTypes": dict(Counter(pb.ModelProto.SentencePiece.Type.Name(p.type) for p in proto.pieces)),
        "piecesWithNulChar": sum("\x00" in p for p in pieces),
        "piecesWithReplacementCharU+FFFD": sum("\ufffd" in p for p in pieces),
        "piecesStillInGpt2ByteFormInvalidUtf8": sum(is_byte_form_fragment(p) for p in pieces),
        "piecesWithGpt2SpaceMarkerU+0120": sum("\u0120" in p for p in pieces),
        "piecesWithRealDevanagari": sum(in_range(p, "\u0900", "\u097f") for p in pieces),
        "piecesWithRealTelugu": sum(in_range(p, "\u0c00", "\u0c7f") for p in pieces),
        "exampleByteFormPieces": [p for p in pieces if is_byte_form_fragment(p)][:6],
        "exampleReplacementPieces": [p for p in pieces if "\ufffd" in p][:6],
        "samples": {},
    }

    patched = pb.ModelProto()
    patched.CopyFrom(proto)
    for p in patched.pieces:
        if "\x00" in p.piece:
            p.piece = "<mini-nul-placeholder>"
    sp = spm.SentencePieceProcessor(model_proto=patched.SerializeToString())
    unknown_type = pb.ModelProto.SentencePiece.Type.Value("UNKNOWN")
    unk_ids = {i for i, p in enumerate(proto.pieces) if p.type == unknown_type} | {sp.unk_id()}
    report["unknownPiece"] = [proto.pieces[i].piece for i in sorted(unk_ids)]
    for name, text in SAMPLES.items():
        ids = sp.encode(text)
        decoded = sp.decode(ids)
        report["samples"][name] = {
            "tokens": len(ids),
            "unkTokens": sum(1 for i in ids if i in unk_ids),
            "roundTripExact": decoded == text,
            "decoded": decoded,
            "decodedHasByteArtifacts": has_artifacts(decoded),
            "pieces": [sp.id_to_piece(i) for i in ids][:16],
        }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
