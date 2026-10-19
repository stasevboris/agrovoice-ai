"""Users for the initial authenticated API."""
from alembic import op
import sqlalchemy as sa

revision = "0001_users"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("users", sa.Column("id", sa.Uuid(), primary_key=True),
                    sa.Column("username", sa.String(80), nullable=False),
                    sa.Column("password_hash", sa.Text(), nullable=False),
                    sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
                    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()))
    op.create_index("ix_users_username", "users", ["username"], unique=True)


def downgrade():
    op.drop_index("ix_users_username", table_name="users")
    op.drop_table("users")
