#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path
from typing import Any


DEFAULT_EXCLUDED_CODES = (
    "AM",
    "ARM",
    "AZ",
    "AZE",
    "BY",
    "BLR",
    "KG",
    "KGZ",
    "KZ",
    "KAZ",
    "MD",
    "MDA",
    "RU",
    "RUS",
    "TJ",
    "TJK",
    "TM",
    "TKM",
    "UZ",
    "UZB",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Build an ANHUD online-only offline-region search catalog from a "
            "world catalog that already contains region names and bounds."
        )
    )
    parser.add_argument(
        "--source",
        required=True,
        help="Path to the source JSON or JSON.gz catalog.",
    )
    parser.add_argument(
        "--out-dir",
        default="tmp/offline_regions_online_search",
        help="Directory for generated files.",
    )
    parser.add_argument(
        "--exclude-codes",
        default=",".join(DEFAULT_EXCLUDED_CODES),
        help="Comma-separated alpha2/alpha3 prefixes to exclude from the output.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source_path = Path(args.source)
    payload = read_json(source_path)
    source_items = payload.get("items")
    if not isinstance(source_items, list):
        raise SystemExit("Source catalog must contain an 'items' list")

    excluded_codes = {
        part.strip().upper()
        for part in args.exclude_codes.split(",")
        if part.strip()
    }
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    regions: list[dict[str, Any]] = []
    for item in source_items:
        region = build_region_entry(item, excluded_codes)
        if region is None:
            continue
        regions.append(region)

    result = {
        "version": 1,
        "source": {
            "kind": "online_only_search",
            "derivedFrom": str(source_path),
        },
        "regions": sorted(
            regions,
            key=lambda entry: (
                entry["name"],
                entry["countryCode"],
                entry["level"],
                entry["id"],
            ),
        ),
    }
    raw_json = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    json_path = out_dir / "offline_regions_online_search.json"
    gz_path = out_dir / "offline_regions_online_search.json.gz"
    json_path.write_text(raw_json, encoding="utf-8")
    with gzip.open(gz_path, "wt", encoding="utf-8", compresslevel=9) as stream:
        stream.write(raw_json)

    print(f"Regions written: {len(regions)}")
    print(f"JSON: {json_path} ({format_bytes(json_path.stat().st_size)})")
    print(f"JSON.gz: {gz_path} ({format_bytes(gz_path.stat().st_size)})")
    return 0


def build_region_entry(item: dict[str, Any], excluded_codes: set[str]) -> dict[str, Any] | None:
    name = clean_text(item.get("name"))
    level = clean_text(item.get("admLevel"))
    raw_country = clean_text(item.get("country"))
    bounds = item.get("bounds")
    if not name or not level or not raw_country:
        return None
    if not isinstance(bounds, list) or len(bounds) != 4:
        return None
    prefix = raw_country.split("-", 1)[0].strip().upper()
    if prefix in excluded_codes:
        return None
    if len(prefix) not in (2, 3) or not prefix.isalpha():
        return None
    region_id = clean_text(item.get("id")) or build_fallback_id(name, raw_country, level)
    shape_iso = raw_country if "-" in raw_country else None
    return {
        "id": region_id,
        "countryCode": raw_country,
        "level": level,
        "name": name,
        "shapeISO": shape_iso,
        "bbox": [round(float(value), 6) for value in bounds],
    }


def build_fallback_id(name: str, raw_country: str, level: str) -> str:
    slug = "-".join(part for part in (raw_country, level, name) if part)
    return "".join(ch.lower() if ch.isalnum() else "-" for ch in slug)


def clean_text(value: Any) -> str:
    return str(value or "").strip()


def read_json(path: Path) -> dict[str, Any]:
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            return json.load(stream)
    return json.loads(path.read_text(encoding="utf-8"))


def format_bytes(size: int) -> str:
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024.0 or unit == "GiB":
            if unit == "B":
                return f"{int(value)} {unit}"
            return f"{value:.2f} {unit}"
        value /= 1024.0
    return f"{int(size)} B"


if __name__ == "__main__":
    raise SystemExit(main())
