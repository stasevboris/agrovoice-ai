import argparse
from getpass import getpass

from sqlalchemy import select

from app.db import Session
from app.models import User
from app.security import passwords


def main():
    parser = argparse.ArgumentParser(description="Create a local AgroVoice user")
    parser.add_argument("username")
    args = parser.parse_args()
    if not 1 <= len(args.username) <= 80:
        raise SystemExit("Username must contain 1..80 characters")
    password = getpass("Password (at least 12 characters): ")
    if len(password) < 12:
        raise SystemExit("Password is too short")
    with Session.begin() as database:
        if database.scalar(select(User).where(User.username == args.username)) is not None:
            raise SystemExit("User already exists; nothing changed")
        database.add(User(username=args.username, password_hash=passwords.hash(password)))
    print("User created")


if __name__ == "__main__":
    main()
