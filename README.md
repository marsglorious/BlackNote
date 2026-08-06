# BlackNote

A dark-themed Android Markdown note-taking app. Compose UI on top of a Rust core (UniFFI-bridged) for parsing, format toggling, and SQLite FTS5 search. Notes live as plain `.md` files in a user-chosen folder via Android's Storage Access Framework.

**Current version:** 1.11.0 (`versionCode` 35 in `app/app/build.gradle.kts`).

> **Toolchain moved (July 2026):** this box has no `/opt` toolchains. See
> `docs/HANDOVER.md` §3 for the current Gradle/JDK/SDK paths (nix store + `~/.cache`).
> There is **no Android NDK here** — the Rust `.so` files in `jniLibs/` are prebuilt
> and checked in; behavioral changes must be made Kotlin-side until an NDK is installed.

## Documentation

| Doc | Purpose |
|---|---|
| [`docs/HANDOVER.md`](docs/HANDOVER.md) | **Start here** if you're a new maintainer or AI session — toolchain paths, build matrix, gotchas, recipes |
| [`docs/OVERVIEW.md`](docs/OVERVIEW.md) | Full architecture, data flow, decisions log, release history |
| [`docs/BUILD.md`](docs/BUILD.md) | Build and publish steps |
| [`docs/PLAN.md`](docs/PLAN.md) | Original design plan (historical; superseded by OVERVIEW for current behavior) |

**Publish to phone:** `scripts/final_export` copies the freshest APK to `/var/www/downloads/blacknote.apk` plus a versioned snapshot. Always use the script; never `cp` by hand.

## Layout

```
rust-core/    Rust + UniFFI — Markdown parse, format toggles, FTS5 index, fuzzy search
app/          Android Gradle project (Kotlin + Jetpack Compose)
docs/         HANDOVER, OVERVIEW, BUILD, PLAN
scripts/      final_export (publish APK)
```

## Quick build

```bash
# Rust → libblacknote.so per ABI
cd rust-core
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
cargo build --release --target x86_64-linux-android
cp target/aarch64-linux-android/release/libblacknote.so   ../app/app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libblacknote.so ../app/app/src/main/jniLibs/armeabi-v7a/
cp target/x86_64-linux-android/release/libblacknote.so    ../app/app/src/main/jniLibs/x86_64/

# Regen Kotlin bindings if the UDL changed
cargo run --release --bin uniffi-bindgen -- generate src/blacknote.udl \
  --language kotlin --out-dir /tmp/binds --config uniffi.toml
cp -r /tmp/binds/com/marsglorious/blacknote/ffi/* \
      ../app/app/src/main/java/com/marsglorious/blacknote/ffi/

# Build + publish APK
cd ../app
/opt/gradle-8.4/bin/gradle :app:assembleRelease --no-daemon
../scripts/final_export
```

## Tests

Two layers: JVM unit tests (Robolectric) and instrumented tests on a **managed Pixel 7 virtual device** (API 34, defined in `app/app/build.gradle.kts`).

```bash
./scripts/run_instrumented_tests.sh                        # unit + full emulator suite
# or manually:
cd app
/opt/gradle-8.4/bin/gradle :app:testDebugUnitTest          # JVM unit tests (Robolectric)
/opt/gradle-8.4/bin/gradle :app:pixel7DebugAndroidTest     # instrumented on managed Pixel 7
```

Instrumented suites in `app/app/src/androidTest/`:

| Suite | Covers |
|---|---|
| `NoteLifecycleInstrumentedTest` / `NoteLifecycleUiTest` | Open/close notes, create, persistence |
| `TrashInstrumentedTest` / `TrashUiTest` | Delete → trash, restore, permanent delete |
| `TextFormattingInstrumentedTest` / `TextFormattingUiTest` | Bold, italic, underline, strike, lists, undo/redo, preview |
| `RapidUsageInstrumentedTest` / `RapidUsageUiTest` | Speed and stability under rapid usage |
| `FullUiFeatureTest` | Menu, settings, trash, search, collage, folders, long-press menu |
| `EndToEndBugReproTest` | Regression repros for reported bugs |

Tests use `TestSafStore` (real on-device filesystem, no SAF picker) via `App.setRepositoryForTest()`.
