"""Testcontainers Postgres, not SQLite -- the schema uses JSONB, TIMESTAMPTZ,
and ON CONFLICT, so testing against a different engine tests a different
program (DESIGN.md §16).

IMPORTANT: nothing in this file or any test module may `import app.*` at
module scope. `app.core.config.get_settings()` is `@lru_cache`d and
`app.db.session` builds its engine once at import time from whatever
DATABASE_URL is set then -- if any app module got imported during pytest's
collection phase (before the container fixture below has set the env var),
it would permanently bind to the wrong database for the rest of the
process. Every `app.*` import here is therefore deferred into a fixture
body, which runs after collection.
"""

import asyncio
import os
import sys

import pytest
import pytest_asyncio
from testcontainers.postgres import PostgresContainer

if sys.platform == "win32":
    # asyncpg's connection cleanup (cancellation/close) is incompatible with
    # Windows' default ProactorEventLoop -- a well-documented asyncpg/Windows
    # issue, unrelated to anything about this app's own code. The selector
    # loop is the standard workaround.
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())


@pytest.fixture(scope="session")
def postgres_url():
    with PostgresContainer("postgres:16") as container:
        url = container.get_connection_url().replace("postgresql+psycopg2", "postgresql+asyncpg")
        yield url


@pytest.fixture(scope="session", autouse=True)
def _configure_env(postgres_url):
    os.environ["DATABASE_URL"] = postgres_url
    os.environ["JWT_SECRET"] = "test-secret"
    os.environ["DEV_ENDPOINTS_ENABLED"] = "true"
    os.environ["REDIS_URL"] = "redis://localhost:6379/15"
    # The automatic state-advancer timer is a production feature with no
    # place in a fast test suite: its shortest stage outlives most tests,
    # and a background task still sleeping when the session disposes the
    # engine produces asyncpg/event-loop teardown noise that looks like a
    # real bug but isn't one.
    os.environ["AUTO_ADVANCE_ENABLED"] = "false"
    os.environ["DB_POOL_PRE_PING"] = "false"
    yield


@pytest_asyncio.fixture(scope="session")
async def _schema_ready(_configure_env):
    from app.db.base import Base
    from app.db.session import engine

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    await engine.dispose()


@pytest_asyncio.fixture
async def client(_schema_ready):
    from httpx import ASGITransport, AsyncClient

    from app.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest_asyncio.fixture
async def db_session(_schema_ready):
    from app.db.session import AsyncSessionLocal

    async with AsyncSessionLocal() as session:
        yield session


def unique_email() -> str:
    import uuid

    return f"test-{uuid.uuid4()}@example.com"
