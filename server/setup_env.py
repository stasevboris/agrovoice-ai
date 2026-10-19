"""Generate local development secrets once; never replace an existing configuration."""
from pathlib import Path
import secrets

target = Path(__file__).with_name(".env")
if target.exists():
    print("Existing local configuration preserved")
else:
    with target.open("x", encoding="utf-8") as output:
        output.write(f"DB_PASSWORD={secrets.token_hex(24)}\nJWT_SECRET={secrets.token_hex(32)}\n")
    print("Local configuration created in server/.env")
