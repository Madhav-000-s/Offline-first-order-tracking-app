from fastapi import APIRouter

from app.api.v1.routers import auth, orders, restaurants, sync

api_router = APIRouter(prefix="/v1")
api_router.include_router(auth.router)
api_router.include_router(restaurants.router)
api_router.include_router(orders.router)
api_router.include_router(sync.router)
