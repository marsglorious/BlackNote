# BlackNote — Full Design Overview

A dark-themed Android Markdown note-taking app. Compose UI on top of a Rust core via UniFFI. Files-on-disk only (no cloud) — notes live in a user-chosen folder via the Storage Access Framework.

Long-form companion to [`PLAN.md`](PLAN.md). Currently at **v1.9.4**.

> If you're a future AI session or maintainer dropped into this codebase: read [`HANDOVER.md`](HANDOVER.md) first. It's the operations manual. This file is the *why*.

---

## 1. Project structure

```
BlackNote/
├── rust-core/                         Rust crate, UniFFI-exported FFI
│   ├── Cargo.toml
│   ├── build.rs                      ← runs uniffi::generate_scaffolding
│   ├── uniffi.toml                   ← Kotlin bindgen config
│   └── src/
│       ├── blacknote.udl             ← THE FFI contract
│       ├── lib.rs                    ← module exports + scaffolding include
│       ├── markdown.rs               ← pulldown-cmark parser → spans
│       ├── format.rs                 ← bold/italic/list toggling on raw text
│       ├── meta.rs                   ← YAML front-matter, title, preview, hashtags
│       ├── search.rs                 ← in-memory ranked substring fallback
│       ├── fuzzy.rs                  ← fzy-style subsequence matching + highlight positions
│       ├── index.rs                  ← SQLite FTS5 wrapper (rusqlite + bundled)
│       └── bin/uniffi-bindgen.rs     ← stub binary used for Kotlin bindgen
├── app/                               Android Gradle project
│   ├── settings.gradle.kts
│   ├── build.gradle.kts              ← root: AGP/Kotlin plugin versions
│   ├── gradle.properties             ← daemon, parallel, cache, -Xmx3g
│   └── app/
│       ├── build.gradle.kts          ← module: compileSdk, deps, ABIs, lint off
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── jniLibs/<abi>/libblacknote.so   (Rust per-ABI build outputs)
│           ├── java/com/marsglorious/blacknote/
│           │   ├── App.kt                       ← Application; opens SearchIndex
│           │   ├── MainActivity.kt              ← single-Activity, Screen router
│           │   ├── CrashReporter.kt             ← uncaught-exception → file + MediaStore
│           │   ├── ffi/                         ← UniFFI-generated Kotlin bindings
│           │   ├── data/
│           │   │   ├── Note.kt + TreeEntry.kt   ← models, Trash/ + legacy .Trash/ const
│           │   │   ├── SafStore.kt              ← SAF + DataStore wrapper
│           │   │   └── NoteRepository.kt        ← tree walk, mutations, index sync
│           │   ├── viewmodel/
│           │   │   ├── EditorHistory.kt         ← undo/redo with coalescing
│           │   │   └── AppViewModel.kt          ← single VM, all UI state
│           │   └── ui/
│           │       ├── theme/                   ← Color, Type, Theme
│           │       ├── CrashBanner.kt           ← persistent crash log surfacing
│           │       ├── editor/
│           │       │   ├── EditorScreen.kt      ← edit + preview, BackHandler
│           │       │   ├── FormatToolbar.kt     ← undo/redo/B/I/U/S/lists
│           │       │   ├── MarkdownStyler.kt    ← inline styler + render renderer
│           │       │   └── RenderView.kt        ← preview using rendered string
│           │       └── list/
│           │           ├── NoteListScreen.kt    ← tree + search + hamburger
│           │           ├── CollageView.kt       ← LazyVerticalStaggeredGrid
│           │           ├── BasicTextFieldCompat ← placeholder-aware text field
│           │           ├── ScrollbarModifier    ← fading scrollbar overlay
│           │           ├── FolderPickerDialog   ← move/copy target picker
│           │           ├── NewFolderDialog
│           │           ├── TrashScreen
│           │           └── SettingsScreen
│           └── res/
│               ├── drawable/ic_launcher_{bg,fg}.xml (white 8-point star on black)
│               ├── mipmap-anydpi-v26/ic_launcher.xml
│               ├── values/{strings,themes}.xml
│               └── values-night/themes.xml
├── docs/PLAN.md  BUILD.md  OVERVIEW.md (this file)  HANDOVER.md
└── scripts/final_export                 ← publish APK to /var/www/downloads/
```

