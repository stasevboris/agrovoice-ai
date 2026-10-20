from alembic import context

from app.config import settings
from app.db import Base, engine
from app import models  # Register mapped metadata for Alembic.


if context.is_offline_mode():
    context.configure(url=settings().database_url, target_metadata=Base.metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()
else:
    supplied_connection = context.config.attributes.get("connection")
    if supplied_connection is not None:
        context.configure(connection=supplied_connection, target_metadata=Base.metadata)
        with context.begin_transaction():
            context.run_migrations()
    else:
        with engine.connect() as connection:
            context.configure(connection=connection, target_metadata=Base.metadata)
            with context.begin_transaction():
                context.run_migrations()
