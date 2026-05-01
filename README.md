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

## Build plan

This project is being built in 10 phases, each ending in a demonstrable gate.
See [DESIGN.md §17](./DESIGN.md#17-build-phases-with-validation-gates) for details
on the underlying phase gates this plan is derived from.

| # | Phase |
|---|---|
| 1 | Repo scaffolding |
| 2 | Backend foundation (config, models, migrations, auth) |
| 3 | Backend orders & delta sync |
| 4 | Backend realtime (WebSocket, Redis pub/sub, courier simulator) |
| 5 | Android core modules (model, common, database, datastore) |
| 6 | Android network & data layer (repositories, `OrderWriter` merge engine) |
| 7 | Android offline write path (outbox, `WorkManager`) |
| 8 | Android orders + tracking features (maps, FCM) |
| 9 | Android feed + menu + app shell |
| 10 | Testing, debug drawer, docs & demo script |
