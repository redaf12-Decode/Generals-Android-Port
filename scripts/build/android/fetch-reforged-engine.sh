#!/bin/bash
# Fetch the pinned Reforged engine source (olcayseygan/CnCGeneralsZH-Reforged) at
# the exact commit recorded in docs/reforged-integration/engine-source.manifest.json,
# and verify the checkout identity before any adaptation layer is applied.
#
# This is Phase 2 of the Reforged integration: a reproducible, pinned source
# acquisition step. It NEVER downloads game assets and NEVER touches upstream.
#
# Usage:
#   fetch-reforged-engine.sh [--dest <dir>] [--ref <dir-with-manifest>]
#
#   --dest   where to place the checkout (default: <repo-root>/reforged-engine)
#   --ref    directory holding engine-source.manifest.json
#            (default: <repo-root>/docs/reforged-integration)
#
# Exit 0 on success (checkout verified at the pinned commit); non-zero otherwise.
#
# Updating the pin: edit "commit" (and commit_message/pinned_on) in the manifest,
# then re-run this script and re-validate the Phase 3+ adaptation layer against the
# new revision. Do not pin a floating branch for production builds.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

DEST=""
MANIFEST_DIR=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dest) DEST="${2:-}"; shift 2 ;;
        --ref)  MANIFEST_DIR="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
    esac
done
DEST="${DEST:-${REPO_ROOT}/reforged-engine}"
MANIFEST_DIR="${MANIFEST_DIR:-${REPO_ROOT}/docs/reforged-integration}"
MANIFEST="${MANIFEST_DIR}/engine-source.manifest.json"

[[ -f "${MANIFEST}" ]] || { echo "ERROR: manifest not found: ${MANIFEST}" >&2; exit 2; }

# Read fields from the manifest without requiring jq (grep/sed on the flat JSON).
json_get() {
    local key="$1"
    grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "${MANIFEST}" \
        | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
}
REPO_URL="$(json_get repository)"
PINNED_SHA="$(json_get commit)"
PINNED_MSG="$(json_get commit_message)"
[[ -n "${REPO_URL}" && -n "${PINNED_SHA}" ]] || { echo "ERROR: manifest missing repository/commit" >&2; exit 2; }

# Basic sanity: a full 40-hex commit SHA (not a branch/tag).
[[ "${PINNED_SHA}" =~ ^[0-9a-f]{40}$ ]] || { echo "ERROR: pinned commit is not a full SHA: ${PINNED_SHA}" >&2; exit 2; }

echo "==> Reforged engine pin"
echo "    repository: ${REPO_URL}"
echo "    commit:     ${PINNED_SHA}"
echo "    message:    ${PINNED_MSG}"
echo "    dest:       ${DEST}"

if [[ ! -d "${DEST}/.git" ]]; then
    echo "==> Cloning (blobless) at the pinned commit"
    # --filter=blob:none keeps the checkout small; blobs are fetched on demand at
    # build time. --no-checkout then a detached checkout of the exact SHA.
    git clone --filter=blob:none --no-checkout "${REPO_URL}" "${DEST}"
fi

echo "==> Fetching and checking out the pinned commit"
git -C "${DEST}" fetch --filter=blob:none origin "${PINNED_SHA}" 2>/dev/null \
    || git -C "${DEST}" fetch origin "${PINNED_SHA}" \
    || true
git -C "${DEST}" checkout --force --detach "${PINNED_SHA}"

echo "==> Verifying checkout identity"
ACTUAL_SHA="$(git -C "${DEST}" rev-parse HEAD)"
if [[ "${ACTUAL_SHA}" != "${PINNED_SHA}" ]]; then
    echo "ERROR: checkout is at ${ACTUAL_SHA}, expected ${PINNED_SHA}" >&2
    exit 1
fi
echo "    HEAD == ${ACTUAL_SHA}  (verified)"

# Attribution / license must be present (GPLv3).
if [[ -f "${DEST}/LICENSE.md" ]]; then
    echo "    LICENSE.md present"
else
    echo "WARNING: LICENSE.md not found in the engine tree" >&2
fi

echo "==> OK: Reforged engine pinned at ${PINNED_SHA}"
echo "    Next: Tools/vendor.sh inside ${DEST}, then the Phase 3+ Android adaptation."