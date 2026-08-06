# BlackNote — Final Build Plan

> **Historical document.** This was the initial design plan written before implementation. For the current architecture, behavior, and release history, see [`OVERVIEW.md`](OVERVIEW.md). For build/publish operations, see [`HANDOVER.md`](HANDOVER.md) and [`BUILD.md`](BUILD.md). Current version: **v1.9.4**.

A crisp, modern, dark-themed Android Markdown note-taking app matching the supplied screenshots exactly. Built with Jetpack Compose + Rust-via-UniFFI hybrid for native crispness + Rust speed/reliability.

---

## 1. Assessment of the Original Plan

The original plan is solid. Confirmed choices:

| Decision | Verdict | Notes |
|----------|---------|-------|
| **Jetpack Compose UI** | ✅ Keep | Native Material 3, buttery smooth animations, perfect screenshot fidelity. |
| **Rust core via UniFFI** | ✅ Keep, scope tightly | Use it only where it earns its keep: Markdown parsing (`pulldown-cmark`), full-text search index, file batch I/O. Do **not** push the editor state or Compose UI through the bridge — bridge cost dominates per-keystroke savings. |
| **compose-rich-editor** | ⚠️ Replace with custom `BasicTextField` + `AnnotatedString` styled by Rust-parsed CMark spans | The library is stable but heavy and doesn't allow exact toolbar/UX control. A thin custom editor + Rust span-emit gives smoother feel and exact toolbar match. |
| **Storage Access Framework (SAF) + persisted URI** | ✅ Keep | `ACTION_OPEN_DOCUMENT_TREE`, persistable read/write URI permission, `DocumentFile` for traversal, `ContentResolver` for streaming I/O. |
| **.md files in user folder** | ✅ Keep | Plain `.md`, optional YAML front-matter for `title` + `tags`. No DB; mirror to a Rust-built SQLite FTS5 index for fast search. |
| **Auto-save** | ✅ Keep | 500 ms debounce on edits + flush on lifecycle pause. |
| **Material You dynamic color** | ➕ Add | Honor system accent on Android 12+, fallback palette below. Background `#0F0F0F`, surface `#181818`, text `#ECECEC`, dim `#A0A0A0`, accent `#8AB4F8`. |

### Risks & mitigations
- **UniFFI build complexity** — pinned `uniffi 0.28`, single `cargo ndk` step, prebuilt `.so` for `arm64-v8a`, `armeabi-v7a`, `x86_64` checked into `app/src/main/jniLibs/` so a fresh device build doesn't require Rust toolchain.
- **SAF performance on large folders** — index in background on first run, then watch via `ContentResolver.registerContentObserver`. List view reads from the local FTS index, not SAF, except on demand.
- **Markdown round-trip fidelity** — `pulldown-cmark` parse + a small Rust re-emitter to keep formatting clean when the user toggles bold/italic. Never overwrite user file unless content actually changed.

---

## 2. Project Structure

```
/root/BlackNote/
├── docs/
│   ├── PLAN.md                     ← this file
│   └── BUILD.md                    ← build instructions
├── scripts/
│   └── final_export                ← publish APK to /var/www/downloads
├── rust-core/                      ← Rust UniFFI crate
│   ├── Cargo.toml
│   ├── build.rs
│   ├── uniffi.toml
│   └── src/
│       ├── lib.rs                  ← UniFFI entry, public API
│       ├── markdown.rs             ← pulldown-cmark parse → span list
│       ├── render.rs               ← span → AnnotatedString descriptors
│       ├── search.rs               ← SQLite FTS5 wrapper
│       └── io.rs                   ← byte-level Markdown read/write helpers
└── app/                            ← Android Gradle project (Kotlin + Compose)
    ├── settings.gradle.kts
    ├── build.gradle.kts            (root)
    ├── gradle/libs.versions.toml
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libblacknote.so
            ├── java/com/marsglorious/blacknote/
            │   ├── App.kt
            │   ├── MainActivity.kt
            │   ├── ui/
            │   │   ├── theme/{Color.kt,Type.kt,Theme.kt}
            │   │   ├── list/NoteListScreen.kt
            │   │   ├── editor/EditorScreen.kt
            │   │   ├── editor/FormatToolbar.kt
            │   │   ├── editor/RichTextField.kt
            │   │   └── common/{TopBarPill.kt,SearchField.kt}
            │   ├── data/
            │   │   ├── NoteRepository.kt   (SAF + Rust bridge)
            │   │   ├── SafStore.kt         (DocumentFile/ContentResolver)
            │   │   └── Note.kt
            │   ├── viewmodel/
            │   │   ├── NoteListViewModel.kt
            │   │   └── EditorViewModel.kt
            │   └── ffi/uniffi/blacknote/  (generated Kotlin bindings)
            └── res/
                ├── drawable/ (icons matching screenshots)
                ├── values/{strings.xml,themes.xml}
                └── values-night/themes.xml
```

