"""Consistent PostgreSQL + BYTEA backup; restore only into a newly created DB.

Run from server/: python -m app.backup --help. Backup directories contain private
photos and password hashes; keep them in private local storage.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import uuid

from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import DateTime, Uuid, create_engine, select, text

from app.db import Base, engine
from app import models  # noqa: F401


def fingerprint(path):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"bytes": path.stat().st_size, "sha256": digest}


def safe_path(root, name):
    if not isinstance(name, str) or not re.fullmatch(r"[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)?", name):
        raise ValueError("Invalid backup path")
    path = root / name
    if any(part in (".", "..") for part in Path(name).parts) or path.is_symlink():
        raise ValueError("Invalid backup path")
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Invalid backup path")
    return path


def write_snapshot(connection, directory):
    """Caller provides one REPEATABLE READ transaction (or a test transaction)."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "photos").mkdir()
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    config.set_main_option("script_location", str(Path(__file__).resolve().parents[1] / "migrations"))
    version = connection.scalar(text("SELECT version_num FROM alembic_version"))
    if version != ScriptDirectory.from_config(config).get_current_head():
        raise ValueError("Migrate source database before backup")
    manifest = {"schema_version": 1, "database_revision": version,
        "created_at": datetime.now(timezone.utc).isoformat(), "tables": {}, "photos": []}
    for table in Base.metadata.sorted_tables:
        name = f"{table.name}.jsonl"
        count = 0
        with (directory / name).open("w", encoding="utf-8", newline="\n") as stream:
            query = select(table).order_by(*table.primary_key.columns).execution_options(yield_per=1)
            rows = connection.execute(query).mappings()
            for row in rows:
                record = dict(row)
                if table.name == "photos":
                    content = record.pop("content")
                    record["content_file"] = None
                    if content is not None:
                        content = bytes(content)
                        if len(content) != record["size"] or hashlib.sha256(content).hexdigest() != record["sha256"]:
                            raise ValueError("Source photo size/hash mismatch")
                        photo_name = f'photos/{record["owner_id"]}_{record["client_id"]}.bin'
                        (directory / photo_name).write_bytes(content)
                        record["content_file"] = photo_name
                        manifest["photos"].append({"file": photo_name, **fingerprint(directory / photo_name)})
                stream.write(json.dumps(record, default=str, ensure_ascii=False, allow_nan=False) + "\n")
                count += 1
        manifest["tables"][table.name] = {"file": name, "rows": count, **fingerprint(directory / name)}
    # A missing manifest identifies an incomplete backup; never reuse its directory.
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    verify_backup(directory)
    return manifest


def verify_backup(directory):
    directory = Path(directory)
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or set(manifest.get("tables", {})) != set(Base.metadata.tables):
        raise ValueError("Unsupported backup schema")
    files = list(manifest["tables"].values()) + manifest["photos"]
    if len({entry["file"] for entry in files}) != len(files):
        raise ValueError("Duplicate backup path")
    for entry in files:
        path = safe_path(directory, entry["file"])
        if fingerprint(path) != {key: entry[key] for key in ("bytes", "sha256")}:
            raise ValueError("Backup size/hash mismatch")
    photo_files = {entry["file"]: entry for entry in manifest["photos"]}
    referenced = set()
    for name, entry in manifest["tables"].items():
        count = 0
        with safe_path(directory, entry["file"]).open(encoding="utf-8") as stream:
            for line in stream:
                record = json.loads(line)
                count += 1
                if name == "photos" and record["content_file"] is not None:
                    photo = photo_files.get(record["content_file"])
                    if photo is None or photo["bytes"] != record["size"] or photo["sha256"] != record["sha256"]:
                        raise ValueError("Photo manifest mismatch")
                    referenced.add(photo["file"])
        if count != entry["rows"]:
            raise ValueError("Backup row count mismatch")
    if referenced != set(photo_files):
        raise ValueError("Unreferenced backup photo")
    return manifest


def validate_restore_name(name):
    if not re.fullmatch(r"agrovoice_restore_[a-z0-9_]{1,40}", name):
        raise ValueError("Restore requires a new agrovoice_restore_* database")


def restore_backup(directory, target_name, output_directory):
    validate_restore_name(target_name)
    if target_name == engine.url.database:
        raise ValueError("Source database cannot be a restore target")
    manifest = verify_backup(directory)
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    config.set_main_option("script_location", str(Path(__file__).resolve().parents[1] / "migrations"))
    if manifest["database_revision"] != ScriptDirectory.from_config(config).get_current_head():
        raise ValueError("Backup revision differs from application")
    output_directory = Path(output_directory)
    # Copy and verify before creating a DB. The private copy is the restore input.
    output_directory.mkdir(parents=True, exist_ok=False)
    for entry in list(manifest["tables"].values()) + manifest["photos"]:
        target_file = safe_path(output_directory, entry["file"])
        target_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(safe_path(Path(directory), entry["file"]), target_file)
    (output_directory / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    manifest = verify_backup(output_directory)
    admin = create_engine(engine.url.set(database="postgres"), isolation_level="AUTOCOMMIT")
    try:
        with admin.connect() as connection:
            # PostgreSQL rejects existing names. No DROP, CLEAN or overwrite path.
            connection.execute(text(f'CREATE DATABASE "{target_name}"'))
    finally:
        admin.dispose()
    target = create_engine(engine.url.set(database=target_name))
    try:
        with target.begin() as connection:
            config.attributes["connection"] = connection
            command.upgrade(config, "head")
            for table in Base.metadata.sorted_tables:
                entry = manifest["tables"][table.name]
                with safe_path(output_directory, entry["file"]).open(encoding="utf-8") as stream:
                    for line in stream:
                        record = json.loads(line)
                        if table.name == "photos":
                            name = record.pop("content_file")
                            record["content"] = None if name is None else safe_path(output_directory, name).read_bytes()
                        for column in table.columns:
                            value = record[column.name]
                            if value is not None and isinstance(column.type, Uuid):
                                record[column.name] = uuid.UUID(value)
                            elif value is not None and isinstance(column.type, DateTime):
                                record[column.name] = datetime.fromisoformat(value)
                        connection.execute(table.insert().values(**record))
        # Re-export restored DB and compare every table's serialized contents.
        with target.connect().execution_options(isolation_level="REPEATABLE READ") as connection:
            with connection.begin():
                restored = write_snapshot(connection, output_directory / "verified_snapshot")
        if restored["tables"] != manifest["tables"] or restored["photos"] != manifest["photos"]:
            raise ValueError("Restored database content mismatch")
        return {"database": target_name, "verified": True,
                "rows": {name: entry["rows"] for name, entry in manifest["tables"].items()},
                "photos": len(manifest["photos"])}
    finally:
        target.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    backup = sub.add_parser("create")
    backup.add_argument("directory", type=Path)
    verify = sub.add_parser("verify")
    verify.add_argument("directory", type=Path)
    restore = sub.add_parser("restore")
    restore.add_argument("directory", type=Path)
    restore.add_argument("--database", required=True)
    restore.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "create":
        with engine.connect().execution_options(isolation_level="REPEATABLE READ") as connection:
            with connection.begin():
                connection.execute(text("SET TRANSACTION READ ONLY"))
                result = write_snapshot(connection, args.directory)
        result = {"verified": True, "rows": {name: entry["rows"] for name, entry in result["tables"].items()},
                  "photos": len(result["photos"])}
    elif args.action == "verify":
        verify_backup(args.directory)
        result = {"verified": True}
    else:
        result = restore_backup(args.directory, args.database, args.output)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
