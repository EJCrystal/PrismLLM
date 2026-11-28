#!/usr/bin/env python3
"""Update the embedded detector from the upstream prebuilt fingerprint bank."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import tempfile
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = ROOT / "prism-app/src/main/resources/fingerprint"
DEFAULT_REPOSITORY = "Ikaleio/lm-detector"
DEFAULT_REF = "main"
MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
FAMILY_NAMES = {
    "claude": "Claude",
    "deepseek": "DeepSeek",
    "gemini": "Gemini",
    "glm": "GLM",
    "gpt": "GPT",
    "grok": "Grok",
    "hunyuan": "Hunyuan",
    "kimi": "Kimi",
    "mimo": "MiMo",
    "minimax": "MiniMax",
    "muse": "Muse",
    "openpangu": "openPangu",
    "qwen": "Qwen",
    "seed": "Seed",
    "step": "Step",
    "swe": "SWE",
}


class FingerprintBankError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download and install the upstream prebuilt fingerprint detector."
    )
    parser.add_argument(
        "--source-repo",
        default=DEFAULT_REPOSITORY,
        help=f"GitHub repository containing the detector (default: {DEFAULT_REPOSITORY})",
    )
    parser.add_argument(
        "--ref",
        default=DEFAULT_REF,
        help=f"Git ref or commit to read (default: {DEFAULT_REF})",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="Directory containing shared_detector.bin and models.json",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate and summarize the downloaded bank without changing files",
    )
    return parser.parse_args()


def source_url(repository: str, ref: str) -> str:
    if repository.count("/") != 1 or any(not part for part in repository.split("/")):
        raise FingerprintBankError("source repository must be in owner/name form")
    if not ref or ref.startswith("-"):
        raise FingerprintBankError("source ref must not be empty or start with '-'")
    encoded_ref = quote(ref, safe="/._-")
    return f"https://raw.githubusercontent.com/{repository}/{encoded_ref}/data/shared_detector.bin"


def download(url: str) -> bytes:
    request = Request(url, headers={"User-Agent": "PrismLLM-fingerprint-updater/1"})
    try:
        with urlopen(request, timeout=60) as response:
            declared_length = response.headers.get("Content-Length")
            if declared_length and int(declared_length) > MAX_DOWNLOAD_BYTES:
                raise FingerprintBankError("upstream detector is larger than the safety limit")
            chunks: list[bytes] = []
            size = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_DOWNLOAD_BYTES:
                    raise FingerprintBankError("upstream detector is larger than the safety limit")
                chunks.append(chunk)
            return b"".join(chunks)
    except HTTPError as error:
        raise FingerprintBankError(f"failed to download upstream detector: HTTP {error.code}") from error
    except URLError as error:
        raise FingerprintBankError(f"failed to download upstream detector: {error.reason}") from error


def parse_detector(binary: bytes) -> tuple[dict[str, Any], int, int]:
    if len(binary) < 8:
        raise FingerprintBankError("detector is too small")
    header_length = struct.unpack_from("<I", binary)[0]
    if header_length <= 0 or 4 + header_length > len(binary):
        raise FingerprintBankError("detector header is invalid")
    try:
        header = json.loads(binary[4 : 4 + header_length].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise FingerprintBankError("detector header is not valid JSON") from error
    if not isinstance(header, dict) or header.get("schema") != "shared-detector-v2":
        raise FingerprintBankError("unsupported detector schema")
    start = (4 + header_length + 3) // 4 * 4
    if start > len(binary) or (len(binary) - start) % 4:
        raise FingerprintBankError("detector float section is invalid")
    return header, start, (len(binary) - start) // 4


def decode_f32_reference(
    binary: bytes, start: int, float_count: int, reference: Any
) -> list[float]:
    if isinstance(reference, list):
        values = reference
    elif isinstance(reference, dict):
        offset = reference.get("$f32")
        length = reference.get("n")
        if not isinstance(offset, int) or not isinstance(length, int) or offset < 0 or length < 0:
            raise FingerprintBankError("detector float reference is invalid")
        if offset + length > float_count:
            raise FingerprintBankError("detector float reference is out of range")
        values = []
        for index in range(offset, offset + length):
            bits = 0
            for byte_index in range(4):
                bits |= binary[start + byte_index * float_count + index] << (byte_index * 8)
            values.append(struct.unpack("<f", bits.to_bytes(4, "little"))[0])
    else:
        raise FingerprintBankError("detector float reference is invalid")
    if not all(isinstance(value, (int, float)) and math.isfinite(value) for value in values):
        raise FingerprintBankError("detector contains a non-finite response count")
    return [float(value) for value in values]


def model_family(model_id: str) -> tuple[str, str]:
    prefixes = (
        ("openpangu", "openpangu"),
        ("deepseek", "deepseek"),
        ("minimax", "minimax"),
        ("claude", "claude"),
        ("gemini", "gemini"),
        ("grok", "grok"),
        ("gpt", "gpt"),
        ("glm", "glm"),
        ("qwen", "qwen"),
        ("kimi", "kimi"),
        ("muse", "muse"),
        ("mimo", "mimo"),
        ("step", "step"),
        ("seed", "seed"),
        ("swe", "swe"),
        ("hy4", "hunyuan"),
    )
    family = next((family for prefix, family in prefixes if model_id == prefix or model_id.startswith(prefix)), None)
    if family is None:
        raise FingerprintBankError(f"unknown model family: {model_id}")
    return family, FAMILY_NAMES[family]


def build_metadata(
    binary: bytes,
    header: dict[str, Any],
    start: int,
    float_count: int,
    existing: dict[str, Any] | None = None,
    repository: str = DEFAULT_REPOSITORY,
) -> dict[str, Any]:
    model_ids = header.get("model_ids")
    if not isinstance(model_ids, list) or not model_ids or not all(isinstance(model_id, str) and model_id for model_id in model_ids):
        raise FingerprintBankError("detector model IDs are invalid")
    if len(set(model_ids)) != len(model_ids):
        raise FingerprintBankError("detector contains duplicate model IDs")
    counts = decode_f32_reference(binary, start, float_count, header.get("response_counts"))
    if len(counts) != len(model_ids):
        raise FingerprintBankError("detector response counts do not match model IDs")
    response_counts = []
    for count in counts:
        rounded = round(count)
        if rounded < 1 or abs(count - rounded) > 1e-4:
            raise FingerprintBankError("detector response counts are invalid")
        response_counts.append(rounded)
    previous = {
        model["id"]: model
        for model in (existing or {}).get("models", [])
        if isinstance(model, dict) and isinstance(model.get("id"), str)
    }
    models = []
    for model_id, response_count in zip(model_ids, response_counts):
        family, family_name = model_family(model_id)
        old = previous.get(model_id, {})
        models.append({
            "id": model_id,
            "display_name": old.get("display_name", model_id),
            "family": old.get("family", family),
            "family_name": old.get("family_name", family_name),
            "response_count": response_count,
        })
    built_at = header.get("bank_built_at")
    if not isinstance(built_at, str) or not built_at:
        raise FingerprintBankError("detector build timestamp is missing")
    return {
        "schema": "fingerprint-bank-metadata-v1",
        "source": {
            "project": f"https://github.com/{repository}",
            "license": "MIT",
            "detector_file": "shared_detector.bin",
            "detector_sha256": hashlib.sha256(binary).hexdigest(),
            "bank_built_at": built_at,
            "reference_schema": "robust-number-fingerprint-bank",
        },
        "models": models,
    }


def serialize_metadata(metadata: dict[str, Any]) -> bytes:
    return (json.dumps(metadata, ensure_ascii=False, indent=1) + "\n").encode("utf-8")


def read_existing_metadata(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError) as error:
        raise FingerprintBankError(f"failed to read existing metadata: {path}") from error
    return value if isinstance(value, dict) else {}


def atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = tempfile.NamedTemporaryFile(dir=path.parent, prefix=f".{path.name}.", delete=False)
    temporary_path = Path(temporary.name)
    try:
        with temporary:
            temporary.write(content)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def update(repository: str, ref: str, output_dir: Path, dry_run: bool) -> dict[str, Any]:
    binary = download(source_url(repository, ref))
    header, start, float_count = parse_detector(binary)
    metadata_path = output_dir / "models.json"
    existing = read_existing_metadata(metadata_path)
    metadata = build_metadata(binary, header, start, float_count, existing, repository)
    metadata_bytes = serialize_metadata(metadata)
    binary_path = output_dir / "shared_detector.bin"
    previous_ids = {model.get("id") for model in existing.get("models", []) if isinstance(model, dict)}
    current_ids = {model["id"] for model in metadata["models"]}
    changed = not binary_path.exists() or binary_path.read_bytes() != binary or not metadata_path.exists() or metadata_path.read_bytes() != metadata_bytes
    if changed and not dry_run:
        atomic_write(binary_path, binary)
        atomic_write(metadata_path, metadata_bytes)
    result = {
        "changed": changed,
        "source_repo": repository,
        "source_ref": ref,
        "bank_built_at": header["bank_built_at"],
        "model_count": len(current_ids),
        "added_models": sorted(current_ids - previous_ids),
        "removed_models": sorted(previous_ids - current_ids),
        "detector_sha256": metadata["source"]["detector_sha256"],
    }
    return result


def main() -> int:
    arguments = parse_args()
    try:
        result = update(arguments.source_repo, arguments.ref, arguments.output_dir, arguments.dry_run)
    except FingerprintBankError as error:
        print(f"error: {error}")
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
