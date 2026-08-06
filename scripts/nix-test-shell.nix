# Nix dev shell for running BlackNote unit + instrumented tests when /opt/android-sdk
# is not present. Usage:
#   nix-shell scripts/nix-test-shell.nix --run "cd app && gradle :app:testDebugUnitTest"
{ pkgs ? import <nixpkgs> { config.allowUnfree = true; } }:

let
  androidEnv = pkgs.callPackage "${pkgs.path}/pkgs/development/mobile/androidenv" {
    inherit pkgs;
    licenseAccepted = true;
  };
  sdkArgs = {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    includeEmulator = "if-supported";
    includeSystemImages = "if-supported";
    systemImageTypes = [ "google_apis" ];
    abiVersions = [ "x86_64" ];
  };
  androidComposition = androidEnv.composeAndroidPackages sdkArgs;
  androidSdk = androidComposition.androidsdk;
  nixSdkPath = "${androidSdk}/libexec/android-sdk";
  jdk = pkgs.jdk17;
in
pkgs.mkShell {
  name = "blacknote-tests";
  packages = [
    jdk
    pkgs.gradle
    androidSdk
    androidComposition.platform-tools
    pkgs.rsync
  ];
  JAVA_HOME = jdk.home;
  LANG = "C.UTF-8";
  LC_ALL = "C.UTF-8";
  shellHook = ''
    NIX_SDK="${nixSdkPath}"
    WRITABLE_SDK="''${BLACKNOTE_SDK:-$HOME/.cache/blacknote-android-sdk}"
    if [ ! -f "$WRITABLE_SDK/.initialized" ]; then
      echo "Seeding writable Android SDK at $WRITABLE_SDK"
      mkdir -p "$WRITABLE_SDK"
      rsync -a --copy-links "$NIX_SDK"/ "$WRITABLE_SDK"/
      chmod -R u+w "$WRITABLE_SDK"
      touch "$WRITABLE_SDK/.initialized"
    fi
    export ANDROID_HOME="$WRITABLE_SDK"
    export ANDROID_SDK_ROOT="$WRITABLE_SDK"
    export PATH="$WRITABLE_SDK/platform-tools:$WRITABLE_SDK/emulator:$PATH"
    echo "BlackNote test shell"
    echo "  JAVA_HOME=$JAVA_HOME"
    echo "  ANDROID_HOME=$ANDROID_HOME"
    mkdir -p "$(pwd)/app"
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$(pwd)/app/local.properties"
  '';
}