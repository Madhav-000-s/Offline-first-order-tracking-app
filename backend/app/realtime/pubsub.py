import asyncio
import json
import logging

import redis.asyncio as redis

from app.core.config import get_settings
from app.realtime.connection_manager import manager

settings = get_settings()
logger = logging.getLogger(__name__)

_redis: redis.Redis | None = None


def get_redis() -> redis.Redis:
    global _redis
    if _redis is None:
        _redis = redis.from_url(settings.redis_url, decode_responses=True)
    return _redis


async def publish(order_id: str, message: dict) -> None:
    await get_redis().publish(f"order:{order_id}", json.dumps(message))


async def subscriber_loop() -> None:
    """One long-lived task per worker process, forwarding Redis pub/sub
    traffic into whatever local WebSocket connections this worker is
    holding. Runs for the lifetime of the app (started in main.py's lifespan)."""
    pubsub = get_redis().pubsub()
    await pubsub.psubscribe("order:*")
    logger.info("realtime subscriber listening on order:*")
    try:
        async for msg in pubsub.listen():
            if msg["type"] != "pmessage":
                continue
            channel = msg["channel"]
            order_id = channel.split(":", 1)[1]
            try:
                payload = json.loads(msg["data"])
            except (TypeError, ValueError):
                continue
            await manager.send_to_order(order_id, payload)
    except asyncio.CancelledError:
        raise
    finally:
        await pubsub.punsubscribe("order:*")
        await pubsub.close()


async def close_redis() -> None:
    global _redis
    if _redis is not None:
        await _redis.close()
        _redis = None
