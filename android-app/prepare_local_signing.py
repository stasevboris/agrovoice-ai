"""Create a local release identity outside the repository; never print passwords."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path.home() / ".agrovoice/signing")
    args = parser.parse_args()
    directory = args.directory.expanduser().resolve()
    repository = Path(__file__).resolve().parents[1]
    if directory == repository or repository in directory.parents:
        parser.error("Signing credentials must be stored outside the repository")
    directory.mkdir(parents=True, exist_ok=True)
    properties = directory / "release.properties"
    keystore = directory / "release.p12"
    if properties.exists() or keystore.exists():
        parser.error("Signing identity already exists; reuse it without replacing credentials")
    password = secrets.token_urlsafe(36)
    environment = dict(os.environ, AGROVOICE_KEY_PASSWORD=password)
    result = subprocess.run([
        "keytool", "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12",
        "-alias", "agrovoice", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-storepass:env", "AGROVOICE_KEY_PASSWORD", "-keypass:env", "AGROVOICE_KEY_PASSWORD",
        "-dname", "CN=AgroVoice AI Local Release", "-noprompt",
    ], env=environment, capture_output=True)
    if result.returncode:
        print("keytool failed; local signing identity was not prepared")
        return result.returncode
    properties.write_text(
        f"storeFile={keystore.as_posix()}\nstorePassword={password}\n"
        f"keyAlias=agrovoice\nkeyPassword={password}\n", encoding="utf-8")
    if os.name != "nt":
        directory.chmod(0o700)
        properties.chmod(0o600)
        keystore.chmod(0o600)
    print(f"Local signing properties: {properties}")
    print("Back up the identity securely. A new identity cannot update an existing differently signed APK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
