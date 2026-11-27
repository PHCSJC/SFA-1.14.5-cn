#!/usr/bin/env bash
# Apply the China-specific customisations to a clean SFA checkout.
#
# Usage: apply-patches.sh <path-to-sfa-checkout>
#
# The repository keeps the changes in two forms on purpose:
#
#   scripts/custom.patch   - edits to files that already exist upstream
#   overlay/               - files that do not exist upstream at all
#
# A patch breaks whenever upstream touches the same hunk, so the script
# reports which file failed instead of leaving a half-patched tree. The
# overlay half is plain file copying and cannot conflict.

set -euo pipefail

SFA_DIR="${1:-}"
if [ -z "$SFA_DIR" ]; then
  echo "usage: $0 <path-to-sfa-checkout>" >&2
  exit 2
fi
if [ ! -d "$SFA_DIR/app/src/main" ]; then
  echo "error: $SFA_DIR does not look like an SFA checkout" >&2
  exit 2
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH_FILE="$REPO_DIR/scripts/custom.patch"
OVERLAY_DIR="$REPO_DIR/overlay"

echo "==> applying $PATCH_FILE"
if ! git -C "$SFA_DIR" apply --verbose --whitespace=nowarn "$PATCH_FILE"; then
  echo "error: patch failed to apply" >&2
  echo "       upstream most likely changed one of the patched files." >&2
  echo "       run: git -C $SFA_DIR apply --check -v $PATCH_FILE" >&2
  echo "       to see the rejected hunks, then refresh scripts/custom.patch." >&2
  exit 1
fi

echo "==> copying overlay from $OVERLAY_DIR"
( cd "$OVERLAY_DIR" && find . -type f -print0 ) | while IFS= read -r -d '' rel; do
  dest="$SFA_DIR/${rel#./}"
  mkdir -p "$(dirname "$dest")"
  cp "$OVERLAY_DIR/${rel#./}" "$dest"
  echo "    $rel"
done

echo "==> done"
git -C "$SFA_DIR" status --short
