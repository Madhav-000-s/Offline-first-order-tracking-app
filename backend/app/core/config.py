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


@lru_cache
def get_settings() -> Settings:
    return Settings()
