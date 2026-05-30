import pytest

from tests.conftest import unique_email

# `asyncio_mode = auto` implicitly marks every async test with
# @pytest.mark.asyncio, but that implicit marker defaults to function-scoped
# loops regardless of asyncio_default_fixture_loop_scope in pytest.ini --
# only fixtures read that default. Since the engine (and its connections)
# are created once per session, tests need the same explicit loop scope or
# asyncpg sees a connection awaited from a different loop than it was made on.
pytestmark = pytest.mark.asyncio(loop_scope="session")


async def test_register_then_me(client):
    email = unique_email()
    resp = await client.post("/v1/auth/register", json={"email": email, "password": "hunter2pass"})
    assert resp.status_code == 201
    access = resp.json()["access_token"]

    me = await client.get("/v1/auth/me", headers={"Authorization": f"Bearer {access}"})
    assert me.status_code == 200
    assert me.json()["email"] == email


async def test_duplicate_email_is_409(client):
    email = unique_email()
    first = await client.post("/v1/auth/register", json={"email": email, "password": "hunter2pass"})
    assert first.status_code == 201

    second = await client.post("/v1/auth/register", json={"email": email, "password": "different"})
    assert second.status_code == 409


async def test_login_wrong_password_is_401(client):
    email = unique_email()
    await client.post("/v1/auth/register", json={"email": email, "password": "hunter2pass"})

    resp = await client.post("/v1/auth/login", json={"email": email, "password": "wrong"})
    assert resp.status_code == 401


async def test_refresh_rotates_and_reuse_is_detected(client):
    email = unique_email()
    register = await client.post("/v1/auth/register", json={"email": email, "password": "hunter2pass"})
    refresh_token = register.json()["refresh_token"]

    first_refresh = await client.post("/v1/auth/refresh", json={"refresh_token": refresh_token})
    assert first_refresh.status_code == 200
    new_refresh_token = first_refresh.json()["refresh_token"]
    assert new_refresh_token != refresh_token

    # Replaying the *old* (already-rotated) token is theft-shaped: reject it
    # and revoke the whole family, not just this one token (DESIGN.md §13).
    replay = await client.post("/v1/auth/refresh", json={"refresh_token": refresh_token})
    assert replay.status_code == 401

    # The token that *should* still be valid is now also revoked as a result.
    now_also_revoked = await client.post("/v1/auth/refresh", json={"refresh_token": new_refresh_token})
    assert now_also_revoked.status_code == 401
