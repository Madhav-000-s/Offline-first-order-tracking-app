# DESIGN.md — Offline-First Order Tracking (Android + FastAPI)

**Status:** architecture locked, implementation not started
**Scope:** full vertical slice — Compose client, Room-as-SSOT sync engine, FastAPI/Postgres backend, simulated courier fleet
**Target:** portfolio project defensible in a deep-dive interview on *both* halves

---

## 0. The thesis

One sentence to defend in the room:

> **The network is a suggestion. Room is the truth. Every screen renders from a `Flow` off a DAO, and every byte that arrives from REST, WebSocket, or FCM is funnelled through a single writer that merges it into SQLite. The UI has no idea the network exists.**

Everything below is downstream of that. If a design choice would let a network response reach a `@Composable` without passing through Room, it's wrong.

The second differentiator: **there is no stub server.** The backend is real, the courier is simulated server-side, and the failure modes (lost responses, out-of-order pushes, clock skew) are exercised end to end rather than hand-waved.

---

## 1. Non-goals

Stating these explicitly is worth as much as the features, because it shows the boundary was drawn deliberately rather than by fatigue.

| Out of scope | Why |
|---|---|
| Real payments | PCI surface, zero architectural learning, mock a `PaymentIntent` state instead |
| Driver-side app | The courier is simulated server-side; a second app doubles work for no new concepts |
| Production auth (OAuth/social, MFA) | JWT + refresh rotation demonstrates the interceptor/`Authenticator` problem, which is the interesting part |
| Restaurant/merchant portal | Seed data via Alembic + a fixtures script |
| Offline map tiles | Google Maps SDK doesn't support it cleanly; offline behaviour degrades to "last known marker + timeline", which is the honest answer |
| Multi-device conflict on the same account | Single-device assumption; the merge rules are still written to be safe if it happened |
| Foreground service for background tracking | FCM is the Android-idiomatic channel for server-pushed state. A foreground service would only be justified if we owned the *courier* device. |

---

## 2. Client module graph

Gradle multi-module, feature-sliced, mirroring the Now-in-Android shape (interviewers recognise it, and it forces the layering to be real rather than aspirational).

```
:app                      — Application, Hilt root, nav host, FCM service
:core:model               — pure Kotlin domain models, no Android deps
:core:common              — Result wrapper, dispatchers, Clock, error types
:core:database            — Room: entities, DAOs, migrations, converters
:core:network             — Retrofit, OkHttp, DTOs, WebSocket client, auth
:core:datastore           — Proto/Preferences DataStore: session, sync cursor, prefs
:core:designsystem        — theme, tokens, reusable Compose components
:core:data                — repositories, the OrderWriter/merge engine, mappers
:core:testing             — fakes, test rules, Turbine helpers
:sync                     — WorkManager workers, SyncManager, outbox drain
:feature:feed             — restaurant list (Paging 3 + Coil)
:feature:menu             — restaurant detail + cart
:feature:orders           — order list + detail timeline
:feature:tracking         — Maps + live courier
```

**Dependency rule:** `:feature:*` → `:core:data` → (`:core:database`, `:core:network`, `:core:datastore`). Features never touch `:core:network` or `:core:database` directly. `:core:model` is a leaf that everything can see.

**Why multi-module:** the honest reason is build parallelism is irrelevant at this size — the real reason is that module boundaries are *compiler-enforced* layering. `:feature:tracking` physically cannot call Retrofit. That's a stronger claim than "we have a repository package."

---

## 3. Layering and data flow