---

## 3. UI Specification (pixel-faithful to screenshots)

### 3.1 Theme tokens
```kotlin
object MdColors {
    val Background = Color(0xFF0F0F0F)
    val Surface    = Color(0xFF181818)
    val SurfaceHi  = Color(0xFF222222)   // pill, toolbar, card
    val OnSurface  = Color(0xFFECECEC)
    val OnSurfaceDim = Color(0xFFA0A0A0)
    val Accent     = Color(0xFF8AB4F8)   // overridden by dynamicDarkColorScheme on Android 12+
    val Divider    = Color(0x14FFFFFF)
}
```
Typography: `Roboto Flex` if available else system. Title 22 sp / weight 600. Body 16 sp. Metadata 12 sp / dim.

### 3.2 Editor screen (Screenshot 1)
- **Top bar** (height 56 dp, transparent over `Background`):
  - Leading `IconButton` ← back arrow.
  - Pill button "All notes" (24 dp height, `SurfaceHi`, 100% corner radius, 12 dp horizontal padding, small leading folder icon).
  - Trailing row: search 🔍, book/outline 📖, list-toggle ☰. Each 40 dp tap target, `OnSurfaceDim`.
- **Title field**: full-width, no border, `Roboto Flex 28 sp / 600`, placeholder "Title", 8 dp top margin under top bar, 20 dp horizontal padding.
- **Content area**: weight=1, `BasicTextField` styled by `AnnotatedString` from Rust spans, 16 dp line height, scrollable, IME-aware insets.
- **Bottom toolbar** (height 52 dp, `SurfaceHi`, 16 dp corner radius top corners, sticky above IME): icons in order — Undo, Redo, **B**, *I*, U̲, S̶, 1. numbered list, • bullet list. 8 dp gap, ripple, toggled state shows accent fill.

### 3.3 List screen (Screenshot 2)
- **Top search bar** (`SurfaceHi`, 12 dp corners, 48 dp height, leading 🔍, "Search notes" placeholder).
- **LazyColumn** of cards:
  - Card: `Surface`, 16 dp corners, 12 dp inner padding, 8 dp vertical gap.
  - Line 1: `4/6/26, 1:36 am` — `12 sp / OnSurfaceDim`. Optional label chip right-aligned.
  - Line 2: **Title** — `18 sp / 600 / OnSurface`.
  - Line 3: 2-line content preview — `14 sp / OnSurfaceDim`, ellipsis.
- FAB bottom-right: rounded square (16 dp corner), 56 dp, `Accent`, **+** icon → new note.

### 3.4 Animations
- Shared element title transition list → editor (Compose 1.7 `sharedTransitionScope`).
- `AnimatedVisibility` (fade + slide) on toolbar appearance.
- `animateContentSize` on cards.

---

## 4. Rust Core — Public API (UniFFI)

```rust
// rust-core/src/lib.rs
#[derive(uniffi::Record)]
pub struct NoteMeta { pub path: String, pub title: String,
    pub preview: String, pub modified_millis: i64, pub label: Option<String> }

#[derive(uniffi::Enum)]
pub enum SpanStyle { Bold, Italic, Underline, Strike, Code,
    Heading(u8), BulletItem, NumberedItem(u32), Link(String) }

#[derive(uniffi::Record)]
pub struct StyledSpan { pub start: u32, pub end: u32, pub style: SpanStyle }

#[derive(uniffi::Record)]
pub struct ParsedDoc { pub plain: String, pub spans: Vec<StyledSpan> }

#[uniffi::export]
pub fn parse_markdown(src: String) -> ParsedDoc { ... }

#[uniffi::export]
pub fn apply_format(src: String, sel_start: u32, sel_end: u32,
                    style: SpanStyle, on: bool) -> String { ... }

#[uniffi::export]
pub fn extract_meta(path: String, bytes: Vec<u8>) -> NoteMeta { ... }

pub struct SearchIndex { /* sqlite + fts5 */ }
#[uniffi::export]
impl SearchIndex {
    #[uniffi::constructor] pub fn open(db_path: String) -> Arc<Self> { ... }
    pub fn upsert(&self, meta: NoteMeta, body: String) { ... }
    pub fn delete(&self, path: String) { ... }
    pub fn query(&self, q: String, limit: u32) -> Vec<NoteMeta> { ... }
}
```

