# Realm → Room Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Realm Java with Room as Foxhole Messages' database without losing any app-only data on the owner's phone, ending with a Realm-free release.

**Architecture:** Two phases on the long-running `room-migration` branch. Phase 1 takes Realm out of every screen while Realm is still the store: repositories return `Flow<List<T>>` of unmanaged objects, screens convert with `asObservable()`, adapters become `ListAdapter`s. Phase 2 swaps the store underneath: models become Room entities (relation properties become `@Ignore` fields the repositories fill), DAOs replace Realm queries, an importer copies app-only data from the old Realm file (Build 1), and Build 2 removes Realm.

**Tech Stack:** Kotlin 2.2.21, KSP 2.2.21-2.0.4, Room (current stable with KSP2 support, `room-runtime`, `room-ktx`, `room-compiler` via `ksp`), kotlinx-coroutines 1.10.2 + `kotlinx-coroutines-rx2`, RxJava 2, Dagger 2.57.2, Realm 10.19.0 (until Task 13).

**Spec:** `docs/superpowers/specs/2026-11-19-realm-to-room-design.md`

## Global Constraints

- minSdk 24, targetSdk 36, Java 11, Kotlin 2.2.21, KSP 2.2.21-2.0.4, AGP 8.11.
- All annotation processing on KSP; after Task 13 no module applies kapt.
- No database call on the main thread. Interactors run on `Schedulers.io()`; any presenter/ViewModel write hops to `Schedulers.io()` first.
- WorkManager: Worker class names and unique-work names never change; periodic work uses `KEEP`; `doWork()` wraps its body in try/catch with `Timber.e`.
- App-only data must never be lost: Message `isStarred`, `deletedAt`, `category`, `isOtp`; Conversation `archived`, `blocked`, `pinned`, `name`, `blockingClient`, `blockReason`, `categoryOverride`, `sendAsGroup`, `draft`, `draftDate`; tables ScheduledMessage, BlockedNumber, TrustedSender, SenderCategoryRule, MessageContentFilter, SyncLog, EmojiSyncNeeded.
- Message ids stay app-assigned by `KeyManager` (no `autoGenerate`); Conversation id = threadId.
- Receiving SMS/MMS and notifying is never gated on the import.
- Work happens on `room-migration`; each task (or task group marked as one PR) is a PR into it, CI-built, checked on the **Note 10** (`R3CM709XTMY`) only. The S23+ gets nothing until Task 12 is complete.
- Never `connectedAndroidTest`; on-device tests follow `CLAUDE.md` → Tests (install test APK, `am instrument`, uninstall the test package).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A message received while the import is pending or failing must be saved to Android's store, notify, and appear after the import (Task 11: `ReceiveDuringImportTest`).
2. A conversation with a draft and no messages must still be listed, and its draft must survive a full sync (Task 7 DAO test, Task 9 `draftsSurviveFullSync`).
3. Trash must stay invisible everywhere: unread badge, search, gallery, shortcuts, conversation snippet (Task 7: ported `TrashFiltersTest` cases plus `snippetSkipsTrashedLastMessage`).
4. Running the importer twice (process killed mid-import, then relaunch) must give the same Room contents, never duplicates (Task 11: `importIsRepeatable`).
5. Case-insensitive search: ASCII must match regardless of case; behaviour for accented letters is recorded, not assumed (Task 7: `searchIsCaseInsensitiveForAscii`, `searchAccentedBehaviourRecorded`).

---

## Phase 1 — Screens stop touching Realm (Realm is still the store)

Phase 1 is one PR into `room-migration` (Tasks 1–5), checked on the Note 10 when Task 5 ends.

### Task 1: List adapter base and live-list helper

**Files:**
- Create: `presentation/src/main/java/com/moez/QKSMS/common/base/QkListAdapter.kt`
- Create: `data/src/main/java/com/moez/QKSMS/extensions/RealmFlow.kt`
- Test: `presentation/src/test/java/com/moez/QKSMS/common/base/QkListAdapterDiffTest.kt`

