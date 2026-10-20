"""Fetch the files the APK needs but git does not store: vision, Vosk and built-in Russian TTS.

Standard library only, so it works in a fresh clone:
  python android-app/fetch_assets.py              # check and fetch everything that is missing or corrupted
  python android-app/fetch_assets.py --check      # only report
  python android-app/fetch_assets.py --local-dir E:\\models   # copy models from a local folder instead of GitHub
  python android-app/fetch_assets.py --tts-only    # Denis voice and pinned sherpa Android runtime

Models: every file listed in app/src/main/assets/models/manifest.json is verified by SHA-256.
Missing ones are downloaded from the GitHub Release named in model_release.json (only models published
there; experimental models stay local). Voice: the official archive from alphacephei.com, verified by the
archive SHA-256 and by every file in app/src/main/assets/voice/manifest.json.
TTS uses original upstream archives from app/src/main/assets/tts/manifest.json,
also verified by SHA-256. --local-dir may contain the .aar and .tar.bz2 originals.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

APP = Path(__file__).resolve().parent
ASSETS = APP/"app/src/main/assets"
MODEL_MANIFEST = ASSETS/"models/manifest.json"
VOICE_MANIFEST = ASSETS/"voice/manifest.json"
RELEASE_CONFIG = APP/"model_release.json"
CACHE = APP/"build/asset_cache"
TTS_MANIFEST = ASSETS/"tts/manifest.json"


def fetch_tts(check_only: bool, local_dir: Path | None) -> list[str]:
    """Install upstream Denis and sherpa files; never publish/repackage model weights."""
    manifest = json.loads(TTS_MANIFEST.read_text(encoding="utf-8"))
    root = ASSETS / "tts" / manifest["model"]
    runtime = manifest["runtime"]
    runtime_target = APP / "app/libs" / runtime["file"]

    def valid(path, expected):
        return path.is_file() and sha256(path) == expected

    missing = [item for item in manifest["files"]
               if not valid(root / item["path"], item["sha256"])]
    runtime_missing = not valid(runtime_target, runtime["sha256"])
    if not missing and not runtime_missing:
        print(f"ok      TTS {manifest['model']} + {runtime['file']}")
        return []
    if check_only:
        return ["tts: missing or corrupt voice/runtime files"]

    def source_file(specification):
        source = local_dir / specification["file"] if local_dir else None
        if source is None or not source.is_file():
            source = CACHE / specification["file"]
            if not valid(source, specification["sha256"]):
                download(specification["source"], source)
        if not valid(source, specification["sha256"]):
            raise ValueError(f"SHA-256 mismatch: {source.name}")
        return source

    try:
        if runtime_missing:
            source = source_file(runtime)
            runtime_target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, runtime_target)
        if missing:
            archive = source_file(manifest["archive"])
            # Read only manifest-listed regular files. No extractall or archive links.
            pending = {}
            for item in missing:
                relative = PurePosixPath(item["path"])
                if relative.is_absolute() or ".." in relative.parts:
                    raise ValueError("invalid TTS manifest path")
                pending[f"{manifest['model']}/{relative}"] = item
            with tarfile.open(archive, "r|bz2") as bundle:
                for member in bundle:
                    item = pending.get(member.name)
                    if item is None:
                        continue
                    relative = PurePosixPath(item["path"])
                    if not member.isfile():
                        raise ValueError(f"not a regular TTS file: {relative}")
                    with bundle.extractfile(member) as stream:
                        data = stream.read()
                    if hashlib.sha256(data).hexdigest() != item["sha256"]:
                        raise ValueError(f"SHA-256 mismatch: {relative}")
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    temporary = target.with_suffix(target.suffix + ".part")
                    temporary.write_bytes(data)
                    temporary.replace(target)
                    del pending[member.name]
            if pending:
                raise ValueError("TTS archive is missing manifest-listed files")
    except (OSError, ValueError, KeyError, tarfile.TarError) as error:
        return [f"tts: {error}"]
    print(f"ready   TTS {manifest['model']}")
    return []


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path):
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix+".part")
    print(f"  скачиваю {url}")
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as stream:
        shutil.copyfileobj(response, stream, 1 << 20)
    temporary.replace(target)


def model_files(manifest: dict):
    """(asset path relative to assets/, sha256) for every detector and classifier graph, without duplicates."""
    files = {}
    for model in manifest["models"]:
        files[model["asset"]] = model["sha256"]
        classifier = model.get("classifier")
        if classifier:
            files[classifier["asset"]] = classifier["sha256"]
    return sorted(files.items())


def release_url(config: dict, asset: str) -> str | None:
    name = PurePosixPath(asset).name
    if name not in config.get("files", []):
        return None
    return f"https://github.com/{config['repository']}/releases/download/{config['tag']}/{name}"


def fetch_models(check_only: bool, local_dir: Path | None) -> list[str]:
    manifest = json.loads(MODEL_MANIFEST.read_text(encoding="utf-8"))
    config = json.loads(RELEASE_CONFIG.read_text(encoding="utf-8")) if RELEASE_CONFIG.exists() else {}
    problems = []
    for asset, expected in model_files(manifest):
        target = ASSETS/asset
        if target.is_file() and sha256(target) == expected:
            print(f"ok      {asset}")
            continue
        state = "повреждён" if target.exists() else "нет"
        if check_only:
            problems.append(f"{asset}: {state}")
            print(f"{state:7} {asset}")
            continue
        source = local_dir/PurePosixPath(asset).name if local_dir else None
        url = release_url(config, asset)
        try:
            if source and source.is_file():
                print(f"  копирую {source}")
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            elif url:
                download(url, target)
            else:
                problems.append(f"{asset}: нет в релизе {config.get('tag', '?')} и не найден локально")
                print(f"нет     {asset} (не опубликован; положите вручную или укажите --local-dir)")
                continue
        except OSError as error:
            problems.append(f"{asset}: {error}")
            print(f"ошибка  {asset}: {error}")
            continue
        if sha256(target) != expected:
            target.unlink()
            problems.append(f"{asset}: SHA-256 не совпал, файл удалён")
            print(f"ошибка  {asset}: SHA-256 не совпал")
        else:
            print(f"готово  {asset}")
    return problems


def voice_ready(manifest: dict) -> list[str]:
    root = ASSETS/"voice/ru"
    return [item["path"] for item in manifest["files"]
            if not (root/item["path"]).is_file() or sha256(root/item["path"]) != item["sha256"]]


def fetch_voice(check_only: bool) -> list[str]:
    manifest = json.loads(VOICE_MANIFEST.read_text(encoding="utf-8"))
    bad = voice_ready(manifest)
    if not bad:
        print(f"ok      голосовая модель {manifest['model']} ({len(manifest['files'])} файлов)")
        return []
    if check_only:
        print(f"нет     голосовая модель: {len(bad)} файлов отсутствуют или повреждены")
        return [f"voice: {len(bad)} файлов"]
    archive = CACHE/f"{manifest['model']}.zip"
    if not archive.is_file() or sha256(archive) != manifest["archive_sha256"]:
        download(manifest["source"], archive)
    if sha256(archive) != manifest["archive_sha256"]:
        return [f"voice: SHA-256 архива {archive.name} не совпал с манифестом"]
    root = ASSETS/"voice/ru"
    with zipfile.ZipFile(archive) as bundle, tempfile.TemporaryDirectory() as folder:
        bundle.extractall(folder)
        unpacked = Path(folder)/manifest["model"]
        for item in manifest["files"]:
            target = root/item["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(unpacked/item["path"], target)
    bad = voice_ready(manifest)
    if bad:
        return [f"voice: после распаковки не совпали {len(bad)} файлов"]
    print(f"готово  голосовая модель {manifest['model']}")
    return []


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="only report what is missing")
    parser.add_argument("--local-dir", type=Path, help="folder with .onnx files to copy instead of downloading")
    parser.add_argument("--models-only", action="store_true")
    parser.add_argument("--voice-only", action="store_true")
    parser.add_argument("--tts-only", action="store_true", help="only built-in Russian TTS and its runtime")
    args = parser.parse_args(argv)
    problems = []
    if not args.voice_only and not args.tts_only:
        problems += fetch_models(args.check, args.local_dir)
    if not args.models_only and not args.tts_only:
        problems += fetch_voice(args.check)
    if not args.models_only and not args.voice_only:
        problems += fetch_tts(args.check, args.local_dir)
    if problems:
        print("\nНе готово:\n- "+"\n- ".join(problems))
        return 1
    print("\nВсе ассеты на месте.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
