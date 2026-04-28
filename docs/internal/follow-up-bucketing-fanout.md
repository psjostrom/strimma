# Follow-up: ReadingFanOut + ReadingObserver multibinding

This document captures architectural debt deliberately deferred from PR #205
(the bucketed CGM dedup fix for issue #192). It exists so a future engineer can
pick this up cold and ship it.

## Why this is needed

PR #205 introduced two narrow interfaces — `ReadingPusher` and `ReadingUploader` —
so the real `ReadingPipeline` could be exercised in tests without dragging in
`NightscoutPusher`'s and `TidepoolUploader`'s transitive dependencies (HTTP
client, settings store, alert manager, etc.). That solved the immediate
testability problem but left several latent issues:

1. **Split side-effect ownership.** `ReadingPipeline` invokes
   `pusher.pushReading(reading)` and `uploader.onNewReading()` directly, but
   the rest of the post-store fan-out — `updateNotification()`,
   `alertManager.checkReading()`, `broadcastBgIfEnabled()`,
   `writeToHealthConnectIfEnabled()`, `updateWidgets()` — happens in
   `StrimmaService.onNewReading()` after the pipeline returns the stored
   reading. There is no consistent rule for which side effects live where.
2. **Two parallel ingestion paths.** `NightscoutFollower` and
   `LibreLinkUpFollower` bypass `ReadingPipeline` entirely. They call
   `processNightscoutEntry` (which has its own 3 s gap dedup) and re-implement
   their own subset of the post-store fan-out in their `start(scope, callback)`
   blocks. The new bucketed dedup does not apply to them. Tidepool uploads
   were silently broken for follower-mode users until PR #205 added a tactical
   wire-up — but the deeper duplication remains.
3. **Cluster-aware downstream behaviour.** The bucketing fix correctly
   collapses Eversense's repost spam in the local DB. But when an Eversense
   cluster contains a value change (OLD value first, NEW value ~1 s later),
   the OLD push-to-Nightscout fires before the NEW value arrives. PR #205 adds
   an in-flight cancel on `NightscoutPusher` that catches roughly half the
   cases (those where the HTTP request hasn't completed yet). The other half —
   already-pushed OLD entries — leak to Nightscout. Same applies to Health
   Connect writes (no cancel mechanism wired in PR #205) and to alerts (which
   can fire on the OLD value if it crosses a threshold the NEW value doesn't).
4. **Adding a future observer is invasive.** Any new post-store reactor
   (e.g., a CGM-to-watch broadcast for a new platform) requires touching
   `ReadingPipeline`'s constructor, `StrimmaService.onNewReading`, and both
   follower callbacks. The fan-out is hand-wired in three places.

## The proper architecture

```
ReadingPipeline   →  pure storage. validate → dedup → compute → persist → return
ReadingFanOut     →  takes a stored reading, dispatches to all observers
StrimmaService    →  thin coordinator. calls pipeline.processReading then
                     fanOut.onReadingStored
ReadingObserver   →  small interface; one implementation per side effect
```

Each post-store side effect becomes its own small `ReadingObserver`
implementation:

```kotlin
interface ReadingObserver {
    suspend fun onReadingStored(reading: GlucoseReading)
}

@Singleton class NightscoutPushObserver @Inject constructor(...) : ReadingObserver
@Singleton class TidepoolUploadObserver @Inject constructor(...) : ReadingObserver
@Singleton class AlertObserver @Inject constructor(...) : ReadingObserver
@Singleton class HealthConnectWriteObserver @Inject constructor(...) : ReadingObserver
@Singleton class BgBroadcastObserver @Inject constructor(...) : ReadingObserver
@Singleton class WidgetRefreshObserver @Inject constructor(...) : ReadingObserver
@Singleton class NotificationUpdateObserver @Inject constructor(...) : ReadingObserver
```

A new Hilt module binds each into a set:

```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class ReadingObserverModule {
    @Binds @IntoSet abstract fun bindNightscoutPushObserver(impl: NightscoutPushObserver): ReadingObserver
    @Binds @IntoSet abstract fun bindTidepoolUploadObserver(impl: TidepoolUploadObserver): ReadingObserver
    // ... etc
}
```

`ReadingFanOut` iterates the set:

```kotlin
@Singleton
class ReadingFanOut @Inject constructor(
    private val observers: Set<@JvmSuppressWildcards ReadingObserver>
) {
    suspend fun onReadingStored(reading: GlucoseReading) {
        observers.forEach { it.onReadingStored(reading) }
    }
}
```

`ReadingPipeline` becomes pure:

```kotlin
@Singleton
class ReadingPipeline @Inject constructor(
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
) {
    suspend fun processReading(mgdl: Double, timestamp: Long, source: String?): GlucoseReading? {
        // bucketed dedup, store, return — no side effects
    }
}
```

`StrimmaService` becomes thin:

```kotlin
val reading = readingPipeline.processReading(mgdl, timestamp, source)
if (reading != null) readingFanOut.onReadingStored(reading)
```

Both follower callbacks call the same fan-out:

```kotlin
nightscoutFollower.start(scope) { reading -> readingFanOut.onReadingStored(reading) }
libreLinkUpFollower.start(scope) { reading -> readingFanOut.onReadingStored(reading) }
```

## What this fixes

- **Issue #6** (split ownership) — one place owns fan-out.
- **Issues #10, #11, #15** — `ReadingPusher`/`ReadingUploader` deleted; one
  interface; FQN imports gone.
- **Issue #4 architectural** — followers go through the same fan-out as the
  pipeline. Dedup remains different (bucketing for notifications,
  timestamp-match for followers — see "Why dedup stays per-source" below) but
  the post-store reactor chain is unified.
- **Issue #1 residual** — `ReadingFanOut` is the right place to add
  cluster-aware debouncing. After `pipeline.processReading` returns, instead
  of fanning out immediately, `ReadingFanOut` can buffer the reading for a
  short window (e.g., 250 ms) and only fire observers if no further reading
  arrives in the same bucket. This prevents the OLD-then-NEW push pattern at
  the cost of bounded latency. Each observer can opt into eager vs debounced
  delivery (alerts may want eager, push/upload/HC may want debounced).

## What's NOT changing

- Dedup remains per-source. Followers receive pre-deduped batched data from a
  remote NS or Abbott API; they don't have the Eversense-style repost problem.
  Routing them through `ReadingPipeline.processReading` would add bucketing
  overhead with no benefit. Leave `processNightscoutEntry` (or its
  equivalent) for follower-side dedup; route only the post-store fan-out
  through `ReadingFanOut`.
- `NightscoutPuller` (history backfill) continues to use `dao.insertBatch`
  with `OnConflictStrategy.IGNORE`. It's a bulk operation, not real-time, and
  doesn't need the observer chain.

## Implementation sequence

1. Add the `ReadingObserver` interface in `service/`.
2. Add `ReadingFanOut` in `service/` with `Set<ReadingObserver>` injection.
3. Add `ReadingObserverModule` Hilt module with `@IntoSet` bindings.
4. Convert each existing reactor to a `ReadingObserver`:
   - `NightscoutPushObserver` wraps `NightscoutPusher.pushReading`
   - `TidepoolUploadObserver` wraps `TidepoolUploader.onNewReading`
   - `AlertObserver` extracts the alert-checking logic from
     `StrimmaService.onNewReading`
   - `HealthConnectWriteObserver` extracts the HC write logic
   - `BgBroadcastObserver` extracts `broadcastBgIfEnabled`
   - `WidgetRefreshObserver` extracts `updateWidgets`
   - `NotificationUpdateObserver` extracts `updateNotification`
5. Remove `ReadingPusher`/`ReadingUploader` interfaces from
   `service/ReadingObservers.kt` (the file becomes the home of the new
   `ReadingObserver`).
6. Remove the `pusher` and `uploader` parameters from `ReadingPipeline`'s
   constructor.
7. Replace `pipeline → push + upload, service → alert + ...` with
   `pipeline → fanOut.onReadingStored(reading)` in both `StrimmaService` and
   the two follower callbacks.
8. Add cluster-aware debouncing to `ReadingFanOut` (the `#1` residual fix).
   Each observer declares whether it wants eager or debounced delivery via an
   annotation or a property on the interface.
9. Update tests:
   - `ReadingPipelineIntegrationTest` becomes pure storage tests; no fakes
     needed for push/upload.
   - Each new `ReadingObserver` gets its own focused test (HC observer test,
     alert observer test, etc.).
   - Add `ReadingFanOutTest` that verifies dispatch to all observers and
     debouncing semantics.
10. Delete `processNightscoutEntry`'s post-store work (alert/notif/etc.) —
    keep only the dedup. Followers call `readingFanOut.onReadingStored` after
    the dedup decision.

## Cluster-aware debouncing — design notes

The `#1` residual is fixed by debouncing the fan-out, not by adding cancel
mechanisms downstream. Sketch:

```kotlin
class ReadingFanOut(...) {
    private val pendingByBucket = ConcurrentHashMap<Long, PendingReading>()

    suspend fun onReadingStored(reading: GlucoseReading) {
        val bucket = bucketKey(reading)
        val pending = PendingReading(reading, scope.launch {
            delay(DEBOUNCE_WINDOW_MS)  // 250 ms
            if (pendingByBucket.remove(bucket) != null) {
                fireEager(reading)      // alerts, notification — could fire now
                fireDebounced(reading)  // push, upload, HC — only after window
            }
        })
        // If the bucket already had a pending dispatch, cancel it; the new
        // reading supersedes it.
        pendingByBucket.put(bucket, pending)?.cancel()
    }
}
```

The eager/debounced split lets alerts fire fast (~250 ms latency) while
shielding NS/HC/Tidepool from cluster-leading stale values. The 250 ms window
is below human perception for an urgent-low alert and well within the 5-min
poll cadence of any closed-loop pump reading from Health Connect.

Edge cases to handle:
- Service shutdown: in-flight debounce coroutines should fire their reading
  synchronously (use `NonCancellable` for the dispatch).
- Multiple readings in the same bucket from different sources (very rare):
  the latest wins, prior is cancelled. Already the bucket invariant from
  `ReadingPipeline`.
- Backwards-compatible behaviour for follower paths: followers don't have
  cluster behaviour, so debouncing is a no-op for them. The same
  `onReadingStored` call works correctly.

## Estimated scope

- ~7 new small files (1 per observer + 1 fan-out + 1 module).
- ~150 lines added, ~80 lines removed (the pipeline shrinks; observers replace
  inlined logic).
- ~4 modified existing files (`ReadingPipeline`, `StrimmaService`,
  `ReadingObservers`, `ReadingObserverModule`).
- ~2 weeks of focused work including tests and on-device validation.

## Tracking

When this refactor lands, this document should be deleted. Until then, link to
it from any future review that surfaces #6, #10, #11, #15, or the residual
half of #1.