```
┌──────────────────────────────────────────────────────────────┐
│ Compose screen  — stateless, hoisted, takes UiState + lambdas │
└───────────────▲──────────────────────────┬───────────────────┘
                │ StateFlow<UiState>       │ user intents
┌───────────────┴──────────────────────────▼───────────────────┐
│ ViewModel — UDF: single immutable UiState, sealed Intent,     │
│             Channel<Effect> for one-shots (nav, snackbar)     │
└───────────────▲──────────────────────────┬───────────────────┘
                │ Flow<Domain>             │ suspend fun
┌───────────────┴──────────────────────────▼───────────────────┐
│ Repository (interface in :core:model, impl in :core:data)     │
│   reads  : ALWAYS Room Flow                                   │
│   writes : Room first → outbox → WorkManager                  │
└───────────────▲──────────────────────────┬───────────────────┘
                │                          │
        ┌───────┴────────┐        ┌────────▼─────────┐
        │ Room (SSOT)    │◄───────┤   OrderWriter    │
        └────────────────┘  merge └────────▲─────────┘
                                           │
                        ┌──────────────────┼──────────────────┐
                        │                  │                  │
                   REST (Retrofit)   WebSocket (OkHttp)   FCM data msg
```

**The single-writer rule.** `OrderWriter` is the only class in the app permitted to `INSERT`/`UPDATE` the `orders` table from remote data. REST responses, WS frames, and FCM payloads all call `OrderWriter.apply(remote)`. This is what makes the three-channel convergence tractable — there is exactly one place where "is this update stale?" is decided.

---

## 4. Room schema (client)

```kotlin
@Entity(tableName = "orders", indices = [Index("server_id", unique = true), Index("status")])
data class OrderEntity(
    @PrimaryKey val localId: String,        // client UUID, stable forever
    val serverId: String?,                  // null until first successful sync
    val idempotencyKey: String,             // == localId; explicit for clarity
    val restaurantId: String,
    val status: OrderStatus,                // enum, see FSM below
    val serverVersion: Long,                // 0 for never-synced
    val placedAtLocal: Instant,             // device clock, display only
    val serverUpdatedAt: Instant?,          // server clock, authoritative for merge
    val totalMinor: Long,                   // integer minor units, never Double
    val currency: String,
    val syncState: SyncState,               // PENDING_CREATE | SYNCING | SYNCED | FAILED
    val lastError: String?,
    val etaAtServer: Instant?
)

@Entity(tableName = "order_items", foreignKeys = [/* cascade from orders.localId */])
data class OrderItemEntity(
    @PrimaryKey val id: String,
    val orderLocalId: String,
    val menuItemId: String,
    val nameSnapshot: String,               // denormalised: menu changes must not rewrite history
    val unitPriceMinor: Long,
    val quantity: Int
)

@Entity(tableName = "order_events")          // append-only timeline, drives the tracking UI
data class OrderEventEntity(
    @PrimaryKey val id: String,              // server event id, or "local:$uuid"
    val orderLocalId: String,
    val status: OrderStatus,
    val occurredAt: Instant,                 // server clock
    val note: String?
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,                  // "order", "order_cancel", "fcm_token"
    val entityLocalId: String,
    val operation: String,                   // CREATE | CANCEL | UPDATE
    val payloadJson: String,
    val createdAt: Instant,
    val attemptCount: Int = 0,
    val nextAttemptAt: Instant,
    val lastError: String? = null
)

@Entity(tableName = "restaurants")           // Paging-backed feed cache
@Entity(tableName = "menu_items")
@Entity(tableName = "remote_keys")           // (restaurantId, prevKey, nextKey)
@Entity(tableName = "courier_last_known")    // (orderId, lat, lng, bearing, recordedAt)
@Entity(tableName = "sync_cursor")           // (resource, cursor, lastSyncAt)
@Entity(tableName = "sync_log")              // debug drawer: every merge decision, ring-buffered
```

Deliberate choices worth defending:

- **`localId` is the primary key, not `serverId`.** An order exists before the server has ever heard of it. Making the PK server-assigned means either a nullable PK or a row-identity change on first sync, which breaks every `Flow` and `LazyColumn` key downstream. This one decision is what makes offline creation clean.
- **Money is `Long` minor units.** Never `Double`, never `Float`. Currency stored alongside.
- **`nameSnapshot` on order items.** Historical orders must not mutate when the restaurant renames a dish. Denormalisation here is correctness, not laziness.
- **`serverUpdatedAt` is separate from `placedAtLocal`.** Device clocks are wrong. Merge decisions use server time and `serverVersion` exclusively; device time is display-only.
- **`sync_log` table.** A debug drawer that shows the last N merge decisions ("rejected WS v6, local at v7") is the single best interview demo in the project. It makes the invisible sync engine visible in fifteen seconds.

