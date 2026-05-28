"""Seed fixtures for local/demo use (DESIGN.md §1: "Seed data via Alembic + a
fixtures script" instead of building a restaurant/merchant portal).

Run from backend/ with the venv active:
    python -m scripts.seed                 # adds restaurants if fewer than --count exist
    python -m scripts.seed --reset          # truncates restaurants/menu_items first
    python -m scripts.seed --count 750
"""

import argparse
import asyncio
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sqlalchemy import delete, func, select

from app.db.models.menu_item import MenuItem
from app.db.models.order import Order
from app.db.models.order_event import OrderEvent
from app.db.models.order_item import OrderItem
from app.db.models.restaurant import Restaurant
from app.db.session import AsyncSessionLocal

CUISINES = [
    "Italian", "Japanese", "Mexican", "Thai", "Indian", "American", "Chinese",
    "Mediterranean", "Korean", "Vietnamese", "French", "Greek", "Spanish", "BBQ",
]

NAME_PREFIXES = [
    "The Golden", "Blue", "Green", "Little", "Corner", "Uptown", "Downtown",
    "Rustic", "Urban", "Coastal", "Sunset", "Midnight", "Silver", "Copper",
]

NAME_NOUNS = [
    "Kitchen", "Table", "Bistro", "House", "Garden", "Grill", "Diner", "Spoon",
    "Plate", "Fork", "Oven", "Pot", "Tavern", "Cantina", "Noodle Bar",
]

DISH_POOL = [
    ("Signature Burger", 899), ("Margherita Pizza", 1099), ("Pad Thai", 999),
    ("Chicken Tikka Masala", 1199), ("California Roll", 799), ("Tacos al Pastor", 799),
    ("Pho Bo", 1099), ("Falafel Wrap", 699), ("Ramen", 1199), ("Caesar Salad", 699),
    ("Fish and Chips", 1099), ("Dumplings (6pc)", 699), ("Pulled Pork Sandwich", 999),
    ("Vegetable Curry", 899), ("Spaghetti Carbonara", 1099), ("Bibimbap", 1099),
    ("Greek Salad", 799), ("Churros", 499), ("Mango Sticky Rice", 599), ("Iced Tea", 299),
]

# A modest bounding box around Bangalore, matching the courier fixture
# routes' coordinate range so seeded restaurants sit near where the
# simulator actually drives (backend/app/realtime/fixture_routes.py).
LAT_RANGE = (12.90, 13.02)
LNG_RANGE = (77.55, 77.65)


def random_restaurant(i: int) -> Restaurant:
    name = f"{random.choice(NAME_PREFIXES)} {random.choice(NAME_NOUNS)} #{i}"
    seed = random.randint(0, 1_000_000)
    return Restaurant(
        name=name,
        cuisine=random.choice(CUISINES),
        rating=round(random.uniform(3.5, 5.0), 1),
        image_url=f"https://picsum.photos/seed/{seed}/600/400",
        lat=round(random.uniform(*LAT_RANGE), 6),
        lng=round(random.uniform(*LNG_RANGE), 6),
    )


def random_menu(restaurant_id) -> list[MenuItem]:
    dishes = random.sample(DISH_POOL, k=random.randint(5, 9))
    return [
        MenuItem(
            restaurant_id=restaurant_id,
            name=name,
            description="",
            price_minor=price + random.choice([-100, 0, 0, 100, 200]),
            currency="USD",
            image_url="",
        )
        for name, price in dishes
    ]


async def main(count: int, reset: bool) -> None:
    async with AsyncSessionLocal() as db:
        if reset:
            # This is a dev/demo convenience script, not a production
            # migration -- orders reference restaurants, so a real reset has
            # to clear the whole dependency chain, not just the two tables
            # this script itself populates.
            await db.execute(delete(OrderEvent))
            await db.execute(delete(OrderItem))
            await db.execute(delete(Order))
            await db.execute(delete(MenuItem))
            await db.execute(delete(Restaurant))
            await db.commit()
            print("cleared orders, restaurants, and menu_items")

        existing = await db.scalar(select(func.count()).select_from(Restaurant))
        to_create = max(0, count - existing)
        if to_create == 0:
            print(f"already have {existing} restaurants (>= {count}), nothing to do")
            return

        for i in range(existing, existing + to_create):
            restaurant = random_restaurant(i)
            db.add(restaurant)
            await db.flush()  # assign restaurant.id before building its menu
            db.add_all(random_menu(restaurant.id))

            if (i + 1) % 50 == 0:
                await db.commit()
                print(f"seeded {i + 1} restaurants...")

        await db.commit()
        print(f"done: {to_create} restaurants added ({existing + to_create} total)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=500, help="target number of restaurants")
    parser.add_argument("--reset", action="store_true", help="truncate restaurants/menu_items first")
    args = parser.parse_args()
    asyncio.run(main(args.count, args.reset))
