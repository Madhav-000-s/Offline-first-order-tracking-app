import asyncio
import logging
import random
import uuid

from app.core.config import get_settings
from app.core.enums import OrderStatus
from app.realtime import pubsub
from app.realtime.geo import bearing_degrees, haversine_meters

settings = get_settings()
logger = logging.getLogger(__name__)

_GPS_JITTER_STD_DEV = 0.00003  # ~3m at these latitudes


async def simulate(order_id: uuid.UUID, route_points: list[tuple[float, float]]) -> None:
    """One asyncio task per active order, started the moment it transitions to
    PICKED_UP. Walks the precomputed fixture polyline at a fixed speed,
    publishing a position every ~1s with jitter, then hands the order off to
    DELIVERED on arrival (DESIGN.md §14.4).

    `courier_position` frames are intentionally never written to Postgres --
    they're pure pub/sub traffic. Durable truth is only ever `order_status`,
    which goes through `OrderWriter`-equivalent transition_and_notify.
    """
    from app.services import order_service  # local import: avoids a circular import at module load time

    order_id_str = str(order_id)
    speed = settings.courier_speed_mps

    try:
        for i in range(len(route_points) - 1):
            lat1, lng1 = route_points[i]
            lat2, lng2 = route_points[i + 1]
            distance = haversine_meters(lat1, lng1, lat2, lng2)
            bearing = bearing_degrees(lat1, lng1, lat2, lng2)
            steps = max(1, round(distance / speed))

            for step in range(1, steps + 1):
                frac = step / steps
                lat = lat1 + (lat2 - lat1) * frac + random.gauss(0, _GPS_JITTER_STD_DEV)
                lng = lng1 + (lng2 - lng1) * frac + random.gauss(0, _GPS_JITTER_STD_DEV)
                await pubsub.publish(
                    order_id_str,
                    {
                        "v": 1,
                        "type": "courier_position",
                        "order_id": order_id_str,
                        "data": {"lat": lat, "lng": lng, "bearing": bearing, "speed_mps": speed},
                    },
                )
                await asyncio.sleep(1)

        await order_service.transition_and_notify(order_id, OrderStatus.DELIVERED)
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception("courier simulator crashed for order %s", order_id_str)
