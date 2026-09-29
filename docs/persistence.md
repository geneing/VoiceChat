# Conversation Persistence and Bounded Context (M05)

This document records how durable conversation history is stored and how the
smaller context sent to an LLM is chosen. It implements the M00 persistence
decision ([decisions.md](./decisions.md) §1: **Room 2.8.5 + KSP**) and the
dialog-history requirements in
[product-requirements.md](./product-requirements.md) and
[architecture.md](./architecture.md).

## Storage

- **Database:** Room `2.8.5` (`androidx.room:room-runtime`), code generated with
  KSP `2.3.11`. Room's KTX APIs merged into `room-runtime` in 2.7, so there is no
  separate `room-ktx` dependency.
- **Location:** app-private storage, `databases/voicechat-conversations.db`.
- **Domain purity:** Room entities and DAOs live in
  `com.voicechat.agent.persistence` only. They are mapped to the platform-free
  `com.voicechat.agent.domain` types by `ConversationMappers`, so the domain and
  contracts packages import no `android.*`/`androidx.*` type
  (`DomainPurityTest` still passes).

### Schema (version 1)

| Table | Columns |
| --- | --- |
| `conversations` | `id` (PK), `createdAtEpochMillis`, `updatedAtEpochMillis`, `title` |
| `turns` | `id` (PK), `conversationId` (FK → `conversations.id`, `ON DELETE CASCADE`, indexed), `position`, `kind`, plus grouped user/assistant columns |

`turns` stores both turn kinds in one table so a conversation keeps a single
monotonic `position` order. The populated columns are chosen by `kind`
(`USER`/`ASSISTANT`):

- User: `transcriptText`, `transcriptRevision`, `transcriptIsFinal`,
  `transcriptLanguageTag`, `transcriptConfidence`, `userTurnSource`.
- Assistant: `generatedText`, `generationState`, `deliveredText`,
  `deliveryState`.

Enum values are stored by their Kotlin `name`, matching the persisted-contract
rule already documented for `ErrorCode`. Interim and final transcripts are both
storable (`transcriptIsFinal`), and generated versus delivered assistant text is
kept separate so the stored conversation never claims the user heard text that
was never played.

### Versioning and migrations

Version 1 is the first schema. **There are no migrations yet.** The schema is
exported to
`app/schemas/com.voicechat.agent.persistence.ConversationDatabase/1.json` by the
KSP argument in `app/build.gradle.kts`. `ConversationSchemaExportTest` proves the
export exists, that its version matches `ConversationDatabase.VERSION`, and that
only the current version is exported. When a schema change is needed, add a
`Migration`, bump `ConversationDatabase.VERSION`, keep the previous exported
schema, and add a schema-to-schema migration test before the change ships.

## Repository operations

`RoomConversationRepository` implements the M02 `ConversationRepository`
contract exactly; no storage-specific method was added.

- **Create / save** (`save`): upserts the conversation and replaces its whole
  turn list in one transaction, so removed or reordered turns cannot be left
  behind.
- **List** (`observeConversations`): a cold Flow of `ConversationSummary`, newest
  `updatedAtEpochMillis` first (stable by ID).
- **Open / reopen** (`load`): reads the conversation and its turns in `position`
  order; returns `null` when the ID is absent.
- **Rename:** load, then `Conversation.renamed(title, updatedAtEpochMillis)`, then
  `save`.
- **Delete conversation** (`delete`): removes the row; `ON DELETE CASCADE`
  removes its turns. Deleting a missing ID is a no-op.
- **Delete a single turn:** load, then `Conversation.withoutTurn(turnId)`, then
  `save`.

Failures (read, write, or delete) are translated to `VoiceAgentException` with
`ErrorCode.PERSISTENCE_FAILED`. The exception message is the stable code name and
the detail names only the operation, so no transcript, prompt, or credential can
leak through an error. `CancellationException` is rethrown unchanged.

## Recovery after process death mid-turn

`Conversation.reconcileAfterProcessDeath()` reconciles state that a restart can
never finish, keeping the stored conversation truthful:

- A user turn whose transcript is not final was never committed and is dropped,
  so provisional recognition is not mistaken for a sent turn.
- Assistant generation still `IN_PROGRESS` becomes `CANCELLED`.
- Delivery that had not finished (`NOT_STARTED` or `SPEAKING`) becomes
  `INTERRUPTED`, preserving the delivered prefix as what the user actually heard.

Turns already in a terminal state are unchanged, so recovery is safe to run on
every reopen. Storage itself preserves the exact mid-turn state; recovery is an
explicit call so a live session is never clobbered by a load.

## Bounded model context

`ModelContextBuilder` (`com.voicechat.agent.domain.context`) is a separate,
platform-free, persistence-independent component that selects the messages sent
to a model for one request.

- **Bound.** At most `DEFAULT_MAX_MESSAGES = 20` messages and
  `DEFAULT_MAX_CHARACTERS = 4_000` characters, newest first. The newest message
  is always kept even if it alone exceeds the character bound, so a user's current
  request is never dropped; older messages must fit the remaining budget.
- **One conversation only.** The builder reads the single `Conversation` it is
  given and carries that `conversationId` in the window; it has no repository and
  cannot append another conversation's turns.
- **Truthful content.** Only final user transcripts and actually delivered
  assistant text are eligible; interim recognition and generated-but-unheard text
  are never sent.

Full local history stays in the repository; the UI can reopen it, and only the
bounded window is eligible for a request. A test proves an older conversation is
not silently added to a new request while the full history remains loadable.

## Retention, backup, and deletion

- Conversation data is kept until the user deletes it; there is no automatic
  expiry or remote sync.
- `android:allowBackup="false"` and
  `src/main/res/xml/data_extraction_rules.xml` exclude every domain from cloud
  backup and device-to-device transfer, so transcripts stay on the device and
  deletion is in-app only.
- Deleting a conversation removes its turns in the same database. There is no
  long-term personalization memory and no configurable prompt store in this
  milestone; those remain separate future work with their own consent and
  retention rules.

## Test strategy and trade-off

Room normally needs an Android runtime. Repository and schema tests run in
`:app:testDebugUnitTest` under **Robolectric `4.16.1`**, pinned to API 35 with
`@Config(sdk = [35])`:

- API 36 `android-all` jars require JDK 21; CI uses JDK 17, so API 35 keeps the
  suite runnable on both the local JBR and CI.
- Tests use `Room.inMemoryDatabaseBuilder` for CRUD/ordering/context checks and a
  file-backed database (close then reopen) to prove process-restart restoration.
- Robolectric downloads its `android-all` runtime from Maven on first run. That is
  a build-time artifact fetch through the normal dependency path, not a live
  service; tests still require no device, credentials, network API, or
  microphone. The alternative — instrumentation tests requiring a device — was
  rejected for repeatability in CI.
