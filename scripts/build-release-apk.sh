#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

gradle_args=()

if [[ "${XRAY_ANDROID_RELEASE_WITH_LINT:-0}" != "1" ]]; then
  gradle_args+=(
    "-x" "lintVitalAnalyzeRelease"
    "-x" "generateReleaseLintVitalReportModel"
    "-x" "lintVitalReportRelease"
  )
fi

if [[ $# -gt 0 ]]; then
  gradle_args+=("$@")
fi

"$ROOT_DIR/scripts/run-gradle-task.sh" assembleRelease "${gradle_args[@]}"

signed_apk="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
unsigned_apk="$ROOT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"

if [[ -f "$signed_apk" ]]; then
  echo "Signed release APK: $signed_apk"
elif [[ -f "$unsigned_apk" ]]; then
  echo "Unsigned release APK: $unsigned_apk"
  if [[ -f "$ROOT_DIR/keystore.properties" ]]; then
    echo "Release signing is currently disabled. Check keystore.properties and the referenced keystore file."
  else
    echo "To generate a signed APK, create keystore.properties from keystore.properties.example."
  fi
else
  echo "Release build finished, but no APK was found in app/build/outputs/apk/release." >&2
  exit 1
fi