### Why split Rust ↔ Kotlin like this?

The bridge is kept **narrow** and **stateless where possible**. Across UniFFI we pay marshalling cost per call (buffer lower/lift, JNA roundtrip), so it's worth crossing only when the work meaningfully exceeds the bridge cost.

| Operation | Where | Why |
|---|---|---|
| Markdown parse to spans | Rust | `pulldown-cmark` is fast + correct |
| Format toggle (`apply_format`) | Rust | Pure string transform; pairs naturally with parse |
| Title / preview / hashtag extraction | Rust | One pass over the body |
| YAML front-matter parsing | Rust | Pairs naturally with meta extraction |
| Fuzzy search (`fuzzy_search`) | Rust | fzy-style scoring; returns char positions for highlighting |
| SQLite FTS5 index | Rust | rusqlite + bundled SQLite C; prefix-match + `snippet()` |
| Search fallback (in-memory) | Rust | Used when SQLite handle fails |
| File I/O (SAF) | Kotlin | SAF is Android framework; Rust has no useful access |
| **Inline editor styling** | **Kotlin** | Runs on every keystroke; bridge cost would dominate |
| Tree assembly | Kotlin | Cheap; groups flat NoteMeta by parent |
| All UI | Kotlin | Jetpack Compose |

**Rule:** if it touches Android types (Uri, ContentResolver, Compose state) → Kotlin. If it's pure data over strings and benefits from a real library (pulldown-cmark, SQLite, fuzzy scoring) → Rust.

---

## 2. The Rust FFI surface (the UDL contract)

`rust-core/src/blacknote.udl` is the single source of truth.

```idl
namespace blacknote {
    ParsedDoc parse_markdown(string src);
    string    apply_format(string src, u32 sel_start, u32 sel_end, FormatKind kind, boolean on);
    NoteMeta  extract_meta(string path, string parent, string file_name, string text, i64 modified_millis);
    sequence<NoteMeta>    search_notes(sequence<NoteMeta> notes, string query, u32 limit);
    sequence<FuzzyResult> fuzzy_search(sequence<NoteMeta> notes, string query, u32 limit);
};

dictionary NoteMeta {
    string path; string parent;
    string title; string preview;
    i64 modified_millis; i64 created_millis;
    sequence<string> tags;
    string? label;
};

dictionary FuzzyResult {
    NoteMeta note;
    sequence<u32> title_matches;
    sequence<u32> preview_matches;
};

interface SearchIndex {
    [Throws=IndexError] constructor(string db_path);
    [Throws=IndexError] void upsert(string path, string parent, string title, string body,
                                    string? label, sequence<string> tags,
                                    i64 modified_millis, i64 created_millis);
    [Throws=IndexError] void delete(string path);
    [Throws=IndexError] sequence<NoteMeta> query(string q, u32 limit);
    [Throws=IndexError] sequence<NoteMeta> all_sorted(u32 limit);
    [Throws=IndexError] void retain(sequence<string> alive_paths);
};
```

Decisions encoded here:

- **`parent` is a field on `NoteMeta`** so the tree can be reconstructed from a flat list — no second query needed.
- **`file_name` is passed in to `extract_meta`** because SAF URIs aren't filesystem paths; you cannot derive a sane filename from them in Rust.
- **`tags` is a flat `Vec<String>`** at the meta level; in FTS5 it's stored as a space-joined string in the indexed `tags` column.
- **`SearchIndex` is an interface** with state in `Mutex<Connection>`. Kotlin holds one handle for the app's lifetime.
- **`[Throws=IndexError]`** on every method. Kotlin wraps in `runCatching` and falls back to in-memory search if SQLite ever fails to open.
- **`retain(alive_paths)`** instead of "DELETE NOT IN" being constructed in Kotlin — keeps the Rust side authoritative for the schema.

---

## 3. The Kotlin shape

### One ViewModel, one StateFlow

`AppViewModel` drives the whole app. `UiState` is one immutable data class covering list, editor, trash, settings, dialogs.

