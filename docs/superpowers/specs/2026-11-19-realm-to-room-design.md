# Realm → Room migration — design

Issue: #44 (decision: migrate). Date: 2026-11-19.

## Intent

Replace Realm Java 10.19.0 with Room as the app's database.

Why, in the owner's terms:
- **Keep the app maintained and modern.** Realm Java is deprecated by MongoDB; 10.19.0 is likely its last release. Room is part of AndroidX and tracks Android, Kotlin and the build tools.
- **Smaller download.** The app is published as one universal APK on GitHub. Of the 49.5 MB v2.6.2 release APK, 33.4 MB is `librealm-jni.so` for four CPU types. Room uses the SQLite built into Android.
- **Simpler toolchain.** Realm's Gradle plugin rewrites compiled classes (it forced `Message.emojiReactions` to become `var` under Java 11) and is the likely blocker for AGP 9 (#36). Realm is also the only thing still on kapt (#35). Room runs on KSP and needs no plugin, so kapt leaves the project.

Only the owner's own phone runs the app (S23+ daily, Note 10 for testing). No Play Store or other users depend on this fork's builds.

**This work stays in the fork** (owner, 2026-11-19: it will not go to Erik's upstream `Curly815/FoxholeMessages`, which ships to Play). Consequence, accepted: future upstream fixes that touch Realm code won't apply to the fork without being rewritten for Room.

## Goals

- All 15 Realm models live in Room, and every repository reads and writes Room.
- **No app-only data is lost** on the owner's phone (list below).
- Message drafts survive a full sync (today they don't: `saveDraft` writes `Conversation.draft`/`draftDate`, and the full sync's preserved-field list leaves them out).
- The final release contains no Realm: no Realm dependency, Gradle plugin, keep rules, native libraries or kapt.
- The app behaves the same as today on every screen.

## Non-goals

- Rewriting screens off RxJava, or to Compose. Screens stay on RxJava; see "Live data".
- New features. Search behaviour, sorting, Trash rules and sync rules stay as they are.
- RxJava 3 (#34). After this migration, #34 should be re-scoped to "move screens from RxJava to Flow one at a time" rather than a 2→3 upgrade. Recorded as a follow-up, not part of this work.
- Full-text search (FTS). Search stays a substring match.
- Paging, unless the measurement under "Risks" says it's needed.

## Anti-goals (failure even if it "works")

- Any app-only data lost or silently reset (a star gone, Trash emptied back into threads, a sender rule or scheduled message missing).
- Messages appearing twice after the data move or after a sync.
- Database work on the main thread (Room refuses it; Realm also refuses writes, which caused the Trash "Delete forever" crash fixed in #59).
- A build that can't be rolled forward from a bad data move (see "Data move").

## Constraints

- minSdk 24, targetSdk 36, Java 11, Kotlin 2.2.21, KSP 2.2.21-2.0.4, AGP 8.11 (current state after #28–#35).
- Annotation processing is KSP; Room's processor must run on KSP.
- WorkManager rules in `CLAUDE.md` apply to any new or changed Worker (literal unique-work names; `KEEP` for periodic work; try/catch in `doWork`).
- R8: the release build is obfuscated (93%+). `ProguardRulesTest` asserts the keep rules that exist today.
- On-device tests must not use `connectedAndroidTest` (it uninstalls the app); see `CLAUDE.md` → Tests.

## What exists today (facts the plan depends on)

**Models** (`domain/src/main/java/com/moez/QKSMS/model/`, 15 Realm classes):

| Model | Key | Relations / notes |
|---|---|---|
| Message | `id` (app-assigned via `KeyManagerImpl.newId()`, reassigned on full sync) | `parts: RealmList<MmsPart>` (linked by `MmsPart.messageId == Message.contentId`), `emojiReactions: RealmList<EmojiReaction>` (reactions targeting this message), indexed `threadId` |
| MmsPart | `id` | `messageId` (= MMS contentId), back-link `@LinkingObjects("parts") messages` |
| Conversation | `id` (= threadId) | `recipients: RealmList<Recipient>`, `lastMessage: Message?`, indexed archived/blocked/pinned |
| Recipient | `id` | `contact: Contact?` |
| Contact | `lookupKey: String` | `numbers: RealmList<PhoneNumber>` |
| PhoneNumber, ContactGroup (`contacts: RealmList<Contact>`), EmojiReaction (indexed `reactionMessageId`, `threadId`) | `id` | |
| ScheduledMessage | `id` | `recipients`, `attachments`: `RealmList<String>` |
| BlockedNumber, TrustedSender, SenderCategoryRule, MessageContentFilter | `id` | app-only |
| SyncLog, EmojiSyncNeeded | none | |

Schema version 18; `data/.../migration/QkRealmMigration.kt` (351 lines). Fields added by migration with an index: `Message.category`, `Message.deletedAt`, `Conversation.categoryOverride`.

**App-only data** (cannot be rebuilt from Android's SMS/MMS or contacts providers):
- Message: `isStarred`, `deletedAt` (Trash), `category`, `isOtp`.
- Conversation: `archived`, `blocked`, `pinned`, `name`, `blockingClient`, `blockReason`, `categoryOverride`, `sendAsGroup`, `draft`, `draftDate`.
- Whole tables: ScheduledMessage, BlockedNumber, TrustedSender, SenderCategoryRule, MessageContentFilter, SyncLog (last sync time), EmojiSyncNeeded.

Everything else (messages, parts, conversations' membership, recipients, contacts, emoji reactions) is rebuilt by the full sync (`SyncRepositoryImpl.syncMessages`), which already snapshots and re-applies the Message app-only fields by provider identity (`AppOnlyMessageFields`, key `"type:contentId"`) and the Conversation app-only fields.

**Live data today:** 23 repository methods return `RealmResults` (mostly `findAllAsync()` on a never-closed Realm instance). 11 `*State` classes hold `RealmResults`. 12 adapters extend `QkRealmAdapter` (`RealmRecyclerViewAdapter`, self-updating). ViewModels turn Realm results into Rx via `RealmExtensions.asObservable()`.

**Queries needing care:** 27 sorts, 23 OR groups, 7 case-insensitive matches, link-path sorts on `lastMessage.*` (`ConversationRepositoryImpl:60-80`), "list not empty" filters on `recipients`, and case-insensitive substring search across `body` OR `parts.text` (`ConversationRepositoryImpl:160-162`, `MessageRepositoryImpl:114-116`).

## Approaches considered

1. **Room with Kotlin Flow in the data layer; screens keep RxJava through an adapter** — *chosen by the owner.* DAOs and repositories expose `Flow` (live) and `suspend`/plain calls (one-shot). ViewModels convert with `kotlinx-coroutines-rx2` (already a dependency). Screens change only where they held `RealmResults`. Sets up moving screens off RxJava one at a time later.
2. Room with RxJava everywhere (`room-rxjava2`). Least screen churn, but builds the new layer on the technology the owner eventually wants to leave.
3. Room, Flow and all screens rewritten at once. Most modern, but weeks of simultaneous change that is hard to verify on one phone.

## Design

### Schema mapping (owner-approved direction; table shapes are the builder's)

- One Room `@Entity` per Realm model; same column names as today's field names where possible.
- **Entities stay in `domain`** (where the models live); that module gets `room-common` annotations only. Database, DAOs and type converters live in `data`.
- Relations become columns or tables:
  - `Message.parts` → `MmsPart.messageId` (already present) joined on `Message.contentId` for MMS.
  - `Message.emojiReactions` → a `targetMessageId` column on `EmojiReaction`. (Today membership in the list *is* the link; `EmojiReactionRepositoryImpl` appends to `targetMessage.emojiReactions`.)
  - `Conversation.recipients` → join table `conversation_recipient(conversationId, recipientId, position)`.
  - `Conversation.lastMessage` → `lastMessageId` column; queries join on it (or a SQL view).
  - `Recipient.contact` → `contactLookupKey` column. `Contact.numbers` → `PhoneNumber.contactLookupKey`. `ContactGroup.contacts` → join table.
  - `ScheduledMessage.recipients/attachments` (lists of strings) → a type converter (builder's choice of encoding).
  - `SyncLog`, `EmojiSyncNeeded` get an auto-generated primary key.
- Indexes: every `@Index` field today, plus `Message.category`, `Message.deletedAt`, `Conversation.categoryOverride`.
- Message ids stay app-assigned (`KeyManager`), not auto-generated, so ids keep their meaning in intents, notifications and shortcuts.
- Computed model properties that query Realm from inside the model (e.g. `Conversation.reactionEmoji()` at `Conversation.kt:60`) move into repositories.
- Room schema export is on (`room.schemaLocation`), checked into the repo, with a Room migration test from v1 onward for any later schema change.

### Live data

- Repositories: live queries return `Flow<List<T>>` / `Flow<T?>`; writes and one-shot reads are `suspend` (or blocking calls already running on a background thread inside Workers/interactors). Domain repository interfaces change from `RealmResults<T>` to these types.
- ViewModels/presenters: where they consumed `RealmResults`, they convert the `Flow` with `asObservable()` (`kotlinx-coroutines-rx2`) and keep the rest of their Rx chains. `*State` classes hold `List<T>` instead of `RealmResults<T>`.
- Adapters: `QkRealmAdapter` is replaced by a `ListAdapter` base with `DiffUtil` keyed on the entity id, keeping today's empty-view behaviour. The 12 subclasses move to it.
- No database call on the main thread. Interactors already run on `Schedulers.io()`; any presenter that writes directly must hop threads (as #59 did for Trash).

### Sync

- `SyncRepositoryImpl.syncMessages` keeps its algorithm, inside one Room transaction: snapshot app-only fields → clear rebuilt tables → rebuild from providers → re-apply → recompute `lastMessage` (skipping Trash) → reparse reactions → log.
- **Fix:** `draft`, `draftDate` and `sendAsGroup` join the preserved Conversation fields unconditionally.
- `SortingAndSyncDataTest` (which fails if a new `Message` field isn't classified as app-only or rebuilt) is kept and extended to Conversation fields.

### Search and sorting

- Case-insensitive substring search uses `LIKE ... ` against `body` OR an `EXISTS` over parts' `text`. Note: SQLite's case folding is ASCII-only. Realm's `Case.INSENSITIVE` also only folds a limited character set; the builder records any difference found (e.g. accented letters) rather than adding ICU/FTS.
- Link-path sorts (`lastMessage.date`, `lastMessage.read`) become joins on `lastMessageId`.

### Data move (proposed by Claude; owner did not object)

Two builds, installed only on the owner's phone, about a day apart.

**Build 1 — "Room with importer"** (contains Realm and Room):
1. On launch, if the Realm file exists and the import marker for the current importer version isn't set, open Realm with today's configuration (migration chain to v18) on a background thread.
2. Read the app-only data listed above. Message fields are keyed by provider identity (`type:contentId`), exactly like `AppOnlyMessageFields`, because Realm message ids are not stable.
3. In one Room transaction: write the app-only tables and a snapshot of the app-only Message and Conversation fields; then run the full sync from the providers, which re-applies them.
4. Log per-table counts (Realm vs Room) and set the import marker. The Realm file is **not modified or deleted**.
5. Until the import has succeeded, screens show a "Moving your data…" state.
6. **Receiving is never paused** (Claude's call): incoming SMS/MMS are always written to Android's message store and always notify, whether the import is pending, running or failed. The full sync after a successful import picks them up. Accepted cost: messages that arrive while an import is failing are left unsorted (no category/OTP tag) until "Sort existing messages" is run.
7. If the import fails: log the error, leave the marker unset, retry on next launch.

**Roll back = roll forward.** Downgrading an installed release APK isn't possible without uninstalling (which deletes all app data), so rollback is a fixed Build 1.x: bumping the importer version re-runs the import from the untouched Realm file into a cleared Room database. The owner also makes a normal backup (Settings → Backup) before installing Build 1.

**Build 2 — "Room only"**: after the owner confirms Build 1 on the phone (stars, Trash, categories, rules, scheduled messages, blocked numbers, filters, pins, names, drafts), remove Realm entirely: dependency, plugin, `QkRealmMigration`, the importer, `RealmExtensions`, the `-keep class dev.octoshrimpy.quik.model.**` rule (and its `ProguardRulesTest` entry), kapt, and delete the old Realm files from the app's storage.

### Tests

- Room DAO and repository tests: run on the JVM if Room's bundled SQLite driver works for this setup; otherwise Robolectric; otherwise on-device (builder's choice, recorded).
- Port `TrashFiltersTest` (unread badge, search, gallery, shortcut ranking ignore Trash).
- The importer is tested on-device against a Realm file populated with every app-only field (Realm can't run on the JVM).
- Sync preservation (`SortingAndSyncDataTest`) extended with drafts.
- Each step is checked on the Note 10 before the S23+.

## Risks

- **Long threads.** Realm loads results lazily; Room loads the whole query result. Before building the compose screen's list, measure the largest thread's message count and its load time on the Note 10. If the first frame takes noticeably longer than today (more than ~300 ms), use Paging 3 for the message list. Builder decides from the measurement.
- **Search case folding** differences (see above).
- **Ids:** Realm reassigns Message ids on every full sync; anything that stores a Message id outside the database (notifications, scheduled work input data) must keep working after the data move's full sync, the same as after today's full sync.
- **Scale of change:** 60 files import Realm (domain 26, data 14, presentation 19); 114 `Realm.getDefaultInstance` calls in `data`. Effort estimate: 1–2 weeks.

## Left to the builder

- Room version (current stable that supports KSP 2.2.21), and whether to use the bundled SQLite driver.
- Exact table and column shapes within the mapping above; type-converter encodings.
- Whether `lastMessage` is a join or a view.
- Paging, from the measurement.
- Task order and how many PRs.

## Workflow (Claude's call)

- The work lives on a long-running branch (`room-migration`). Each step is a PR into that branch, built by CI and checked on the **Note 10 only**, with test data or the Note 10's own data.
- `master` is untouched until Build 1 is complete, so unrelated fixes and personal releases can still be cut from `master` during the migration.
- The S23+ gets Build 1 only when the whole branch is done and Build 1 has passed on the Note 10, then Build 2 after the owner's check.
