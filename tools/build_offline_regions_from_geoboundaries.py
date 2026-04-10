#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import json
import math
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


GEOBOUNDARIES_API_BASE = "https://www.geoboundaries.org/api/current"
DEFAULT_PRODUCT = "gbOpen"
DEFAULT_LEVELS = ("ADM1", "ADM2")
DEFAULT_CIS_ISO3 = (
    "ARM",
    "AZE",
    "BLR",
    "KAZ",
    "KGZ",
    "MDA",
    "RUS",
    "TJK",
    "TKM",
    "UZB",
)

COUNTRY_LABELS_RU = {
    "ARM": "Армения",
    "AZE": "Азербайджан",
    "BLR": "Беларусь",
    "KAZ": "Казахстан",
    "KGZ": "Кыргызстан",
    "MDA": "Молдова",
    "RUS": "Россия",
    "TJK": "Таджикистан",
    "TKM": "Туркменистан",
    "UZB": "Узбекистан",
}


@dataclass
class BuildStats:
    countries_processed: int = 0
    boundary_sets_processed: int = 0
    regions_written: int = 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fetch geoBoundaries regions, repack them into ANHUD-friendly "
            "catalog/geometry JSON files, and print output sizes."
        )
    )
    parser.add_argument(
        "--iso3",
        default=",".join(DEFAULT_CIS_ISO3),
        help="Comma-separated ISO3 country codes. Default: CIS starter set.",
    )
    parser.add_argument(
        "--levels",
        default=",".join(DEFAULT_LEVELS),
        help="Comma-separated geoBoundaries levels, for example ADM1,ADM2.",
    )
    parser.add_argument(
        "--product",
        default=DEFAULT_PRODUCT,
        help="geoBoundaries product name. Default: gbOpen.",
    )
    parser.add_argument(
        "--out-dir",
        default="tmp/offline_regions_geoboundaries",
        help="Directory for generated files.",
    )
    parser.add_argument(
        "--precision",
        type=int,
        default=5,
        help="Decimal places to keep in coordinates. Default: 5.",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=60,
        help="HTTP timeout in seconds. Default: 60.",
    )
    parser.add_argument(
        "--use-full-geometry",
        action="store_true",
        help="Use full GeoJSON instead of simplifiedGeometryGeoJSON.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    iso3_codes = [part.strip().upper() for part in args.iso3.split(",") if part.strip()]
    levels = [part.strip().upper() for part in args.levels.split(",") if part.strip()]
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    catalog_regions: list[dict[str, Any]] = []
    geometry_regions: list[dict[str, Any]] = []
    stats = BuildStats()

    for iso3 in iso3_codes:
        stats.countries_processed += 1
        for level in levels:
            metadata_url = f"{GEOBOUNDARIES_API_BASE}/{args.product}/{iso3}/{level}/"
            try:
                metadata = fetch_json(metadata_url, timeout=args.timeout)
            except urllib.error.HTTPError as exc:
                print(f"[skip] {iso3} {level}: HTTP {exc.code}", file=sys.stderr)
                continue
            except urllib.error.URLError as exc:
                print(f"[error] {iso3} {level}: {exc}", file=sys.stderr)
                return 1

            geometry_url = (
                metadata.get("gjDownloadURL")
                if args.use_full_geometry
                else metadata.get("simplifiedGeometryGeoJSON") or metadata.get("gjDownloadURL")
            )
            if not geometry_url:
                print(f"[skip] {iso3} {level}: no GeoJSON URL in metadata", file=sys.stderr)
                continue

            geojson = fetch_json(geometry_url, timeout=args.timeout)
            features = geojson.get("features") or []
            if not isinstance(features, list) or not features:
                print(f"[skip] {iso3} {level}: empty feature list", file=sys.stderr)
                continue

            stats.boundary_sets_processed += 1
            country_ru = COUNTRY_LABELS_RU.get(iso3, metadata.get("boundaryName", iso3))
            dataset_id = metadata.get("boundaryID", f"{iso3}-{level}")

            for feature in features:
                region_entry = build_region_entry(
                    feature=feature,
                    iso3=iso3,
                    level=level,
                    country_ru=country_ru,
                    dataset_id=dataset_id,
                    precision=args.precision,
                )
                catalog_regions.append(region_entry["catalog"])
                geometry_regions.append(region_entry["geometry"])
                stats.regions_written += 1

    catalog_payload = {
        "version": 1,
        "generatedAt": now_iso(),
        "source": {
            "provider": "geoBoundaries",
            "product": args.product,
            "levels": levels,
        },
        "regions": sorted(
            catalog_regions,
            key=lambda item: (
                item["countryIso3"],
                item["level"],
                item["name"],
                item["id"],
            ),
        ),
    }
    geometry_payload = {
        "version": 1,
        "generatedAt": now_iso(),
        "source": {
            "provider": "geoBoundaries",
            "product": args.product,
            "levels": levels,
        },
        "regions": sorted(
            geometry_regions,
            key=lambda item: item["id"],
        ),
    }

    catalog_json = json.dumps(catalog_payload, ensure_ascii=False, separators=(",", ":"))
    geometry_json = json.dumps(geometry_payload, ensure_ascii=False, separators=(",", ":"))

    catalog_path = out_dir / "offline_regions_catalog.json"
    geometry_path = out_dir / "offline_regions_geometry.json"
    catalog_gz_path = out_dir / "offline_regions_catalog.json.gz"
    geometry_gz_path = out_dir / "offline_regions_geometry.json.gz"

    catalog_path.write_text(catalog_json, encoding="utf-8")
    geometry_path.write_text(geometry_json, encoding="utf-8")
    write_gzip_text(catalog_gz_path, catalog_json)
    write_gzip_text(geometry_gz_path, geometry_json)

    print(f"Countries processed: {stats.countries_processed}")
    print(f"Boundary sets processed: {stats.boundary_sets_processed}")
    print(f"Regions written: {stats.regions_written}")
    print(f"Catalog JSON: {catalog_path} ({format_bytes(catalog_path.stat().st_size)})")
    print(f"Geometry JSON: {geometry_path} ({format_bytes(geometry_path.stat().st_size)})")
    print(
        "Combined JSON size: "
        f"{format_bytes(catalog_path.stat().st_size + geometry_path.stat().st_size)}"
    )
    print(f"Catalog JSON.gz: {catalog_gz_path} ({format_bytes(catalog_gz_path.stat().st_size)})")
    print(f"Geometry JSON.gz: {geometry_gz_path} ({format_bytes(geometry_gz_path.stat().st_size)})")
    print(
        "Combined JSON.gz size: "
        f"{format_bytes(catalog_gz_path.stat().st_size + geometry_gz_path.stat().st_size)}"
    )
    return 0


def build_region_entry(
    feature: dict[str, Any],
    iso3: str,
    level: str,
    country_ru: str,
    dataset_id: str,
    precision: int,
) -> dict[str, dict[str, Any]]:
    properties = feature.get("properties") or {}
    geometry = feature.get("geometry") or {}
    rounded_geometry = round_geometry(geometry, precision)
    bbox = compute_bbox(rounded_geometry)
    center = bbox_center(bbox)
    shape_id = str(properties.get("shapeID") or "")
    region_id = shape_id if shape_id else f"{dataset_id}-{properties.get('shapeName', 'region')}"
    region_name = str(properties.get("shapeName") or "Unknown")
    shape_iso = clean_optional_string(properties.get("shapeISO"))
    shape_group = clean_optional_string(properties.get("shapeGroup")) or iso3
    shape_type = clean_optional_string(properties.get("shapeType")) or level

    catalog = {
        "id": region_id,
        "countryIso3": iso3,
        "countryRu": country_ru,
        "countryName": shape_group,
        "level": level,
        "name": region_name,
        "shapeISO": shape_iso,
        "shapeType": shape_type,
        "bbox": bbox,
        "center": center,
    }
    geometry_entry = {
        "id": region_id,
        "geometry": rounded_geometry,
    }
    return {
        "catalog": catalog,
        "geometry": geometry_entry,
    }


def fetch_json(url: str, timeout: int) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "ANHUD offline region builder/1.0",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = response.read()
    return json.loads(payload)


def round_geometry(geometry: dict[str, Any], precision: int) -> dict[str, Any]:
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    return {
        "type": geometry_type,
        "coordinates": round_coordinates(coordinates, precision),
    }


def round_coordinates(value: Any, precision: int) -> Any:
    if isinstance(value, list):
        return [round_coordinates(item, precision) for item in value]
    if isinstance(value, float):
        return round(value, precision)
    return value


def compute_bbox(geometry: dict[str, Any]) -> list[float]:
    lon_values: list[float] = []
    lat_values: list[float] = []

    def visit(node: Any) -> None:
        if isinstance(node, list):
            if len(node) >= 2 and all(isinstance(item, (int, float)) for item in node[:2]):
                lon_values.append(float(node[0]))
                lat_values.append(float(node[1]))
                return
            for item in node:
                visit(item)

    visit(geometry.get("coordinates"))
    if not lon_values or not lat_values:
        raise ValueError("Geometry has no coordinates")
    return [
        round(min(lon_values), 5),
        round(min(lat_values), 5),
        round(max(lon_values), 5),
        round(max(lat_values), 5),
    ]


def bbox_center(bbox: list[float]) -> list[float]:
    west, south, east, north = bbox
    return [
        round((west + east) / 2.0, 5),
        round((south + north) / 2.0, 5),
    ]


def clean_optional_string(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.lower() == "nan" or text.lower() == "none":
        return None
    return text


def write_gzip_text(path: Path, content: str) -> None:
    with gzip.open(path, "wt", encoding="utf-8") as handle:
        handle.write(content)


def format_bytes(size: int) -> str:
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.1f} KiB"
    return f"{size / (1024 * 1024):.2f} MiB"


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


if __name__ == "__main__":
    raise SystemExit(main())
