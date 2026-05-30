# Offline-First Order Tracking

Android (Kotlin, Compose, Room-as-SSOT) + FastAPI backend for a food-delivery-style
order tracking app. Full architecture, rationale, and design decisions live in
[DESIGN.md](./DESIGN.md).

> The network is a suggestion. Room is the truth. Every screen renders from a `Flow`
> off a DAO, and every byte that arrives from REST, WebSocket, or FCM is funnelled
> through a single writer that merges it into SQLite.

## Repo layout

```
backend/    FastAPI + SQLAlchemy async + Alembic + Redis (Docker Compose)
android/    Gradle multi-module Compose client (Room, WorkManager, Paging 3, Maps)
```

## Running the backend

```bash
docker compose up --build
```

API docs at `http://localhost:8000/docs` once running.

## Building the Android client

```bash
cd android
./gradlew assembleDebug
```

### Maps and push notifications (developer-supplied)

Two features need credentials that aren't checked into the repo:

- **Maps SDK for Android** (used by `:feature:tracking`'s live courier map):
  create a key in [Google Cloud Console](https://console.cloud.google.com/)
  (Maps SDK for Android, billing enabled), then add it to
  `android/local.properties` (already gitignored):
  ```
  MAPS_API_KEY=your-key-here
  ```
- **FCM push notifications**: create a Firebase project, register the app
  (`com.ordertracking.app`), download `google-services.json`, and place it at
  `android/app/google-services.json` (gitignored).

The project builds and every test suite passes without either of these —
they're only needed for real map tiles to render and for a push to actually
be deliverable at runtime.

## Running the tests

```bash
# Backend: Testcontainers spins up a throwaway Postgres, no setup needed
# beyond Docker running.
cd backend && python -m pytest tests/ -v

# Android: unit tests across all 13 modules run on the JVM via Robolectric --
# no emulator needed.
cd android && ./gradlew test
```

## Seeding demo data

```bash
cd backend
python -m scripts.seed --count 500       # add restaurants up to this count
python -m scripts.seed --reset --count 500   # wipe and reseed from scratch
```

## 3-minute demo script

1. **Cold start, offline.** Launch the app in airplane mode. The feed and
   any existing orders render immediately from Room -- nothing spins
   waiting for a network that isn't there.
2. **Place an order offline.** Add items, place the order. It appears
   instantly in the order list badged "Waiting to send" (`syncState =
   PENDING_CREATE`). Kill the app. Reopen it -- the order is still there,
   still pending, because it and its outbox row were written in one Room
   transaction (DESIGN.md §7).
3. **Reconnect.** Turn network back on. Within moments the badge clears:
   `OutboxDrainWorker` drained the outbox, the order reconciled to a real
   `serverId`, and Room emitted the update with nobody having refreshed
   anything.
4. **Force the duplicate-response case.** From the debug log ("Sync log"
   button on the orders screen), point at the same order and note the
   idempotency key used. Curl `POST /v1/orders` again with that exact
   `Idempotency-Key` -- same `serverId` comes back, and the sync log shows
   the merge accepting it as a no-op, not a second order.
5. **Watch it move.** Hit the backend's dev endpoint
   (`POST /v1/dev/orders/{id}/advance`) a few times, or just wait for the
   timers. Status updates arrive over the WebSocket in under a second.
6. **Tracking.** Once `PICKED_UP`, open the tracking screen: the courier
   marker walks the polyline smoothly (interpolated, not teleporting
   between 1 Hz updates), with a follow-camera that backs off the moment
   you pan it yourself.
7. **The sync log, unprompted.** Open it and scroll: every REST/WS/FCM
   merge decision this session made is right there -- "ACCEPT", "REJECT_STALE",
   "REJECT_REGRESSION" -- the invisible engine made visible in fifteen
   seconds (DESIGN.md §4).

## Build plan

This project was built in 10 phases, each ending in a demonstrable gate.
See [DESIGN.md §17](./DESIGN.md#17-build-phases-with-validation-gates) for details
on the underlying phase gates this plan is derived from.

| # | Phase | Status |
|---|---|---|
| 1 | Repo scaffolding | ✅ |
| 2 | Backend foundation (config, models, migrations, auth) | ✅ |
| 3 | Backend orders & delta sync | ✅ |
| 4 | Backend realtime (WebSocket, Redis pub/sub, courier simulator) | ✅ |
| 5 | Android core modules (model, common, database, datastore) | ✅ |
| 6 | Android network & data layer (repositories, `OrderWriter` merge engine) | ✅ |
| 7 | Android offline write path (outbox, `WorkManager`) | ✅ |
| 8 | Android orders + tracking features (maps, FCM) | ✅ |
| 9 | Android feed + menu + app shell | ✅ |
| 10 | Testing, debug drawer, docs & demo script | ✅ |

## Known gaps / honest limitations

- **Manual DI, not Hilt.** `:app`'s `AppContainer` wires dependencies by
  hand. Every class already takes its dependencies through its constructor
  in the shape Hilt would inject into; adding Hilt itself would mean
  retrofitting `@Inject`/`@HiltViewModel` across five phases of already-tested
  modules, which wasn't worth the churn against working code.
- **FCM and real Maps tiles need developer-supplied credentials** (see
  above) -- the code compiles and every test passes without them.
- **No emulator was used for verification.** Android-side testing is
  Robolectric-on-JVM throughout (DAOs, ViewModels, the merge engine,
  WorkManager via `TestListenableWorkerBuilder`); Compose screens compile
  and are exercised through their ViewModels but weren't visually verified
  on a running device.
- **Single-device assumption**, as called out in DESIGN.md §18: no
  per-device sync cursors, and client-owned fields (delivery note, tip)
  use plain LWW rather than anything stronger.
