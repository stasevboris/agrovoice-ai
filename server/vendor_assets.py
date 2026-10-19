"""Fetch pinned browser libraries; retain source URLs, licenses and hashes."""
import hashlib
import json
from pathlib import Path
import urllib.request

ASSETS = {
    "htmx.min.js": "https://unpkg.com/htmx.org@2.0.11/dist/htmx.min.js",
    "htmx-LICENSE.txt": "https://unpkg.com/htmx.org@2.0.11/LICENSE",
    "leaflet.js": "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js",
    "leaflet.css": "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css",
    "leaflet-LICENSE.txt": "https://unpkg.com/leaflet@1.9.4/LICENSE",
}


def main():
    out = Path(__file__).parent / "static/vendor"
    out.mkdir(parents=True, exist_ok=True)
    report = []
    for name, url in ASSETS.items():
        target = out / name
        if not target.exists():
            with urllib.request.urlopen(url, timeout=30) as response:
                target.write_bytes(response.read())
        data = target.read_bytes()
        report.append({"file": name, "url": url, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    (out / "sources.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
