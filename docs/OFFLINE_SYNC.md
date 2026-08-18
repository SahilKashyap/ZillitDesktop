# Offline sync — the foundation (`core:sync`)

Phases 1–2 of the offline Account Hub plan
(see `OFFLINE_ACCOUNT_HUB_FEASIBILITY.md`): the machinery, and the first two
modules on it (purchase orders, timecards).

## Pieces

| Piece | Where | What |
|---|---|---|
| Durable store | `core:database` — `SyncDatabase`, file `~/.zillit/zillit-sync.db` | Two tables: `syncOperation` (the outbox) and `localDraft`. Same keychain key as the cache, **different schema policy**: `SchemaPolicy.MigrateForward` — `user_version`-driven forward-only migrations, verified at build time (`verifyCommonMainSyncDatabaseMigration`), never dropped, and a file from a newer build is refused rather than touched. The cache keeps `RebuildOnChange`. |
| Outbox engine | `core:sync` — `SyncEngine`, `OutboxStore`/`SqlOutboxStore` | One worker; runs the open production's operations one at a time in the order `SyncScheduling` says; wakes on enqueue, network return, backoff expiry, or `wake()`. |
| Handlers | `core:sync` — `SyncHandler`, `SyncHandlerRegistry` | Per-`kind` executors that modules register. The engine knows nothing about cash or POs. |
| Retry | `core:sync` — `RetryPolicy` | `NoConnection`/`Timeout`/5xx → `RetryLater` (exponential from 5 s, cap 5 min, ±20% jitter). Everything else → `Failed`, parked for the user. |
| Connectivity | `core:sync` — `ConnectivityMonitor` | Fed by every `ApiClient` call (`onOutcome`), the socket coming up, and a `HEAD` probe on the API host every 15 s while offline. Starts optimistic. |
| Drafts | `core:sync` — `DraftStore`/`SqlDraftStore` | Per (user, project, kind) JSON drafts that survive restarts. |
| Shell | `desktopApp` — `SyncStatusUi.kt`, `AppShell.statusAction` | Status bar: "Offline — N changes will be sent when you're back", "N changes waiting to send", "N changes need attention"; click → **Pending changes** dialog (Retry / Discard / Sync now). Settings → Sign out warns when unsent work would be lost. |

Wired in `AppGraph`: `connectivity`, `syncEngine` (null when the durable store
could not be opened → online-only, as before), `draftStore`. The engine's
scope is `HeaderContext.(userId, projectId)`; a production switch calls
`wake()`.

## Phase 2 pilot — purchase orders and timecards (2026-08-18)

Both modules take an `OfflineSupport` (engine + drafts + online flag); null
keeps them exactly as before. With it wired:

| | Purchase orders | Timecards |
|---|---|---|
| Handler(s) | `po.create` — `PoSyncHandler`: **looks before creating** (same vendor, description, total, not older than the form) among `myOrders()`, adopts the id; creates otherwise and reads back to adopt | `timecard.save` — `TimecardSaveHandler`: the week's Monday is the natural key — PATCHes an existing week for that `week_starting`, POSTs otherwise, reads back to adopt the id. `timecard.submit` — `TimecardSubmitHandler`: uses the payload id, else the save's result (`dependsOn`), else a lookup by week |
| Write path | offline → enqueue; online → direct, and a `NoConnection` failure (request never left) enqueues; timeout/4xx → reported, form kept | same for Save; Submit of a local-only week or while offline enqueues behind the week's save (`groupKey = timecard:<monday>`, `dependsOn`) |
| Local rows | My Orders / All Orders show queued orders first — "Waiting to send" / "Not sent" pill, no number, detail explains; no server actions | My Timecards shows queued weeks — "Waiting to send" / "Submitting when online" / "Not sent"; only Submit is offered, once |
| Draft on disk | `po.draft` per (user, production), debounced 400 ms, restored on open if nothing typed, forgotten on raise | `timecard.draft` per week, restored over the server copy only if newer than it, forgotten on save |
| Read cache | `po.orders.<tab>`, `po.vendors` | `timecard.list.<tab>`, `timecard.metadata`, `timecard.allowances` |
| Banner | "You're offline — showing orders saved <date time>" | same for timecards |
| After sync | when the queue shrinks while online the list reloads, so the local row is replaced by the server's | same |

