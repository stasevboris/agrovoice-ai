"""Expert role and append-only review provenance."""
from alembic import op
import sqlalchemy as sa

revision = "0003_reviews"
down_revision = "0002_sync"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("users", sa.Column("is_expert", sa.Boolean(), nullable=False, server_default=sa.false()))
    op.create_table(
        "review_events",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("owner_id", sa.Uuid(), nullable=False),
        sa.Column("inspection_id", sa.Uuid(), nullable=False),
        sa.Column("reviewer_id", sa.Uuid(), nullable=False),
        sa.Column("evidence_version", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.ForeignKeyConstraint(["owner_id", "inspection_id"], ["inspections.owner_id", "inspections.client_id"]),
        sa.ForeignKeyConstraint(["reviewer_id"], ["users.id"]),
    )


def downgrade():
    op.drop_table("review_events")
    op.drop_column("users", "is_expert")
