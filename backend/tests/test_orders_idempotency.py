import asyncio
import uuid

import pytest

from tests.conftest import unique_email

pytestmark = pytest.mark.asyncio(loop_scope="session")


async def _seed_restaurant_and_menu_item(db_session):
    from app.db.models.menu_item import MenuItem
    from app.db.models.restaurant import Restaurant

    restaurant = Restaurant(name="Test Diner", cuisine="American", rating=4.5, image_url="", lat=12.97, lng=77.59)
    db_session.add(restaurant)
    await db_session.flush()

    menu_item = MenuItem(
        restaurant_id=restaurant.id, name="Burger", description="", price_minor=899, currency="USD", image_url="",
    )
    db_session.add(menu_item)
    await db_session.commit()
    return restaurant, menu_item


async def _registered_access_token(client) -> str:
    resp = await client.post("/v1/auth/register", json={"email": unique_email(), "password": "hunter2pass"})
    return resp.json()["access_token"]


async def test_concurrent_identical_requests_create_exactly_one_order(client, db_session):
    """This is the test that proves the claim (DESIGN.md §16): fire N
    concurrent identical POST /v1/orders with the same Idempotency-Key,
    assert exactly one order row and N identical responses."""
    restaurant, menu_item = await _seed_restaurant_and_menu_item(db_session)
    access_token = await _registered_access_token(client)
    headers = {"Authorization": f"Bearer {access_token}"}

    idempotency_key = str(uuid.uuid4())
    body = {"restaurant_id": str(restaurant.id), "items": [{"menu_item_id": str(menu_item.id), "quantity": 1}]}

    async def fire():
        return await client.post("/v1/orders", json=body, headers={**headers, "Idempotency-Key": idempotency_key})

    results = await asyncio.gather(*[fire() for _ in range(15)])

    assert all(r.status_code in (201, 409) for r in results)
    order_ids = {r.json()["id"] for r in results if r.status_code == 201}
    assert len(order_ids) == 1, f"expected exactly one order id, got {order_ids}"

    # A retry after everything has settled must replay the same order, not
    # create a second one (the lost-response scenario DESIGN.md §7 describes).
    replay = await client.post("/v1/orders", json=body, headers={**headers, "Idempotency-Key": idempotency_key})
    assert replay.status_code == 201
    assert replay.json()["id"] in order_ids


async def test_same_key_different_body_is_422(client, db_session):
    restaurant, menu_item = await _seed_restaurant_and_menu_item(db_session)
    access_token = await _registered_access_token(client)
    headers = {"Authorization": f"Bearer {access_token}", "Idempotency-Key": str(uuid.uuid4())}

    body_a = {"restaurant_id": str(restaurant.id), "items": [{"menu_item_id": str(menu_item.id), "quantity": 1}]}
    body_b = {"restaurant_id": str(restaurant.id), "items": [{"menu_item_id": str(menu_item.id), "quantity": 2}]}

    first = await client.post("/v1/orders", json=body_a, headers=headers)
    assert first.status_code == 201

    second = await client.post("/v1/orders", json=body_b, headers=headers)
    assert second.status_code == 422


async def test_cancel_is_idempotent(client, db_session):
    restaurant, menu_item = await _seed_restaurant_and_menu_item(db_session)
    access_token = await _registered_access_token(client)
    headers = {"Authorization": f"Bearer {access_token}"}

    create = await client.post(
        "/v1/orders",
        json={"restaurant_id": str(restaurant.id), "items": [{"menu_item_id": str(menu_item.id), "quantity": 1}]},
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
    )
    order_id = create.json()["id"]

    cancel_key = str(uuid.uuid4())
    first_cancel = await client.post(
        f"/v1/orders/{order_id}/cancel", json={}, headers={**headers, "Idempotency-Key": cancel_key},
    )
    assert first_cancel.status_code == 200
    assert first_cancel.json()["status"] == "CANCELLED"
    version_after_first = first_cancel.json()["version"]

    second_cancel = await client.post(
        f"/v1/orders/{order_id}/cancel", json={}, headers={**headers, "Idempotency-Key": cancel_key},
    )
    assert second_cancel.status_code == 200
    assert second_cancel.json()["version"] == version_after_first
