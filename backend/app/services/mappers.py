from app.db.models.menu_item import MenuItem
from app.db.models.order import Order
from app.db.models.order_event import OrderEvent
from app.db.models.order_item import OrderItem
from app.db.models.restaurant import Restaurant
from app.schemas.order import OrderEventOut, OrderItemOut, OrderOut
from app.schemas.restaurant import MenuItemOut, RestaurantOut


def restaurant_out(row: Restaurant) -> RestaurantOut:
    return RestaurantOut(
        id=row.id,
        name=row.name,
        cuisine=row.cuisine,
        rating=float(row.rating),
        image_url=row.image_url,
        lat=float(row.lat),
        lng=float(row.lng),
        version=row.version,
        updated_at=row.updated_at,
        deleted=row.deleted_at is not None,
    )


def menu_item_out(row: MenuItem) -> MenuItemOut:
    return MenuItemOut(
        id=row.id,
        restaurant_id=row.restaurant_id,
        name=row.name,
        description=row.description,
        price_minor=row.price_minor,
        currency=row.currency,
        image_url=row.image_url,
        version=row.version,
        updated_at=row.updated_at,
        deleted=row.deleted_at is not None,
    )


def order_item_out(row: OrderItem) -> OrderItemOut:
    return OrderItemOut(
        id=row.id,
        menu_item_id=row.menu_item_id,
        name_snapshot=row.name_snapshot,
        unit_price_minor=row.unit_price_minor,
        quantity=row.quantity,
    )


def order_event_out(row: OrderEvent) -> OrderEventOut:
    return OrderEventOut(id=row.id, status=row.status, occurred_at=row.occurred_at, note=row.note)


def order_out(row: Order) -> OrderOut:
    return OrderOut(
        id=row.id,
        client_local_id=row.client_local_id,
        restaurant_id=row.restaurant_id,
        status=row.status,
        total_minor=row.total_minor,
        currency=row.currency,
        eta=row.eta,
        placed_at=row.placed_at,
        delivery_note=row.delivery_note,
        tip_minor=row.tip_minor,
        route_polyline=row.route_polyline,
        version=row.version,
        updated_at=row.updated_at,
        deleted=row.deleted_at is not None,
        items=[order_item_out(i) for i in row.items],
        events=[order_event_out(e) for e in row.events],
    )
