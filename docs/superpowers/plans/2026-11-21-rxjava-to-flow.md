# RxJava 2 → Kotlin Flow (#34) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove RxJava 2 and its companion libraries from Foxhole Messages; screens, background actions, settings, repositories, workers and receivers use coroutines and Flow, and the app behaves exactly as before.

**Architecture:** Foundations first, behind their current APIs (settings, interactors, a Flow screen kit with temporary Rx bridges), then the 23 screens one PR each, then cleanup that removes the bridges and libraries.

**Tech Stack:** Kotlin 2.2.21, kotlinx-coroutines 1.10.2, Lifecycle 2.8.7, Conductor 2.1.5, Room 2.8, Robolectric 4.16.1, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-21-rxjava-to-flow-design.md`

## Global Constraints

- One PR per task, branched from up-to-date `master`; CI green before merge; merge only when Justin says "merge". `gh` always with `--repo jloutsch/FoxholeMessages`.
- Gradle runs through ctx_execute with `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`. Full suite: `./gradlew test`.
- Every PR: full suite green, Note 10 check (`R3CM709XTMY`, debug app `com.foxhole.messages.debug`) of what it touches, plain-English PR description (what was wrong / what changed / tested).
- No new third-party runtime libraries. Test-only additions from the same coroutines family (`kotlinx-coroutines-test:$coroutines_version`) are allowed.
- Settings: same file (`${packageName}_preferences`), same keys, byte-identical stored formats.
- `Interactor.execute(params, onComplete, onTerminate)` keeps its signature and semantics: work on IO; `onComplete` on main only on success; `onTerminate` on main always, including after `dispose()`; errors logged with `Timber.w`, never thrown.
- Lifetimes: Activities (8 `QkViewModel` screens) collect in `lifecycleScope` (until destroy); Conductor controllers (15 `QkPresenter` screens) collect in a scope created at attach and cancelled at detach; explicit `ON_DESTROY` bindings keep that lifetime. Never `repeatOnLifecycle`.
- Database reads stay off the main thread; debug builds keep throwing on main-thread reads (#71).
- Workers stay `androidx.work.Worker`.

## Review Focus

1. A user upgrading with settings saved by rx-preferences sees every setting unchanged, including `backupDirectory` (Object converter) and `fh_widgetTabs` (StringSet) — pinned in Task 1.
2. A broadcast receiver whose action fails or is cancelled still calls `goAsync().finish()` — pinned in Task 2 (`onTerminate` after failure and after `dispose()`).
3. Returning to a settings screen from a child screen does not double-handle a tap — pinned in Task 5 (attach scope cancelled at detach) and checked in every controller screen task.
4. A tap's event emitted from the main thread is never dropped when the collector is busy — pinned in Task 5 (`events()` `tryEmit` returns true).
5. A screen renders the first state immediately on open and the latest state after rotation — checked in each screen task's phone check (rotate once).

---

## Phase F — foundations

### Task 1: Settings without rx-preferences

**Files:**
- Create: `domain/src/main/java/com/moez/QKSMS/util/Preference.kt`
- Modify: `domain/src/main/java/com/moez/QKSMS/util/Preferences.kt` (constructor takes `SharedPreferences`; every `rxPrefs.getX` becomes a `Preference`; `keyChanges` becomes `Flow<String>` plus bridge), `presentation/.../injection/AppModule.kt` (drop `provideRxPreferences`), `data/.../repository/SyncRepositoryImpl.kt` and `data/.../manager/RatingManagerImpl.kt` (stop injecting `RxSharedPreferences`), every file importing `com.f2prateek.rx.preferences2.Preference` (import only), `domain/build.gradle`, `presentation/build.gradle` (remove rx-preferences)
- Test: `presentation/src/test/java/com/moez/QKSMS/util/PreferenceCompatibilityTest.kt` (Robolectric, `@Config(sdk = [35])`)

**Interfaces:**
- Produces: `class Preference<T>` in `dev.octoshrimpy.quik.util` with `val key: String`, `val defaultValue: T`, `fun get(): T`, `fun set(value: T)`, `fun delete()`, `val isSet: Boolean`, `fun asFlow(): Flow<T>` (current value first, then each change; default after `delete()`), and `@Deprecated("Rx bridge, removed in Task 29") fun asObservable(): Observable<T>`. Factories on `Preferences`: same property names and per-thread/per-category functions as today, returning this `Preference<T>`. `Preferences.keyChanges: Flow<String>` and `@Deprecated keyChangesObservable: Observable<String>` (call sites switch to the bridge in this task).
- Storage per type, matching rx-preferences 2.0.0-RC3 (confirm against its source in the Gradle cache before writing): Boolean `putBoolean`, Integer `putInt`, String `putString`, Float `putFloat`, Long `putLong`, StringSet `putStringSet`, Object `putString(converter.serialize(value))` with `UriPreferenceConverter` unchanged.

- [ ] **Step 1: Write the failing tests** in `PreferenceCompatibilityTest`:
  - `valuesStoredByRxPreferencesReadBack`: raw `SharedPreferences` writes `putBoolean("black", true)`, `putInt("nightMode", 2)`, `putString("nightStart", "19:30")`, `putFloat("messageTextScale", 1.5f)`, `putLong("fh_lastAutoCopiedCodeMessageId", 42L)`, `putStringSet("fh_widgetTabs", setOf("PERSONAL"))`, `putString("backupDirectory", "content://x/y")`; `Preferences(...)` reads `true`, `2`, `"19:30"`, `1.5f`, `42L`, `setOf("PERSONAL")`, `Uri.parse("content://x/y")`.
  - `setWritesTheSameFormat`: `prefs.nightMode.set(3)` → raw `getInt("nightMode", -1) == 3`; `prefs.backupDirectory.set(Uri.parse("content://a"))` → raw `getString("backupDirectory", null) == "content://a"`.
  - `unsetReturnsDefaultAndDeleteRestoresIt`: fresh prefs → `prefs.black.get() == false`, `isSet == false`; after `set(true)` then `delete()` → `get() == false`, `isSet == false`.
  - `asFlowEmitsCurrentThenChanges`: collect `prefs.black.asFlow()` into a list, `set(true)`, `set(false)` → `[false, true, false]`.
- [ ] **Step 2: Run** `./gradlew :presentation:testDebugUnitTest --tests '*PreferenceCompatibilityTest*'` — Expected: compile failure (`Preference` constructor/`asFlow` missing).
- [ ] **Step 3: Implement** `Preference<T>` (a small adapter interface per type for read/write; `asFlow` via `callbackFlow` on `OnSharedPreferenceChangeListener`, filtering on `key`, plus `onStart { emit(get()) }`), rewrite `Preferences`, switch DI and the two data-layer users, swap imports.
- [ ] **Step 4: Run** the test, then `./gradlew test` — Expected: all pass.
- [ ] **Step 5: Phone check (Note 10):** install debug over the existing one; open Settings: theme, night mode, text size, auto-sort, signature, Backup folder still show their old values; change one setting and confirm the screen reacts (e.g. night mode recreates the activity).
- [ ] **Step 6: Commit and PR** "Settings no longer use rx-preferences (#34)".

### Task 2: Interactor base on coroutines, with a temporary Rx bridge

**Files:**
- Modify: `domain/src/main/java/com/moez/QKSMS/interactor/Interactor.kt`, `domain/build.gradle` (`testImplementation kotlinx-coroutines-test`), the five direct callers: `data/.../repository/SyncRepositoryImpl.kt:123`, `data/.../receiver/BlockThreadReceiver.kt:61`, `data/.../worker/RetentionWorker.kt:80`, `data/.../worker/ClassifyExistingMessagesWorker.kt:128`, `presentation/.../feature/messageutils/MessageUtilsPresenter.kt:50`
- Test: `domain/src/test/java/com/moez/QKSMS/interactor/InteractorTest.kt`

**Interfaces:**
- Produces: `abstract class Interactor<in Params>` with `open suspend fun perform(params: Params): Any?` (default: runs `buildObservable(params)` to completion via `kotlinx-coroutines-rx2` and returns its last item or null), `@Deprecated("Rx bridge, removed in Task 4") open fun buildObservable(params: Params): Flowable<*>` (default throws `UnsupportedOperationException`), unchanged `execute(params, onComplete, onTerminate)`, and `dispose()`. Converted interactors override `perform` with a narrower return type where callers use the result (`DeduplicateMessages.perform(Unit): MessageRepository.DeduplicationResult`).
- `execute` launches in a per-instance `CoroutineScope(SupervisorJob() + Dispatchers.IO)`; `onComplete` via `withContext(Dispatchers.Main)` after success; `onTerminate` in `finally` with `withContext(NonCancellable + Dispatchers.Main)`; non-cancellation exceptions → `Timber.w`. `dispose()` cancels the scope.
- Direct callers call `perform` (inside `runBlocking` where the caller is a non-suspending background path; the presenter launches it in its existing background chain).

- [ ] **Step 1: Write the failing tests** (`Dispatchers.setMain(StandardTestDispatcher())`):
  - `successRunsOnCompleteThenOnTerminate`: records `["complete", "terminate"]`.
  - `failureSkipsOnCompleteStillTerminatesAndDoesNotThrow`: `perform` throws `IllegalStateException` → records `["terminate"]`, `execute` returns normally.
  - `disposeCancelsAndStillTerminates`: `perform` suspends on `awaitCancellation()`; `dispose()` → records `["terminate"]`.
  - `callbacksRunOnMain`: callbacks record `Thread.currentThread()`; both equal the test Main dispatcher's thread (not an IO thread).
  - `unconvertedInteractorRunsThroughBridge`: subclass overriding only `buildObservable = Flowable.just(7)` → `perform(Unit) == 7`.
- [ ] **Step 2: Run** `./gradlew :domain:testDebugUnitTest --tests '*InteractorTest*'` — Expected: compile failure (`perform` missing).
- [ ] **Step 3: Implement** the base and switch the five direct callers to `perform`.
- [ ] **Step 4: Run** the test, then `./gradlew test` — Expected: all pass.
- [ ] **Step 5: Phone check (Note 10):** send a message; inject a received text (`adb shell am broadcast -n com.foxhole.messages.debug/dev.octoshrimpy.quik.receiver.DebugInjectSmsReceiver --es body 'Test'`) and mark it read from the notification; Settings → Sync messages; block a conversation from its notification; Message utils → remove duplicates; Message sorting → sort existing messages. No crash; each action completes.
- [ ] **Step 6: Commit and PR** "Background actions run on coroutines (#34)".

### Task 3: Convert interactors A–M to `perform`

**Files:** every `domain/.../interactor/*.kt` whose class name starts A–M (override `perform`, delete `buildObservable`). Repository methods they call that return Rx types are awaited with `kotlinx-coroutines-rx2` for now (converted in Phase C).

- [ ] **Step 1:** For each interactor, replace the `Flowable` chain with a sequential `suspend` body doing the same writes in the same order.
- [ ] **Step 2: Run** `./gradlew test` — Expected: all pass (existing interactor tests, e.g. `ScheduledMessageFilesTest`, still pass).
- [ ] **Step 3: Phone check:** the Task 2 list, plus delete and restore a message from Trash, archive and unarchive, pin, block and unblock a number.
- [ ] **Step 4: Commit and PR** "Background actions A–M written as coroutines (#34)".

### Task 4: Convert interactors N–Z, remove the bridge

**Files:** the remaining interactors; `Interactor.kt` (delete `buildObservable` and the bridge default; `perform` becomes `abstract`).

- [ ] **Step 1:** Convert as in Task 3. Delete the bridge.
- [ ] **Step 2: Run** `./gradlew test` and `grep -rn buildObservable --include='*.kt' .` — Expected: tests pass; grep finds nothing outside build outputs. Remove `unconvertedInteractorRunsThroughBridge` from `InteractorTest` in this task.
- [ ] **Step 3: Phone check:** the Task 3 list, plus schedule a message for 1 minute ahead and see it send, and mark a scheduled message "send now".
- [ ] **Step 4: Commit and PR** "Background actions N–Z written as coroutines; Rx bridge removed (#34)".

### Task 5: Flow screen kit

**Files:**
- Create: `presentation/.../common/base/QkFlowView.kt`, `QkFlowViewModel.kt`, `QkFlowPresenter.kt`, `QkFlowController.kt`, `presentation/.../common/util/extensions/ViewFlows.kt`, `presentation/.../common/util/extensions/FlowCollect.kt`
- Test: `presentation/src/test/java/com/moez/QKSMS/common/base/FlowScreenKitTest.kt` (Robolectric)

**Interfaces (produces):**
- `interface QkFlowView<in State> { fun render(state: State); val bindScope: CoroutineScope }` — Activities return `lifecycleScope`; `QkFlowController` returns its attach scope.
- `abstract class QkFlowViewModel<in V : QkFlowView<S>, S : Any>(initialState: S) : ViewModel()` with `protected val state: StateFlow<S>`, `protected fun newState(reducer: S.() -> S)` (queued, applied in call order on `Dispatchers.Main`, never inline), `@CallSuper open fun bindView(view: V)` (collects `state` into `view::render` in `view.bindScope`).
- `abstract class QkFlowPresenter<V : QkFlowView<S>, S : Any>(initialState: S)` with the same `state`/`newState`, `@CallSuper open fun bindIntents(view: V)`, `open fun onCleared()` (cancels its own `CoroutineScope(SupervisorJob() + Dispatchers.Main)` that applies reducers).
- `abstract class QkFlowController<VB, V, S, P : QkFlowPresenter<V, S>> : LifecycleController(), QkFlowView<S>` — same view-binding API as `QkController`; `bindScope` is created in `onAttach` (`MainScope()`) and cancelled in `onDetach`.
- `ViewFlows.kt`: `fun View.clicks(): Flow<Unit>`, `fun View.longClicks(): Flow<Unit>` (consumes the long press), `fun TextView.textChanges(): Flow<CharSequence>` (current text first), `fun TextView.editorActions(): Flow<Int>` — each a `callbackFlow` that removes its listener in `awaitClose`.
- `FlowCollect.kt`: `fun <T> events(): MutableSharedFlow<T>` (`extraBufferCapacity = Int.MAX_VALUE`), `fun <T> Flow<T>.collectIn(scope: CoroutineScope, action: suspend (T) -> Unit): Job`.

- [ ] **Step 1: Write the failing tests** in `FlowScreenKitTest`:
  - `reducersApplyInCallOrder`: three `newState` calls from a background thread → rendered states end with the third reducer's result, applied in order.
  - `renderStopsWhenBindScopeIsCancelled`: cancel the view's scope, call `newState` → no further `render`.
  - `controllerScopeEndsAtDetach`: drive `QkFlowController` attach → detach → attach via Conductor's Robolectric-compatible `Router`; a `clicks()` collector started in `bindIntents` fires once per click after re-attach, not twice. (If Conductor can't be driven under Robolectric, extract the attach/detach scope holder into a class and test it directly; say so in the PR.)
  - `clicksEmitAndUnregister`: `performClick()` twice → 2 emissions; after cancelling the collector, `hasOnClickListeners() == false`.
  - `textChangesStartsWithCurrentText`: text "a", collect, set "ab" → `["a", "ab"]`.
  - `eventsNeverDropFromMain`: `events<Int>()` with a suspended collector; `tryEmit` 100 times → all return `true`.
- [ ] **Step 2: Run** `./gradlew :presentation:testDebugUnitTest --tests '*FlowScreenKitTest*'` — Expected: compile failure.
- [ ] **Step 3: Implement** the kit.
- [ ] **Step 4: Run** the test, then `./gradlew test` — Expected: all pass.
- [ ] **Step 5: Commit and PR** "Flow versions of the screen base classes (#34)". (No phone check: nothing uses the kit yet.)

## Phase S — screens (Tasks 6–28, one PR each)

**Screen task template** (applies to every task in this phase):
1. Move the ViewModel/Presenter to the Flow base, its View interface to `QkFlowView` with `Flow` intents, and the Activity/Controller to `QkFlowView`/`QkFlowController`. Replace RxBinding with `ViewFlows.kt`, `Subject`s with `events()`/`MutableStateFlow`, `autoDisposable(...).subscribe` with `collectIn(view.bindScope)`, settings `.asObservable()` with `.asFlow()`, `asMainObservable()` with collecting the database `Flow` on the main scope. Keep every background hop (`observeOn(Schedulers.io())` → `withContext(Dispatchers.IO)` / `flowOn(Dispatchers.IO)`); keep explicit `ON_DESTROY` lifetimes.
2. Adapters, dialogs and widgets used only by this screen convert in the same task; shared ones are bridged (`asFlow()` from `kotlinx-coroutines-rx2`) until their last user converts.
3. Repositories/managers this screen consumes in Rx form (`getUnmanagedContacts`, `syncProgress`, backup/restore progress, `RatingManager.shouldShowRating`, `setConversationName`, …) switch to `Flow`/`suspend` in the first task that needs them, with the remaining Rx users bridged.
4. `./gradlew test` green; `grep -n "io.reactivex" <the screen's files>` finds nothing.
5. Phone check on the Note 10: open the screen, use every control once, rotate once, leave to a child screen and come back, then use a control again (it must act once).
6. Commit and PR "<Screen> on Flow (#34)".

| Task | Screen (files under `presentation/.../feature/`) | Extra checks |
|---|---|---|
| 6 | About — pilot (`settings/about/`) | each row opens its target |
| 7 | ThemePicker (`themepicker/`) | pick a colour, hex entry |
| 8 | SwipeActions (`settings/swipe/`) | change both swipe actions |
| 9 | MessageUtils (`messageutils/`) | auto-delete picker, duplicate removal |
| 10 | Trash (`trash/`) | restore, delete forever, empty Trash |
| 11 | BlockedMessages (`blocking/messages/`) | unblock a conversation |
| 12 | BlockedNumbers (`blocking/numbers/`) | add and remove a number |
| 13 | MessageContentFilters (`blocking/filters/`) | add and remove a filter |
| 14 | Blocking (`blocking/`) | export and import rules |
| 15 | SenderRules (`settings/messagesorting/senderrules/`) | add and remove a rule |
| 16 | TrustedSenders (`settings/messagesorting/trustedsenders/`) | add and remove a sender |
| 17 | MessageSorting (`settings/messagesorting/`) | toggles, OTP retention picker |
| 18 | CategoryNotification (`settings/messagesorting/notifications/`) | each toggle |
| 19 | NotificationPrefs (`notificationprefs/`) | each toggle, ringtone picker |
| 20 | Backup (`backup/`) | back up, then restore a backup |
| 21 | Settings (`settings/`) | every row once; night mode recreates |
| 22 | ConversationInfo (`conversationinfo/`) | rename, theme, pin, archive, block, gallery tile |
| 23 | Gallery (`gallery/`) | swipe pages, save, share |
| 24 | Contacts (`contacts/`) | search, pick a contact, starred list |
| 25 | Scheduled (`scheduled/`) | select, copy, send now, edit, delete |
| 26 | QkReply (`qkreply/`) | inject a text; expand, delete, reply |
| 27 | Main (`main/`, `conversations/`) | tabs, selection menu, swipe actions, search, drawer, Starred, Move to |
| 28 | Compose (`compose/`) | send SMS and MMS, attach, schedule, select message actions, reactions, search in thread, gallery from thread |

## Phase C — cleanup

### Task 29: Remove RxJava

**Files:** everything still importing `io.reactivex` (receivers, workers, `NotificationManagerImpl`, widget, data-layer helpers, repositories' Rx methods, `QkThemedActivity`, `PagerTitleView`, shared adapters); delete `QkViewModel`, `QkPresenter`, `QkController`, `QkViewContract`, `QkView` (old) and `asMainObservable`; rename the `QkFlow*` classes to the old names; remove the bridges (`Preference.asObservable`, `keyChangesObservable`); remove from Gradle: `rxjava`, `rxandroid`, `rxkotlin`, both RxBinding artifacts, four AutoDispose artifacts, both RxDogTag artifacts, `kotlinx-coroutines-rx2`, `work-rxjava2`; remove RxDogTag setup in `QKApplication` and any Rx keep rules in `proguard-rules.pro`; drop the AutoDispose lint note from CLAUDE.md.
**Test:** `presentation/src/test/java/com/moez/QKSMS/NoRxJavaTest.kt`.

- [ ] **Step 1: Write the failing test** `noFileImportsRxJava`: walks `src/main/java` of all five modules (paths relative to `presentation/`, as `MainThreadDatabaseUpdatesTest` does) and asserts the list of files containing `import io.reactivex` is empty.
- [ ] **Step 2: Run** it — Expected: FAIL listing the remaining files.
- [ ] **Step 3:** Convert the remaining files and remove libraries as listed.
- [ ] **Step 4: Run** `./gradlew test` and `./gradlew :presentation:minifyReleaseWithR8` — Expected: all pass; R8 build succeeds.
- [ ] **Step 5: Phone check:** release-style build on the Note 10 (temporary `// TEMP` minify lines in the debug block, removed by marker afterwards): receive, send, notification actions, widget, backup, sync, sort existing messages, every drawer screen.
- [ ] **Step 6:** Rewrite `MainThreadDatabaseUpdatesTest` for the Flow form (screens collect database Flows only in a main-thread scope: no `Dispatchers.IO` collector feeding `render`), and update CLAUDE.md (Flow conventions replace the Rx notes). Commit and PR "Remove RxJava (#34)", closing #34.
