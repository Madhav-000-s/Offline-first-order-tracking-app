from fastapi import APIRouter

from app.api.v1.routers import auth, dev, devices, orders, restaurants, sync, ws

api_router = APIRouter(prefix="/v1")
api_router.include_router(auth.router)
api_router.include_router(restaurants.router)
api_router.include_router(orders.router)
api_router.include_router(sync.router)
api_router.include_router(devices.router)
api_router.include_router(dev.router)
api_router.include_router(ws.router)
