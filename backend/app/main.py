import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.v1.router import api_router
from app.core.config import get_settings
from app.core.errors import AppError
from app.realtime import pubsub
from app.workers import state_advancer

settings = get_settings()
logger = logging.getLogger(__name__)

# Uvicorn configures its own named loggers but leaves the root logger (and
# therefore every `logging.getLogger(__name__)` in this app) at the Python
# default of WARNING, so our own INFO logs -- the subscriber loop starting,
# the startup reconciler's summary -- were silently getting dropped.
logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    subscriber_task = asyncio.create_task(pubsub.subscriber_loop())
    # Bare asyncio tasks (not a durable queue) die with the process, so on
    # every startup we re-arm timers/simulators for whatever was left
    # in-flight -- see workers/state_advancer.py's docstring for the tradeoff.
    await state_advancer.reconcile_in_flight_orders()

    yield

    subscriber_task.cancel()
    try:
        await subscriber_task
    except asyncio.CancelledError:
        pass
    await pubsub.close_redis()


def create_app() -> FastAPI:
    app = FastAPI(title=settings.app_name, lifespan=lifespan)

    @app.exception_handler(AppError)
    async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
        return JSONResponse(status_code=exc.status_code, content={"detail": exc.message})

    app.include_router(api_router)

    @app.get("/healthz")
    async def healthz() -> dict:
        return {"status": "ok"}

    @app.get("/readyz")
    async def readyz() -> dict:
        return {"status": "ready"}

    return app


app = create_app()
