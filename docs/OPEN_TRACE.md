# Reading the open trace

Why a board sits on "Loading posts…" when the production was opened before and
its posts are on disk. One block of log lines per production open, tagged
`Open`, written by `core:common` — `OpenTrace`.

The question it exists to answer is which **gate** the wait is in. The board's
own disk read is a few milliseconds and is scoped to one production, so the
posts of a dozen other shoots cost it nothing; what costs time is what has to
happen before that read is usable. The gates, in the order a trace shows them:

| Line | Gate | What it means when it is the slow one |
|---|---|---|
| `cached context short: profile missing…` | The context cache | Nothing kept for this production: it has never been opened on this machine, or somebody signed out since. `ProjectCache` is not pruned by age at all, so this is rarer than it looks — a warm context over an empty board is the usual shape, and the two rows below are where that comes from. |
| `nothing cached … the open waits` + `first-open context fetch took …` | Cold open | `onProjectOpened` suspends until profile, record, crew and remote config answer. `profile` / `production record` / `crew list` are timed inside it — they run in parallel, so the cost is the slowest. |
| `project token (none held) took …` | Per-production token | These are held in memory only, so the first call into a production after a launch mints one, and every other call of that open queues behind it on that production's mint lock. |
| `tab strip from disk took …` / `no tab strip on disk` | The tab strip | **The one to look for.** Kept posts cannot be drawn until the board knows its tab ids, and the strip and the posts live in different stores, pruned on their own. A missing strip over a full board store means a round trip with every post already on disk — the spinner condition is `isLoadingUnits && tabs.isEmpty()`. Both stores are now pruned by **when the production was last opened** (`ProjectVisits`), 30 days, so this should no longer be a production somebody still works on; a strip missing while the board store is full now means the admin/user key split below, not retention. |
| `posts from disk took …` / `rows in the board store` | The board store | Which of the two stores answered: the notice table (every page ever read, merged) or the kept newest-page row (one window). |
| `tab strip from the server took …`, `posts from the server (…) took …` | The network legs | Compare against a warm open where nothing else is in flight. A leg that is slow only while `notification seed`, `crew list` and the rest overlap it is queueing, not a slow server: the engine is OkHttp on its defaults, five connections per host, and an open fires a dozen calls at one host. The seed is one listing across **every** production a person is on, so it grows with how many that is. |
| `landing tab arrived late — re-reading …` | A wasted read | The saved landing tab rides on the profile, which on a cold open lands after the strip: the board read whichever tab came first and now has to read the real one too. Also logged as a warning under `HomeBoard`, so it survives in a field log outside a trace. |
| `… — board on screen` | The end | What finally drew it, and the total. |

Every line carries the production as a tail (`…ee1234`) and its offset from the
open, so lines that arrive out of order still read in order. A mark outside a
trace is dropped — a board is re-read on every socket arrival for the rest of a
session, and tracing those would bury the block somebody opened the log to
read.

## Where the lines are

`~/.zillit/logs/zillit.log` (and `zillit.log.1`). `Open` is in
`ZillitLogging.FIELD_TAGS`, so these survive at INFO on a release build — the
question is only ever asked about somebody else's machine. Nothing in them
names a person or a production beyond the tail.

## Shape of a trace

Illustrative, not measurements — this is what to expect to see, with the
numbers from whichever machine the log came off:

```
…ee1234 +0ms production selected
…ee1234 +4ms cached context: profile, record and 38 crew
…ee1234 +4ms cached context read took 4ms
…ee1234 +5ms cached context published — the open does not wait
…ee1234 +9ms board load begins
…ee1234 +14ms tab strip from disk took 5ms
…ee1234 +15ms tabs drawn from disk (6)
…ee1234 +18ms 25 rows in the board store
…ee1234 +19ms posts from disk took 4ms
…ee1234 +19ms 25 posts from disk — board on screen
```

A trace that instead runs

```
…ee1234 +2ms cached context short: profile missing, record missing, crew 0
…ee1234 +3ms nothing cached for this production — the open waits
…ee1234 +612ms profile took 609ms
…ee1234 +1206ms crew list took 1203ms
…ee1234 +1210ms first-open context fetch took 1207ms
…ee1234 +1888ms project token (none held) took 674ms
…ee1234 +1904ms no tab strip on disk — the spinner stands until the server answers
```

is the pruned-cache case, and the fix is the cache, not the network.

## Known, and not fixed: the admin/user key split

The tab strip's route is `home/unit/admin` for an admin and `home/unit/user`
for everybody else, picked live off the profile
(`HomeFeedRepositoryImpl.unitsUrl`), and the route *is* the cache key. So on an
open where the profile has not landed yet, an admin's kept strip is looked for
under the user key and missed — and the live read then asks for the user strip
too, which is not the tab set an admin should see.

Giving the strip one stable `cacheAs` name, the way the board's newest page has
one, would fix the miss, at the cost of an admin briefly seeing the non-admin
strip from disk before the live read corrects it. Worth doing only once a trace
says this is really what people are hitting — which is now a line in the log:
a missing strip beside `N rows in the board store`.

## What this does not measure

Time spent in OkHttp's own queue, as distinct from the server being slow. It is
inferable — the same call is quick on an open where nothing overlaps it — but
attributing it properly needs an OkHttp `EventListener` timing
`callStart → connectionAcquired`, which means configuring the engine and so
belongs with its own look at `OkHttpEngineProvider`.
