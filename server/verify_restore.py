"""Compare a restored isolated database with its backup, then exercise its API.

No passwords or tokens are written to the report. Run using the same environment
as app.backup; --database must identify a newly restored agrovoice_restore_* DB.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

from sqlalchemy.engine import make_url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--backup", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    # Configure the isolated DB before app.db is imported and creates its engine.
    source = make_url(os.environ["DATABASE_URL"])
    if not args.database.startswith("agrovoice_restore_") or args.database == source.database:
        raise ValueError("Only an isolated restored database may be verified")
    os.environ["DATABASE_URL"] = source.set(database=args.database).render_as_string(hide_password=False)

    from fastapi.testclient import TestClient
    from sqlalchemy import select
    from app.backup import validate_restore_name, verify_backup, write_snapshot
    from app.db import engine, Session
    from app.main import app
    from app.models import Photo, User
    from app.security import issue_token

    validate_restore_name(args.database)
    original = verify_backup(args.backup)
    args.out.mkdir(parents=True, exist_ok=False)
    with engine.connect().execution_options(isolation_level="REPEATABLE READ") as connection:
        with connection.begin():
            restored = write_snapshot(connection, args.out / "snapshot")
    assert restored["tables"] == original["tables"], "Restored table contents differ"
    assert restored["photos"] == original["photos"], "Restored photo contents differ"
    checked_photos = 0
    checked_owners = 0
    with Session() as database, TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/sync/inspections").status_code == 401
        for owner in database.scalars(select(User).where(User.active.is_(True))):
            auth = {"Authorization": f"Bearer {issue_token(owner.id)}"}
            assert client.get("/me", headers=auth).status_code == 200
            assert client.get("/sync/inspections", headers=auth).status_code == 200
            checked_owners += 1
            photos = select(Photo.client_id, Photo.sha256, Photo.size,
                            Photo.content.is_not(None).label("uploaded")).where(Photo.owner_id == owner.id)
            for photo in database.execute(photos):
                reply = client.get(f"/photos/{photo.client_id}", headers=auth)
                if not photo.uploaded:
                    assert reply.status_code == 404
                else:
                    assert reply.status_code == 200
                    assert hashlib.sha256(reply.content).hexdigest() == photo.sha256
                    assert len(reply.content) == photo.size
                    checked_photos += 1
    report = {"database": args.database, "table_contents_match": True, "photos_match": True,
              "authenticated_owners": checked_owners, "downloaded_originals": checked_photos,
              "rows": {name: entry["rows"] for name, entry in restored["tables"].items()}}
    (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
