# MyNotes Android

Native Android client for the [MyNotes](https://github.com/mikaelstaldal/mynotes) personal notes server, with full offline support.

## Features

- Create, view, edit, and delete notes (Markdown content)
- Notes render exactly as in the web UI — callouts, inline icons, emoji shortcodes, wikilinks,
  AsciiMath and Mermaid diagrams — by embedding the server's shared render kit rather than
  reimplementing the Markdown dialect
- Full-text note search
- Embedded images
- Works fully offline — changes queue locally and sync automatically when connectivity returns
- Conflict detection and resolution for edits made on multiple devices
- HTTP Basic Auth
- Material 3 with dynamic color (Material You) on Android 12+

## Requirements

- Android SDK (API 36)
- Gradle (system install, no wrapper)
- Min SDK: Android 8.0 (API 26)
- A running MyNotes server

## Build

```bash
gradle assembleDebug
```

The debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

The Markdown render kit is vendored (and committed) under `app/src/main/assets/renderer/`, so the
build needs nothing extra. To pick up changes to the dialect, refresh it from a local checkout of
the server repo — run `./build.sh` there first — and commit the result:

```bash
tools/sync-renderer.sh ../mynotes
```

## Setup

1. Install the APK on a device or emulator
2. On the first launch, you'll be prompted to configure the server
3. Enter the MyNotes server URL (e.g. `http://192.168.1.100:8080`), username, and password
4. Use "Test Connection" to verify connectivity before saving

## Tech Stack

- Kotlin
- Jetpack Compose
- Material 3
- Retrofit 2 + OkHttp 3
- Room (local database, offline cache)
- WorkManager (background sync)
- Preferences DataStore + EncryptedSharedPreferences

## API

This app consumes the MyNotes REST API. See the server's
[OpenAPI specification](https://github.com/mikaelstaldal/mynotes/blob/main/openapi.yaml) for details.

## License

Copyright 2026 Mikael Ståldal.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
