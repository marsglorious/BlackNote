# Build & Publish — BlackNote

## Toolchain on this box

These paths are real and preconfigured — see [`HANDOVER.md`](HANDOVER.md) §3 for the full table.

| Tool | Path |
|---|---|
| Android SDK | `/opt/android-sdk` (platform 34, build-tools 33/34) |
| Android NDK | `/opt/android-ndk-r27c` |
| Gradle | `/opt/gradle-8.4/bin/gradle` |
| JDK 17 | `/root/.nix-profile/bin/java` |
| Rust + cargo | `/root/.cargo/bin/` |
| Cargo config | `~/.cargo/config.toml` — pins per-target Android linkers |

`app/local.properties` (gitignored) must contain:
```
sdk.dir=/opt/android-sdk
```

On a fresh machine you'd also need:
```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

## Build matrix

### Kotlin-only change (no Rust rebuild)

```bash
cd /root/BlackNote/app
/opt/gradle-8.4/bin/gradle :app:assembleRelease
/root/BlackNote/scripts/final_export
```

~1m 30s. The `.so` files in `app/app/src/main/jniLibs/` are checked into git.

### Rust change (rebuild native libs)

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

### Rust API change (UDL edit — also regen Kotlin bindings)

After the Rust builds above, also run:

```bash
cd /root/BlackNote/rust-core
rm -rf /tmp/binds
cargo run --release --bin uniffi-bindgen -- generate src/blacknote.udl \
    --language kotlin --out-dir /tmp/binds --config uniffi.toml
rm -rf /root/BlackNote/app/app/src/main/java/com/marsglorious/blacknote/ffi/*
cp -r /tmp/binds/com/marsglorious/blacknote/ffi/* \
      /root/BlackNote/app/app/src/main/java/com/marsglorious/blacknote/ffi/
```

Commit the new bindings together with the UDL change.

## Tests

```bash
cd /root/BlackNote/app
/opt/gradle-8.4/bin/gradle :app:testDebugUnitTest          # JVM unit tests (Robolectric)
/opt/gradle-8.4/bin/gradle :app:pixel7DebugAndroidTest     # instrumented on managed Pixel 7
```

## Publish

**Always** use the script — never copy by hand:

```bash
/root/BlackNote/scripts/final_export
```

The script:
- finds the freshest signed APK under `app/app/build/outputs/apk/`
- copies it atomically to `/var/www/downloads/blacknote.apk`
- writes a versioned snapshot `blacknote-<version>-<sha>.apk` for rollback
- prints the install URL

## Install on phone

1. On Android: Settings → Security → allow install from browser.
2. Open `https://<host>/downloads/blacknote.apk` in Chrome.
3. Tap the downloaded APK to install.

## Notes for future iterations

- After **any** successful build, call `scripts/final_export`. It is the contract.
- Bump `versionCode` and `versionName` in `app/app/build.gradle.kts` for each published build.
- `compileSdk` / `targetSdk` are 34; Compose BOM floor is `2024.10.01`.
- Keep the Rust `.so` outputs in git (~3 MB per ABI) so a Kotlin-only build doesn't need the Rust toolchain.