- The app is small enough that lifting state up doesn't hurt.
- Editor ↔ list transitions need coordination (save → rename → refresh).
- Easier to reason about than N small VMs sharing state.

### Single Activity, no nav library

`MainActivity` switches on `ui.screen` (a 4-value enum: LIST / EDITOR / TRASH / SETTINGS). No Navigation Compose. `BackHandler { onBack() }` in the editor is sufficient.

### Coroutines

All I/O goes through `viewModelScope.launch { withContext(Dispatchers.IO) { ... } }` in the repo. VM methods are fire-and-forget; UI updates atomically via `MutableStateFlow.update`.

### Compose specifics

- **No Material `Scaffold`.** Compose 1.6.x's Scaffold corrupted its SlotTable on Android 16 (SDK 36). We dropped it and use plain `Box`/`Column` with `windowInsetsPadding(WindowInsets.systemBars)` and `imePadding()`.
- **Compose BOM 2024.10.01** (Compose 1.7.x) is the floor. 1.6.x is broken on SDK 36.
- **`VisualTransformation`** for the editor's live Markdown styling — offset-preserving so the cursor/selection math stays trivial.
- **`LazyVerticalStaggeredGrid`** for collage mode.
- **`Modifier.fadingScrollbar`** is a custom overlay that fades in on scroll and out after 800 ms idle.

---

## 4. Storage, SAF, and the tree

Source of truth: the SAF folder the user picked. Notes are plain `.md` files; folders are real SAF directories.

### The tree walk

`NoteRepository.refreshTree()`:

1. `DocumentFile.fromTreeUri(ctx, treeUri)` → root.
2. Recursive `walk(dir, depth)`:
   - Skip `.Trash/`.
   - Directories → push `FolderInfo(path, parent = dir.uri.toString(), depth)`, recurse.
   - `*.md` files → read text, call `extract_meta` (Rust) for title/preview/tags/created, push `Note(parent = dir.uri.toString())`, upsert into FTS index.
3. After walk: `index.retain(alivePaths)` prunes index rows for files that no longer exist.

### Why `parent` is the URI string

SAF URIs are the durable identity. Tree assembly is just `groupBy { it.parent }` — no need for a relational ID table.

### Tree assembly on the Kotlin side

`buildRows()` in `NoteListScreen.kt`:

1. `notesByParent = notes.groupBy { it.parent }`
2. `foldersByParent = folders.groupBy { it.parent }`
3. Root parents = parent values *not* present in `knownFolderPaths` (i.e. the SAF root URI).
4. Walk recursively, emitting `Row.FolderRow` followed by — only if expanded — children.

`expandedFolders: Set<String>` lives in `UiState` and persists for the session.

### Trash

`Trash/` inside the user's notes root (legacy `.Trash/` is still recognised for existing data):

- Named without a leading dot because Samsung's ExternalStorageProvider and several other SAF providers silently fail to create or list dot-prefixed directories.
- Skipped from main tree walk.
- Browsable on the Trash screen via `repo.refreshTrash()`.
- Soft delete = `SafStore.moveDocumentCompat(note, currentParent, trash)` with post-move verification (source must be gone, destination must be readable).
- Optimistic UI removal on delete; if the move fails, `refreshTreeAwait()` restores the note and `CrashReporter` logs the failure.
- Restore = move back to root. (We don't track original parent; sidecar JSON wasn't worth it.)
- Permanent delete = `DocumentsContract.deleteDocument(note)`.

### SAF robustness (`SafStore.kt`)

Several SAF providers (Samsung ExternalStorage, cloud drives) lie or no-op on standard `DocumentsContract` calls. `SafStore` wraps every critical operation with fallbacks:

| Operation | Tier 1 | Tier 2 (fallback) |
|---|---|---|
| Read | `openInputStream` | `openFileDescriptor` |
| Write | mode `"wt"` | mode `"w"` |
| Move | `moveDocument` (verified: dest readable + source gone) | Manual read → create in target → write → verify → delete source |
| Rename | `renameDocument` | Manual read → create with new name → write → delete source |

`moveDocumentCompat` intentionally **skips** `copyDocument` as a middle tier — Samsung returns a non-null URI even when no file appears at the destination, which previously caused silent data loss on delete-to-trash.