Crates: `uniffi = "0.28"`, `pulldown-cmark = "0.10"`, `rusqlite = { version = "0.31", features=["bundled","fts5"] }`, `serde`, `chrono`.

---

## 5. Auto-save & Editor Pipeline

1. User types → Compose updates `TextFieldValue`.
2. Debounced (`500 ms`) `flow.collectLatest` triggers `Rust.parse_markdown` on a background dispatcher.
3. Spans → `AnnotatedString` patch applied via `remember(parsedDoc)`.
4. `NoteRepository.save(uri, text)` via `ContentResolver.openOutputStream(uri, "wt")`.
5. On `Lifecycle.Event.ON_PAUSE` → force flush + close.

Undo/Redo: in-memory stack of `TextFieldValue` snapshots (bounded 100). Toolbar B/I/U/S calls `Rust.apply_format`, replaces range, restores selection.

---

## 6. Storage Access Framework

- On first launch: empty state CTA "Pick notes folder" → `Intent(ACTION_OPEN_DOCUMENT_TREE)`.
- `contentResolver.takePersistableUriPermission(uri, READ|WRITE)`; store in `DataStore<Preferences>`.
- All file ops go through `DocumentFile.fromTreeUri(...)`.
- Folder watcher: `ContentResolver.registerContentObserver(treeUri, true, observer)` → re-index on change.

---

## 7. Build Configuration

`app/build.gradle.kts` essentials:
```kotlin
android {
    namespace = "com.marsglorious.blacknote"
    compileSdk = 35
    defaultConfig { minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    packaging { jniLibs.useLegacyPackaging = false }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("net.java.dev.jna:jna:5.14.0@aar")   // UniFFI runtime
}
```

`signingConfigs.debug` produces a self-signed debug APK suitable for sideloading.

---

## 8. Build Instructions (summary; full in `docs/BUILD.md`)

```bash
# One-time
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk uniffi-bindgen-cli

# Build Rust → .so for each ABI + Kotlin bindings
cd rust-core
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/app/src/main/jniLibs build --release
uniffi-bindgen-cli generate src/lib.rs --language kotlin \
  --out-dir ../app/app/src/main/java/com/marsglorious/blacknote/ffi

# Build APK
cd ../app
./gradlew :app:assembleRelease

# Publish to Nginx downloads
/root/BlackNote/scripts/final_export
```

---

## 9. `final_export` Contract

`scripts/final_export` is the **single source of truth** for publishing. Future iterations and agents must call it after any successful build instead of copying APKs by hand.

It:
1. Locates the freshest signed APK under `app/app/build/outputs/apk/`.
2. Copies it to `/var/www/downloads/blacknote.apk` (atomic via `mv` from a tempfile in the same filesystem).
3. Also writes a versioned snapshot `/var/www/downloads/blacknote-<versionName>-<gitsha>.apk` for rollback.
4. Sets permissions `0644`.
5. Prints the install URL (`https://<host>/downloads/blacknote.apk`).
6. Exits non-zero with a clear message on any failure (no silent partial publish).

This mirrors the established pattern from `MarsLegislationParser` (every rebuild publishes to `/var/www/downloads/`).

---

## 10. Definition of Done

- [ ] Editor screen matches Screenshot 1 (top bar pill, large title, sticky toolbar with all 8 icons).
- [ ] List screen matches Screenshot 2 (search, dated cards with title + preview, FAB).
- [ ] Toolbar formatting (B/I/U/S, ordered/bullet) applies and round-trips through Markdown.
- [ ] Auto-save 500 ms debounce + lifecycle flush.
- [ ] SAF folder pick persists across restarts; switching folders works.
- [ ] Search returns results <50 ms for 1k notes via Rust FTS5.
- [ ] Cold start <500 ms on a Pixel 6.
- [ ] APK installs on Android 8.0 (minSdk 26) and Android 15.
- [ ] `final_export` copies the APK to `/var/www/downloads/blacknote.apk`.

---

## 11. Out of Scope (explicit non-goals)

- Sync / cloud (files-on-disk only).
- iOS, desktop.
- Image attachments (Markdown image *links* render, but no embedded media editor).
- Collaborative editing.
- Custom themes UI (single tuned dark theme + Material You accent).
