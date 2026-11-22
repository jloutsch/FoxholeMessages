# RxJava 2 → Kotlin Flow (#34) — design

## Intent

Justin wants a cleaner codebase with modern underpinnings. Foxhole Messages is built on QKSMS, which
is old, and its reactive layer is RxJava 2.1.4 (2017; the 2.x line has been unsupported since 2021).
Google's Android libraries speak Kotlin coroutines and Flow (Room, which this app already uses since
2.9.0; DataStore; WorkManager's coroutine workers; Lifecycle scopes; Compose), so the app should too.
Success is an app with **no RxJava at all**, whose screens, background actions and settings use
coroutines and Flow, and which behaves exactly as before.

## Goals

- Remove RxJava 2 and every companion library: `rxjava`, `rxandroid`, `rxkotlin`, RxBinding 2
  (`rxbinding-kotlin`, `rxbinding-support-v4-kotlin`), AutoDispose (4 artifacts), RxDogTag (2),
  `rx-preferences`, `kotlinx-coroutines-rx2`, and `work-rxjava2` (already unused).
- Screens, background actions (interactors), settings, repositories, workers and receivers use
  coroutines and Flow.
- Every step ships on its own: one PR per step, CI green, checked on the Note 10.

## Non-goals

- No visible change: no new features, layouts or wording.
- No Jetpack Compose, no replacing Conductor, no move from SharedPreferences to DataStore.
- No refactoring beyond what the conversion needs.

## Anti-goals (a failure even if it builds)

- Anyone loses a setting. The settings file, keys and stored formats must stay exactly as they are.
- A broadcast receiver or job is left open (or closed too early) because an action's "finished"
  callback timing changed.
