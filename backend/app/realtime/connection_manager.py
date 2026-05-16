from fastapi import WebSocket


class ConnectionManager:
    """dict[order_id, set[WebSocket]] -- per-worker-process registry.

    This alone is wrong the moment you run more than one uvicorn worker: a
    status change published by worker B has to reach a socket held open on
    worker A. That's what the Redis subscriber in realtime/pubsub.py is for --
    every worker runs one, and every worker's ConnectionManager only needs to
    know about the sockets it personally holds (DESIGN.md §14.3).
    """

    def __init__(self) -> None:
        self._connections: dict[str, set[WebSocket]] = {}

    def subscribe(self, order_id: str, ws: WebSocket) -> None:
        self._connections.setdefault(order_id, set()).add(ws)

    def unsubscribe(self, order_id: str, ws: WebSocket) -> None:
        conns = self._connections.get(order_id)
        if conns is not None:
            conns.discard(ws)
            if not conns:
                self._connections.pop(order_id, None)

    def unsubscribe_all(self, ws: WebSocket) -> None:
        for order_id in list(self._connections.keys()):
            self.unsubscribe(order_id, ws)

    async def send_to_order(self, order_id: str, message: dict) -> None:
        dead: list[WebSocket] = []
        for ws in self._connections.get(order_id, ()):
            try:
                await ws.send_json(message)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.unsubscribe(order_id, ws)


manager = ConnectionManager()
