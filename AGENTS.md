# AI coding agent instructions

This file provides guidance to AI coding agents when working with code in this repository.

## Build

```bash
gradle assembleDebug       # debug build
gradle assembleRelease     # release build
```

No Gradle wrapper — uses system `gradle` command.

## API

The app consumes the MyNotes REST API which is specified in `../mynotes/openapi.yaml`.

The Retrofit API client is generated from `../mynotes/openapi.yaml` using the OpenAPI Generator Gradle Plugin. **Never edit generated files manually.**

To regenerate after spec changes:
```bash
gradle openApiGenerate
```

Generated files live in `app/build/generated/openapi/` and are excluded from version control. They are regenerated automatically before every build.

Hand-written files in `data/api/`:
- `RetrofitClient.kt` — OkHttp/Retrofit singleton with Basic Auth

Generated files (do not edit):
- `DefaultApi` — Retrofit interface for all MyNotes API endpoints
- `Note`, `NoteSummary`, `NoteList`, `CreateNoteRequest`, `UpdateNoteRequest`, `Artifact`, and other model classes

## Architecture

Native Android app (Kotlin, Jetpack Compose) that consumes the MyNotes REST API with HTTP Basic Auth, with full offline support.

**Layers:**
- **data/api/** — Generated Retrofit interface (`DefaultApi`), generated request/response models, HTTP client with Basic Auth interceptor (`RetrofitClient`)
- **data/local/** — Room database: `NoteEntity`, `PendingChange`, `ConflictEntity`, `ArtifactEntity` and their DAOs, `NoteMapper`
- **data/preferences/** — DataStore for server URL/offline mode (`UserPreferences`), EncryptedSharedPreferences for credentials (`CredentialStore`)
- **data/sync/** — `SyncWorker` (WorkManager periodic + one-time background sync)
- **data/** — `NoteRepository` (single source of truth, offline-first), `ArtifactRepository` (local image cache + deferred upload), `ConnectivityObserver`
- **ui/note/** — Note list, detail, and create/edit form (`NoteListScreen`, `NoteDetailScreen`, `NoteFormScreen` + ViewModels)
- **ui/conflict/** — Conflict list/detail screens for resolving version conflicts detected during sync
- **ui/settings/** — Server configuration screen (`SettingsScreen`, `SettingsViewModel`)
- **ui/navigation/** — Compose Navigation graph (`NavGraph`)
- **ui/theme/** — Material 3 theme with dynamic color support
- **util/** — `SlugGenerator` (mirrors the server's slug derivation), `NoteDateUtils` (RFC 3339 formatting)

**Key design decisions:**
- Single-activity architecture with Compose Navigation
- Notes are keyed by `slug` (client-assignable, mirrors the server's slugify algorithm) — no server-assigned numeric ID, so no temp-ID remapping is needed for offline-created notes
- Optimistic concurrency via `version`/`Etag`/`If-Match`: a 412 conflict during sync is never silently resolved — it's surfaced to the user via the Conflict screens
- `RetrofitClient` is a singleton that rebuilds the OkHttp/Retrofit instance when server URL or credentials change
- ViewModels use `AndroidViewModel` to access application context for Room/DataStore; no dependency injection framework
- Offline-first: all reads/writes go through Room; `NoteRepository` queues local mutations as `PendingChange` rows and `SyncWorker` replays them against the API
- There is no delta/"since" sync endpoint — `refreshNotes()` pages through `GET /notes` and diffs `(slug, version)` against the local cache, fetching full content only for changed notes
- Images embedded in note Markdown are content-addressed artifacts; offline-attached images are cached locally and uploaded (with content rewritten to the real URL) before the owning note's create/update syncs

## Version control

Git is used for version control. When creating new files, make sure to add them to Git.