**Interfaces:**
- Produces: `abstract class QkListAdapter<T : Any, VH : QkViewHolder>(itemId: (T) -> Long, sameContents: (T, T) -> Boolean = { _, _ -> false }) : ListAdapter<T, VH>` with the same public API as `QkRealmAdapter`: `emptyView: View?`, `selectionChanges: Subject<List<Long>>`, `toggleSelection(id: Long, force: Boolean = true): Boolean`, `isSelected(id: Long): Boolean`, `clearSelection()`, `highlight: Long`, `getItem(position: Int): T?`, and `updateData(list: List<T>)` (calls `submitList`, updates `emptyView` visibility to `list.isEmpty()`).
- Produces: `fun <T : RealmModel> RealmQuery<T>.asLiveList(): Flow<List<T>>` — `findAllAsync()` on the calling (main, Looper) thread → `asFlowable()` → only loaded results → `realm.copyFromRealm(results)` → `asFlow()`. Unmanaged copies, safe on any thread.

- [ ] **Step 1: Write `QkListAdapterDiffTest`** — `itemsAreTheSameById` (two different objects with id 7 → `areItemsTheSame == true`), `contentsDifferByDefault` (default `sameContents` → `areContentsTheSame == false`).
- [ ] **Step 2: Run** `./gradlew :presentation:testDebugUnitTest --tests '*QkListAdapterDiffTest*'` — Expected: FAIL (class missing).
- [ ] **Step 3: Implement `QkListAdapter` and `asLiveList()`.** The diff callback is built from `itemId`/`sameContents`.
- [ ] **Step 4: Run the test** — Expected: PASS.
- [ ] **Step 5: Commit** `Add QkListAdapter and Realm asLiveList helper`.

### Task 2: Settings-style lists (blocked numbers, filters, trusted senders, sender rules)

**Files:**
- Modify: `domain/.../repository/{BlockingRepository,MessageContentFilterRepository,TrustedSenderRepository,SenderCategoryRuleRepository}.kt` and their `data/.../repository/*Impl.kt`
- Modify: the four adapters, their `*State.kt`, presenters/controllers under `presentation/.../feature/blocking/` and `.../settings/messagesorting/senders/`

**Interfaces:**
- Consumes: `QkListAdapter`, `asLiveList()` (Task 1).
- Produces (domain): `getBlockedNumbers(): Flow<List<BlockedNumber>>`, `getMessageContentFilters(): Flow<List<MessageContentFilter>>`, `getTrustedSenders(): Flow<List<TrustedSender>>`, `getRules(): Flow<List<SenderCategoryRule>>`. States hold `List<T>` (default `emptyList()`).

- [ ] **Step 1:** Change the four interface methods and impls to `Flow<List<T>>` via `asLiveList()`.
- [ ] **Step 2:** Presenters subscribe with `repo.getX().asObservable()` and put the list in state; adapters extend `QkListAdapter` with `itemId = { it.id }`.
- [ ] **Step 3: Build** `./gradlew :presentation:assembleDebug test` — Expected: BUILD SUCCESSFUL, 0 Kotlin warnings.
- [ ] **Step 4: Commit** `Settings lists read Flow instead of RealmResults`.

### Task 3: Message lists (trash, starred, blocked messages, gallery, scheduled)

**Files:** `MessageRepository` (`getDeletedMessages`, `getStarredMessages`, `getPartsForConversation`), `ScheduledMessageRepository` (`getScheduledMessages`, `getScheduledMessagesForConversation`), `ConversationRepository.getBlockedConversations*`, and the Trash, Starred, BlockedMessages, Gallery, Scheduled adapters/states/view models.

**Interfaces:**
- Produces: `getDeletedMessages(): Flow<List<Message>>`, `getStarredMessages(): Flow<List<Message>>`, `getPartsForConversation(threadId: Long): Flow<List<MmsPart>>`, `getScheduledMessages(): Flow<List<ScheduledMessage>>`, `getScheduledMessagesForConversation(conversationId: Long): Flow<List<ScheduledMessage>>`, `getBlockedConversations(): Flow<List<Conversation>>` (the `Async` variant is removed; callers use this one).
- `ScheduledMessage.recipients`/`attachments` keep their names; callers treat them as `List<String>`.