Not offline (unchanged): approvals, rejections, posting, closing, payroll
processing, deductions, bulk actions — anything on someone else's record.

**Also part of the pilot, found by the on-screen run (2026-08-18):**
- The **Film Tools grid** is now kept per production (`home.tools`, `HomeViewModel`)
  and drawn from its saved copy with a dated banner when the network is gone —
  without it there was no way *into* a tool offline (`project/tools` was
  network-only). Cache written after each successful rights fetch.
- A list with **no saved copy** but with local rows (never fetched online in
  this install) shows the local rows over an empty list rather than an error
  page; cache keys are per *request*, so My Orders and Raise (both
  `myOrders()`) share one, as do My Timecards and the editor.
- The **purchase-order wire** was wrong on the desktop and the first live
  create landed with no vendor, £0.00 and status "Unknown". Fixed against
  Android's `CreatePORequest`/`PurchaseOrderResponse`: body sends `vendor_id`,
  `currency` (default GBP), `net_amount`, `status` (`PENDING`, or
  `ACCT_ENTERED` when accounts raise it) and `line_items[{description,
  quantity, unit_price, total, account, tax_rate}]`; the DTO reads
  `gross_amount`/`net_total`/`net_amount`/`gross_total` and `line_items`
  (array *or* JSON string); `PoStatus` uses the server's vocabulary
  (`DRAFT PENDING ACCT_ENTERED QUEUED APPROVED REJECTED POSTED CLOSED
  CANCELLED`); vendor names are resolved from the vendor list, as Android does.
  Pinned by `PoWireShapeTest`.

**Known, not done:**
- The desktop **timecard `save()` day payload** predates the server's day
  schema (Android's `overlayDay`: upper-case `day_type`, epoch `call_time`/
  `wrap_time`, `minutes_worked`, `basic_hours`, `rates_ots`, `meals`, week
  totals from the OT engine). The server accepts the week (seen live: created
  as Draft) but stores no hours. This is the timecard module's own wire gap
  and needs its own port; the outbox part is proven.
- The timecard tool reads only the slim `my-summary` projection and never
  fetches the full week on selection, so days/hours show as 0 in the detail.
- `WorkspaceViewModel.restoreSession()` exists but nothing calls it — tabs
  are not restored on launch (the plan lists workspace persistence as unread).
  Offline, a tool is reached through the cached grid instead.

## Phase 3 — every screen viewable offline, per production (2026-08-18)

The ask: with no network, every tool shows what it showed the last time it was
opened in that production, never another production's data; a production
never opened on this computer says so instead of opening; chat sends queue
with a clock and go by themselves when the network is back.

### The read cache — one mechanism for every module

Rather than porting ~20 modules one by one, the cache sits at the one place
every read already passes through: **`ApiClient`**.

- Every successful **GET** whose module is `Default`, `Project`, `Chat`,
  `Media` or `ProjectUser` is kept as its whole envelope in the encrypted local
  database (`cachedRead` table, `SqlReadCache`), keyed
  `userId | projectId | GET | url | sorted query`. The same identity that
  signs the request scopes the cache, so **production A's answers cannot
  surface in production B** by construction; a `CallOptions.projectId`
  override keys under that production.
- When the same read fails with `NoConnection`/`Timeout`, the kept envelope
  stands in and the caller cannot tell — its list simply renders. A **refusal
  (4xx/5xx) is never answered from the cache**: "forbidden" from the server
  must not become "here is a saved list".