**Migrations:** every schema change ships an explicit `Migration` plus a `MigrationTest` using `MigrationTestHelper` against exported schema JSON. `fallbackToDestructiveMigration()` is banned — being able to say "I never used it" is the point.

---

## 5. The order state machine

```
                    ┌──────────────┐
     PLACED ──► ACCEPTED ──► PREPARING ──► READY ──► PICKED_UP ──► DELIVERED
        │            │           │           │           │
        └────────────┴───────────┴───────────┴───────────┴──► CANCELLED
        │
        └──► REJECTED
```

Ordinals: `PLACED=0, ACCEPTED=1, PREPARING=2, READY=3, PICKED_UP=4, DELIVERED=5`.
Terminal states: `DELIVERED`, `CANCELLED`, `REJECTED`.

The FSM is **server-authoritative and monotonically forward** for the non-terminal path. This is the property the merge rule exploits.

---

## 6. Merge / conflict resolution

Three channels deliver the same fact with different latencies and no ordering guarantee between them. A naïve last-write-wins produces visible status flicker (`PICKED_UP` → `PREPARING` → `PICKED_UP`) when a slow REST response lands after a fast WS frame.

```kotlin
fun merge(local: OrderEntity?, remote: RemoteOrder): MergeResult {
    if (local == null) return MergeResult.Insert(remote.toEntity())

    // 1. Version guard — the primary defence. Server version is monotonic per row.
    if (remote.version <= local.serverVersion) return MergeResult.RejectStale

    // 2. Status guard — defence in depth against a server bug or a replayed event.
    val nextStatus = when {
        remote.status.isTerminal              -> remote.status   // terminal always wins
        local.status.isTerminal               -> local.status    // never leave a terminal state
        remote.status.ordinal >= local.status.ordinal -> remote.status
        else                                  -> local.status    // reject regression, log it
    }

    // 3. Client-owned fields (delivery note, tip) use LWW on serverUpdatedAt,
    //    because the server echoes back whatever the outbox last pushed.
    return MergeResult.Update(local.copy(
        serverId       = remote.id,
        status         = nextStatus,
        serverVersion  = remote.version,
        serverUpdatedAt= remote.updatedAt,
        etaAtServer    = remote.eta,
        syncState      = SyncState.SYNCED,
        lastError      = null
    ))
}
```

**Why not LWW everywhere:** because order status is not an opaque value, it's a position in a monotonic FSM. LWW throws away that structure and buys visible UI regressions in exchange for nothing. The version guard alone would suffice if the server were perfect; the ordinal guard is the seatbelt.

**Why the terminal escape hatch:** `CANCELLED` has to beat `PICKED_UP` despite a lower conceptual position, so terminality is checked before ordinality.

Every rejection writes a `sync_log` row. Silent conflict resolution is unobservable and therefore undebuggable.

---

## 7. Offline write path — the outbox

This is the load-bearing mechanism for "survives airplane mode mid-order."

**Placing an order offline:**

1. `PlaceOrderUseCase` generates `localId = UUID.randomUUID()`.
2. **Single Room transaction:** insert `orders` (`syncState = PENDING_CREATE`, `serverVersion = 0`, `status = PLACED`), insert `order_items`, insert a local `order_events` row, insert an `outbox` row with the serialised request. Transactional atomicity means we can never have an order without its outbox entry, or vice versa.
3. The DAO `Flow` emits. The order appears in the list **instantly**, badged "Waiting to send".
4. `SyncManager.enqueueOutboxDrain()` — `enqueueUniqueWork("outbox", APPEND_OR_REPLACE, request)` with `NetworkType.CONNECTED`.
5. Airplane mode: WorkManager holds the job. No polling loop, no wakelock, no battery cost. This is the whole reason to use WorkManager rather than a home-grown retry loop.