- [ ] **Step 1–2:** Same pattern as Task 2. `GalleryPagerAdapter` keeps its selected-page logic keyed by part id.
- [ ] **Step 3: Build and test** as Task 2 — Expected: PASS, 0 warnings.
- [ ] **Step 4: Commit** `Message lists read Flow instead of RealmResults`.

### Task 4: Compose thread and inbox (conversations, tabs, messages, contacts)

**Files:** `MessageRepository.getMessages(threadId, query)`, `getMessagesSync`, `getMessages(ids)`, `getLastIncomingMessage`, `getUnreadMessages`, `getUnreadUnseenMessages`, `getMediaPartsForMessage`, `getPartsForMessage`; `ConversationRepository.getConversations(...)` (both), `getConversationsByCategory`, `getRecipients`, `getConversationAsync`; `ContactRepository.getContacts`; `MainState`, `ComposeState`, `QkReplyState`, `MessagesAdapter`, `ConversationsAdapter`, `ConversationsPagerAdapter`, `StarredMessagesAdapter`, their view models.

**Interfaces:**
- Produces: live variants return `Flow<List<T>>` (`getMessages(threadId, query)`, `getConversations(unreadAtTop, archived)`, `getConversationsByCategory(unreadAtTop, category)`, `getConversationAsync(threadId): Flow<Conversation?>`, `getContacts()`, `getRecipients()`); synchronous variants (`getMessagesSync`, `getMessages(ids)`, `getLastIncomingMessage`, `getUnreadMessages`, `getUnreadUnseenMessages`, `getMediaPartsForMessage`, `getPartsForMessage`) return `List<T>` and are called only off the main thread.
- `MainState.tabData: Map<Tab, List<Conversation>>`; `ComposeState.messages: Pair<Conversation, List<Message>>?`; same for `QkReplyState`.

- [ ] **Step 1: Measure first (spec risk "long threads").** On the Note 10 debug build, log the largest thread's message count and the time from `getMessages(threadId)` emission to first `MessagesAdapter` bind for that thread. Record both numbers in the PR description.
- [ ] **Step 2:** Convert repositories, states, view models and adapters as in Task 2. `MessagesAdapter` keeps its per-ViewHolder link-preview and reaction logic; its `itemId = { it.id }`.
- [ ] **Step 3:** Re-measure the same thread on this build. If first bind is more than ~300 ms slower than Step 1, stop and record a `Ruling:` for Paging 3 before Task 7; otherwise continue.
- [ ] **Step 4: Build and test** — Expected: PASS, 0 warnings.
- [ ] **Step 5: Commit** `Inbox and compose read Flow instead of RealmResults`.

### Task 5: Remove Realm from presentation and from domain interfaces

**Files:** `MainViewModel.kt` (its 3 `Realm.getDefaultInstance` uses), `Conversation.kt:60` (`reactionEmoji()`), `EmojiReactionRepository.kt` (methods taking `realm: Realm`), `QkRealmAdapter.kt` (delete), `RealmExtensions.asObservable` users in presentation.

**Interfaces:**
- Produces: `SyncRepository.getLastSyncDate(): Flow<Long?>`; `EmojiReactionRepository.reparseAllEmojiReactions()` (no Realm parameter; `findTargetMessage`/`saveEmojiReaction`/`deleteAndReparseAllEmojiReactions` with a `Realm` parameter move to an `internal` class in `data`); `ConversationRepository.getReactionEmoji(conversation: Conversation): String?` replacing `Conversation.reactionEmoji()`.

- [ ] **Step 1:** Move each use; delete `QkRealmAdapter`.
- [ ] **Step 2: Verify** `grep -rl "io.realm" presentation/src/main` → no output; `grep -rl "io.realm" domain/src/main/java/com/moez/QKSMS/repository` → no output.
- [ ] **Step 3: Full check** `./gradlew clean :presentation:assembleDebug test :presentation:minifyReleaseWithR8 lint --no-build-cache` — Expected: BUILD SUCCESSFUL, 0 warnings.
- [ ] **Step 4: Note 10 check (owner):** inbox and all tabs, open a thread, search, compose, gallery, Trash (restore + delete forever), Starred, Scheduled, blocked numbers/messages, filters, trusted senders, sender rules, QRF Reply. Every list updates live when its data changes.
- [ ] **Step 5: Commit, open PR into `room-migration`.**

