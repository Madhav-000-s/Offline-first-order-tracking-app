import uuid

import jwt
from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.core.security import decode_token
from app.db.session import AsyncSessionLocal
from app.realtime.connection_manager import manager
from app.services import order_service

router = APIRouter(tags=["ws"])


@router.websocket("/ws/orders")
async def ws_orders(websocket: WebSocket) -> None:
    """wss://.../v1/ws/orders -- token arrives in the first frame, not the
    query string, so it never lands in a server access log (DESIGN.md §9).

    After auth, the client sends `{"type": "subscribe", "order_id": "..."}`
    per order it wants live updates for (a tracking screen watches one order
    at a time) and `{"type": "ping"}` for an application-level heartbeat on
    top of OkHttp's protocol-level one, proving the server side isn't wedged.
    """
    await websocket.accept()

    try:
        first = await websocket.receive_json()
        payload = decode_token(first.get("token", ""))
        if payload.get("type") != "access":
            raise jwt.PyJWTError("not an access token")
        user_id = uuid.UUID(payload["sub"])
    except (jwt.PyJWTError, ValueError, KeyError):
        await websocket.close(code=4401)
        return

    subscribed: set[str] = set()
    seq = 0
    try:
        while True:
            msg = await websocket.receive_json()
            msg_type = msg.get("type")

            if msg_type == "subscribe":
                try:
                    order_uuid = uuid.UUID(str(msg.get("order_id")))
                except ValueError:
                    continue
                async with AsyncSessionLocal() as db:
                    try:
                        order = await order_service.get_order(db, user_id, order_uuid)
                    except order_service.OrderNotFound:
                        continue
                manager.subscribe(str(order.id), websocket)
                subscribed.add(str(order.id))

            elif msg_type == "ping":
                seq += 1
                await websocket.send_json({"v": 1, "type": "pong", "seq": seq})

    except WebSocketDisconnect:
        pass
    finally:
        manager.unsubscribe_all(websocket)
