"""Extend previous search without mistaking mirrored repositories for new data."""

import csv
import json
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "ml-pipeline/.cache/discovery_20261019"
QUERIES = ["plant disease", "leaf disease", "PlantWild", "PlantSeg", "DeepWeeds",
           "cassava", "wheat disease", "rice disease", "russian speech",
           "russian intent", "russian function calling", "agriculture", "golos", "fleurs"]


def search(query):
    url = "https://huggingface.co/api/datasets?" + urllib.parse.urlencode(
        {"search": query, "limit": 50, "full": "true"})
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return {"query": query, "url": url, "results": json.load(response)}
    except Exception as error:
        return {"query": query, "url": url, "error": str(error), "results": []}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    previous = ROOT / "ml-pipeline/.cache/external/search/candidates.json"
    records = {}
    for row in json.loads(previous.read_text(encoding="utf-8")).values():
        host = row["host"]
        prefix = "https://www.kaggle.com/datasets/" if host == "kaggle" else "https://huggingface.co/datasets/"
        url = prefix + row["ref"]
        records[url] = {"url": url, "title": row["title"], "host": host,
                        "license_metadata": row.get("license", ""),
                        "discovered": "2026-10-17", "review": "previous_search_candidate"}
    with ThreadPoolExecutor(max_workers=4) as pool:
        searches = list(pool.map(search, QUERIES))
    for batch in searches:
        for row in batch["results"]:
            url = "https://huggingface.co/datasets/" + row["id"]
            records[url] = {"url": url, "title": row["id"], "host": "huggingface",
                            "license_metadata": ",".join(t[8:] for t in row.get("tags", []) if t.startswith("license:")),
                            "discovered": "2026-10-19", "review": "metadata_only_not_approved"}
    agml_url = "https://raw.githubusercontent.com/Project-AgML/AgML/main/agml/_assets/public_datasources.json"
    with urllib.request.urlopen(agml_url, timeout=30) as response:
        agml = json.load(response)
    (OUT / "agml_sources.json").write_text(json.dumps(agml, ensure_ascii=False, indent=2), encoding="utf-8")
    for name, row in agml.items():
        url = row["docs_url"]
        if not url.startswith("https://"):
            continue
        records.setdefault(url, {"url": url, "title": name, "host": "AgML_reference",
                                 "license_metadata": "", "discovered": "2026-10-19",
                                 "review": "catalogue_reference_not_approved"})
    with (OUT / "candidates.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(next(iter(records.values()))))
        writer.writeheader()
        writer.writerows(records[key] for key in sorted(records))
    (OUT / "search_responses.json").write_text(json.dumps(searches, ensure_ascii=False, indent=2), encoding="utf-8")
    report = {"checked_on": "2026-10-19", "previous_candidate_references": len(json.loads(previous.read_text(encoding="utf-8"))),
              "search_queries": len(QUERIES), "candidate_references": len(records),
              "search_errors": [{"query": b["query"], "error": b["error"]} for b in searches if "error" in b],
              "limitations": "URLs are not independent datasets. Search metadata is not provenance/license verification. Results limited per query; not exhaustive market coverage."}
    (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
