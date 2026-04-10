#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <gradle-task> [more-gradle-args...]" >&2
  exit 1
fi

sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/leo-cy/Android/Sdk}}"
gradle_bin="${GRADLE_BIN:-}"

if [[ ! -d "$sdk_dir" ]]; then
  echo "Android SDK not found: $sdk_dir" >&2
  echo "Set ANDROID_SDK_ROOT or ANDROID_HOME, or create local.properties manually." >&2
  exit 1
fi

if [[ -z "$gradle_bin" ]]; then
  if [[ -x "$ROOT_DIR/.gradle-bin/gradle/bin/gradle" ]]; then
    gradle_bin="$ROOT_DIR/.gradle-bin/gradle/bin/gradle"
  elif [[ -x "/home/leo-cy/.local/gradle-8.14.3/bin/gradle" ]]; then
    gradle_bin="/home/leo-cy/.local/gradle-8.14.3/bin/gradle"
  elif command -v gradle >/dev/null 2>&1; then
    gradle_bin="$(command -v gradle)"
  else
    gradle_bin="$ROOT_DIR/gradlew"
  fi
fi

if [[ ! -f "$ROOT_DIR/local.properties" ]]; then
  escaped_sdk_dir="${sdk_dir//\\/\\\\}"
  printf 'sdk.dir=%s\n' "$escaped_sdk_dir" > "$ROOT_DIR/local.properties"
  echo "Created local.properties using sdk.dir=$sdk_dir"
fi

"$gradle_bin" --no-daemon "$@"