**On reconnect:**

6. Constraint satisfied → `OutboxDrainWorker` runs. It drains **serially, in insertion order**, because order-cancel must not overtake order-create.
7. `POST /v1/orders` with header `Idempotency-Key: <localId>`.
8. Response → `OrderWriter.apply()` → fills `serverId`, bumps `serverVersion`, `syncState = SYNCED`, replaces optimistic status with canonical. Outbox row deleted **in the same transaction** as the merge.
9. The badge disappears. No screen was ever told to refresh — Room emitted, Compose recomposed.

**The interesting failure:** the server created the order but the response was lost (connection dropped after commit). The client retries and would double-place. The idempotency key makes the retry return the *original* response, so it reconciles to the same `serverId`. This is the scenario worth walking an interviewer through, because it's the one everybody's stubbed-JSON project cannot answer.

**Retry policy:** exponential backoff with jitter, `attemptCount` persisted in the outbox row. Classification matters:
- `5xx`, timeout, IO → retry, `Result.retry()`
- `409 Conflict` (in-flight idempotent request) → retry after `Retry-After`
- `4xx` other than 409/429 → **permanent failure**. Mark `syncState = FAILED`, keep the row, surface it in the UI with a manual retry action. Silently discarding a user's order because the server said 422 is unacceptable.
- After N attempts (say 10, ~hours of backoff) → `FAILED`, user-visible.

---

## 8. Downstream sync — delta protocol

```
GET /v1/sync?cursor=<opaque>&limit=200
Authorization: Bearer <access>

200 {
  "changes": {
    "orders":      [ {...OrderDto, "version": 7, "deleted": false}, ... ],
    "restaurants": [ ... ]
  },
  "next_cursor": "eyJ0cyI6...",
  "has_more": false,
  "server_time": "2026-07-27T10:12:03.881Z"
}
```

- The cursor is an **opaque server token** encoding a keyset `(updated_at, id)` tuple, base64'd. Never a client-supplied timestamp — that hands correctness to the device clock.
- Keyset pagination, not `OFFSET`, so rows mutating mid-scan can't cause skips.
- **Tombstones** (`deleted: true`) are required; without them a cancelled/purged row lives forever in the client cache.
- Applied in one Room transaction per page: merge all rows, then persist `next_cursor`. Crash mid-page → cursor unchanged → page replays → merges are idempotent by the version guard. This is why the merge must be idempotent.

**Triggers:**
| Trigger | Work type |
|---|---|
| App foreground | one-shot expedited |
| FCM data message | one-shot expedited |
| Periodic | `PeriodicWorkRequest`, 15 min, `CONNECTED` + `BatteryNotLow` |
| Manual pull-to-refresh | one-shot expedited, unique `REPLACE` |
| WS gap detected (`seq` jump) | one-shot expedited |

All of these enqueue the *same* unique work name `delta_sync` with `KEEP` so a burst of FCM messages collapses into one run.

---

## 9. WebSocket — the accelerator, never the truth

