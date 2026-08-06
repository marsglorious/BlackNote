# HANDOVER — BlackNote

**For the next agent / AI session / human maintainer dropped into this repo cold.** This is the operations manual. Architecture *why* is in [`OVERVIEW.md`](OVERVIEW.md).

> If you are a Claude session: there is also a project memory entry `project-blacknote-final-export` at `~/.claude/projects/-root/memory/`. Read it.

---

## 1. TL;DR

- **What:** Android Markdown note-taking app. Compose UI, Rust core (UniFFI). Files live in a user-picked SAF folder as plain `.md`.
- **Where it lives:** `/root/BlackNote/`
- **Where the APK goes:** `/var/www/downloads/blacknote.apk` — published by `scripts/final_export`. **Always** use the script. Never `cp` by hand.
- **Current version:** see `app/app/build.gradle.kts` `versionName`. Bump for every published change.
- **Git:** local repo, master branch. No remote yet. Commits use `marsglorious@gmail.com`.

---

## 2. The publish contract (read this twice)

After **any** successful release build, run:

```bash
/root/BlackNote/scripts/final_export
```

It atomically copies `app/app/build/outputs/apk/release/app-release.apk` to:
- `/var/www/downloads/blacknote.apk` (the install URL — what the user fetches)
- `/var/www/downloads/blacknote-<version>-<sha>.apk` (versioned snapshot for rollback)

Do not `cp` by hand. Do not skip the version bump. The script is the contract; deviations cause silent drift between "what's deployed" and "what's archived."

This mirrors the older `MarsLegislationParser` pattern (see `feedback_nginx_downloads_publish.md` memory). Same rule, same reason.

---

## 3. Toolchain (updated July 2026 — the box migrated; /opt paths are GONE)

The repo now lives at `/home/mars/root/BlackNote/`. Old `/opt/*` toolchains do not exist here.

