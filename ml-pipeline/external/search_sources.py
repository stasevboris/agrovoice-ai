"""Search Kaggle and Hugging Face for leaf-disease datasets of the 15 crops.

Writes ml-pipeline/.cache/external/search/candidates.json: one entry per dataset with
the crops whose queries found it, licence, size and popularity. The shortlist itself is
chosen by hand and documented in docs/data/SOURCES.md.
"""

import json
from pathlib import Path

from huggingface_hub import HfApi
from kaggle.api.kaggle_api_extended import KaggleApi

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "ml-pipeline" / ".cache" / "external" / "search" / "candidates.json"

QUERIES = {
    "apple": ["apple leaf disease", "apple scab", "apple rust leaf", "plant pathology apple"],
    "blueberry": ["blueberry leaf", "blueberry disease"],
    "cherry": ["cherry leaf disease", "cherry powdery mildew"],
    "corn": ["corn leaf disease", "maize leaf disease", "northern leaf blight", "maize field disease"],
    "cucumber": ["cucumber leaf disease", "cucumber downy mildew", "cucumber disease detection"],
    "grape": ["grape leaf disease", "grapevine disease", "grape esca"],
    "orange": ["citrus leaf disease", "citrus greening", "orange leaf disease"],
    "peach": ["peach leaf disease", "peach bacterial spot"],
    "pepper": ["pepper leaf disease", "bell pepper disease", "chili leaf disease"],
    "potato": ["potato leaf disease", "potato late blight", "potato disease field"],
    "raspberry": ["raspberry leaf", "raspberry disease"],
    "soybean": ["soybean leaf disease", "soybean leaf"],
    "squash": ["squash powdery mildew", "pumpkin leaf disease", "zucchini leaf disease"],
    "strawberry": ["strawberry leaf disease", "strawberry disease detection"],
    "tomato": ["tomato leaf disease", "tomato disease field", "tomato leaf miner",
               "tomato nutrient deficiency", "tomato spider mite"],
}


def search_kaggle(api, crop, query, found):
    for page in (1, 2):
        try:
            datasets = api.dataset_list(search=query, page=page)
        except Exception as error:
            print(f"kaggle {query!r}: {error}")
            return
        for dataset in datasets:
            key = f"kaggle:{dataset.ref}"
            entry = found.setdefault(key, {
                "host": "kaggle",
                "ref": dataset.ref,
                "title": dataset.title,
                "license": dataset.license_name,
                "bytes": dataset.total_bytes,
                "downloads": dataset.download_count,
                "votes": dataset.vote_count,
                "updated": str(dataset.last_updated),
                "crops": [],
            })
            if crop not in entry["crops"]:
                entry["crops"].append(crop)


def search_hf(api, crop, query, found):
    try:
        datasets = list(api.list_datasets(search=query, limit=40, full=True))
    except Exception as error:
        print(f"hf {query!r}: {error}")
        return
    for dataset in datasets:
        key = f"hf:{dataset.id}"
        tags = dataset.tags or []
        licenses = [tag.split(":", 1)[1] for tag in tags if tag.startswith("license:")]
        entry = found.setdefault(key, {
            "host": "huggingface",
            "ref": dataset.id,
            "title": dataset.id,
            "license": ",".join(licenses),
            "bytes": None,
            "downloads": dataset.downloads,
            "votes": dataset.likes,
            "updated": str(dataset.last_modified),
            "crops": [],
        })
        if crop not in entry["crops"]:
            entry["crops"].append(crop)


def main():
    kaggle = KaggleApi()
    kaggle.authenticate()
    hf = HfApi()
    found = {}
    for crop, queries in QUERIES.items():
        for query in queries:
            search_kaggle(kaggle, crop, query, found)
            search_hf(hf, crop, query, found)
        print(f"{crop}: total candidates so far {len(found)}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(found, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"saved {len(found)} candidates -> {OUT}")


if __name__ == "__main__":
    main()