- A read whose URL carries something that is not part of the question — the
  Home board's newest page is `chat/<unit>/<now>/previous`, likewise the
  Drafts folder and the newest page of call history — names itself with
  `CallOptions(cacheAs = …)`, so a later "now" finds the page kept under the
  earlier one. Without it the cache would keep answers nobody asks for again.
  The same name is the one way a **POST** is kept: a POST that is really a
  read (the mail server's `get-emails` takes its ids in the body) says so by
  naming itself, and the name carries everything the body asks for. PUT and
  DELETE are never kept, whatever they call themselves.
- Never kept: `Device` (a stale device/session check could revive a revoked
  device), `Configuration` (the credential bundle — secrets stay in the
  keychain), socket/notification bookkeeping, and any call with
  `CallOptions(readCache = false)`. Bodies over 4 MB are skipped; entries
  older than 30 days are pruned at start. Cache semantics: rebuilt on schema
  change, gone with the key on sign-out.
- The status bar says "Offline — showing what's saved on this computer; …".
- Pinned by `ReadCacheTest` (kept/served, project isolation, refusal not
  cached, query part of key, device/config/opt-out never kept, writes never
  kept).

What this does **not** cover: bytes that never go through `ApiClient` —
attachments, images, PDFs from S3/Box, chat voice notes (a blob cache is its
own piece of work); the **Budget Builder**, which is a hosted web app in an
embedded browser fetching from its own service — its launch page now says
"needs a connection" and the button does nothing until the network is back;
the socket (live updates, calls); email attachments (`get-attachment`).
Email bodies *are* covered: `get-emails` is a POST that is really a read, so
it names itself with `cacheAs` — a message opened online opens again offline;
the mailbox cache keeps folders and summaries. Writes still
fail with "No internet connection" except the queued ones (PO create,
timecard save/submit, chat send).

### Opening a production offline

`AuthViewModel.selectProject` refuses, without opening, when the API cannot be
reached **and** the production has no saved profile/record on this computer
(`ProjectContextLoader.hasCached`): the picker shows **"No offline data
available for this production. Connect to the internet to open it."** A
production seen before opens from cache as usual.

### Chat: send with a clock

`ChatSendState.Queued` — the clock. Offline (or when the socket cannot carry
the message: `NoConnection`), a text message is put in the outbox as
`chat.send` (`ChatSendHandler` → `ChatRepository.send`; the socket already
keys on the client `uniqueId`, so a retry cannot duplicate) with the
message's own id, `groupKey = chat:<peer>` so a thread's messages leave in
order. The bubble wears the clock; it follows the outbox (tick when sent, the
failure mark if the server refuses); queued bubbles are restored when the
thread is reopened, even after a restart. The engine wakes when the socket
comes up, so queued messages go the moment the connection is back.
Attachments written offline still fail as before (upload first is its own
port).

### Verified on screen, 2026-08-18 (SG Document Distribution, dead-proxy launch)

Warmed online, then relaunched behind `-Dhttps.proxyPort=9`; 146 reads
answered from the cache in the first pass. What each area showed offline:

| # | Area | Offline |
|---|------|---------|
| 1 | Project list | From `ProjectListCache`; a production never opened here → red banner "No offline data available for this production. Connect to the internet to open it." (tried on *Test1*) |
| 2 | Home | Bulletin / Calendar (events) / Call Sheet boards, avatars fall back to initials (images are not cached) |
| 3 | Account Hub | Production Setup, Vendors, Chart of Accounts; the "add a currency" picker is hidden (reference catalogue is a `Device`-module read, not kept) |
| 4 | Box schedule | The diary, days and events |
| 5 | Drive | File list (bytes are S3, not offline) |
| 6 | Email | Inbox summaries from the mailbox cache **with the "No internet connection" strip above the list, not instead of it** (fixed today); a message opened online opens again; Drafts |
| 7 | Chats | Groups + Direct messages, thread history from disk; **send → clock**, "1 change waiting to send"; back online it went by itself (`Sync: chat.send attempt 2: Done`), double tick |
| 8 | Budget Builder | "hosted application and needs a connection" notice, Open does nothing |
| 9 | Tools list | 42-tool grid, search works |
| 10 | Permissions | Derived from the cached grid/profile; write buttons stay, writes say no connection |
| 11 | Documents & Signature | Envelopes lists (empty here, no error), library |
| 12 | Document Distribution | Library folders |
| 13 | Schedule D.O.D. | The two PDFs listed |
| 14 | Call Sheet Creation | Drafts/Approvals/Published lists |
| 15 | Production Report | Drafts/Approvals/Published lists |
| 16 | Deal Memo | "No deal on file yet" (same as online) |
| 17 | Card Expenses | My Transactions / My Card / Card Extension |
| 18 | Cash Expenses | Overview and tabs |
| 19 | Script & Pages Distribution | The document, Pages tab |
| 20 | Sides | The side and its script |
| — | Purchase Orders, Timecards, Payroll | Lists from cache (PO overview: 2 open) |
| — | Calls history | Newest page (named) |
| — | Another production (Ios Developer Team) | Its own units, its own 31-tool grid, its own contacts and inbox — nothing of SG DD's |

Fixes the sweep produced: the Home board's newest page, the Drafts folder,
call history and chat history name themselves (`cacheAs`) so a "now" URL
still hits; `get-emails` is kept as a named POST; the Email screen keeps the
cached list under a failed-refresh strip; the DM list is remembered per
production and unions with threads kept on disk (the socket's `USER_LIST`
never answers offline); the chat send handler treats "no open project" /
"encryption key not yet loaded" as *retry* — the engine runs the moment a
production's scope exists, seconds before the socket and the credential
bundle, and the first pass after reconnecting used to fail the message
outright; the engine's log line now says why an attempt failed.

## How a module queues work

```kotlin
// 1. Register a handler once, at wiring time.
handlers.register(object : SyncHandler {
    override val kind = "cash.requestFloat"
    override suspend fun execute(operation: SyncOperation, context: SyncContext): SyncOutcome {
        val request = json.decodeFromString<NewFloatRequest>(operation.payload)
        return repository.requestFloat(request).toSyncOutcome(policy)   // Done / RetryLater / Failed
    }
})

// 2. Enqueue instead of calling the repository when offline (or always, if
//    the module wants every send to be durable).
engine.enqueue(
    NewOperation(
        kind = "cash.requestFloat",
        label = "Float request: £250 for location petty cash",  // what the pending list shows
        payload = json.encodeToString(request),
        groupKey = "float-$localId",      // strict FIFO with anything else about this record
        dependsOn = createOp.id,          // optional: must be Done first; its result is readable
    ),
)
```

### The handler contract

1. **Idempotent where it can be.** An attempt may have reached the server and
   timed out on the way back. Send `operation.id` as the server's idempotency
   key wherever the backend accepts one (deal memo `_id` today; cash/card/PO/
   timecard once phase 0 lands), and where it does not, look before you create.
2. **Classify honestly.** `RetryLater` only for what time fixes. A 4xx is
   `Failed` — the user must see it. `toSyncOutcome()` does this for you.
3. **Verify, don't trust.** Some Account Hub endpoints answer success and store
   nothing; when it matters, read the record back before `Done`.
4. **Leave what dependents need.** `Done(result = serverId)`; the next
   operation in the group reads it via `context.dependencyResult(op)`.
5. **Record partial progress.** An upload that succeeded before the create
   failed goes into the payload via `context.updatePayload(...)`, so the retry
   does not upload twice.
6. **Label for a human.** The label is fixed at enqueue time and shown with or
   without the module loaded.

### Rules the engine enforces

- Only the **open production's** operations run; others are counted
  ("N changes waiting in other productions") and run when it is opened. A
  request signed for another production would be wrong.
- Within a `groupKey`: strict creation order; a `Failed` or in-flight head
  holds the rest.
- `dependsOn` must be `Done`; discarding an operation discards its dependents.
- Rows left `IN_FLIGHT` by a crash are requeued at start; a cancellation
  mid-attempt writes the row back to pending before propagating.
- `Done` rows are kept 7 days (for dependents), then pruned.
- Sign-out clears the keychain, which makes the durable file unreadable — it
  is recreated empty. That is intentional (a shared machine keeps nothing
  readable), and why Settings warns first.

## Schema changes

Add `src/commonMain/sqldelight-sync/.../N.sqm`, edit the `.sq`, run
`./gradlew :core:database:generateCommonMainSyncDatabaseSchema`, and commit the
new `databases/N+1.db`. `verifyMigrations` fails the build if the migration
does not reproduce the `.sq` schema. Never edit an existing `.sqm`.

## Testing

`core:sync` tests use the real `SqlOutboxStore` over an in-memory JDBC driver.
Note for anyone writing engine tests: `kotlinx-coroutines-test`'s
`advanceUntilIdle()` only runs while *foreground* tasks remain, so an engine on
`backgroundScope` never runs under it — use `runCurrent()` and
`advanceTimeBy()`, which do run background work.