| Tool | Path |
|---|---|
| Android SDK | `/home/mars/.cache/blacknote-android-sdk` (platform-34, build-tools 34) |
| Android NDK | **not installed** — Rust `.so` files can't be rebuilt on this box; the prebuilt ones in `jniLibs/` are checked in. Make behavioral changes Kotlin-side. |
| Gradle | `/nix/store/hwcbpab3i98nbx7alfq4ggsfgv3i2kc3-gradle-8.14.4/bin/gradle` |
| JDK 17 | `/nix/store/q6zvjlnhypm88y989l8fq0hjqrz9agda-openjdk-17.0.20+2` (set `JAVA_HOME`) |
| Rust + cargo | `/home/mars/root/.cargo/bin/` (rustup; set `RUSTUP_HOME=/home/mars/root/.rustup`, `CARGO_HOME=/home/mars/root/.cargo`) |
| Host C wrapper | `/nix/store/xcnqqnhw9hb4j5rjgds2yjryi8qki5f3-gcc-wrapper-15.2.0/bin/cc` (x86_64 — the `v72…` one is i686, don't use it) |
| Cargo config | `~/.cargo/config.toml` — host linker updated; Android linker entries still point at the missing NDK |

Typical build invocation:

```bash
export JAVA_HOME=/nix/store/q6zvjlnhypm88y989l8fq0hjqrz9agda-openjdk-17.0.20+2
export PATH=$JAVA_HOME/bin:$PATH
cd /home/mars/root/BlackNote/app
/nix/store/hwcbpab3i98nbx7alfq4ggsfgv3i2kc3-gradle-8.14.4/bin/gradle :app:assembleRelease
```

There is **no `/dev/kvm`** (and no host VMX/SVM) on this box — pure software TCG only.
Gradle Managed Devices (`:app:pixel7DebugAndroidTest`) do not work with the Nix-wrapped
emulator. Use the local TCG path:

```bash
./scripts/run_emulator_tests.sh          # boots API 30 AOSP under TCG + runs instrumented
```

Without KVM the script:
1. Uses API 30 AOSP x86_64 (API 34 google_apis is too heavy under TCG).
2. Starts with `-no-accel -gpu swiftshader_indirect -feature -ModemSimulator`.
3. Patches vendor.img to disable zram swapon (otherwise zygote OOMs right after boot).
4. Soft-reboots after first boot, then installs via `adb` + `am instrument`
   (AGP `connectedDebugAndroidTest` hits short ddmlib timeouts → "Unknown API Level").

Cold boot is ~15–30 min. When `/dev/kvm` exists, the script uses API 34 + hardware accel.

Still gate on JVM unit tests when you cannot wait for TCG:
`:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin`.

`local.properties` (gitignored) points the Android project at the SDK:
```
sdk.dir=/home/mars/.cache/blacknote-android-sdk
```

---

## 4. The three-path build matrix

### 4a. Pure Kotlin change (UI tweak, new ViewModel method, layout fix)

```bash
cd /root/BlackNote/app
/opt/gradle-8.4/bin/gradle :app:assembleRelease
/root/BlackNote/scripts/final_export
```

Build takes ~1m 30s. **You do not need to touch Rust.** The `.so` files in `app/app/src/main/jniLibs/` are checked into git.

### 4b. Rust change without API change (algorithm bugfix in `fuzzy.rs`, `meta.rs`, etc.)

```bash
NDK=/opt/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC_aarch64_linux_android="$NDK/aarch64-linux-android24-clang" AR_aarch64_linux_android="$NDK/llvm-ar"
export CC_armv7_linux_androideabi="$NDK/armv7a-linux-androideabi24-clang" AR_armv7_linux_androideabi="$NDK/llvm-ar"
export CC_x86_64_linux_android="$NDK/x86_64-linux-android24-clang" AR_x86_64_linux_android="$NDK/llvm-ar"

cd /root/BlackNote/rust-core
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
cargo build --release --target x86_64-linux-android

cp target/aarch64-linux-android/release/libblacknote.so   ../app/app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libblacknote.so ../app/app/src/main/jniLibs/armeabi-v7a/
cp target/x86_64-linux-android/release/libblacknote.so    ../app/app/src/main/jniLibs/x86_64/

cd /root/BlackNote/app
/opt/gradle-8.4/bin/gradle :app:assembleRelease
/root/BlackNote/scripts/final_export
```

Each ABI compile is ~50 s. Three ABIs is ~2 minutes 30 seconds. Cross-compiles in parallel via shell `&` aren't free — they share cargo's target dir and cause lock contention. Sequential is fine.

### 4c. Rust API change (UDL edit — adding/removing/renaming a function or field)

Same as 4b, plus regenerate the Kotlin bindings:

```bash
cd /root/BlackNote/rust-core
rm -rf /tmp/binds
cargo run --release --bin uniffi-bindgen -- generate src/blacknote.udl \
    --language kotlin --out-dir /tmp/binds --config uniffi.toml
rm -rf /root/BlackNote/app/app/src/main/java/com/marsglorious/blacknote/ffi/*
cp -r /tmp/binds/com/marsglorious/blacknote/ffi/* \
      /root/BlackNote/app/app/src/main/java/com/marsglorious/blacknote/ffi/
```

The bindings are checked into git so a clean build doesn't need bindgen. **If you edit the UDL, regen and commit the new bindings together.**

---

## 5. Common gotchas (these have bitten us)

1. **Smart-cast doesn't survive lambdas.**
   ```kotlin
   if (index != null) {
       runCatching { index.upsert(...) }  // ❌ unresolved — capture is non-final
   }
   val idx = index
   if (idx != null) {
       runCatching { idx.upsert(...) }   // ✅
   }
   ```

2. **Compose BOM 2024.02 (Compose 1.6.x) crashes on Android 16.** The minimum is BOM 2024.10.01. Don't downgrade.

3. **Material `Scaffold` is forbidden.** Compose 1.6.x's Scaffold corrupts SlotTable on SDK 36. Even after bumping Compose we don't use it. Use `Box` + `windowInsetsPadding(WindowInsets.systemBars)` + `imePadding()`.

4. **SAF URIs are not filesystem paths.** Never `Path::new(uri).file_stem()`. Pass the actual filename through as a parameter (see `extract_meta`'s `file_name`).

5. **`ContentResolver.openInputStream` throws `FileNotFoundException`** on stale URIs — catch it. `SafStore.readText` already does. Never let it propagate.

6. **FTS5 schema changes can't `ALTER TABLE`.** `SearchIndex::new` checks `pragma_table_info('notes')` for the required column set and `DROP TABLE` + recreates if missing. Add new columns to `REQUIRED_COLS` in `index.rs`. Migration is automatic.

7. **The `tags` column in FTS5 is space-joined.** `parse_tag_array` ↔ `tags.join(" ")`. Don't add tags with spaces inside them or the join roundtrip breaks.

8. **`setQuery` must update state synchronously.** If you wrap the `_ui.update` in a `launch { }`, the IME and field value diverge and the user sees scrambled letters ("ocialism" instead of "socialism"). Do the field update first, then launch the search.

9. **`openNote` is read-first, not eager-flip.** v1.4.0 flipped to the editor before the read completed; that caused flash-and-bounce when reads failed transiently. Current code reads (with retry), then opens. Don't revert to eager-flip without handling the failure path.

10. **Trash folder is `Trash`, not `.Trash`.** Samsung's ExternalStorageProvider hides or refuses dot-prefixed directories. We still skip/list both names for backwards compatibility.

11. **Title comes from the filename, not front-matter.** `NoteRepository.walk` calls `titleFromFileName(fileName)` and overwrites `extract_meta`'s title. The editor title field edits the filename; the body is saved as-is.

12. **`moveDocumentCompat` skips `copyDocument`.** Samsung returns a non-null URI from `copyDocument` even when no file exists at the destination. The manual read→create→write→verify→delete fallback is the safe path.

13. **Check `writeText` return value.** `repo.write` returns `false` on failure; autosave and `closeEditor` log via `CrashReporter` instead of silently dropping edits.

14. **`viewModels { factory }` lazy-instantiates.** The `AppViewModel` is constructed the first time it's accessed inside a Composable, not at Activity creation. If you want eager work, do it in `App.onCreate`.

15. **`final_export` is the contract.** Don't manually `cp` APKs. Don't skip the version bump. The script writes the snapshot for rollback; manual copies break that history.

---

## 6. Common changes — recipes

### Add a new FFI function

1. Edit `rust-core/src/blacknote.udl` — add the function to the `namespace` block.
2. Implement in Rust (`rust-core/src/<module>.rs`).
3. Re-export from `lib.rs`.
4. Cross-compile (path 4c above).
5. Use the generated Kotlin function — it's snake_case in UDL, camelCase in Kotlin.

### Add a new column to the FTS index

1. Edit `index.rs`:
   - Add to `REQUIRED_COLS`.
   - Add to the `CREATE VIRTUAL TABLE` statement.
   - Add to `upsert` signature and `INSERT` statement.
   - Add to `query` and `all_sorted` `SELECT` lists.
   - Update `row_to_meta`.
2. Edit `blacknote.udl` — `NoteMeta` dictionary and `upsert` signature.
3. Edit `meta.rs` — `extract_meta` populates the new field.
4. Edit Kotlin `Note.kt` — add field.
5. Edit `NoteRepository.kt` — pass through in upsert calls.
6. Cross-compile + regen bindings (path 4c).
7. On next launch the schema mismatch will trigger an auto `DROP TABLE` + recreate.

### Add a new Composable screen

1. Add to `Screen` enum in `AppViewModel.kt`.
2. Add open/close methods in `AppViewModel`.
3. Write the Composable in `ui/list/` or `ui/editor/`.
4. Route it in `MainActivity.kt`'s `when (ui.screen)`.

### Bump a version

1. Edit `app/app/build.gradle.kts`: `versionCode` (always +1) and `versionName`.
2. Build + publish.
3. Commit. The git sha gets baked into the snapshot filename automatically.

### Run tests

```bash
cd /root/BlackNote/app
/opt/gradle-8.4/bin/gradle :app:testDebugUnitTest          # fast, JVM-only
/opt/gradle-8.4/bin/gradle :app:pixel7DebugAndroidTest     # needs managed emulator
```

Instrumented tests use `TestSafStore` (real filesystem, no SAF picker) and `App.setRepositoryForTest()` to drive production `AppViewModel` code. See `EndToEndBugReproTest.kt`.

---

## 7a. State of play (v1.11.0 — Fable 5 session, July 2026)

Start marker: commit `Fable 5 start`. Everything after it is that session's work.

**Fixed:**
- Format toolbar now toggles (Bold twice un-bolds) and is UTF-16-correct for emoji/CJK.
  Formatting moved from Rust `apply_format` to Kotlin `data/MarkdownFormat.kt` (also
  removes an FFI hop). The Rust function still exists but is no longer called.
- `copyTo` verified + manual fallback (`SafStore.copyDocumentCompat`) — provider
  `copyDocument` lies on Samsung, same as move/rename did.
- Unicode titles survive: `sanitizeFileName` blacklists only illegal chars instead of
  whitelisting ASCII (was: "日記" → "Untitled").
- Switching notes folder clears the old tree (was: both folders' notes interleaved).
- Search consults the FTS index for body-only matches (index existed since v1.0, was never queried).
- Undo/redo is a single title+body timeline (was: body always undid before title).
- System back on Trash/Settings returns to list (was: exited app).
- "Delete forever" asks first; optimistic just-saved cards keep their creation date;
  search coroutines cancel per keystroke.

**Added:** pin notes (persisted), sort menu (newest/oldest/recently-edited/title),
Empty trash, share (editor + long-press), tap tag to filter, folder long-press →
"New note here", clear-search ✕, `[[wiki link]]` tap-to-open in preview,
persisted view mode / expanded folders / sort.

**Tests:** 69 JVM unit tests (was 29). New suites: `MarkdownFormatTest`,
`SanitizeAndCopyTest`, `AppViewModelFeatureTest`; `EditorHistoryTest` rewritten for
the snapshot history.

## 7. State of play (v1.9.4 — historical)

**Working:**
- Everything from v1.4.0 (see [`OVERVIEW.md`](OVERVIEW.md) §12 release history), plus:
- **Title = filename minus `.md`** — list title always matches the file manager; body saved verbatim (no `# Title` prepended).
- **`Trash/` folder** (no leading dot) with legacy `.Trash/` recognition — fixes Samsung hiding dot-prefixed dirs.
- **SAF robustness layer** — two-tier read/write, `moveDocumentCompat`/`renameDocumentCompat` with manual fallback and post-move verification.
- **Optimistic delete with rollback** — UI removes note immediately; if trash move fails, tree is refreshed and note reappears.
- **Read-first `openNote`** — retry + `refreshTreeAwait` recovery; no more editor flash-and-bounce on transient SAF errors.
- **Scroll position preserved** across editor round-trips (list + collage).
- **Test suite** — JVM unit tests (Robolectric) + instrumented E2E via `TestSafStore` on managed Pixel 7 emulator.

**Known TODOs (none blocking):**
- Wiki-link tap navigation (title→path map).
- Persist `expandedFolders` across launches.
- Trash sidecar JSON to track original parent for Restore.
- Incremental SAF re-index using mtime.
- SQLite transaction around bulk upserts (faster first-run with many notes).
- `final_export --fast` for debug-only/arm64-only iteration.
- Tag chip → filter view.
- Long-press a folder → "New note here".

**Hardware tested on:**
- Samsung Galaxy S22 (SM-S901E), Android 16 (SDK 36), arm64-v8a.

---

## 8. Memory references (Claude sessions)

The user's persistent memory at `~/.claude/projects/-root/memory/`:

- `project-blacknote-final-export.md` — the publish contract.
- `feedback-nginx-downloads-publish.md` — older pattern this mirrors (MarsLegislationParser).
- `feedback-archive-output-html.md` — unrelated.

Update these if the contract changes.

---

## 9. If something breaks

**Build fails** with cryptic linker error → check `~/.cargo/config.toml` exists and the `gcc-wrapper-15.2.0` nix store path is still valid (`ls /nix/store/788mx070y81zjlg5ipcl0cra3afviw9k-gcc-wrapper-15.2.0/bin/cc`). Nix garbage collection can remove it; if it has, find a newer wrapper with `find /nix/store -maxdepth 1 -name 'gcc-wrapper-*' -type d | head -1` and update the config.

**Gradle build hangs** → `--no-daemon` was a workaround for early crashes; daemon is on now via `gradle.properties`. If it hangs, `pkill -f gradle` and try again. Stale daemons sometimes get into a bad state.

**App crashes on phone** → file should appear at `/sdcard/Download/blacknote-crash.txt`. The user can `cat` it from Termux. If even that file doesn't appear, the crash is pre-`App.onCreate()` (process spawn / native lib load failure) — diagnose via logcat only.

**FFI changed but Kotlin won't compile** → you forgot to regen the bindings (path 4c). The `ffi/` directory must match the UDL exactly.

**A change made it past CI but the APK is wrong** → check `versionCode` was bumped. AGP will happily produce identical-version APKs and the user's Android installer will refuse the update with no useful error.

---

## 10. The single most important thing

`/root/BlackNote/scripts/final_export` after every successful build. Always. The script is what makes the app reach the phone, and every iteration of this project has been about closing the loop between "the code changed" and "the user can install it." Don't break the loop.