- Work keeps running after its screen is gone, or stops while its screen is still showing.
- Database reads move back onto the main thread (#71: debug builds throw on them; keep it that way).
- A screen ships converted without a phone check.

## Current state (2026-11-21, master after 2.9.7)

- `io.reactivex` is imported in 174 files: presentation 110, domain 48, data 16.
- **Screens:** 23. Eight extend `QkViewModel` (Activities: Main, Compose, Contacts, Gallery,
  Scheduled, QkReply, NotificationPrefs, CategoryNotification) and fifteen extend `QkPresenter`
  (Conductor `QkController` screens: Settings, About, SwipeActions, ThemePicker, MessageSorting,
  SenderRules, TrustedSenders, MessageContentFilters, Blocking, BlockedMessages, BlockedNumbers,
  ConversationInfo, MessageUtils, Trash, Backup). Both base classes hold state in a
  `BehaviorSubject`, apply reducers on the main thread with `scan`, and render via `autoDisposable`.
  Views expose user actions as `Observable`/`Subject` properties (about 173).
- **Interactors:** 40, built on `Flowable` (`buildObservable`). Called through
  `execute(params, onComplete, onTerminate)` at 75 call sites in 32 files, including 13 broadcast
  receivers, `HeadlessSmsSendService`, `ReceiveSmsWorker`/`ReceiveMmsWorker` and two interactors.
  `execute` runs on `Schedulers.io()`, calls `onComplete` on the main thread **only on success**, calls
  `onTerminate` **always** (receivers call `goAsync` `finish()` there), and logs errors with `Timber.w`.
- **Settings:** `Preferences.kt` wraps `rx-preferences` 2.0.0-RC3 (72 preferences: 32 Boolean,
  25 Integer, 9 String, 3 Float, 1 Long, 1 StringSet, 1 Object with a custom converter, plus
  per-thread/per-category factories). Used as `.get()` (164), `.asObservable()` (69), `.delete()`
  (13), and `keyChanges: Observable<String>`. File: `${packageName}_preferences`.
- **RxBinding:** `.clicks()` 77, `.textChanges()` 5, `.longClicks()` 1, `.editorActions()` 1.
- **AutoDispose:** `autoDisposable(...)` at 257 call sites.
- **Repositories/managers returning Rx:** backup/restore progress, `syncProgress`,
  `deduplicationProgress`, `deduplicateMessages()`, `getUnmanagedContacts/ContactGroups/
  Conversations/Recipients`, `setConversationName` (Completable), `RatingManager.shouldShowRating`.
  Data-layer Rx also in `ContactAddedListenerImpl`, `ActiveSubscriptionObservable`, `QksmsBlockingClient`,
  `CursorExtensions`, `WidgetManagerImpl`, `BlockThreadReceiver`, `SmsReceivedReceiver`,
  `ClassifyExistingMessagesWorker`.
- Already modern: Room returns `Flow` (`db/Observe.kt`); screens convert with `asMainObservable()`.
  Coroutines 1.10.2 and Lifecycle 2.8.7 are on the classpath. Conductor 2.1.5 controllers are
  `LifecycleController`s, so they have a Lifecycle.

## Approaches considered

1. **Foundations first, then screens, then cleanup** — chosen by Justin. Replace the shared pieces
   behind their current APIs first, so each screen then converts once, straight to the final pieces.
   Costs a few days with no visible screen changes; the riskiest shared code (settings, interactors)
   changes early, while little else is in flight.
2. **Screens first, foundations last.** Visible progress sooner, but screens call Rx settings and
   interactors through bridges for weeks, and the riskiest pieces change last, under everything.
3. **One big branch.** Ruled out: hundreds of files in one review, regressions hard to place.

(Upgrading to RxJava 3 instead, or converting only opportunistically, were turned down earlier:
the goal is the modern end state, not a supported Rx.)

## The plan's shape (decided)

### Phase F — foundations (about 2–3 days, 3–4 PRs; nothing on screen changes)

**F1. Settings.** Replace `rx-preferences` with an in-repo `Preference<T>` in `domain`:
`get()`, `set(value)`, `delete()`, `isSet`, `asFlow(): Flow<T>` (emits the current value, then each
change; built on `OnSharedPreferenceChangeListener`), and a temporary `asObservable()` bridge so
existing screens keep working until they convert. `keyChanges` becomes a Flow (plus a bridge).
Same file, same keys, **byte-identical stored formats**, including the StringSet and the custom
Object converter. A test writes values the way rx-preferences 2.0.0-RC3 does and reads them back
through the new class.

**F2. Interactors.** Rebuild the base on coroutines. Five places call an interactor's
`buildObservable` directly rather than `execute` (`SyncRepositoryImpl`, `BlockThreadReceiver`,
`RetentionWorker`, `ClassifyExistingMessagesWorker`, `MessageUtilsPresenter`, which also uses the
emitted result); F2 converts them to call the `suspend` function, with no Rx bridge, and its phone
check covers those paths (a sync, blocking from a notification, retention, sorting existing messages,
duplicate removal). Each interactor implements a `suspend` function
instead of `buildObservable`. `execute(params, onComplete, onTerminate)` keeps its signature and
semantics exactly: work on `Dispatchers.IO`; `onComplete` on the main thread only on success;
`onTerminate` on the main thread always; errors logged with `Timber.w`, never thrown to the caller;
`dispose()` cancels that interactor's running work. Interactors that call other interactors call the
`suspend` function directly. Tests pin the callback semantics (success, failure, cancellation).

**F3. Screen kit.** Flow versions of the two screen base classes, beside the old ones until cleanup:
state in a `MutableStateFlow`, `newState { }` reducers applied in order on the main thread, render
collected in the view's `lifecycleScope`; small view helpers returning Flow (`clicks`, `longClicks`,
`textChanges`, `editorActions`) to replace RxBinding; a `MutableSharedFlow` convention for events
views emit themselves (with buffer capacity so emits from the main thread never drop).

### Phase S — screens (about 2 weeks, one PR per screen)

Convert each of the 23 screens' ViewModel/Presenter, its View interface, its Activity/Controller, and the
adapters and widgets only it uses. Order: smallest first, with **About as the pilot**, then the
other settings screens, Trash, Blocking screens, NotificationPrefs, Backup, ConversationInfo,
Gallery, Contacts, Scheduled, QkReply, then **Main and Compose last**. Shared presentation pieces
(`QkThemedActivity.theme`, `PagerTitleView`, shared adapters) convert with the first screen that
needs them in Flow form, bridged for the rest. Repositories/managers that a screen consumes in Rx
form switch to Flow in that screen's PR (or earlier if several screens share them).

