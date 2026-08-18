# Offline Account Hub — feasibility (2026-08-17)

**Question:** can the desktop Account Hub work offline — draft locally with no
connectivity, send to the server when it returns?

**Verdict: feasible, but it is a new subsystem, not a feature toggle — and one
part of it is not ours to decide (the backend has to accept client-minted ids
on creates). Nothing in the current structure blocks it; almost nothing in the
current structure provides it. Neither the phones nor the web do any of this
today — the only offline path in any Zillit client is chat's pending-message
queue.**

## What the current structure gives us

| Need | Today | Verdict |
|---|---|---|
| Clean seams to intercept writes | Every finance module is MVVM with a repository *interface* (7 modules, ~455 server operations, ~28k lines) — cash, card, PO, timecard, payroll, deal memo, account hub | ✔ An offline layer sits behind the interfaces; screens untouched |
| Encrypted local store | `core:database` — SQLCipher + SQLDelight, key in the OS keychain | ✔ The right place for drafts and a queue (the plaintext preferences store is explicitly forbidden for anything sensitive) |
| Requests can be built later | `moduledata` (with its timestamp) and `bodyhash` are built **inside `ApiClient` at send time**; nothing is stored with the body | ✔ Queue the tuple (verb, url, module, body **JSON string**, local id) and replay through `ApiClient`; it signs correctly hours later. Store the string, not a re-encoded object, so `bodyhash` matches |
| Retryable vs terminal errors | `ZillitError`: `NoConnection`/`Timeout` (retry) vs `TlsFailure`/4xx/`Validation` (never) | ✔ Taxonomy already right for a queue |
| An "online" signal | Socket `connectionState`; chat's `connections` flow fires on every transition to Connected; shell already shows "Offline — updates paused" | ✔ Ready-made flush trigger. Caveat: socket-up ≠ every REST host reachable; needs a small probe |
| Precedent in miniature | Home board: optimistic post with client `localId` **sent to the server**, `NoticeSendState` Failed → Retry, completed uploads memoised so retry doesn't re-upload; Chat: `uniqueId` dedupe, acked id swap | ✔ The pattern exists — but all in memory; kill the app and a Failed card is gone |
| A dirty-vs-saved state machine | Account Hub setup: `SectionEdit<T>(saved, edited, saving)`, `committed()` re-snapshots from the server **echo**, edits survive a failed save | ✔ Exactly the model a draft store wants; it just has no disk behind it |
| Draft models already isolated | `PoDraft` → `NewPurchaseOrder`; `TimecardDraft` (nullable `timecardId`; `save()` already does POST-or-PATCH); cash `SubmitDraft`/`FloatRequestDraft`/`CodingDraft`; `NewDeal` | ✔ Plain data classes — trivially `@Serializable` |

## What is missing

1. **No finance module persists anything locally.** None of the seven depends on
   `core:database`. A failed save is a toast; the draft lives only in the
   ViewModel and dies with the process.
2. **The local database is a disposable cache.** Drop-and-rebuild on any schema
   change (`reconcileSchema`, `verifyMigrations = false`, no `.sqm`), and
   `ZillitDatabaseFactory.open()` **deletes the file** if it fails to open with
   the current key ("everything in it is refetchable"). Its own comment names
   the trigger: *"when something non-recoverable is stored here — an outbox,
   unsent drafts — this must become real forward-only migrations."* Both
   behaviours must change (or drafts go in a separate `outbox.db` with
   migrations) before the first draft is written.
3. **No outbox / queue / replay / backoff.** No `HttpRequestRetry`, no queue,
   no idempotency key on any finance endpoint. To build: operation table,
   dependency ordering (create → attach → submit), backoff, resume across
   restarts, per-record sync state in the UI, "discard" path.
4. **No idempotency on creates (backend).** Cash, card, PO and timecard creates
   are server-assigned ids only, and on desktop `submitReceipts`/`create`
   **return `Unit`** — the new id is only knowable by re-fetching. A retry of a
   create that landed but timed out makes a duplicate. **Deal memo is the
   exception**: the server accepts a client-minted Mongo `_id` on create
   (backend email 2026-07-27; a collision is a 409 the client adopts) — proof
   the backend can do it, but for one endpoint. Without extending that
   contract, an offline queue is unsafe for money records.
5. **Local → server id remap.** Cash/card are keyed batch → claim → line: you
   can draft a batch offline, but coding its lines (`PATCH …/claims/{claimId}/code`,
   `save-claims`) needs the server's ids, so it must wait for the create round
   trip. Chart-of-accounts children need a server `parent_id`. PO, timecard and
   deal memo are single-body creates and need no remap.
