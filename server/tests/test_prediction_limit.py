import json

import pytest

from tests.test_api import client, database, migrate_test_database  # noqa: F401
from tests.test_sync import auth, push, update  # noqa: F401


@pytest.mark.parametrize("extra,expected", [(0, 200), (1, 422)])
def test_prediction_limit_keeps_exact_boundary_and_rejects_above(client, auth, extra, expected):
    limit = 1024 * 1024
    overhead = len(json.dumps({"fixture": ""}, allow_nan=False).encode())
    item = update()
    item["data"]["prediction"] = {"fixture": "a" * (limit - overhead + extra)}
    assert len(json.dumps(item["data"]["prediction"], allow_nan=False).encode()) == limit + extra
    response = push(client, auth, item)
    assert response.status_code == expected
    if expected == 200:
        assert response.json()["results"][0]["inspection"]["data"]["prediction"] == item["data"]["prediction"]
