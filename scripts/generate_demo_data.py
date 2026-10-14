"""Create reproducible hourly cumulative meter readings for local demos."""
from __future__ import annotations

import argparse
import csv
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path


def generate(output: Path, site_id: str, days: int, seed: int, duplicate_sample: bool,
             meter_ids: list[str] | None = None, main_meter_id: str = "building-main") -> int:
    rng = random.Random(seed)
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(days=days)
    meters = meter_ids or ["unit-101", "unit-102", "unit-103"]
    if not meters or len(set(meters)) != len(meters) or main_meter_id in meters:
        raise ValueError("Meter IDs must be unique and the main meter must differ from submeter IDs.")
    totals = {meter: 5000.0 + 700 * index for index, meter in enumerate(meters)}
    building_total = 100_000.0
    rows: list[dict[str, str]] = []
    for hour in range(days * 24 + 1):
        timestamp = start + timedelta(hours=hour)
        local_hour = timestamp.hour
        usages: dict[str, float] = {}
        for meter in meters:
            typical = rng.uniform(4, 10) if 6 <= local_hour <= 22 else rng.uniform(0.1, 1.4)
            if meter == meters[-1] and 0 <= local_hour < 5 and hour > days * 24 * 0.45:
                typical += 21.0  # sustained continuous flow for deterministic rule-based investigation
            usages[meter] = round(typical, 3)
        for meter in meters:
            totals[meter] += usages[meter]
            if meter == "unit-102" and hour == 73:
                continue  # intentional missing interval for quality/coverage demonstrations
            rows.append({
                "event_id": f"demo-{meter}-{timestamp.strftime('%Y%m%dT%H%M%SZ')}",
                "site_id": site_id,
                "meter_id": meter,
                "timestamp": timestamp.isoformat().replace("+00:00", "Z"),
                "value": f"{totals[meter]:.3f}",
                "unit": "L",
                "reading_mode": "CUMULATIVE",
                "source_id": "aquatrace-demo-generator-v1",
            })
        building_total += sum(usages.values())
        rows.append({
            "event_id": f"demo-{main_meter_id}-{timestamp.strftime('%Y%m%dT%H%M%SZ')}",
            "site_id": site_id,
            "meter_id": main_meter_id,
            "timestamp": timestamp.isoformat().replace("+00:00", "Z"),
            "value": f"{building_total:.3f}",
            "unit": "L",
            "reading_mode": "CUMULATIVE",
            "source_id": "aquatrace-demo-generator-v1",
        })
    if duplicate_sample and rows:
        rows.append(rows[10].copy())
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return len(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("data-pipeline/sample-data/demo_readings.csv"))
    parser.add_argument("--site-id", default="demo-building")
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--duplicate-sample", action="store_true", help="Append an exact duplicate event to demonstrate deduplication")
    parser.add_argument("--submeter-ids", default="unit-101,unit-102,unit-103", help="Comma-separated registered submeter document IDs")
    parser.add_argument("--main-meter-id", default="building-main", help="Registered main meter document ID")
    args = parser.parse_args()
    if not 1 <= args.days <= 365:
        parser.error("--days must be between 1 and 365")
    meter_ids = [value.strip() for value in args.submeter_ids.split(",") if value.strip()]
    try:
        count = generate(args.output, args.site_id, args.days, args.seed, args.duplicate_sample, meter_ids, args.main_meter_id)
    except ValueError as exception:
        parser.error(str(exception))
    print(f"Wrote {count} rows to {args.output}")


if __name__ == "__main__":
    main()