## Phase 2 — Room underneath

### Task 6: Room setup and test infrastructure

**Files:**
- Modify: root `build.gradle` (`ext.room_version`), `domain/build.gradle` (`androidx.room:room-common`), `data/build.gradle` (`room-runtime`, `room-ktx`, `ksp room-compiler`, `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`)
- Create: `data/src/main/java/com/moez/QKSMS/db/FoxholeDatabase.kt`, `data/src/main/java/com/moez/QKSMS/db/Converters.kt`, `data/schemas/` (checked in)
- Modify: `presentation/.../injection/AppModule.kt` (provide `FoxholeDatabase` singleton, `Room.databaseBuilder(context, FoxholeDatabase::class.java, "foxhole.db")`, no `allowMainThreadQueries`)
- Test: `data/src/test/java/com/moez/QKSMS/db/DatabaseOpensTest.kt`

**Interfaces:**
- Produces: `@Database(version = 1, exportSchema = true) abstract class FoxholeDatabase : RoomDatabase()`; `object StringListConverter` (`List<String>` ↔ `String`, encoding is the builder's).
- Produces: a test helper `fun inMemoryDatabase(): FoxholeDatabase` in `data/src/test/.../db/TestDatabase.kt`.

- [ ] **Step 1: Decide the JVM test runner** — try Room's bundled SQLite driver on the host JVM; if it doesn't work here, Robolectric. Record the choice as a `Ruling:` and in `CLAUDE.md` → Tests.
- [ ] **Step 2: Write `DatabaseOpensTest.opensAndCloses`** (in-memory database opens, `openHelper` / driver reports version 1). Run — Expected: FAIL (no database class).
- [ ] **Step 3: Add dependencies and `FoxholeDatabase`** (with a placeholder entity removed in Task 7 if Room requires ≥1 entity).
- [ ] **Step 4: Run the test** — Expected: PASS.
- [ ] **Step 5: Commit** `Add Room database and test setup`.

### Task 7 (one PR with Tasks 8–9): Core entities and DAOs

**Files:**
- Modify: all 15 files in `domain/src/main/java/com/moez/QKSMS/model/` (drop `RealmObject`/Realm annotations; add Room `@Entity`, `@PrimaryKey`, `@ColumnInfo(index = true)`)
- Create: `data/src/main/java/com/moez/QKSMS/db/dao/{MessageDao,MmsPartDao,ConversationDao,RecipientDao,ContactDao,EmojiReactionDao,ScheduledMessageDao,BlockingDao,FilterDao,TrustedSenderDao,SenderRuleDao,SyncLogDao}.kt`
- Create: `data/src/main/java/com/moez/QKSMS/db/entity/{ConversationRecipient,ContactGroupMember}.kt` (join tables)
- Test: `data/src/test/java/com/moez/QKSMS/db/{ConversationDaoTest,MessageDaoTest,AppOnlyTablesDaoTest}.kt`

**Interfaces (schema — exact columns):**
- `Message`: existing columns; `@PrimaryKey id`; indexes `threadId`, `category`, `deletedAt`; `@Ignore var parts: List<MmsPart>`, `@Ignore var emojiReactions: List<EmojiReaction>` (filled by repositories).
- `MmsPart`: `messageId` indexed (= MMS `contentId`); the `messages` back-link is removed (callers use `MessageDao.getMessageForPart(partId)`).
- `EmojiReaction`: adds `targetMessageId: Long` (indexed).
- `Conversation`: `lastMessageId: Long?` column; `@Ignore var recipients: List<Recipient>`, `@Ignore var lastMessage: Message?`; indexes `archived`, `blocked`, `pinned`, `categoryOverride`.
- `ConversationRecipient(conversationId: Long, recipientId: Long, position: Int)`, primary key `(conversationId, recipientId)`.
- `Recipient.contactLookupKey: String?` + `@Ignore var contact: Contact?`; `Contact.numbers` → `PhoneNumber.contactLookupKey`; `ContactGroupMember(groupId, lookupKey)`.
- `SyncLog`, `EmojiSyncNeeded`: `@PrimaryKey(autoGenerate = true) id: Long = 0`.
- `ScheduledMessage.recipients/attachments: List<String>` via `StringListConverter`.

**DAO queries the tests pin (one per Realm query family; SQL is the builder's):**
- `ConversationDao.observeConversations(archived: Boolean, unreadAtTop: Boolean): Flow<List<Conversation>>` — order: pinned first; unread first when `unreadAtTop`; then latest of `lastMessage.date`/`draftDate`; includes conversations with a non-empty draft and no `lastMessageId`; excludes blocked and those with no recipients.
- `MessageDao.unreadCount(): Long`, `searchMessages(query): List<Message>`, `mediaPartsForConversation(threadId): Flow<List<MmsPart>>`, `topConversationIds(sinceMs)` — all exclude `deletedAt != null`.

- [ ] **Step 1: Write the DAO tests** (in-memory database from Task 6):
  - `ConversationDaoTest`: `pinnedComeFirst`, `unreadAtTopOrdersUnreadFirst`, `draftOnlyConversationIsListed`, `blockedAndArchivedFilter`, `snippetSkipsTrashedLastMessage` (lastMessage recomputation via repository helper `latestVisibleMessageId(threadId)` returns the newest non-trashed message).
  - `MessageDaoTest`: `unreadCountIgnoresTrash`, `searchIgnoresTrash`, `galleryIgnoresTrash`, `topConversationsIgnoreTrash` (the four `TrashFiltersTest` cases), `searchMatchesBodyOrPartText`, `searchIsCaseInsensitiveForAscii` (`"HeLLo"` finds `"hello world"`), `searchAccentedBehaviourRecorded` (asserts whatever `"CAFÉ"` vs `"café"` returns, with a comment stating it).
  - `AppOnlyTablesDaoTest`: round-trip and live-Flow emission for ScheduledMessage (including recipient/attachment lists), BlockedNumber, TrustedSender, SenderCategoryRule, MessageContentFilter, SyncLog.
- [ ] **Step 2: Run** `./gradlew :data:testDebugUnitTest --tests '*DaoTest*'` — Expected: FAIL (entities/DAOs missing; compile errors expected until Task 9 — this PR compiles at the end of Task 9).
- [ ] **Step 3: Convert models and write DAOs** to the schema above.
- [ ] **Step 4:** Continue directly with Task 8 (the `data` module does not compile until repositories stop using Realm).

### Task 8: Repositories on Room

**Files:** all 11 `data/.../repository/*Impl.kt`, `KeyManagerImpl.kt`, `data/.../mapper/CursorTo*Impl.kt` (produce entities + join rows), `RealmExtensions.kt` (delete), `QKApplication.kt` (remove `Realm.init`/config from normal startup; Realm is opened only by the importer in Task 11).

**Interfaces:**
- Consumes: DAOs (Task 7); repository interfaces from Phase 1 (unchanged signatures).
- Produces: repositories fill the `@Ignore` relation fields (`Message.parts`, `Message.emojiReactions`, `Conversation.recipients`, `Conversation.lastMessage`, `Recipient.contact`, `Contact.numbers`) for every object they return. Writes go through `@Transaction` DAO methods or `database.withTransaction {}`.
- `KeyManager.newId()` = `max(Message.id)` from `MessageDao` + in-memory counter, `reset()` unchanged.

- [ ] **Step 1:** Rewrite each repository on its DAOs, one class per commit if useful; the build is red until all are done.
- [ ] **Step 2: Build** `./gradlew :presentation:assembleDebug` — Expected: BUILD SUCCESSFUL, 0 warnings. `grep -rl "io.realm" data/src/main domain/src/main presentation/src/main` lists only files Task 11 will own (none yet).
- [ ] **Step 3: Run** `./gradlew test` — Expected: all Task 7 DAO tests PASS; `SortingAndSyncDataTest` PASS.

### Task 9: Full sync on Room, drafts preserved

**Files:** `data/.../repository/SyncRepositoryImpl.kt` (and `AppOnlyMessageFields` at line ~491), `data/src/test/.../repository/SortingAndSyncDataTest.kt`.

**Interfaces:**
- Produces: `AppOnlyConversationFields` (snapshot of `archived, blocked, pinned, name, blockingClient, blockReason, categoryOverride, sendAsGroup, draft, draftDate`, keyed by conversation id), applied unconditionally after rebuild.

- [ ] **Step 1: Write tests** in `SortingAndSyncDataTest`: `draftsSurviveFullSync` (conversation with `draft = "hi"`, `draftDate = 5` keeps both after snapshot/re-apply), `sendAsGroupSurvivesWithoutOtherFlags`, and extend the "every field classified" check to `Conversation`.
- [ ] **Step 2: Run** — Expected: FAIL on the two new tests.
- [ ] **Step 3: Implement** the snapshot, inside one `database.withTransaction {}` for the whole sync.
- [ ] **Step 4: Run** `./gradlew test` — Expected: PASS.
- [ ] **Step 5: Full check** as Task 5 Step 3; **Note 10 check:** install, run Settings → Sync messages (full rebuild from the provider), then the Task 5 Step 4 screen list. App-only data on the Note 10 is expected to be empty at this point (the importer is Task 11).
- [ ] **Step 6: Commit; open the PR for Tasks 7–9 into `room-migration`.**

### Task 10: Remove the Realm models' runtime leftovers

**Files:** `presentation/proguard-rules.pro` (`-keep class dev.octoshrimpy.quik.model.**` stays until Task 13), `domain/build.gradle` & `presentation/build.gradle` (Realm plugin stays until Task 13 because the importer needs the native library), `QkRealmMigration.kt` (delete — the importer opens the file at its current schema version 18 with `DynamicRealm`), `QkMigration.kt` (remove Realm uses).

- [ ] **Step 1:** Delete `QkRealmMigration` and any remaining typed-Realm code.
- [ ] **Step 2: Verify** `grep -rln "io.realm" '--include=*.kt' . | grep -v /build/` → no output.
- [ ] **Step 3: Full check** — Expected: PASS, 0 warnings.
- [ ] **Step 4: Commit** `Remove typed Realm code`.

### Task 11: Importer (Build 1)

**Files:**
- Create: `data/src/main/java/com/moez/QKSMS/migration/RealmImporter.kt`, `.../migration/ImportState.kt`
- Modify: `QKApplication.kt` (start import on a background thread at launch), `MainActivity`/`MainViewModel` ("Moving your data…" state while `ImportState` is not `Done`), `Preferences.kt` (`roomImportVersion: Int`, default 0)
- Test: `data/src/androidTest/java/com/moez/QKSMS/migration/RealmImporterTest.kt`, `data/src/androidTest/.../migration/ReceiveDuringImportTest.kt`

**Interfaces:**
- Produces: `class RealmImporter @Inject constructor(context, database, syncRepo, prefs)` with `fun importIfNeeded(): ImportResult`; `const val IMPORTER_VERSION = 1`; `sealed class ImportState { NotNeeded, Running, Done(counts: Map<String, Pair<Long, Long>>), Failed(error: Throwable) }` exposed as `Flow<ImportState>`.
- Algorithm (not determined by the signature):
  1. If `default.realm` doesn't exist or `prefs.roomImportVersion >= IMPORTER_VERSION` → `NotNeeded`.
  2. Open `DynamicRealm` with `RealmConfiguration.Builder().schemaVersion(18)` (read-only use; no migration). If the file's schema version isn't 18 → `Failed`.
  3. Read the app-only tables and the app-only Message fields keyed `"${type}:${contentId}"` and Conversation fields keyed by id.
  4. In one Room transaction: clear every table, insert the app-only tables, store the Message/Conversation app-only snapshots where the full sync's re-apply step reads them.
  5. Run `syncRepo.syncMessages()` (rebuilds messages/conversations/contacts/reactions from the providers and re-applies the snapshots).
  6. Log per-table counts (Realm vs Room) with `Timber.i`; set `prefs.roomImportVersion = IMPORTER_VERSION`; never modify or delete the Realm file.
  7. Any exception → `Failed`, logged, marker unset; next launch retries.
- Receive path (`ReceiveSmsWorker`, `ReceiveMmsWorker`, notifications) never waits on `ImportState`.

- [ ] **Step 1: Write the on-device tests:**
  - `RealmImporterTest.importsEveryAppOnlyField`: build a v18 Realm file with `DynamicRealm` containing one row of each app-only table and messages/conversations with every app-only field set; run the importer against an in-memory Room database with a fake provider sync; assert each value arrived.
  - `RealmImporterTest.importIsRepeatable`: run twice (marker reset between) → identical row counts, no duplicates.
  - `RealmImporterTest.untouchedRealmFile`: file bytes/size and last-modified unchanged after import.
  - `ReceiveDuringImportTest.messageSavedWhileImportFailing`: importer forced to `Failed`; a received SMS is still written to the provider and posts a notification.
- [ ] **Step 2: Run on the Note 10** (`:data:assembleDebugAndroidTest`, install, `am instrument -w dev.octoshrimpy.quik.data.test/androidx.test.runner.AndroidJUnitRunner`, uninstall the test package) — Expected: FAIL (importer missing).
- [ ] **Step 3: Implement** `RealmImporter`, `ImportState`, the launch hook and the "Moving your data…" state.
- [ ] **Step 4: Run again** — Expected: all PASS.
- [ ] **Step 5: Real-data check on the Note 10:** install a 2.6.2-era Realm build, star/trash/categorise a few messages, add a scheduled message, blocked number, filter, trusted sender, sender rule, pin, rename, draft; then install this build. Expected: log shows matching Realm/Room counts per table and every item is present.
- [ ] **Step 6: Commit; PR into `room-migration`.**

### Task 12: Build 1 to the S23+

- [ ] **Step 1:** Merge `room-migration` into `master` (PR, CI green). Bump version (versionCode/versionName, `changelog.json`, `metadata/en-US/changelogs/<code>.txt`, F-Droid metadata, `CLAUDE.md` "Current shipped version") so `ReleaseConsistencyTest` passes.
- [ ] **Step 2: Owner, before installing:** Settings → Backup on the S23+.
- [ ] **Step 3:** Build and publish with `scratchpad/build_personal.sh`; owner installs on the S23+.
- [ ] **Step 4: Owner check list:** stars, Trash, tabs/categories, sender rules, trusted senders, filters, blocked numbers, scheduled messages, pins, custom names, drafts, archived, send a text, receive a text (notifies), OTP notification. Claude reads the import count log over adb.
- [ ] **Step 5:** If anything is missing: fix, bump `IMPORTER_VERSION`, ship Build 1.x (re-imports from the untouched Realm file).

### Task 13: Build 2 — remove Realm

**Files:** root `build.gradle` (realm classpath, `realm_version`), `domain/build.gradle` & `presentation/build.gradle` (`realm-android` plugin, `kotlin-kapt`, Realm kapt deps), `presentation/build.gradle` (`realm-android-adapters`), `RealmImporter.kt`/`ImportState.kt` (delete), `QKApplication.kt` (delete `default.realm*` files and `default.realm.management` once `roomImportVersion >= 1`), `presentation/proguard-rules.pro` (remove `-keep class dev.octoshrimpy.quik.model.**`), `ProguardRulesTest.kt` (drop that entry), `CLAUDE.md` (Realm sections → historical; new "Database" section).

- [ ] **Step 1: Write `RealmFilesCleanupTest`** (JVM, temp dir): with marker set, `default.realm`, `default.realm.lock`, `default.realm.management/` are deleted; with marker unset they are kept. Run — Expected: FAIL.
- [ ] **Step 2: Implement** the cleanup and remove everything listed.
- [ ] **Step 3: Verify:** `grep -rn "realm" '--include=*.gradle' .` → no output; `unzip -l <release apk> | grep -c librealm` → 0; APK size recorded (expected ≈16 MB vs 49.5 MB).
- [ ] **Step 4: Full check** — Expected: PASS, 0 warnings, no kapt tasks.
- [ ] **Step 5:** Note 10, then S23+ (release as in Task 12). Owner repeats the Task 12 Step 4 check.
- [ ] **Step 6:** Close #44 with the result; re-scope #34 to "move screens from RxJava to Flow gradually".
