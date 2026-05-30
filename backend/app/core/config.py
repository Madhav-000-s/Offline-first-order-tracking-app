from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = "order-tracking-api"
    environment: str = "development"

    database_url: str = "postgresql+asyncpg://order_tracking:order_tracking@localhost:5432/order_tracking"
    redis_url: str = "redis://localhost:6379/0"

    jwt_secret: str = "dev-secret-change-me"
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 15
    refresh_token_expire_days: int = 30

    idempotency_key_ttl_hours: int = 24

    firebase_credentials_path: str | None = None

    courier_speed_mps: float = 6.0
    dev_endpoints_enabled: bool = True
    # Off in tests: the automatic timer outlives a single test (shortest
    # stage is several seconds), and a background asyncio task still asleep
    # when the test session disposes the engine produces exactly the
    # "event loop is closed" / "greenlet already finalized" noise that
    # looks like a real bug but is really just a lifecycle mismatch.
    auto_advance_enabled: bool = True
    # Off in tests (see tests/conftest.py): the pre-ping health check is
    # exactly where a Windows/asyncpg/ProactorEventLoop incompatibility
    # surfaces during fast pool churn against a throwaway Testcontainers
    # Postgres, and it buys nothing there that a fresh container needs.
    db_pool_pre_ping: bool = True


@lru_cache
def get_settings() -> Settings:
    return Settings()
