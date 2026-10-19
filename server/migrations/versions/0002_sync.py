"""Owner-scoped versioned inspections and atomic photo blobs."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "0002_sync"
down_revision = "0001_users"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("users", sa.Column("sync_revision", sa.BigInteger(), nullable=False, server_default="0"))
    op.create_table(
        "inspections",
        sa.Column("owner_id", sa.Uuid(), primary_key=True),
        sa.Column("client_id", sa.Uuid(), primary_key=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("payload", JSONB(), nullable=False),
        sa.Column("expert_status", sa.String(32), nullable=False),
        sa.ForeignKeyConstraint(["owner_id"], ["users.id"]),
    )
    op.create_index("ix_inspections_revision", "inspections", ["revision"])
    op.create_table(
        "photos",
        sa.Column("owner_id", sa.Uuid(), primary_key=True),
        sa.Column("client_id", sa.Uuid(), primary_key=True),
        sa.Column("inspection_id", sa.Uuid(), nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("size", sa.Integer(), nullable=False),
        sa.Column("content", sa.LargeBinary(), nullable=True),
        sa.ForeignKeyConstraint(["owner_id", "inspection_id"], ["inspections.owner_id", "inspections.client_id"]),
    )


def downgrade():
    op.drop_table("photos")
    op.drop_table("inspections")
    op.drop_column("users", "sync_revision")
