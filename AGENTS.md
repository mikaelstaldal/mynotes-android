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
- **provider/** — `NotesProvider`, the read-only content provider other apps read notes through, and its `NotesContract`; see **Notes provider** below
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
- **Markdown is not rendered in Kotlin.** The dialect (`../mynotes/markdown-spec.md`) is large — CommonMark + GFM subset, `[[…]]` wikilinks, `[!name]` inline Lucide icons, `>*`/`>-`/`>+` boxes and `[!alias]` callouts, `:shortcode:` emoji, `$…$` AsciiMath → MathML, ` ```mermaid ` diagrams — and every part of it is a client render-time transform. This app used to re-implement all of it on commonmark-java (down to a hand-port of the `asciimath2ml` JS library) and keep it in step with the web client by hand. It now embeds the web client's actual pipeline instead: see **Note rendering** below.

## Note rendering

A note's body is displayed by driving the **MyNotes render kit** — the web client's own Markdown
pipeline, packaged as a static page — in a WebView. There is no Kotlin implementation of the
Markdown dialect, so this app is at feature parity with the web UI by construction.

- The kit is vendored at `app/src/main/assets/renderer/`. Refresh it with
  `tools/sync-renderer.sh [path-to-mynotes]` (defaults to `../mynotes`), which delegates to that
  repo's `tools/dist-renderer.sh`; run `./build.sh` there first. **Commit the result** — the app
  renders offline, without the server. This replaced the old `gen-mermaid.sh` /
  `gen-lucide-icons.sh` / `gen-emoji.sh`, which vendored three pieces of the same pipeline.
- `ui/note/NoteRendererWebView.kt` owns the integration. It serves the kit over a real origin with
  `WebViewAssetLoader` (`https://appassets.androidplatform.net/assets/renderer/…`) rather than
  `loadDataWithBaseURL(null, …)`, because the page loads ES modules through an import map.
- **In:** Markdown and the theme are pushed with `evaluateJavascript` into the page's
  `MyNotesRender.render(markdown)` / `setTheme(theme, vars)` API, JSON-quoted. Note content is never
  spliced into HTML or JS syntax, and the kit's DOMPurify gate stays the only path to the DOM. The
  first push waits for `onPageFinished`. Material colours are passed as `--bg`/`--fg`/`--primary`
  overrides so the note blends into the app chrome while callouts/code/tables keep the canonical
  styling from the kit's `note.css`.
- **Out:** taps arrive at `shouldOverrideUrlLoading`. Wikilinks are the same root-relative
  `/notes/<slug>` / `/tags/<slug>` URLs the web UI emits (no more `mynotes://` scheme) and navigate
  in-app; http(s)/mailto open externally; anything else is blocked.
- **Requests:** `shouldInterceptRequest` is an *allow-list* — the kit's own asset files, plus image
  references resolved locally through `ArtifactRepository` (`artifactRefFor`). Everything else gets a
  403, so viewing a note makes no unauthenticated network request and a note embedding a third-party
  image cannot phone home. This preserves the posture the app had when it inlined images as `data:`
  URIs.
- Locally-attached images are `local-artifact://<id>` in the Markdown, a scheme the renderer's URL
  allow-list drops. `rewriteLocalArtifactRefs` rewrites them to `/local-artifact/<id>` before
  rendering, and the interceptor maps that back.
- JavaScript is now always enabled (the renderer *is* JavaScript), where it used to be enabled only
  for notes containing a Mermaid diagram.
- Icons: the renderer inlines every icon it knows as themed `<svg>`, so `/api/v1/icons/…` requests
  only escape for a name the vendored kit lacks — those render broken until the kit is re-synced.

## Sharing a note

The note view's share button offers the note as **Markdown** (its own source) or as **HTML** — a
standalone document, the app's counterpart to the web UI's "Download HTML"
(`../mynotes/web/ts/util/export.ts`). Both are written to `cacheDir/shared/<uuid>/<slug>.<ext>` and
handed out through `FileProvider` as `ACTION_SEND`; each share wipes the directory first, so a stale
grant cannot be replayed against a newer note.

`ui/note/NoteHtmlExport.kt` builds the HTML. Nothing about the Markdown dialect is re-implemented
for it — it drives the same vendored render kit the screen does, in a *throwaway, off-screen*
WebView (`renderKitWebView` + `RenderKitWebViewClient`, shared with `NoteRendererWebView`):

- Off-screen, not the visible WebView, so the export does not depend on what is currently on screen
  and renders under the kit's canonical palette rather than the Material colours the note view
  pushes in. The view is measured and laid out by hand, giving the page a viewport for Mermaid to
  measure in.
- The kit's `render()` resolves only once diagrams are drawn, so the rendered fragment is collected
  in its continuation and handed back over a `@JavascriptInterface` bridge added to that WebView
  alone. A render that never settles hits a 30 s timeout and is reported, not hung.
- "Standalone" means the file needs neither the server nor this app: the kit's own `note.css` is
  inlined (only the page frame and the print rules are written in Kotlin, so styling cannot drift
  from the kit), the theme is baked into `data-theme` on `<html>`, and every image the app can
  resolve becomes a `data:` URI via `ArtifactRepository.rewriteImageSrcToDataUris`. An image that
  cannot be resolved stays a URL and renders broken — as in the web export.

## Notes provider (integration with sibling apps)

`provider/NotesProvider.kt` exports this device's notes, read-only, to other apps —
`provider/NotesContract.kt` is the interface. It exists so a sibling app can *show* a note without
becoming a second notes client: no second database, no second set of credentials, no second sync.
Because it reads the same Room cache the app itself reads, a consumer inherits this app's offline
support for free. MyCal (`../mycal-android`) uses it for the note linked to a calendar event.

- **Published interface.** A consumer hard-codes the authority, paths, columns and permission name
  (MyCal duplicates them in its own `data/api/MyNotesClient.kt` — the two repos are built
  separately, so nothing can be shared). Add to `NotesContract`, don't rename.
- **Permission.** `nu.staldal.mynotes.permission.READ_NOTES`, `signature`. Only an app signed with
  the same key is granted it, at install time and with no prompt. That signing key is the whole of
  the trust boundary: the provider has no per-note authorization. Build both apps with the same key
  or the integration stays dark — the consumer's job is to detect that and say so.
- **Read-only.** Writes throw. A consumer that wants a note changed sends the user here with
  `ACTION_VIEW` on `NotesContract.noteUri` / `tagUri`, so editing always happens in this app under
  its own conflict handling. `MainActivity.routeForUri` turns those URIs into a destination; the
  matching intent filters are in the manifest.
- **Queries.** The URI and its parameters *are* the query surface — `selection` and `sortOrder` are
  rejected rather than passed to SQL, keeping callers away from the schema. Title-prefix matching
  (`NoteDao.searchByTitlePrefix`) escapes LIKE's wildcards so a typed `%` matches literally.
- **Images.** `artifacts/<sha256>` and `local-artifacts/<id>` stream through `ArtifactRepository`,
  the same path the app's own note view uses: local cache first, server only when configured and
  online. A consumer reads the bytes with `openInputStream` and the content type with `getType`;
  the provider memoizes the last resolution so that pair costs one fetch.
- **Content that was never downloaded.** A note synced as a summary only comes back with
  `has_full_content = 0` and empty content rather than a lie. Reading one note calls
  `NoteRepository.ensureFullContent` first, which fetches the body when online and is a no-op when
  not.

## Version control

Git is used for version control. When creating new files, make sure to add them to Git.
