#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir/android"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
  echo 'Android SDK 35 belum terpasang. Lihat README.md.' >&2
  exit 1
fi
./gradlew --no-daemon assembleDebug
mkdir -p "$repo_dir/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$repo_dir/dist/remote-codex.apk"
echo "APK: $repo_dir/dist/remote-codex.apk"