`wss://.../v1/ws/orders?token=<access>` (token in the first frame, not the query string, so it doesn't land in server access logs).

**Envelope:**
```json
{"v":1,"type":"courier_position","seq":42,"order_id":"...","ts":"...","data":{"lat":12.97,"lng":77.59,"bearing":118.4,"speed_mps":6.1}}
{"v":1,"type":"order_status","seq":43,"order_id":"...","version":7,"data":{"status":"PICKED_UP"}}
{"v":1,"type":"pong","seq":44}
```

**Lifecycle:** connected only when a tracking screen is at `Lifecycle.State.STARTED` **and** at least one non-terminal order exists. Implemented as a `WebSocketSession` held by a scoped component, driven from `repeatOnLifecycle`. Backgrounded → disconnect. Background updates are FCM's job.

**Reconnect:** exponential backoff with full jitter, capped at ~30s. Heartbeat: client ping every 20s, server must pong within 10s or we tear down. OkHttp's `pingInterval` handles the protocol-level case; an application-level ping additionally proves the server's event loop isn't wedged.

**Gap detection:** monotonic `seq` per connection. A jump means frames were dropped → enqueue a delta sync so REST repairs the hole. This is the concrete expression of "WS is an accelerator": it is allowed to be lossy because a stronger channel backstops it.

**Position handling — a deliberate asymmetry:**
- `order_status` frames → `OrderWriter` → **Room**. It's durable truth.
- `courier_position` frames → **in-memory `StateFlow` only**, keyed by order. Persisting 1 Hz GPS to SQLite is pure write amplification for data that's worthless thirty seconds later.
- Exception: throttled to one write per ~15s into `courier_last_known`, so a cold start shows the marker at roughly the right place instead of jumping from the restaurant.

---

## 10. FCM

**Data-only messages.** Never `notification` payloads — those bypass the app when backgrounded and rob us of the ability to route the update through `OrderWriter`.

```json
{"data": {"type": "order_status", "order_id": "...", "version": "7", "status": "PICKED_UP"}}
```

`FirebaseMessagingService.onMessageReceived`:
1. Build and post the user-visible notification from the payload (fast path, feels instant).
2. **Do not trust the payload as truth.** Enqueue expedited `delta_sync`. The payload is a *hint*; the authoritative write comes from the REST fetch.

Rationale: FCM has no ordering or delivery guarantee, and payloads can arrive out of order or duplicated. Treating the push as a hint rather than a write keeps the number of writers at one.

**Token lifecycle:** `onNewToken` → insert into outbox (`fcm_token`) → drained by the same worker → `POST /v1/devices`. Token registration is just another offline-capable mutation; it gets the same machinery for free, which is a nice payoff from the outbox abstraction.

Priority: `high` for status transitions (they're user-visible and time-sensitive), `normal` for anything else, to stay within FCM's high-priority quota heuristics.

---

## 11. Restaurant feed — Paging 3

`RemoteMediator<Int, RestaurantEntity>` + `PagingSource` from Room. Network writes pages into SQLite; the UI pages off SQLite. Offline → the feed still renders from cache and `LoadState.append` reports the error non-fatally.

```kotlin
Pager(
  config = PagingConfig(pageSize = 20, prefetchDistance = 5, initialLoadSize = 40),
  remoteMediator = RestaurantRemoteMediator(api, db),
  pagingSourceFactory = { db.restaurantDao().pagingSource() }
).flow.cachedIn(viewModelScope)
```

- `remote_keys` table stores the server cursor per row so `LoadType.APPEND` knows where to resume across process death.
- `LoadType.REFRESH` clears `restaurants` + `remote_keys` **in one transaction** with the insert, so there's never an empty-list flash.
- `initialLoadSize` deliberately 2× page size — first paint should overfill the viewport.

**Why `RemoteMediator` rather than a network-only `PagingSource`:** because offline-first is the thesis. A network `PagingSource` shows a spinner in airplane mode; a `RemoteMediator` shows the last-known feed. The cost is the `remote_keys` bookkeeping, which is the tradeoff to name out loud.

**Coil:** `AsyncImage` with crossfade, explicit `placeholder`/`error`, stable `memoryCacheKey` derived from the image URL, and a disk cache sized ~64 MB. Coil gets its **own** `OkHttpClient` — sharing the API client would attach the JWT `Authenticator` to CDN requests, which is both wasteful and a token-leak vector.

---

## 12. Maps and marker motion

- `GoogleMap` composable from `maps-compose`. Marker state hoisted so a position tick recomposes the marker, not the map.
- **Interpolation:** raw 1 Hz positions look like a stuttering teleport. Animate between the last and current position with a linear interpolator over the inter-arrival interval; rotate the icon to the bearing with shortest-angle wrapping (the 350° → 10° case).
- **Camera:** follow-mode on by default, auto-disabled the moment the user pans, with a "recenter" FAB. Never fight the user's gesture.
- **Route polyline:** server returns an encoded polyline with the order; the client decodes and draws it once. The courier marker snaps to the nearest point on that polyline, which hides GPS jitter for free.
- **Performance:** `derivedStateOf` for camera bounds, and the position `StateFlow` is throttled/conflated so a burst of frames can't outpace the frame budget.
- Degraded state: no location yet → show restaurant and destination pins plus the timeline, not an empty map.

---

## 13. Networking and auth (client)

- **Retrofit + OkHttp + kotlinx.serialization.** Retrofit over Ktor client specifically for OkHttp's `Authenticator`, interceptor ecosystem, and MockWebServer maturity — and because the same OkHttp instance serves the WebSocket, so one connection pool, one TLS config, one auth story.
- **Auth interceptor** attaches `Bearer <access>`.
- **`Authenticator`** handles 401 → refresh → retry. Guarded by a `Mutex` so twelve concurrent 401s produce **one** refresh call, not twelve. Refresh-token rotation: each refresh returns a new refresh token; reuse of an old one invalidates the family server-side.
- Tokens in `EncryptedSharedPreferences` (or DataStore + Tink). Never plain `SharedPreferences`.
- Timeouts: 15s connect, 30s read, 30s write. Retry-on-connection-failure enabled.
- `HttpLoggingInterceptor` at `BODY` in debug only, with header redaction.

---

## 14. Backend — FastAPI

```
backend/
  app/
    main.py                 — app factory, lifespan (Redis pool, simulator tasks)
    core/                   — config (pydantic-settings), security, deps, logging
    api/v1/routers/         — auth, restaurants, orders, sync, devices, ws
    db/
      base.py, session.py   — async engine, session-per-request dependency
      models/               — SQLAlchemy 2.0 declarative
    schemas/                — Pydantic v2 request/response models
    services/               — order_service, sync_service, push_service, idempotency
    realtime/               — redis pubsub, connection manager, courier simulator
    workers/                — arq/asyncio tasks for state advancement
  alembic/
  tests/
```

**Stack:** Python 3.12, FastAPI, SQLAlchemy 2.0 async + asyncpg, Alembic, Postgres 16, Redis 7, `firebase-admin`, `uvicorn`. Docker Compose for the whole thing so it's one command to run in a demo.

### 14.1 Server schema

`users`, `restaurants`, `menu_items`, `orders`, `order_items`, `order_events`, `idempotency_keys`, `devices`, `refresh_tokens`.

Every synced table carries:
```sql
version    BIGINT NOT NULL DEFAULT 1,   -- bumped on every mutation
updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
deleted_at TIMESTAMPTZ NULL             -- soft delete → tombstone
```
with `CREATE INDEX ... ON orders (updated_at, id)` to make the keyset sync cursor an index scan.

`version` is bumped in the **same statement** as the mutation (`UPDATE ... SET version = version + 1`), never read-modify-write from Python. That's what makes the client's version guard trustworthy under concurrency.

### 14.2 Idempotency

```sql
CREATE TABLE idempotency_keys (
  user_id UUID NOT NULL,
  key TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  status TEXT NOT NULL,          -- 'in_flight' | 'completed'
  response_status INT,
  response_body JSONB,
  created_at TIMESTAMPTZ DEFAULT now(),
  expires_at TIMESTAMPTZ,
  PRIMARY KEY (user_id, key)
);
```

Flow on `POST /v1/orders`:
1. `INSERT ... ON CONFLICT DO NOTHING RETURNING key` to *claim* the key atomically.
2. Claimed → execute the order creation **in the same transaction** as flipping the row to `completed` with the stored response. Atomicity here is the whole point: the response is durably recorded iff the order was created.
3. Not claimed, existing row `completed` → return the stored response verbatim (same status, same body).
4. Not claimed, existing row `in_flight` → `409` + `Retry-After: 1`. The client retries.
5. Not claimed but `request_hash` differs → `422`. Same key, different payload is a client bug and must be loud.

Reaper task purges rows past `expires_at` (24h).

### 14.3 Realtime fanout

Multiple uvicorn workers means an in-process connection registry is wrong the moment you scale past one. Redis pub/sub is the fix:

```
order_service.transition(order, PICKED_UP)
   ├─ UPDATE orders SET status, version = version+1   (transaction)
   ├─ INSERT order_events
   ├─ COMMIT
   ├─ redis.publish(f"order:{id}", {...})   → any worker holding that WS forwards it
   └─ push_service.send(order.user_id, {...})  → FCM
```

Publish happens **after commit**, never inside the transaction. Publishing before commit means a client can fetch via REST and see stale data — the classic dual-write ordering bug.

`ConnectionManager` per worker: `dict[order_id, set[WebSocket]]`, plus one Redis subscriber task per worker feeding it.

### 14.4 Courier simulator

An `asyncio` task per active order, started on transition to `PICKED_UP`:
- Loads the planned polyline (precomputed at order creation, from a fixture route set — no external routing API dependency, so the demo works on a plane too).
- Walks it at ~6 m/s, emitting a position every second with small Gaussian jitter and a computed bearing.
- Publishes to `order:{id}`.
- On arrival → `transition(DELIVERED)`, task exits.

State advancement (`PLACED → ACCEPTED → PREPARING → READY → PICKED_UP`) runs on timers with configurable durations, plus **a dev-only endpoint `POST /v1/dev/orders/{id}/advance`** so a demo doesn't require waiting eight minutes. That endpoint is the thing you actually click during the interview.

### 14.5 Endpoints

```
POST   /v1/auth/register, /login, /refresh, /logout
GET    /v1/restaurants?cursor=&limit=          — keyset paginated feed
GET    /v1/restaurants/{id}/menu
POST   /v1/orders                              — Idempotency-Key required
GET    /v1/orders/{id}
POST   /v1/orders/{id}/cancel                  — Idempotency-Key required
GET    /v1/sync?cursor=&limit=                 — delta, all resources, with tombstones
POST   /v1/devices                             — FCM token registration (upsert)
WS     /v1/ws/orders
POST   /v1/dev/orders/{id}/advance             — dev builds only
GET    /healthz, /readyz
```

---

## 15. Concurrency, threading, error model

- `Dispatchers` injected via a `@Qualifier`-annotated provider; `Dispatchers.IO` never hardcoded in a class. Makes every repository test deterministic with `UnconfinedTestDispatcher`.
- `withContext(io)` lives in the repository, not the DAO — Room's suspend functions already dispatch correctly, and double-wrapping is noise.
- Domain errors are a sealed hierarchy (`AppError.Network`, `.Unauthorized`, `.Validation`, `.Conflict`, `.Unknown`), mapped from HTTP at the network boundary. Nothing above `:core:data` sees an `HttpException`.
- Repository reads return `Flow<T>`; writes return `Result<T>` (Kotlin `Result` or a custom `Outcome`). Never throw across the repository boundary for expected failures.

---

## 16. Testing strategy

**Client**
| Layer | Tooling | Non-obvious test worth writing |
|---|---|---|
| DAO | in-memory Room, Turbine | Flow emits on insert *from another coroutine* |
| Migrations | `MigrationTestHelper` + exported schemas | every version pair |
| Merge engine | pure JUnit | stale version rejected; status regression rejected; CANCELLED beats PICKED_UP; merge is idempotent when applied twice |
| Repository | MockWebServer + real in-memory Room | offline create → reconnect → single row with serverId (the headline test) |
| Workers | `TestListenableWorkerBuilder`, `WorkManagerTestInitHelper` | outbox drains in order; permanent 4xx doesn't retry forever |
| ViewModel | `runTest` + Turbine | state after connectivity flip |
| UI | Compose test + Robolectric | "waiting to send" badge appears and clears |

**Backend**
- `pytest` + `pytest-asyncio` + `httpx.AsyncClient` against the ASGI app.
- **Testcontainers Postgres** — not SQLite. The schema uses `JSONB`, `TIMESTAMPTZ`, and `ON CONFLICT`; testing against a different engine tests a different program.
- Idempotency concurrency test: fire N concurrent identical `POST /v1/orders` with `asyncio.gather`, assert exactly one order row and N identical responses. This is the test that proves the claim.
- Sync cursor test: mutate rows mid-pagination, assert no row is skipped.

**End-to-end chaos**: an instrumented test that places an order behind a `MockWebServer` returning failures, flips a fake connectivity source, and asserts final reconciled state. Optionally toxiproxy in the Compose stack for latency/partition injection.

---

## 17. Build phases with validation gates

Each phase ends with a demonstrable thing. Don't start a phase until the previous gate passes.

**Phase 0 — prove the loop (the only phase that matters)**
Scope: one screen, one entity. Hardcoded auth. No maps, no WS, no FCM, no Paging.
Build: Room `orders` + `outbox`, `OrderWriter`, `OutboxDrainWorker`, FastAPI `POST /v1/orders` with idempotency + `GET /v1/sync`.
**Gate:** place an order in airplane mode, kill the app, re-open (order still there), enable network, watch it reconcile to a server id — and prove a forced duplicate `POST` creates exactly one row. If this doesn't work, nothing built on top of it will.

**Phase 1 — the real vertical**: auth + refresh, restaurants/menu, cart, order list + detail timeline, delta sync of all resources, sync-status banner.
**Gate:** cold start with no network renders the full app from cache.

**Phase 2 — realtime**: WebSocket, Redis pub/sub, courier simulator, server-side state advancement, dev advance endpoint.
**Gate:** status changes propagate in <1s; kill the WS mid-order and confirm REST repairs the gap.

**Phase 3 — maps**: Maps SDK, interpolated marker, polyline, camera follow, `courier_last_known` cold start.
**Gate:** marker motion is smooth at 1 Hz input.

**Phase 4 — push**: FCM data messages, token outbox, notification channel, deep link into tracking.
**Gate:** app swiped away → status change → notification → tap → correct order, correct state.

**Phase 5 — feed at scale**: Paging 3 + `RemoteMediator` + `remote_keys`, Coil, seeded 500+ restaurants.
**Gate:** scroll to page 10, airplane mode, kill, relaunch — cached pages still there.

**Phase 6 — polish for the deep dive**: sync log debug drawer, chaos tests, Compose Preview/screenshot coverage, README with the architecture diagram, and a 3-minute demo script.

---

## 18. What to say in the room

The five things to have loaded and ready:

1. **"The PK is the client UUID."** One decision, and offline creation stops being a special case.
2. **"Idempotency key, and here's the lost-response scenario."** Walk the double-charge failure and how the server's claim-then-execute transaction closes it.
3. **"Three channels, one writer, version + ordinal guard."** Explain why LWW would produce visible status flicker and why you rejected it.
4. **"WebSocket is lossy on purpose."** Sequence gaps trigger a REST repair. Durability lives in Room; the socket only buys latency.
5. **"Publish after commit."** Shows you've thought about dual-write ordering, which is a backend-seniority signal from a mobile candidate.

And one thing to volunteer unprompted, because it reads as maturity rather than weakness: **the single-device assumption**. Say what would change for multi-device — per-device sync cursors, and the client-owned fields needing something stronger than LWW.

---

## 19. Open questions

1. **Auth scope** — is refresh-token rotation with a reuse-detection family worth building, or is a long-lived access token acceptable given payments are out of scope? Rotation is ~half a day and is a talking point; it's also not the project's thesis.
2. **`arq`/Celery vs bare `asyncio` tasks** for server-side order advancement. Bare tasks are simpler and die with the process (fine for a demo); a real queue survives restarts. Leaning bare `asyncio` + a startup reconciler that re-arms timers for in-flight orders — same robustness, no extra infra.
3. **Hosting** — local Docker Compose only, or deploy (Fly.io/Railway + Neon) so the APK works on a reviewer's device without running your backend? Deploying materially raises the "they actually shipped it" signal.
4. **Feed pagination key** — offset-based is simpler for a static restaurant list; keyset is consistent with the sync protocol. Consistency probably wins so there's one pagination story to defend.
