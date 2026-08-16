"""Demo driver: walks an order through the status ladder and nudges the
phone into showing each step.

Additive by design -- this touches nothing the app or backend already does.
It only calls the dev advance endpoint that already exists, reads Postgres
read-only, and cycles the app through the foreground with adb.

Why it exists
-------------
Two things make a live demo awkward without it:

1. The status ladder is driven by server-side timers (8s/8s/12s/8s), which
   is either too slow to watch or too fast to narrate. This forces each
   transition on a keypress instead.

2. Live status frames arrive over the WebSocket, which is scoped to the
   tracking screen's ViewModel -- and that screen needs a Google Maps API
   key. On the orders list and order detail, status converges via delta
   sync, whose only practical demo trigger is app foreground. This script
   performs that foreground cycle for you, so the ladder is visible with no
   maps key and no waiting.

Stdlib only. No pip install, no venv.

    python demo/drive_demo.py status
    python demo/drive_demo.py step
    python demo/drive_demo.py run
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
COMPOSE_FILE = REPO_ROOT / "docker-compose.yml"

API_BASE = "http://localhost:8000"
APP_ID = "com.ordertracking.app"
MAIN_ACTIVITY = f"{APP_ID}/.MainActivity"

# Mirrors app/core/enums.py's happy path. Terminal states end the walk.
LADDER = ["PLACED", "ACCEPTED", "PREPARING", "READY", "PICKED_UP", "DELIVERED"]
TERMINAL = {"DELIVERED", "CANCELLED", "REJECTED"}

# Long enough to narrate a step, short enough that nobody gets bored.
STEP_PAUSE_SECONDS = 3.0
# Foregrounding only *enqueues* the delta sync -- WorkManager then schedules
# it, and the round trip plus the Room write take a beat on top. Measured at
# ~1s on an emulator, but scheduling latency is the variable part, so this is
# deliberately generous: too short and the next step advances the server
# before the phone has rendered the previous rung, which makes the ladder
# look like it skipped steps.
SYNC_SETTLE_SECONDS = 8.0
# Gap between HOME and relaunch, so the activity genuinely stops first.
HOME_SETTLE_SECONDS = 2.0


class DemoError(RuntimeError):
    pass


# --------------------------------------------------------------------------
# plumbing
# --------------------------------------------------------------------------


def _run(cmd: list[str], *, timeout: int = 60) -> str:
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout, check=False
        )
    except FileNotFoundError as exc:
        raise DemoError(f"`{cmd[0]}` is not on PATH.") from exc
    except subprocess.TimeoutExpired as exc:
        raise DemoError(f"`{' '.join(cmd)}` timed out.") from exc
    if result.returncode != 0:
        raise DemoError(
            f"`{' '.join(cmd)}` failed:\n{result.stderr.strip() or result.stdout.strip()}"
        )
    return result.stdout


def psql(sql: str) -> list[list[str]]:
    """Read-only query against the compose Postgres. Tuples-only, pipe
    separated, so there is nothing to parse but split()."""
    out = _run(
        [
            "docker", "compose", "-f", str(COMPOSE_FILE), "exec", "-T", "postgres",
            "psql", "-U", "order_tracking", "-d", "order_tracking",
            "-t", "-A", "-F", "|", "-c", sql,
        ]
    )
    return [line.split("|") for line in out.strip().splitlines() if line.strip()]


def api_post(path: str) -> dict:
    req = urllib.request.Request(API_BASE + path, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise DemoError(f"POST {path} -> {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise DemoError(
            f"Couldn't reach {API_BASE}. Is `docker compose up` running?\n  {exc.reason}"
        ) from exc


def adb_available() -> bool:
    try:
        out = _run(["adb", "devices"], timeout=15)
    except DemoError:
        return False
    # First line is the "List of devices attached" header.
    return any(line.strip().endswith("device") for line in out.splitlines()[1:])


def foreground_app() -> None:
    """Home, then relaunch. MainActivity.onStart calls
    SyncManager.onAppForeground(), which enqueues a delta sync -- the only
    practical way to pull a status change onto a non-tracking screen."""
    _run(["adb", "shell", "input", "keyevent", "KEYCODE_HOME"])
    # Long enough for the activity to actually reach onStop. Relaunching too
    # soon leaves it resumed, onStart never fires again, and the delta sync
    # is silently never enqueued -- which looks exactly like a broken sync.
    time.sleep(HOME_SETTLE_SECONDS)
    _run(["adb", "shell", "am", "start", "-n", MAIN_ACTIVITY])


# --------------------------------------------------------------------------
# domain
# --------------------------------------------------------------------------


def recent_orders(limit: int = 5) -> list[dict]:
    rows = psql(
        "SELECT id, status, version, placed_at FROM orders "
        f"ORDER BY placed_at DESC LIMIT {int(limit)}"
    )
    return [
        {"id": r[0], "status": r[1], "version": r[2], "placed_at": r[3]}
        for r in rows
        if len(r) >= 4
    ]


def newest_order() -> dict:
    orders = recent_orders(1)
    if not orders:
        raise DemoError(
            "No orders yet. Place one in the app first -- that's the part you demo."
        )
    return orders[0]


def resolve_order(order_id: str | None) -> dict:
    if order_id is None:
        return newest_order()
    rows = psql(f"SELECT id, status, version, placed_at FROM orders WHERE id = '{order_id}'")
    if not rows:
        raise DemoError(f"No order with id {order_id}.")
    r = rows[0]
    return {"id": r[0], "status": r[1], "version": r[2], "placed_at": r[3]}


def ladder_line(status: str) -> str:
    parts = []
    seen_current = False
    for name in LADDER:
        if name == status:
            parts.append(f"[{name}]")
            seen_current = True
        elif not seen_current:
            parts.append(f" {name} ")
        else:
            parts.append(f" {name.lower()} ")
    line = "->".join(p.strip() for p in parts)
    if status in TERMINAL and status not in LADDER:
        line += f"   ({status})"
    return line


# --------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------


def cmd_status(args: argparse.Namespace) -> int:
    orders = recent_orders(args.limit)
    if not orders:
        print("No orders yet. Place one in the app.")
        return 0
    print(f"{'ORDER':38}  {'STATUS':10}  {'VER':>3}  PLACED AT")
    for o in orders:
        print(f"{o['id']:38}  {o['status']:10}  {o['version']:>3}  {o['placed_at']}")
    print()
    print("Ladder for the newest:")
    print("  " + ladder_line(orders[0]["status"]))
    return 0


def cmd_refresh(args: argparse.Namespace) -> int:
    if not adb_available():
        raise DemoError("No device on adb. Start the emulator, or pass --no-phone.")
    foreground_app()
    print("Cycled the app through the foreground -> delta sync enqueued.")
    return 0


def _advance_once(order_id: str) -> str:
    body = api_post(f"/v1/dev/orders/{order_id}/advance")
    return body["status"]


def cmd_step(args: argparse.Namespace) -> int:
    order = resolve_order(args.order)
    if order["status"] in TERMINAL:
        print(f"Order is already {order['status']} -- nothing left to advance.")
        return 0

    new_status = _advance_once(order["id"])
    print(f"{order['status']} -> {new_status}")
    print("  " + ladder_line(new_status))

    if args.no_phone:
        return 0
    if not adb_available():
        print("  (no device on adb; skipping the phone refresh)")
        return 0
    foreground_app()
    print("  phone foregrounded -> delta sync enqueued")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    order = resolve_order(args.order)
    print(f"Driving order {order['id']}")
    print(f"  starting at {order['status']}")
    print()

    use_phone = not args.no_phone and adb_available()
    if not use_phone and not args.no_phone:
        print("  (no device on adb; running server-side only)\n")

    # Deliberately does not foreground the phone between rungs. Each cycle
    # costs ~10s of wall clock, and empirically the order can advance again
    # inside that window -- so the ladder printed here would skip rungs the
    # server actually passed through. Advance cleanly, then sync once at the
    # end. Use `step` when you want to watch each rung land on the device.
    status = order["status"]
    while status not in TERMINAL:
        time.sleep(args.pause)
        status = _advance_once(order["id"])
        print(f"  -> {status}")
        print("     " + ladder_line(status))

    print()
    if use_phone:
        foreground_app()
        print("Phone foregrounded -> delta sync enqueued; give it a few seconds.")
    print(f"Order is {status}.")
    print("Open the Sync log on the orders screen to see every merge decision.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Drive an order through the status ladder for a live demo.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--order", help="Order UUID. Defaults to the most recently created one."
    )
    parser.add_argument(
        "--no-phone",
        action="store_true",
        help="Skip the adb foreground cycle (server-side only).",
    )
    sub = parser.add_subparsers(dest="command")

    p_status = sub.add_parser("status", help="Show recent orders and the ladder.")
    p_status.add_argument("--limit", type=int, default=5)
    p_status.set_defaults(func=cmd_status)

    p_step = sub.add_parser("step", help="Advance one stage, then refresh the phone.")
    p_step.set_defaults(func=cmd_step)

    p_run = sub.add_parser("run", help="Advance all the way to DELIVERED.")
    p_run.add_argument("--pause", type=float, default=STEP_PAUSE_SECONDS)
    p_run.set_defaults(func=cmd_run)

    p_refresh = sub.add_parser("refresh", help="Force the phone to delta sync.")
    p_refresh.set_defaults(func=cmd_refresh)

    args = parser.parse_args()
    if args.command is None:
        parser.print_help()
        return 0
    if not hasattr(args, "pause"):
        args.pause = STEP_PAUSE_SECONDS

    try:
        return args.func(args)
    except DemoError as exc:
        print(f"\nerror: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