### Phase C — cleanup (about 1 day)

Convert what's left outside screens (receivers, workers, data-layer helpers, `NotificationManagerImpl`,
widget), remove the bridges and the old base classes, rename the Flow base classes to the old names,
remove the libraries and RxDogTag setup, and add a source-reading test that fails on any
`io.reactivex` import (like `MainThreadDatabaseUpdatesTest`).

## Constraints

- Kotlin 2.2.21, coroutines 1.10.2, Lifecycle 2.8.7, Conductor 2.1.5, AGP 8.11, Java 11. No new
  third-party libraries (RxBinding is replaced by in-repo helpers, not FlowBinding).
- Lifetimes must match today's, and they differ by screen type (Conductor-archlifecycle maps a
  controller's attach to `ON_RESUME` and detach to `ON_PAUSE`; checked by the builder review):
  - **Activities** (the 8 `QkViewModel` screens) bind in `onCreate`; `autoDisposable(view.scope())`
    lives until destroy, so these become coroutines in `lifecycleScope`, **not** `repeatOnLifecycle`,
    which would restart work on every resume.
  - **Conductor controllers** (the 15 `QkPresenter` screens) bind in `onAttach`, and their
    subscriptions end at detach; `bindIntents` runs again on every re-attach. The controller base
    gets a scope created at attach and cancelled at detach. Using the controller's `lifecycleScope`
    instead would stack duplicate handlers on each return from a child screen, and `render` would hit
    a null binding after the view is dropped.
  - Where the Rx code bound to a specific event (for example `ConversationInfoPresenter` binding to
    `ON_DESTROY`), keep that event.
- Workers stay `androidx.work.Worker` and wait for coroutine work inside `doWork()` (they already run
  on a background thread). Moving to `CoroutineWorker` would also need `InjectionWorkerFactory`
  (`asSubclass(Worker::class.java)`), the R8 keep rule for `* extends androidx.work.Worker` and
  `ProguardRulesTest` changed together, risking already-scheduled work (see CLAUDE.md's WorkManager
  traps). That's a possible follow-up, not part of #34.
- Threading must match today's: anything that read the database off the main thread still does
  (#71's debug enforcement stays and catches regressions).
- `MainThreadDatabaseUpdatesTest` keeps guarding that screens receive database updates on the main
  thread; update it to the Flow form.
- Each PR: CI green, full JVM suite, Note 10 check of the screens it touches, PR description in
  plain English (what was wrong / what changed / tested).
- Releases: Justin's personal-key GitHub releases as usual; when to cut them is his call.

## Known behaviour differences to handle (not to discover)

- `StateFlow` skips a state equal to the previous one; `BehaviorSubject` re-emitted it. Rendering an
  equal state is a no-op for these screens, but anything that relied on re-delivery must not.
- `PublishSubject` → `MutableSharedFlow`: no replay, and `tryEmit` fails without buffer capacity.
- No direct `withLatestFrom`: read `state.value` or `combine` as fits.
- `switchMap` → `flatMapLatest`, `scan` → `runningFold`, `distinctUntilChanged` is built into
  StateFlow, `debounce` exists, `Observable.merge` → `merge`.
- `Flowable.just(x).mapNotNull(...)` one-shots become plain `suspend` calls in a coroutine.

## Decided after the builder review (Claude's calls, 2026-11-21)

- Conductor screens stop collecting at detach (see Constraints): matching today's behaviour.
- Workers stay `Worker`: `CoroutineWorker` isn't needed to remove RxJava and carries the
  persisted-work risk.
- F2 converts the five direct `buildObservable` callers itself rather than keeping an Rx bridge on
  every interactor.

## Left to the builder

- Exact names and file layout of the new classes and helpers (beyond renaming to the old names at
  cleanup).
- Per-screen conversion details, and which shared pieces get bridged versus converted early.
- How to split the 40 interactors across F2 PRs.
- Test design beyond the pinned cases above.
