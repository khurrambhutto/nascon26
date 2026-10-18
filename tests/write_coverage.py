"""Generate COVERAGE.json (Day 1) and COVERAGE_DAY2.json from real pytest-cov results."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
report = json.loads((root / "backend" / "coverage.json").read_text())
DAY1 = {"fixtures": ["fixtures.py"], "scoring": ["scoring.py"], "standings": ["standings.py"], "store": ["store.py", "orders.py"], "roster": ["roster.py"], "rbac": ["auth.py"]}
DAY2 = {"streaming": ["streaming.py"], "costream": ["costream.py"], "qr_guest": ["qr_guest.py"], "moderation_dlq": ["moderation.py"], "observability": ["observability.py"]}


def percentages(areas):
    result = {}
    for area, filenames in areas.items():
        summaries = [entry["summary"] for path, entry in report["files"].items() if Path(path).name in filenames]
        covered = sum(s["covered_lines"] for s in summaries)
        total = sum(s["num_statements"] for s in summaries)
        result[area] = round(100 * covered / total, 2) if total else 0.0
    return result


for name, areas in (("COVERAGE.json", DAY1), ("COVERAGE_DAY2.json", DAY2)):
    result = percentages(areas)
    (root / name).write_text(json.dumps(result, indent=2) + "\n")
    print(name, json.dumps(result))
