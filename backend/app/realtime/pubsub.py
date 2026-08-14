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


# Kept well past any realistic socket lifetime, but not forever: an order
# that stopped emitting a day ago is never coming back, and a client that
# reconnects after the key expires just sees a lower `seq` than it had --
# which never trips the gap check, because a gap is `seq > last + 1`.
_SEQ_TTL_SECONDS = 24 * 60 * 60


async def publish(order_id: str, message: dict) -> None:
    """Stamps every outbound frame with a per-order monotonic `seq`.

    The counter lives in Redis rather than in the publishing process because
    two workers can both publish for the same order -- a process-local
    counter would emit duplicate sequence numbers and the client's gap
    detection would be reading noise. `INCR` is atomic and creates the key
    on first use, so this is one round trip with no initialisation step.

    The client compares consecutive `seq` values per order and enqueues a
    delta sync when it sees a jump, which is what makes a dropped frame
    self-healing instead of silently lost (DESIGN.md §9).
    """
    redis_client = get_redis()
    key = f"seq:order:{order_id}"
    seq = await redis_client.incr(key)
    await redis_client.expire(key, _SEQ_TTL_SECONDS)
    await redis_client.publish(f"order:{order_id}", json.dumps({**message, "seq": seq}))


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