`lastReadError` is set on every failed read so `AppViewModel` and `CrashReporter` can diagnose why a note couldn't be opened.

### Move / Copy / Rename

Long-press on a note → DropdownMenu (Move / Copy / Delete). Move/Copy bring up a `FolderPickerDialog` listing the live folder tree. All moves go through `moveDocumentCompat`; stale cached parent URIs trigger a tree-walk to find the current parent and retry.

**Title ↔ filename contract** (v1.6.0+):

- The **displayed title is always the filename minus `.md`** — not front-matter `title:` or the first `# heading`. This guarantees the list matches what the user sees in their file manager, even when a SAF provider silently refuses rename (Samsung, some cloud providers).
- The editor's title field edits the filename, not a heading inside the body.
- The body is saved **verbatim** — no `# Title` line is prepended on save.
- **Rename to match title** happens on editor close, not per keystroke: sanitize title → `renameDocumentCompat` (with manual copy+delete fallback) → refresh the FTS row with the new URI.

---

## 5. The editor

### Buffers

`UiState.editingTitle` / `editingBody` are `TextFieldValue` (text + selection). They flow into Compose's `BasicTextField` and back out.

### Auto-save

- Every change schedules `delay(500); saveNow()`, cancelling any pending one.
- `closeEditor()` flips to LIST **immediately**, then runs save + rename + refresh in the background. No lingering editor on back.

### Undo / redo

`EditorHistory` per buffer, capped at 100:

- Coalesces consecutive single-char non-whitespace inserts inside a 600 ms window into one entry. "Hello" is one undo, not five.
- Whitespace breaks coalesce so paragraph boundaries are natural undo points.
- Title and body have separate stacks; `undo()` pops body first, then title.

### Format toolbar

`format(kind)` reads the current selection, sends `(text, start, end, kind, on=true)` to Rust's `apply_format`. Result replaces the body; selection restored at the same logical position adjusted for marker length.

Selection-aware: with a selection, wrap it; without one, insert empty marker pair and park cursor between them (`**|**`).

### Live Markdown styling (Kotlin)

`MarkdownVisualTransformation.filter(text)` is offset-preserving — same text, styled with `AnnotatedString`. Handles per-line `# heading`, `- bullet`, `1. ordered`, and inline `**`, `_`, `~~`, `` ` ``, `<u>`. Also handles:

- **Hashtags** (`#tag`, `#thing/sub`) — chip-coloured background.
- **Wiki-links** `[[Note title]]` — accent + underline on inner text, brackets dimmed.
- **Web links** `[text](url)` — accent + underline on text, markers dimmed. In **render mode** these become real clickable `LinkAnnotation.Url` spans.

### Render mode

Editor top-bar book icon toggles `EditorMode.RENDER`. The body is replaced with `RenderView`, which calls `renderMarkdown(body)` — a **stripping** renderer:

- Heading markers (`#`, `##`, …) removed; size applied.
- Bullets shown as `•`, ordered items as `N.`.
- Inline markers (`**`, `_`, `~~`, `` ` ``, `<u>`) stripped, styles applied to inner text.
- Wiki-links rendered as styled text. Web links as `LinkAnnotation.Url` — tap opens the system browser.

Icon swaps to a pencil while in render mode.

---

## 6. Front-matter

Each note may begin with a YAML `---` block:

```yaml
---
title: My note
created: 2015-06-26
modified: 2015-06-26
source: google-keep
label: My label
tags: [a, b, c]
---
```

Parsed by Rust `meta.rs`:

- `title:` → used as title; otherwise first non-empty body line; otherwise filename minus `.md`; otherwise "Untitled".
- `created:` → ISO date `YYYY-MM-DD` parsed manually (no chrono dep). When non-zero, this is the date shown on cards.
- `modified:` → overrides file mtime for sort.
- `label:` → shown as accent chip top-right on the card.
- `source:` → shown as label when no explicit `label:` is set.
- `tags:` → flat list, displayed as `#tag` chips under the card, indexed in FTS5.

Hashtags found inline in the body (`#foo`, `#thing/sub`, anywhere except heading lines) are merged into the tag list. Numeric-only tags (`#123`) are skipped.

---

## 7. Search

Two engines coexist:

### FTS5 (SQLite, Rust)

Schema:

```sql
CREATE VIRTUAL TABLE notes USING fts5(
    path UNINDEXED, parent UNINDEXED,
    title, body, label, tags,
    modified UNINDEXED, created UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);
```

- `UNINDEXED` columns are stored but not in the inverted index — saves space.
- `unicode61 remove_diacritics 2` makes "café" match "cafe".
- `escape_fts(q)` wraps each whitespace-separated token in `"…"*` for prefix match; strips FTS5-special chars to avoid syntax injection.
- `snippet(notes, 3, '', '', '…', 16)` produces a 16-token preview around the matched term.

FTS5 is also the **instant-load cache**: on every app start, `cachedNotes()` reads `all_sorted()` in ~5–10 ms and renders the list before any SAF scan runs. The background scan emits a fresh list when it completes.

### Fuzzy search (Rust, in-memory)

`fuzzy.rs` runs over the in-memory note list when the user types. Greedy subsequence matcher in the spirit of fzy/fzf:

- Lowercases needle + haystack.
- Each needle char must appear in order.
- Score rewards:
  - Consecutive runs (+15 per consecutive char)
  - Prefix match (+8 at index 0)
  - Word boundary (+10 after whitespace/punctuation)
  - CamelCase boundary (+7 lower→upper transition)
- Title matches weighted 3×, preview 1×, label 2×.
- Returns char positions per match so the UI can highlight them.

This is what the user types into the search bar. FTS5 is used for the cached *snapshot*; fuzzy is used for the *live query*.

### Highlighting

`NoteCard` / `CollageTile` read `state.searchHighlights[note.path]` and build `AnnotatedString` with each consecutive run of matched chars painted in `LabelChipBg`. Subsequence highlighting in title and preview.

---

## 8. Schema migration

When the FTS5 schema gains a column (e.g. `parent` in v1.3.0, `tags`+`created` in v1.4.0), `SearchIndex::new` checks `pragma_table_info('notes')` for the required column set and `DROP TABLE` + recreate if anything's missing. Losing the cache is fine — the next SAF scan rebuilds it.

This avoids the pain of ALTER TABLE (which FTS5 doesn't support) without manual db-version bumping.

---

## 9. Decisions log

### Why no `compose-rich-editor`?
Considered. Decided against: heavy, opinionated toolbar look, and our editor's job is already small — inline styling on raw markdown + format toggle. A library on top means wrestling with its state model.

### Why no Material Scaffold?
Compose 1.6.x's `ScaffoldLayoutWithMeasureFix` crashes its SlotTable on Android 16 (SDK 36) — repro'd on a Galaxy S22 (SM-S901E). v1.1.3 dropped Scaffold; v1.2.0 bumped Compose. Scaffold stayed out even after the bump: FAB + insets are trivial without it, and one fewer Material 3 dependency.

### Why one Activity, no Navigation Compose?
4 screens; transitions are simple state changes. Nav Compose pays off for deep back-stacks, deep links, tabs, save-state restoration. None apply.

### Why SQLite for search instead of just keeping notes in memory?
The same DB serves as the instant-load cache. Without it, first paint is always after the SAF walk. Plus prefix-match, snippet generation, diacritic-insensitive tokenization — all free.

Cost: ~3 MB to the APK for the bundled SQLite C compiled for three ABIs. Acceptable.

### Why both FTS5 and fuzzy?
FTS5 is a great cache (durable on disk, fast to read on start). Fuzzy is what users actually want for "type a few characters and find a note." Running fuzzy over the in-memory tree is microseconds per query; FTS5 wouldn't give the subsequence semantics anyway.

### Why a custom uncaught-exception handler?
Field crashes were diagnosable only via logcat, which wasn't reachable. Now we dump to:
- `<filesDir>/crash.log` (internal)
- `<externalFilesDir>/crash.log` (file manager–visible)
- `MediaStore.Downloads/blacknote-crash.txt` (Termux reachable)

Used to diagnose Scaffold + SlotTable + stale-URI bugs in production. Will keep.

### Why filename = title?
Two reasons:
1. The user can find notes outside the app (in a file manager, in Git, in Obsidian on desktop) by the filename they expect.
2. The original "every note is `Note-{timestamp}.md`" was unreadable.

Rename happens on editor close so we don't spam the filesystem per keystroke.

### Why read-first on note open (v1.9.x)?
v1.4.0 flipped to the editor immediately and filled the body async. That felt fast but caused a visible flash when the read returned null (stale URI / transient SAF error): editor opened, then bounced back to the list, and rapid re-taps cancelled the in-flight refresh. v1.9.1+ reads first (with an 80 ms retry), then opens. On persistent read failure it `refreshTreeAwait()`s, tries to relocate the note by URI/parent+title, and as a last resort opens the editor with an empty body so the user isn't stuck. Re-taps on the same path while already in the editor are no-ops.

### Why ship a versioned APK snapshot every time?
`scripts/final_export` writes both `blacknote.apk` (the install URL) and `blacknote-<ver>-<sha>.apk` (durable archive). Rollbacks are one wget; comparing two builds is trivial. Mirrors the `MarsLegislationParser` pattern.

---

## 10. Performance choices

- **Per-keystroke styling is Kotlin** (no FFI hop). Regex passes over body string stay under a frame even at 100 KB.
- **`remember(body)` around `renderMarkdown(body)`** in render mode so the AnnotatedString isn't recomputed on selection changes.
- **`remember(state.tree, state.expandedFolders, …)`** around `buildRows(state)` so tree flattening doesn't re-run on unrelated state changes.
- **Cache-first list bootstrap** — `cachedNotes()` from SQLite before SAF scans.
- **`refreshTree()` cancels its previous job** before starting a new one — no stacked scans when the user mashes back.
- **`setQuery` updates state synchronously, results async** — IME and field value stay in lockstep (the fix for the "ocialism" char-drift bug).
- **`openNote` read-first with retry + `refreshTreeAwait` recovery** — avoids editor flash-and-bounce on transient SAF failures.
- **`SafStore` two-tier read/write + `moveDocumentCompat`/`renameDocumentCompat`** — Samsung and cloud providers often lie or no-op on standard SAF calls.
- **Scroll position preserved** — `listScrollIndex`/`listScrollOffset` and collage equivalents survive editor round-trips.
- **Gradle daemon + configure-on-demand + parallel + build cache + skip release lint + Kotlin incremental** — release build went from 3m 35s to ~1m 30s.

---

## 11. Toolchain notes

This is a NixOS-style box. Key paths:

- **Android SDK**: `/opt/android-sdk` (platform 34, build-tools 33/34)
- **Android NDK**: `/opt/android-ndk-r27c`
- **Gradle 8.4**: `/opt/gradle-8.4/bin/gradle`
- **JDK 17**: `/root/.nix-profile/bin/java`
- **Rust + cargo**: `/root/.cargo/bin/`
- **Host C wrapper** (Rust build scripts): `/nix/store/788mx070y81zjlg5ipcl0cra3afviw9k-gcc-wrapper-15.2.0/bin/cc`
- **Per-target Android linkers**: NDK clang for `aarch64-linux-android24`, `armv7a-linux-androideabi24`, `x86_64-linux-android24`

`~/.cargo/config.toml` pins all the above so `cargo build --release --target <android-triple>` Just Works.

`uniffi-bindgen-cli` isn't on crates.io — we have a `src/bin/uniffi-bindgen.rs` calling `uniffi::uniffi_bindgen_main()`. Run via `cargo run --release --bin uniffi-bindgen -- generate src/blacknote.udl --language kotlin --out-dir <tmp> --config uniffi.toml`.

---

## 12. Release history

| Ver    | What                                                                                            | Build time |
|---|---|---|
| 1.0.0  | First working APK — Compose UI, Rust core, SAF, in-memory search                                  | – |
| 1.1.0  | All planned "cuts" closed: undo/redo, selection-aware formatting, inline rich render, SQLite FTS5 | – |
| 1.1.1  | Crash-to-file + safe FFI fallback                                                                  | – |
| 1.1.2  | Also publish crash log to MediaStore Downloads (Termux can't read `Android/data/`)                | – |
| 1.1.3  | Removed `Scaffold` (Compose 1.6.x SlotTable corrupts on SDK 36)                                   | – |
| 1.2.0  | Bumped Compose BOM 2024.02 → 2024.10, Kotlin 1.9.22 → 1.9.25                                      | – |
| 1.3.0  | Subfolders, Trash, hamburger/Trash/Settings, long-press menu, instant load, new icon              | – |
| 1.3.1  | Filename = title, system back, "Untitled" default, "Item" placeholder removed, hamburger in search bar, collage toggle, render toggle, New Folder | – |
| 1.3.2  | Survive stale URIs on note open                                                                    | 3m 30s |
| 1.3.3  | Back delay fixed, removed "All notes" pill, real RENDER mode (strip markers)                       | 3m 30s |
| 1.3.4  | Search char-drift fixed; daemon + configure-on-demand + skip release lint                          | **2m 31s** |
| 1.3.5  | VS Code-style fuzzy search with char highlighting                                                  | 2m 20s |
| 1.4.0  | Front-matter (created/modified/source), hashtags, [[wiki-links]], clickable [text](url), fading scrollbar, search-scroll-to-top, eager screen flip on note open | **1m 28s** |
| 1.6.0  | Title = filename (source of truth), body saved verbatim, scroll position preserved, delete-to-trash fixes, stale-parent fallback for move/copy, test suite | – |
| 1.9.0  | Four user-reported bug fixes (openNote flash, edit loss, title drift, trash not showing) | – |
| 1.9.1  | Read-first `openNote` with retry + `refreshTreeAwait` recovery | – |
| 1.9.2  | Optimistic delete rollback on failed trash move | – |
| 1.9.3  | Delete-to-trash no longer leaves source on disk | – |
| 1.9.4  | Trash folder renamed to `Trash` (no dot) for Samsung visibility; post-move verification; `moveDocumentCompat` manual fallback skips lying `copyDocument` | – |

---

## 13. Testing

Two layers:

| Layer | Location | Runner | What it covers |
|---|---|---|---|
| JVM unit tests | `app/src/test/` | Robolectric | `EditorHistory`, `AppViewModel` basics, delete button wiring, `RepositoryFailureModes`, functionality suites |
| Instrumented | `app/src/androidTest/` | Managed Pixel 7 device (`pixel7` in `build.gradle.kts`) | Full `AppViewModel` + `NoteRepository` against `TestSafStore` (real on-device directory, no SAF picker) |

`TestSafStore` subclasses `SafStore` and backs `DocumentFile` with a real filesystem directory so production code paths (read, write, move, trash) can be exercised without the SAF picker. `App.setRepositoryForTest()` injects it.

Key repro tests in `EndToEndBugReproTest.kt`:
1. Note open doesn't flash-and-bounce.
2. Edits persist across close/reopen.
3. List title equals filename minus `.md`.
4. Delete surfaces the note on the Trash screen.

```bash
cd app
/opt/gradle-8.4/bin/gradle :app:testDebugUnitTest
/opt/gradle-8.4/bin/gradle :app:pixel7DebugAndroidTest
```

---

## 14. What's next

- **Wiki-link navigation.** `[[Note title]]` is styled but tapping does nothing. Needs a title→path map and a viewmodel action.
- **Persist `expandedFolders`** across launches.
- **Sidecar JSON in `.Trash/`** mapping `restored.md` → original parent so Restore returns notes where they came from.
- **Incremental SAF scan** — mtime cache; only re-read files whose `lastModified()` advanced.
- **Single SQLite transaction around the SAF walk's upserts** — bulk write is dramatically faster past a thousand notes.
- **`scripts/final_export --fast`** — `assembleDebug` + arm64-only ABI → ~45 s dev iteration.
- **"Create note in this folder"** — long-press a folder row → "New note here". Currently new notes always go to root.
- **Settings**: dark/light mode override, font size, default new-note location.
- **Tag filtering**: tap a chip → list filtered to that tag.

None are required for daily use. Current build is the smallest set of features that makes BlackNote feel like a real Obsidian-adjacent Markdown app on Android.
