#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_PATH="${1:-$ROOT_DIR/macos/build/Build/Products/Release/Vibe Visualizer.app}"
OUTPUT_DIR="$ROOT_DIR/macos/out"
OUTPUT_DMG="$OUTPUT_DIR/Vibe-Visualizer-macOS.dmg"

if [[ ! -d "$APP_PATH" ]]; then
  echo "App bundle not found: $APP_PATH" >&2
  exit 1
fi

STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/vibe-visualizer-dmg.XXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT

mkdir -p "$OUTPUT_DIR"
ditto "$APP_PATH" "$STAGING_DIR/Vibe Visualizer.app"
ln -s /Applications "$STAGING_DIR/Applications"
hdiutil create \
  -volname "Vibe Visualizer" \
  -srcfolder "$STAGING_DIR" \
  -ov \
  -format UDZO \
  -imagekey zlib-level=9 \
  "$OUTPUT_DMG"

echo "Created $OUTPUT_DMG"
