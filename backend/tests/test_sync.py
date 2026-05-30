import uuid

import pytest

from tests.conftest import unique_email

pytestmark = pytest.mark.asyncio(loop_scope="session")


async def _access_token(client) -> str:
    resp = await client.post("/v1/auth/register", json={"email": unique_email(), "password": "hunter2pass"})
    return resp.json()["access_token"]


async def _seed_restaurants(db_session, count: int):
    from app.db.models.restaurant import Restaurant

    rows = [
        Restaurant(name=f"Restaurant {uuid.uuid4()}", cuisine="Test", rating=4.0, image_url="", lat=0.0, lng=0.0)
        for _ in range(count)
    ]
    db_session.add_all(rows)
    await db_session.commit()
    for row in rows:
        await db_session.refresh(row)
    return rows


async def _bump(db_session, restaurant) -> None:
    """Any UPDATE bumps `version`/`updated_at` via the mixin's onupdate, which
    is exactly what moves a row's keyset position forward."""
    restaurant.rating = 5.0
    await db_session.commit()


async def test_sync_visits_every_restaurant_even_when_one_is_mutated_mid_pagination(client, db_session):
    restaurants = await _seed_restaurants(db_session, count=5)
    access_token = await _access_token(client)
    headers = {"Authorization": f"Bearer {access_token}"}

    seen_ids: set[str] = set()
    cursor = None

    page1 = await client.get("/v1/sync", params={"limit": 2}, headers=headers)
    assert page1.status_code == 200
    body1 = page1.json()
    seen_ids.update(r["id"] for r in body1["changes"]["restaurants"])
    cursor = body1["next_cursor"]

    # Mutate a restaurant that hasn't been returned yet -- this bumps it to
    # the *end* of the keyset order. A correct implementation must still
    # eventually return it exactly once; a timestamp-based (not keyset)
    # cursor could instead skip it entirely.
    not_yet_seen = next(r for r in restaurants if str(r.id) not in seen_ids)
    await _bump(db_session, not_yet_seen)

    for _ in range(10):  # bounded loop instead of `while True`
        page = await client.get("/v1/sync", params={"cursor": cursor, "limit": 2}, headers=headers)
        assert page.status_code == 200
        body = page.json()
        seen_ids.update(r["id"] for r in body["changes"]["restaurants"])
        cursor = body["next_cursor"]
        if not body["has_more"]:
            break

    all_ids = {str(r.id) for r in restaurants}
    missing = all_ids - seen_ids
    assert not missing, f"sync skipped these restaurants entirely: {missing}"


async def test_sync_cursor_is_stable_across_repeated_calls_with_no_new_data(client, db_session):
    await _seed_restaurants(db_session, count=3)
    access_token = await _access_token(client)
    headers = {"Authorization": f"Bearer {access_token}"}

    first = await client.get("/v1/sync", params={"limit": 50}, headers=headers)
    cursor = first.json()["next_cursor"]

    # Replaying the same cursor with nothing new in between must be a
    # harmless no-op -- this is what makes "crash mid-page, replay the page"
    # safe (DESIGN.md §8).
    second = await client.get("/v1/sync", params={"cursor": cursor, "limit": 50}, headers=headers)
    assert second.status_code == 200
    assert second.json()["changes"]["restaurants"] == []
    assert second.json()["has_more"] is False
