import asyncio
import logging

from app.core.config import get_settings

settings = get_settings()
logger = logging.getLogger(__name__)

_firebase_app = None


def _get_firebase_app():
    global _firebase_app
    if _firebase_app is None and settings.firebase_credentials_path:
        import firebase_admin
        from firebase_admin import credentials

        cred = credentials.Certificate(settings.firebase_credentials_path)
        _firebase_app = firebase_admin.initialize_app(cred)
    return _firebase_app


async def send_order_status_push(tokens: list[str], order_id: str, status: str) -> None:
    """Data-only message -- never `notification` -- so the payload always
    reaches onMessageReceived and gets routed through OrderWriter rather than
    the OS posting a notification directly from a payload we never even
    trusted as truth (DESIGN.md §10)."""
    if not tokens:
        return

    app = _get_firebase_app()
    if app is None:
        logger.info(
            "push (no FIREBASE_CREDENTIALS_PATH configured, logging instead): "
            "order=%s status=%s recipients=%d",
            order_id,
            status,
            len(tokens),
        )
        return

    from firebase_admin import messaging

    message = messaging.MulticastMessage(
        data={"type": "order_status", "order_id": order_id, "status": status},
        tokens=tokens,
        android=messaging.AndroidConfig(priority="high"),
    )
    await asyncio.to_thread(messaging.send_each_for_multicast, message)
