# MyNotes Android

Native Android client for the [MyNotes](https://github.com/mikaelstaldal/mynotes) personal notes server, with full offline support.

## Features

- Create, view, edit, and delete notes (Markdown content)
- Notes render exactly as in the web UI — callouts, inline icons, emoji shortcodes, wikilinks,
  AsciiMath and Mermaid diagrams — by embedding the server's shared render kit rather than
  reimplementing the Markdown dialect
- Full-text note search
- Shares its notes with [MyCal](https://github.com/mikaelstaldal/mycal-android) on the same device,
  so a calendar event can link a note and show it — offline, without either app talking to the
  other's server — see below
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

### Sharing notes with MyCal

MyNotes exports its notes, read-only, to other apps on the device through a content provider, so
[MyCal](https://github.com/mikaelstaldal/mycal-android) can link a note to a calendar event and show
it inline. Nothing needs configuring in either app — but they must be **signed with the same key**,
since the provider is guarded by a `signature`-level permission
(`nu.staldal.mynotes.permission.READ_NOTES`). When the keys differ, MyCal says so under Settings
instead of failing silently.

Only reads are exported: MyCal can show a note but never change one. Editing always happens here —
following a note link from MyCal opens this app.

#### Signing both apps with one key

The default `~/.android/debug.keystore` would satisfy the signature check, but it is a poor trust
anchor: world-readable, fixed password `android`, and shared by every debug APK built on the machine
— any of which would then be able to read all your notes, silently, since signature permissions are
granted at install with no prompt. Create one keystore of your own instead, and use it for both apps:

```bash
KS="$HOME/.android/staldal-apps.keystore"
PW="$(openssl rand -base64 24)"

keytool -genkeypair -v \
  -keystore "$KS" -storetype PKCS12 \
  -alias staldal-apps \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Mikael Staldal, O=staldal.nu, C=SE" \
  -storepass "$PW" -keypass "$PW"

chmod 600 "$KS"
echo "$PW"   # keep this — it cannot be recovered from the keystore
```

`-storepass` and `-keypass` are the same value on purpose: PKCS12 has no real support for a separate
key password. Then add the following to `local.properties` **in both repos**, with identical values
(that file is never checked in):

```properties
debugKeystore=/home/you/.android/staldal-apps.keystore
debugKeystorePassword=…
debugKeyAlias=staldal-apps
debugKeyPassword=…
```

`app/build.gradle.kts` picks these up for the debug build type. CI can supply the same values as
`DEBUG_KEYSTORE`, `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS` and `DEBUG_KEY_PASSWORD` instead. With
none of them set the build still works, but falls back to the default debug key and warns that it did.

Back the keystore up, and to build on another machine **copy the keystore file** rather than re-running
the command — a second run produces a different key, which breaks the signature match between the apps
and prevents upgrading anything already installed with the first one.

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
