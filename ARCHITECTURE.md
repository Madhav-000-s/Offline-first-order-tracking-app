# ARCHITECTURE.md — the system as built

**What this document is.** A description of what the code actually does, verified
against the running system: the source, the emulator, the device database, and the
backend logs.

**How it differs from [DESIGN.md](./DESIGN.md).** That document is the *design
intent*, written while the project was being planned and built. It describes some
things that were designed and never wired — most visibly a three-channel merge
including FCM, a Hilt dependency-injection root, and a single unique work name
shared by every sync trigger. None of those are in the code. §16 of this document
reconciles the two.

Where the two disagree, this one has been checked.

---

## Table of contents

1. [The problem](#1-the-problem)
2. [System shape](#2-system-shape)
3. [Client module graph](#3-client-module-graph)
4. [The data model](#4-the-data-model)
5. [Read path — Room as the single source of truth](#5-read-path--room-as-the-single-source-of-truth)
6. [Write path — the transactional outbox](#6-write-path--the-transactional-outbox)
7. [The merge engine](#7-the-merge-engine)
8. [Delta sync](#8-delta-sync)
9. [Realtime — the WebSocket](#9-realtime--the-websocket)
10. [Authentication](#10-authentication)
11. [Backend](#11-backend)
12. [Decision log](#12-decision-log)
13. [Bugs found and fixed](#13-bugs-found-and-fixed)
14. [Built vs. wired](#14-built-vs-wired)
15. [Testing and verification](#15-testing-and-verification)
16. [Where DESIGN.md is stale](#16-where-designmd-is-stale)
17. [Known limitations](#17-known-limitations)

---

## 1. The problem

A food-delivery order tracker where the network is optional.

That constraint creates the actual engineering problem: **two databases that can
disagree.** SQLite on the phone, PostgreSQL on the server. Add two delivery
channels with different latencies and no ordering guarantee between them, and a
client that must keep working with no connectivity at all.

Every mechanism in this project — the outbox, the version column, the merge
engine, idempotency keys, the opaque cursor — exists to answer that one problem.

The property that makes it tractable: **the merge is idempotent by construction**,
so at-least-once delivery is sufficient everywhere. Nothing needs exactly-once,
which is the expensive one.

---

## 2. System shape

```
┌──────────────────────── PHONE ─────────────────────────┐
│                                                         │
│   Compose UI                                            │
│      │ user action                    ▲ renders via Flow│
│      ▼                                │                 │
│   PlaceOrderUseCase ──┐               │                 │
│                       │ ONE transaction                 │
│                       ▼               │                 │
│  ┌────────────── Room / SQLite ───────┴───────────────┐ │
│  │ orders · order_items · order_events · sync_log     │ │
│  │ restaurants · menu_items · remote_keys             │ │
│  │ outbox · sync_cursor · courier_last_known          │ │
│  └──┬─────────────────────────────────▲───────────────┘ │
│     │ reads due entries               │ only writer of  │
│     ▼                                 │ remote data     │
│  OutboxDrainWorker            OrderWriter → MergeEngine │
│     │        └──── response ──────────┘        ▲        │
│     │ POST + Idempotency-Key                   │        │
└─────┼──────────────────────────────────────────┼────────┘
      ▼                                          │
   ┌──┴───────────────── SERVER ─────────────────┴──────┐
   │  FastAPI (async)  ↕  PostgreSQL  ↕  Redis pub/sub  │
   └────────────────────────────────────────────────────┘
```

Two directions, and the asymmetry matters:

- **Down** (server → phone): REST delta sync and WebSocket frames both funnel
  through `OrderWriter`
- **Up** (phone → server): actions land in an outbox table; a worker delivers them;
  **the response comes back down through `OrderWriter`** like any other remote data

That last point is what makes "exactly one writer of the orders table" literally
true rather than approximately true.

---

## 3. Client module graph

14 Gradle modules. Arrows point downward only; nothing below depends on anything
above.

```
                        ┌─────────┐
                        │  :app   │  shell, nav, AppContainer, login
                        └────┬────┘
              ┌──────────────┼──────────────┬──────────────┐
              ▼              ▼              ▼              ▼
      ┌──────────────┐ ┌──────────┐ ┌──────────┐ ┌────────────────┐
      │:feature:feed │ │  :menu   │ │ :orders  │ │   :tracking    │
      └──────┬───────┘ └────┬─────┘ └────┬─────┘ └───────┬────────┘
             │              │            │               │
             │              └─── :sync ──┘               │
             │                     │                     │
             └───────────┬─────────┴─────────────────────┘
                         ▼
                  ┌─────────────┐
                  │ :core:data  │ repositories, OrderWriter, MergeEngine
                  └──────┬──────┘
               ┌─────────┴─────────┐
               ▼                   ▼
      ┌────────────────┐  ┌────────────────┐
      │ :core:database │  │ :core:network  │
      └───────┬────────┘  └───────┬────────┘
              └────────┬──────────┘
                       ▼
            ┌────────────────────────┐
            │ :core:model  :common   │ pure Kotlin, zero Android
            └────────────────────────┘

    :core:designsystem — theme + shared state composables
    :screenshots       — Paparazzi renders, test-only
```

### The rule the graph enforces

`:feature:feed`, `:feature:menu` and `:feature:orders` declare **no dependency on
`:core:network`**. A screen physically cannot call Retrofit — it is a compile
error, not a code-review convention.

`:feature:tracking` is the single deliberate exception: it owns the WebSocket
connection. It still writes only through `OrderWriter`.

`:core:model` and `:core:common` use the `kotlin.jvm` plugin, not the Android
plugin, so they cannot reference Android types even by accident.

### Why `implementation` and not `api`

Every inter-module dependency uses `implementation`, so nothing leaks
transitively. This is load-bearing: `:core:network` keeps Retrofit on
`implementation`, which is why `:app` and `:sync` each declare
`libs.retrofit.core` themselves in order to reference `HttpException`.

---

## 4. The data model

Every synced entity exists in three shapes, and each boundary protects something.

| Type | Module | Purpose |
|---|---|---|
| `OrderDto` | `:core:network` | Exactly what the wire carries. `snake_case`, `updated_at` is a `String`. |
| `OrderEntity` | `:core:database` | Exactly what SQLite stores. Plus **client-only** columns. |
| `Order` | `:core:model` | What the UI thinks in. No annotations from anywhere. |

Translation lives in `:core:data`'s mappers — the one module that sees both
database and network.

### `syncState` and `lastError` are client-only

The server has no opinion on them. They answer "where does this row stand with
respect to the server", which is what drives the *Waiting to send* / *Failed*
badges.

### The single most important decision: `localId` is the primary key

```kotlin
@Entity(tableName = "orders", indices = [Index("serverId", unique = true), Index("status")])
data class OrderEntity(
    @PrimaryKey val localId: String,   // client-generated UUID
    val serverId: String?,             // filled in later
    ...
)
```

An order exists before the server has heard of it. If `serverId` were the primary
key, an offline order would have no key — and when sync assigned one, the row's
**identity would change**. Every `Flow`, every `LazyColumn` key, every relation
downstream would see a delete plus an insert: flicker, lost scroll position,
spurious animations.

With a client UUID as the key, `serverId` is just a nullable column that gets
populated. Row identity never changes, and **offline creation stops being a
special case**. Everything else in the offline story follows from this.

### Room schema

Ten entities. `version = 1`, `exportSchema = true`, and the exported JSON is
committed — `fallbackToDestructiveMigration()` is banned, because destroying the
database on a schema change would discard unsent outbox rows.

Type converters store `Instant` as epoch millis (`INTEGER`) and enums as their
`name` (`TEXT`). Renaming an `OrderStatus` constant is therefore a data
migration, not a rename.

---

## 5. Read path — Room as the single source of truth

The chain, end to end:

```
SQLite write
      ↓  Room invalidation tracker (SQLite triggers)
Flow<List<OrderWithDetails>>        ← DAO
      ↓  .map { it.toDomain() }
Flow<List<Order>>                   ← Repository
      ↓  .map { UiState(...) }.stateIn(...)
StateFlow<OrdersListUiState>        ← ViewModel
      ↓  collectAsState()
recomposition                       ← Compose
```

**Nothing calls refresh.** A WorkManager job on a background thread writes a row;
the screen redraws. The screen never asked — it subscribed.

Two details that are easy to miss:

- `OrderWithDetails` spans three tables via `@Embedded` + `@Relation`, so the DAO
  method is annotated `@Transaction`. Room runs separate queries and stitches in
  memory; without the transaction, a write landing between them could staple one
  order to another order's items. The generated code watches all three tables, so
  inserting an `order_event` re-emits the orders list.
- `stateIn(..., SharingStarted.WhileSubscribed(5_000), ...)` keeps the database
  subscription alive for five seconds after the last collector leaves, so a screen
  rotation doesn't tear down and rebuild the query. `MenuViewModel` deliberately
  uses `Eagerly` instead, because `submitOrder()` reads `uiState.value`
  synchronously and cannot rely on a flow that may have paused.

### The feed applies the same rule to paging

```kotlin
Pager(
    config = PagingConfig(pageSize = 20, prefetchDistance = 5, initialLoadSize = 40),
    remoteMediator = RestaurantRemoteMediator(api, restaurantDao),
    pagingSourceFactory = { restaurantDao.pagingSource() },   // reads SQLite
).flow
```

The `RemoteMediator` fetches pages and **writes them into SQLite**; the UI pages
off SQLite. A cold start with no network shows the last known feed instead of a
spinner. The cost is the `remote_keys` bookkeeping table.

---

## 6. Write path — the transactional outbox

### The write

```kotlin
db.withTransaction {
    db.orderDao().insertNewOrder(order, items, event)   // what the user sees
    db.outboxDao().insert(outbox)                       // what needs sending
}
```

Both tables live in the same SQLite database, so this is one atomic commit. It
makes two states unrepresentable:

- an order with no outbox row → badged "Waiting to send" forever, never sent
- an outbox row with no order → a phantom order the user never saw

That is why the badge cannot lie. It is a database guarantee.

The row is written in its offline shape: `serverId = null`, `serverVersion = 0`,
`syncState = PENDING_CREATE`. `serverVersion = 0` matters later — when the real
response arrives at version 1, the merge engine's version guard passes.

### The outbox row

```kotlin
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,  // the ordering
    val entityType: String,      // "order" | "fcm_token"
    val entityLocalId: String,
    val operation: String,       // CREATE | CANCEL | UPDATE
    val payloadJson: String,     // the HTTP body, frozen at write time
    val createdAt: Instant,
    val attemptCount: Int = 0,
    val nextAttemptAt: Instant,
    val lastError: String? = null,
)
```

`payloadJson` is the fully-formed request body, serialised the moment the user
tapped. The worker posts bytes decided in the past rather than reconstructing a
request from state that may have changed.

### The trigger

```kotlin
db.withTransaction { ... }
onOutboxEnqueued()   // AFTER the commit, never inside it
```

Enqueuing inside the transaction would let the worker start on another thread and
query the outbox before the commit is visible, find nothing, and exit
successfully — leaving the order stranded until the next periodic tick.

`onOutboxEnqueued` is a lambda rather than a `SyncManager` dependency, which keeps
the write path unit-testable with no WorkManager in the picture.

### WorkManager

WorkManager holds jobs in its own database and hands constraints to the OS
scheduler. The app never checks connectivity; `NetworkType.CONNECTED` means
enqueuing while offline is free.

There are **three unique work slots**, not one:

| Slot | Triggers | Policy | Effect |
|---|---|---|---|
| `outbox_drain` | place / cancel / retry / foreground | `APPEND_OR_REPLACE` | a new write queues behind an in-progress drain rather than cancelling it |
| `delta_sync` | app foreground, WS sequence gap | `KEEP` | a burst collapses into one run |
| `delta_sync` | pull-to-refresh | `REPLACE` | cancels the in-flight one; the user asked for fresh |
| `delta_sync_periodic` | 15-minute tick | `ExistingPeriodicWorkPolicy.KEEP` | **a separate slot** |

The periodic tick uses `enqueueUniquePeriodicWork` with its own name, because a
`PeriodicWorkRequest` cannot share a unique name with a `OneTimeWorkRequest`. A
consequence worth knowing: the periodic sync does **not** collapse with the
one-time ones, so foregrounding the app as the timer fires can run two
`DeltaSyncWorker`s concurrently. Harmless — the merge is idempotent — but wasteful.

### The drain

```sql
SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY id ASC
```

Serial, in insertion order, because a cancel must never overtake the create it
cancels. A retryable failure `return`s rather than continuing the loop, so nothing
jumps the queue. A second guard covers the same hazard: a cancel that finds
`serverId == null` returns `Retryable` instead of failing.

Both workers begin with a session check. Every entry is an authenticated write, and
`401` is a 4xx that isn't 409 — `classifyFailure` reads it as **permanent**, so a
single logged-out drain would mark every pending order `FAILED` on its first
attempt for a reason unrelated to the orders.

### Failure taxonomy

```
Success    → delete the outbox row
Retryable  → 5xx, 409, timeout, IOException
             attemptCount++, Result.retry(), whole batch stops
             after MAX_ATTEMPTS (10) → give up, mark FAILED
Permanent  → any other 4xx. syncState = FAILED, message stored
             deferred (nextAttemptAt += 1 year), NOT deleted
```

"Deferred, not deleted" reflects a stated principle: silently discarding a user's
order because the server said 422 is unacceptable. `RetryFailedWriteUseCase`
re-arms a deferred entry, resetting **both** `nextAttemptAt` (or it never comes
due) and `attemptCount` (or it gives up on the first pass). It restores `SYNCED`
rather than `PENDING_CREATE` when a `serverId` exists, because a failed *cancel*
leaves an order that reached the server perfectly well.

### Idempotency keys

```kotlin
private val OutboxEntity.idempotencyKey: String
    get() = if (entityType == "order" && operation == "CREATE") {
        entityLocalId
    } else {
        "$entityLocalId:${operation.lowercase()}"
    }
```

Three properties:

- **Deterministic, not random** — the same entry retried five times must send
  byte-identical keys, or it isn't an idempotency key
- **Per-entry, not per-order** — see [§13](#13-bugs-found-and-fixed)
- The CREATE key *is* `entityLocalId`, because the server persists it as
  `client_local_id`, which is how `OrderWriter` matches the response back to a
  local row that has no `serverId` yet

---

## 7. The merge engine

`MergeEngine.decide()` is a **pure function** — no Room, no coroutines, no
network. `OrderWriter` is the thin I/O shell that reads the current row, asks for
a decision, applies it, and records it to `sync_log`.

That split is why the engine has fast unit tests with nothing mocked.

```kotlin
// 1. Version guard — primary defence. Server version is monotonic per row.
if (remote.version <= local.serverVersion) return MergeDecision.RejectStale(...)

// 2. Status guard — defence in depth against a server bug or a replayed event.
val nextStatus = when {
    remoteIsTerminal -> remote.status      // terminal always wins
    localIsTerminal -> local.status         // never leave a terminal state
    remote.status.ordinal >= local.status.ordinal -> remote.status
    else -> local.status                    // regression rejected, logged
}

// 3. LWW only where the server merely echoes what the outbox pushed
```

**`<=`, not `<`.** Equal versions carry equal content; re-applying is wasted work
and a `sync_log` lie.

**Terminal is checked before ordinal.** `CANCELLED` has a higher ordinal than
`PICKED_UP`, so a plain comparison would accept it *by accident* — but `DELIVERED`
is lower than `CANCELLED`, so a stale `DELIVERED` could beat a cancellation.
Checking terminality first makes the behaviour deliberate.

**A status regression does not discard the whole update.** ETA, tip and totals
still advance; only the status is held, and the decision is logged as
`REJECT_REGRESSION`.

### The WebSocket uses the same function

A live frame carries only `order_id`, `version` and `status` — not enough to build
a row. `OrderWriter.applyStatus` resolves the local row by `serverId`, overlays
those two fields onto it, and calls the same `apply`. Not a parallel
implementation that can drift.

It deliberately does **not** set `serverUpdatedAt` from the device clock: a live
frame doesn't know the server's `updated_at`, and inventing one would put a client
timestamp in a server-owned column.

### `sync_log`

Every decision is recorded with channel, decision, detail and timestamp, and
surfaced in an in-app drawer. Silent conflict resolution is unobservable and
therefore undebuggable.

---

## 8. Delta sync

### The cursor

```json
{
  "orders":      ["2026-08-16T18:41:43.706481+00:00", "032b8fa9-…"],
  "restaurants": ["2026-08-02T11:00:00+00:00",        "7a1e4b62-…"],
  "menu_items":  ["2026-08-02T11:00:03+00:00",        "c9d0e1f2-…"]
}
```

base64-encoded. Three independent keyset positions in one opaque token. Each is a
`(updated_at, id)` pair naming the exact last row delivered for that resource.

**The client never parses or constructs one** — it stores a string and hands it
back. Three consequences:

1. Correctness never depends on the device clock. A phone 30 seconds fast cannot
   silently skip 30 seconds of data, permanently, with no recovery.
2. The cursor's internal shape can change without a client release.
3. Clients cannot invent invalid positions.

An empty page leaves that resource's position untouched, so three resources
advance at independent rates through one token. A `null` cursor decodes to epoch +
zero-UUID, so a fresh install and an incremental sync take the identical code path.

### Keyset, not OFFSET

```python
tuple_(model.updated_at, model.id) > tuple_(cursor_updated_at, cursor_id)
```

SQL row-value comparison. Adding `id` breaks ties, so rows sharing a timestamp
cannot be skipped or duplicated. `OFFSET` re-counts from the start each page, so a
row mutating mid-scan shifts everything.

`limit + 1` determines `has_more` without a second `COUNT`.

### Tombstones

Deletions ride the wire as explicit `deleted: true` rows, because an absent row is
indistinguishable from "unchanged since your cursor". The client partitions
**first**, then applies, so a row upserted by an earlier page and deleted in a
later one converges to gone regardless of iteration order.

A cancelled order is deliberately **not** a deleted one: cancelled stays in the
user's history; tombstoned means purged server-side.

### Crash safety

The cursor is persisted only after every row in the page has merged. Crash
mid-page → cursor unchanged → page replays → merges are idempotent by the version
guard. **At-least-once delivery is sufficient**, which is what keeps the protocol
simple.

---

## 9. Realtime — the WebSocket

Auth arrives in the **first frame**, not the query string, so the token never
lands in a server access log. The client then sends
`{"type": "subscribe", "order_id": …}` per order.

### Per-order sequence numbers

```python
seq = await redis_client.incr(f"seq:order:{order_id}")
await redis_client.expire(key, _SEQ_TTL_SECONDS)
await redis_client.publish(f"order:{order_id}", json.dumps({**message, "seq": seq}))
```

The counter lives in Redis, not process memory, because two workers publishing for
the same order would emit duplicate sequence numbers and the client's gap
detection would be reading noise. `INCR` is atomic and creates the key on first
use.

The 24-hour TTL degrades safely: a client reconnecting after expiry sees a *lower*
`seq`, which never trips the gap check, because a gap is `seq > last + 1`.

### Gap detection

A jump in `seq` emits `WsEvent.GapDetected`, which enqueues a delta sync. **The
socket does not need to be reliable — it needs to be honest about being
unreliable.** Correctness always falls back to the REST protocol.

### Two frame types, opposite policies

| | `order_status` | `courier_position` |
|---|---|---|
| Nature | durable truth | ephemeral, 1 Hz |
| Path | `OrderWriter` → `MergeEngine` → Room | a `StateFlow` |
| Persisted | always | throttled to ~one write per 15s |
| Why | must survive and converge | so a cold start shows the marker roughly right |

Writing 1 Hz GPS into SQLite would be thrashing for no benefit.

An unrecognised status is dropped rather than crashing — an older client simply
waits for delta sync to carry the full row.

### Scope

The socket is owned by the tracking screen's ViewModel. Live status frames
therefore merge only while that screen is open; every other screen converges via
delta sync.

---

## 10. Authentication

- Short-lived access token (15 min), long-lived refresh token (30 days)
- Refresh tokens are **hashed at rest**, so a database dump is not a set of live
  credentials
- Every refresh **rotates**; tokens carry a `family_id`
- Replaying an already-rotated token is treated as theft: the whole family is
  revoked and a full re-login is forced

Client side, `AuthInterceptor` attaches the token; `TokenAuthenticator` handles
`401 → refresh → retry`, guarded by a `Mutex` so twelve concurrent 401s produce one
refresh. The refresh call runs on a *separate* OkHttp client with no authenticator
of its own, or it would recurse.

On a **4xx** from `/auth/refresh` the token is genuinely dead, so the session is
cleared and the app routes back to login. On a **network failure** it deliberately
is not — this is an offline-first app, and signing the user out every time they
open it in airplane mode would be a bug wearing a security costume.

`AuthRepository.logout()` clears tokens, session, and the local database. Room is a
per-account cache: keeping it would show the next person to sign in on the device
the previous user's order history, and would let the drain push their queued writes
under a different account's token. The cost — a queued write is discarded — is
deliberate and beats delivering it as somebody else.

---

## 11. Backend

FastAPI + SQLAlchemy 2.0 async + asyncpg + Alembic, PostgreSQL 16, Redis 7.

Async throughout, so one worker holding hundreds of idle WebSockets does not hold
hundreds of threads.

### The line the whole architecture rests on

```python
version: Mapped[int] = mapped_column(
    BigInteger,
    onupdate=literal_column("version + 1"),   # raw SQL, not a Python value
)
```

Every `UPDATE` gets `SET version = version + 1` appended, computed by Postgres in
the same statement as the mutation. Read-modify-write from Python would let two
concurrent updates both read 5 and both write 6 — two different states sharing a
version — and the client's entire version guard would be built on a lie.

### Idempotency claiming

`INSERT ... ON CONFLICT DO NOTHING` — one atomic statement, four outcomes:

| Result | Meaning | HTTP |
|---|---|---|
| `claimed` | first time; do the work | 201 |
| `replay` | same key, same body, already done | the stored original response |
| `in_flight` | same key, still processing | 409 |
| `conflict` | same key, **different** body | 422 |

The claim commits in **its own short transaction**, deliberately. Sharing the
order-creation transaction would make concurrent callers block on the row lock and
only ever observe the final state, so the documented `in_flight` → 409 path would
be unreachable.

The client's taxonomy lines up exactly: 409 → retryable (someone is mid-flight),
422 → permanent (same key, different body will never succeed). The two sides were
designed as one protocol.

### The FSM, mirrored on purpose

`ORDER_STATUS_ORDINAL`, `TERMINAL_STATUSES` and a full `VALID_TRANSITIONS`
adjacency map live server-side; the client keeps its own ordinal ladder. The
duplication is intentional — **the client cannot trust the server to be
bug-free**, and that redundancy is precisely merge guard #2.

### Realtime fanout

One `subscriber_loop()` task per worker, started in the FastAPI `lifespan`,
psubscribed to `order:*`, forwarding into whatever local sockets that worker holds.
Publishing goes through Redis so a status published by worker B reaches a socket
held by worker A.

### State advancer and courier simulator

Server-side timers walk an order up the ladder (8s / 8s / 12s / 8s); `PICKED_UP`
hands off to a courier simulator that streams positions along a fixture polyline.
These are bare `asyncio` tasks, which die with the process, so
`reconcile_in_flight_orders()` re-arms them at startup. A durable queue (Celery,
arq) is what a production system would use; the comment names the tradeoff rather
than hiding it.

### Endpoints

| Endpoint | Auth | Called by the app |
|---|---|---|
| `POST /v1/auth/register` | — | `AuthRepository` |
| `POST /v1/auth/login` | — | `AuthRepository` |
| `POST /v1/auth/refresh` | — | `TokenAuthenticator` |
| `POST /v1/auth/logout` | — | **never** |
| `GET /v1/auth/me` | ✔ | **never** |
| `GET /v1/restaurants` | — | `RestaurantRemoteMediator` |
| `GET /v1/restaurants/{id}/menu` | — | `RestaurantRepository.refreshMenu` |
| `POST /v1/orders` | ✔ | `OutboxDrainWorker` only |
| `GET /v1/orders/{id}` | ✔ | **never** |
| `POST /v1/orders/{id}/cancel` | ✔ | `OutboxDrainWorker` only |
| `GET /v1/sync` | ✔ | `SyncRepository` |
| `POST /v1/devices` | ✔ | reachable, never triggered |
| `POST /v1/dev/orders/{id}/advance` | — | demo harness only |
| `WS /v1/ws/orders` | first frame | `:feature:tracking` |
| `GET /healthz`, `/readyz` | — | Docker healthchecks |

**No screen ever posts an order.** Screens write to Room; the worker is the only
thing that talks to the server.

### Infrastructure

`docker compose up` brings up Postgres, Redis, a one-shot `migrate` container
running `alembic upgrade head`, and the API — which waits on
`service_completed_successfully`, so the first request can never hit an unmigrated
database.

---

## 12. Decision log

| Decision | Why | Cost accepted |
|---|---|---|
| Room as single source of truth | Offline is the default path, not a feature | Every remote field must be modelled locally |
| `localId` (client UUID) as primary key | Row identity never changes when `serverId` arrives | An extra column and a nullable `serverId` |
| Single writer for remote data | One place decides "is this stale?" | All channels must route through it |
| `MergeEngine` as a pure function | Testable with no mocks | An extra indirection over "just write it" |
| Version guard as primary, FSM as secondary | Redundancy against a server bug | Two guards to keep in sync across languages |
| Transactional outbox | The pending badge cannot lie | A second table and a drain worker |
| Deterministic per-entry idempotency keys | Retries are safe; create/cancel don't collide | Key derivation logic must stay stable |
| Opaque server cursor | Device clock can never cause silent data loss | No client-initiated full resync |
| Keyset over OFFSET | Rows mutating mid-scan can't cause skips | `remote_keys` bookkeeping for paging |
| Tombstones over absence | "Gone" and "unchanged" are distinguishable | Deleted rows must be retained server-side |
| WebSocket as accelerator only | Socket unreliability can't affect correctness | A second code path to maintain |
| Redis for the seq counter | Multi-worker publishes stay monotonic | A Redis round trip per frame |
| `version + 1` in SQL | Monotonic under concurrency | Cannot compute it in Python |
| Manual DI (`AppContainer`) | Every class already takes constructor deps | No compile-time graph validation |
| Modules enforce layering | Architecture violations are compile errors | 14 modules to configure |
| JVM-only tests (Robolectric/Paparazzi) | Deterministic, emulator-free CI | The map screen has no coverage |
| Scoped cleartext config | Dev HTTP works; prod hosts can't silently downgrade | One more XML file |

---

## 13. Bugs found and fixed

These were found by driving the running app, not by reading code. Each is worth
knowing because each is a *composition* failure — individually reasonable
decisions that break when combined.

### `INSERT OR REPLACE` + `ON DELETE CASCADE` = silent data loss

`RestaurantDao.upsertAll` used `@Insert(onConflict = REPLACE)`, which compiles to
SQLite `INSERT OR REPLACE` — implemented as **DELETE the conflicting row, then
insert**. `menu_items` holds a foreign key to `restaurants` with `ON DELETE
CASCADE`.

So every re-upsert of an already-cached restaurant silently deleted all of its menu
items. The feed re-upserts on every page load, so `menu_items` was permanently
empty on the device — 0 rows — no matter how many times delta sync fetched them.
The visible symptom was a blank menu screen for every restaurant.

Fixed with `@Upsert`, which issues a real `UPDATE` on conflict and never fires the
cascade. `refreshPage` compounded it with a `clearAll()` on the first page,
cascading away the whole menu cache on every Pager REFRESH; removed, since removing
rows that no longer exist server-side is the tombstone path's job.

**No exception, no error, the write "succeeds" and the rows are gone.**

### One idempotency key, two request bodies

Create and cancel originally derived the key from the order's `localId` alone, so
both sent the identical key with different bodies. The server correctly read that
as key reuse (422); the client correctly read 422 as permanent. Two correct
components, and **every cancel of an already-synced order failed forever**.

### A complete auth system with no entry point

`AuthInterceptor` attached tokens, `TokenAuthenticator` rotated them, `ApiService`
declared `login`/`register` — and nothing ever called them. `TokenStore.save()` had
exactly one caller: the refresh path, which cannot run without a token to refresh.
On a fresh install every authenticated request went out with no bearer header.

### Cleartext blocked at the platform level

`targetSdk 34` blocks cleartext HTTP, and the manifest declared neither
`usesCleartextTraffic` nor a `networkSecurityConfig`. Every request failed with
`UnknownServiceException` before leaving the device. Fixed with a config scoped to
loopback addresses rather than a blanket flag, so a future build pointing at a
production host cannot silently downgrade.

### A setting that only worked in one of three places

`auto_advance_enabled` was checked in `order_service.create_order`, but the dev
advance endpoint and the startup reconciler both re-armed timers unconditionally.
Setting it false stopped the first timer and nothing else. Gated
`state_advancer.start()` itself — the function all three funnel through.

### Machinery with no trigger

A recurring pattern, all now fixed: the `orders` route was registered but nothing
navigated to it; `"Failed — tap to retry"` had `onClick = {}` and no code reset
`nextAttemptAt`; `RestaurantRepository.refreshMenu` had zero callers;
`ShowSnackbar` effects were emitted by every ViewModel and discarded at all three
call sites; `MenuScreen` had no empty state, so an empty list rendered as a blank
screen.

---

## 14. Built vs. wired

Being precise about this is the difference between confidence and overclaiming.

| Capability | State |
|---|---|
| Offline order placement, outbox, drain | **Working**, verified on an emulator |
| Delta sync, cursor, tombstones | **Working**, verified against the running backend |
| Merge engine, version + FSM guards | **Working**, 15 unit tests |
| Auth: register, login, refresh rotation | **Working** end to end |
| Retry for permanently failed writes | **Working** |
| WebSocket, seq, gap detection | **Built**; exercised by unit tests, not on a device |
| Courier simulator + map tracking screen | **Built**; needs a Maps API key, no automated coverage, never run |
| Redis multi-worker fanout | **Built**; compose runs one API container, so unproven |
| FCM push | **Backend only.** No Firebase dependency, no `FirebaseMessagingService`, no receiver. The outbox carries a token-registration entry type that nothing creates. |
| Hilt | **Not used.** `AppContainer` wires the graph by hand. |
| Server-side logout | **Endpoint exists**, client never calls it |
| Pull-to-refresh | Intent, ViewModel handler and lambda exist; **no gesture emits it** |
| Multi-device sync | Not built; one cursor per account |

---

## 15. Testing and verification

**63 JVM tests across 13 suites**, all on the JVM via Robolectric — DAOs,
ViewModels, the merge engine, and WorkManager via `TestListenableWorkerBuilder`.
No emulator, no device farm.

```bash
cd android && ./gradlew test
cd backend && python -m pytest tests/    # Testcontainers spins up a throwaway Postgres
```

The merge test names are the specification:

```
stale version is rejected outright
equal version is also rejected as stale, not just lower
status regression is rejected but other fields still advance
CANCELLED beats PICKED_UP despite a lower ordinal
once terminal, local never leaves that state
merge is idempotent when the same remote frame is applied twice
first sync of a serverId still resolves via localId before any serverId is known
```

`OrderWriterTest` mirrors them for the WebSocket path — *"a stale version is
rejected by the same guard REST uses"* — proving the two channels share one
implementation.

Screenshots in the README are real Paparazzi renders of the production composables,
JVM-only.

**What tests do not cover:** the map screen (Paparazzi renders Compose; a native
`MapView` isn't Compose), the WebSocket against a live server, and multi-worker
Redis fanout.

### Demo harness

`demo/drive_demo.py` (stdlib only) walks an order up the ladder and foregrounds the
app over `adb` so the change lands on the device — demonstrating the full flow with
no Maps key. `demo/docker-compose.demo.yml` is an opt-in overlay that disables the
server's automatic timers so every transition is one you triggered. Neither changes
default behaviour.

---

## 16. Where DESIGN.md is stale

DESIGN.md is the design intent. These parts describe things that were never wired:

| DESIGN.md says | Reality |
|---|---|
| §0, §3 — "every byte that arrives from REST, WebSocket, or FCM is funnelled through a single writer"; "three-channel convergence" | Two channels. FCM never delivers anything to the client. |
| §2 — "`:app` — Application, **Hilt root**, nav host, **FCM service**" | No Hilt anywhere; no FCM service. `AppContainer` is manual DI. |
| §10 — FCM data messages as sync hints | Backend builds and logs them; no client receiver exists. |
| §8 — "All of these enqueue the *same* unique work name `delta_sync` with `KEEP`" | Pull-to-refresh uses `REPLACE`, and the periodic tick uses a separate slot, `delta_sync_periodic`. |
| §9 — WebSocket lifecycle driven by `repeatOnLifecycle`, "background updates are FCM's job" | Owned by the tracking screen's ViewModel; there are no background updates. |
| §14.5 — endpoint list | Accurate, but four endpoints have no client caller (see §11). |

Everything else — the merge rules, the outbox, the cursor protocol, the schema
decisions — matches the code.

---

## 17. Known limitations

- **Single-worker deployment.** The Redis fanout exists precisely so a status
  published by one worker reaches a socket held by another, but compose runs one
  API container. Designed for, unproven.
- **The idempotency table grows forever.** `expires_at` is written on every row and
  **never read** — no cleanup job, no expiry check in `claim()`. The documented
  24-hour TTL is not enforced. (Which incidentally protects against a real edge:
  if it *were* enforced, an outbox entry retried after 24 hours would find its key
  gone and create a duplicate order.)
- **Single-device sync model.** One cursor per account; client-owned fields
  (delivery note, tip) use plain last-write-wins. Multi-device would need
  per-device cursors and a stronger convergence story for those fields.
- **Logout is client-side only.** The refresh token remains valid server-side until
  it expires.
- **No partial-batch progress in the outbox.** One retryable failure stops
  everything behind it — correct for ordering, but one stuck entry blocks unrelated
  ones.
- **Courier progress is not persisted.** A server restart replays a route from the
  beginning.
- **The tracking screen has never been run**, on a device or in CI.

---

## The one-paragraph version

Room is the truth: every screen subscribes to a DAO `Flow`, so nothing ever calls
refresh. Writes go to an outbox in the same transaction as the row the user sees,
and WorkManager delivers them when the OS says there's a network. Every inbound
byte — a delta-sync page, a WebSocket frame, or the response to the app's own
write — funnels through one merge engine whose primary guard is a version column
that Postgres increments in the same statement as the mutation. Because that merge
is idempotent by construction, at-least-once delivery is sufficient everywhere,
which is what lets the sync protocol stay simple. The Gradle module graph makes
violating any of it a compile error rather than a code-review comment.