6. **Attachments — desktop has no file layer in finance at all.** The receipt
   field is a text box: *"Paste or drop the uploaded file reference"*; the key
   is mandatory before submit. Offline receipts therefore need (a) a real
   picker, (b) an encrypted local blob store, (c) a two-phase flush — upload
   bytes to S3 (SigV4 direct, as Home/email do) → obtain key → substitute into
   the queued body → POST. Timecard attachments additionally need the week to
   exist first.
7. **Reference data offline.** Vendors, departments, cost codes, episodes,
   currencies, banks, allowances, approvers, day types are all fetched live.
   Every dropdown source must be cached, with an age shown ("as of 3 days
   ago").
8. **Server-side truth we cannot fake.** PO numbers, batch references,
   approval state, float balances. Drafts show "DRAFT" / "Pending sync"
   (Android already renders an empty `po_number` that way); nothing that
   depends on a real number proceeds offline.
9. **Whole-set replacement writes clobber.** `save-claims`, `PUT
   receipts/{id}/line-items`, payroll `nominal`, and every Account Hub setup
   slice PATCH replace the full set — a stale queued body overwrites newer
   server state. No versioning/ETags on these APIs (last-write-wins). Fine for
   my-own drafts; wrong for shared records → those stay online-only.
10. **Side-effect params.** Deal memo `?notify=` would notify the crew member
    twice on a retried send; the queue must strip or gate it.
11. **The server sometimes answers 1 and stores nothing** (seen live on Account
    Hub PATCHes). Sync must verify by re-reading, never trust the status.
12. **Timestamp staleness.** The client puts no bound on `time_stamp` in
    `moduledata`; whether the server rejects a stale one is unknown — ask.

## Two present-day bugs — fixed 2026-08-17

Both lost the user's typing on a failed save even *online*:
`CashExpensesViewModel.submitFloatRequest()` and `saveCoding()` cleared their
drafts before the server answered. `act()` now takes an `onSuccess` state
transform, applied only after a successful call; pinned by
`DraftSurvivesFailedSaveTest`.

## Recommended scope

- **In:** create/edit **my own drafts** and **queue the submit** — purchase
  orders, timecard weeks, cash expense batches (+ receipts), card expense
  receipts/coding, deal memo drafts. Last-fetched lists browsable read-only.
- **Out (online-only):** approvals/rejections/overrides, reconciliation,
  payroll processing/posting, float postings, top-ups, bulk actions, Account
  Hub setup slices, anything acting on someone else's record.

## Phased plan (rough, one engineer)

| Phase | What | Effort | Needs |
|---|---|---|---|
| 0 | **Backend:** accept a client `_id`/`unique_id` on cash/card/PO/timecard creates like deal memo; return the id in the create response; confirm stale-timestamp policy | backend | **blocks money-safe retries** |
| 1 | **Done 2026-08-17** — see `OFFLINE_SYNC.md`: `SyncDatabase` (own file, forward-only migrations, verified), `core:sync` engine (outbox, groups, dependencies, backoff, park/retry/discard, crash recovery, per-production scope), `ConnectivityMonitor` (every REST call + socket + probe), status bar + Pending changes dialog, sign-out warning; 22 engine tests + 4 migration tests | done | — |
| 2 | **Done 2026-08-18, verified on screen** — purchase orders + timecards on the outbox (see `OFFLINE_SYNC.md`): look-before-create handlers with id adoption, queued create/save/submit with dependency, local "Waiting to send" rows, drafts on disk, dated read caches, tools grid from cache; 40+ module tests. Live: an order raised offline appeared as PO-0002 (vendor, £20.00, Awaiting approval) on reconnect; a week saved offline was created on the server. Found and fixed on the way: the desktop PO create body/DTO/status did not match the backend. Left open: the desktop timecard day payload predates the server's schema (hours not stored) — its own port. Money-safe against a create that landed and timed out only by content/week matching until phase 0 lands | done | 1 (0 still open) |
| 3 | Cash + card expenses — real picker, encrypted blob store, two-phase upload, batch → claim id remap, coding queued behind create; fix the two draft-loss bugs first | ~2.5–3 weeks | 2 |
| 4 | Deal memo drafts (has `_id`; gate `notify`), read caches for the rest of the hub | ~1 week | 2 |
| — | Payroll, reconciliation, approvals, setup slices | not offline | — |

Roughly **8 weeks** for phases 1–4 after the backend contract exists, plus QA
on the sync edge cases (kill during upload, two devices, project switch
mid-queue, sign-out with a non-empty queue, key rotation).

## Open questions for the backend team

1. Will `POST` for claims, receipt batches, POs and timecard weeks accept a
   client-supplied `_id`/`unique_id` and return the existing record on a
   repeat (as deal memo does)? Can the create response carry the new id?
2. Is any `updated`/version field accepted back on PATCH so a stale draft gets
   a 409 instead of silently overwriting?
3. Does the server bound `moduledata.time_stamp` age?
4. Can receipts be uploaded to a *staging* prefix and adopted on create, so an
   abandoned offline draft leaves no orphan objects?
