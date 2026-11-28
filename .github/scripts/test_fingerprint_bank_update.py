import json
from pathlib import Path
import struct
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from scripts.update_fingerprint_bank import (
    build_metadata,
    decode_f32_reference,
    model_family,
    parse_detector,
)


def detector_fixture(model_ids, response_counts):
    header = {
        "schema": "shared-detector-v2",
        "bank_built_at": "2026-11-25T04:42:35.713Z",
        "model_ids": model_ids,
        "response_counts": {"$f32": 0, "n": len(response_counts)},
    }
    header_bytes = json.dumps(header, separators=(",", ":")).encode("utf-8")
    values = b"".join(struct.pack("<f", value) for value in response_counts)
    float_count = len(response_counts)
    planes = b"".join(values[offset::4] for offset in range(4))
    payload = struct.pack("<I", len(header_bytes)) + header_bytes
    payload += b"\0" * ((-len(payload)) % 4)
    return payload + planes


class FingerprintBankUpdateTests(unittest.TestCase):
    def test_decodes_upstream_float_plane_and_builds_metadata(self):
        binary = detector_fixture(["gpt-6.2-sol", "gemini-4-carbon"], [36, 48])
        header, start, float_count = parse_detector(binary)
        self.assertEqual([36.0, 48.0], decode_f32_reference(binary, start, float_count, header["response_counts"]))
        metadata = build_metadata(binary, header, start, float_count)
        self.assertEqual(["gpt-6.2-sol", "gemini-4-carbon"], [model["id"] for model in metadata["models"]])
        self.assertEqual([36, 48], [model["response_count"] for model in metadata["models"]])
        self.assertEqual(["GPT", "Gemini"], [model["family_name"] for model in metadata["models"]])

    def test_preserves_existing_display_metadata(self):
        binary = detector_fixture(["gpt-6-astra"], [72])
        header, start, float_count = parse_detector(binary)
        existing = {"models": [{"id": "gpt-6-astra", "display_name": "GPT Astra", "family": "gpt", "family_name": "GPT"}]}
        metadata = build_metadata(binary, header, start, float_count, existing)
        self.assertEqual("GPT Astra", metadata["models"][0]["display_name"])

    def test_unknown_family_fails_closed(self):
        with self.assertRaises(Exception):
            model_family("new-lab-1")


if __name__ == "__main__":
    unittest.main()
