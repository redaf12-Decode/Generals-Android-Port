#!/usr/bin/env bash
# GeneralsX @feature Android security 12/11/2026
#
# Attach an APK Signature Scheme v3 proof-of-rotation lineage to an already-signed release APK, so
# a device that trusts the OLD signing key accepts an APK signed with the NEW one -- an in-place
# update with no uninstall.
#
# Why this exists: the APK used to be signed with a debug keystore committed to a public
# repository. That key is compromised (anyone can sign an update Android accepts), so releases now
# use a production key. A device that installed an APK signed with the old key will only accept a
# differently-signed APK if the new APK carries a lineage proving the old key authorised the new
# one. Android 9 (API 28) and later honour that lineage; the port's minSdk is 28, so it covers
# every supported device.
#
# The lineage must be built from the OLD private key. The old debug keystore is public, so this
# is not a confidentiality matter -- it is only about producing a signature the old installs
# accept. If the old private key is unavailable, no lineage can be built and existing installs
# need a one-time uninstall/reinstall; the script says so and exits non-zero rather than pretending.
#
# Both the CI release job and a local release build call this, so there is one implementation.
#
# Environment:
#   APK                                  (required) path to the signed release APK to re-sign
#   GX_RELEASE_STORE_FILE                (required) new production keystore
#   GX_RELEASE_STORE_PASSWORD            (required)
#   GX_RELEASE_KEY_ALIAS                 (required)
#   GX_RELEASE_KEY_PASSWORD              (required)
#   GX_RELEASE_LINEAGE_KEYSTORE          (required) the OLD keystore to authorise from
#   GX_RELEASE_LINEAGE_STORE_PASSWORD    (default: android)
#   GX_RELEASE_LINEAGE_KEY_ALIAS         (default: androiddebugkey)
#   GX_RELEASE_LINEAGE_KEY_PASSWORD      (default: android)
#   ANDROID_HOME / ANDROID_SDK_ROOT      where build-tools/apksigner lives
set -euo pipefail

fail() { echo "::error::$*" >&2; exit 1; }

: "${APK:?APK must be set to the signed release APK to re-sign}"
: "${GX_RELEASE_STORE_FILE:?GX_RELEASE_STORE_FILE (new keystore) must be set}"
: "${GX_RELEASE_STORE_PASSWORD:?GX_RELEASE_STORE_PASSWORD must be set}"
: "${GX_RELEASE_KEY_ALIAS:?GX_RELEASE_KEY_ALIAS must be set}"
: "${GX_RELEASE_KEY_PASSWORD:?GX_RELEASE_KEY_PASSWORD must be set}"
: "${GX_RELEASE_LINEAGE_KEYSTORE:?GX_RELEASE_LINEAGE_KEYSTORE (old keystore) must be set; without it no lineage can be built}"

OLD_STORE_PASSWORD="${GX_RELEASE_LINEAGE_STORE_PASSWORD:-android}"
OLD_KEY_ALIAS="${GX_RELEASE_LINEAGE_KEY_ALIAS:-androiddebugkey}"
OLD_KEY_PASSWORD="${GX_RELEASE_LINEAGE_KEY_PASSWORD:-android}"

[[ -f "${APK}" ]] || fail "APK not found: ${APK}"
[[ -f "${GX_RELEASE_STORE_FILE}" ]] || fail "new keystore not found: ${GX_RELEASE_STORE_FILE}"
[[ -f "${GX_RELEASE_LINEAGE_KEYSTORE}" ]] || fail "old keystore not found: ${GX_RELEASE_LINEAGE_KEYSTORE}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "${SDK}" ]] || fail "ANDROID_HOME/ANDROID_SDK_ROOT must be set to find apksigner"
BT="$(ls -d "${SDK}"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
[[ -n "${BT}" && -x "${BT}/apksigner" ]] || fail "apksigner not found under ${SDK}/build-tools"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
umask 077

# 1. The lineage: old key -> new key. apksigner writes a proof-of-rotation blob.
echo "==> building signing lineage (old -> new)"
"${BT}/apksigner" rotate --out "${WORK}/lineage.bin" \
    --old-signer --ks "${GX_RELEASE_LINEAGE_KEYSTORE}" \
        --ks-pass "pass:${OLD_STORE_PASSWORD}" --ks-key-alias "${OLD_KEY_ALIAS}" \
        --key-pass "pass:${OLD_KEY_PASSWORD}" \
    --new-signer --ks "${GX_RELEASE_STORE_FILE}" \
        --ks-pass "pass:${GX_RELEASE_STORE_PASSWORD}" --ks-key-alias "${GX_RELEASE_KEY_ALIAS}" \
        --key-pass "pass:${GX_RELEASE_KEY_PASSWORD}" \
    || fail "apksigner rotate failed; the old private key may not match the one existing installs used"

# 2. Re-sign in place with the new key and the lineage attached. Only v3 is enabled: the
#    proof-of-rotation lives in the v3 signature block, and apksigner refuses to combine a lineage
#    with v1 or v2 (those schemes predate rotation and cannot express it). v3 is supported from
#    Android 9 (API 28), which is exactly this app's minSdk, so every supported device installs it.
echo "==> re-signing with the production key and the lineage (v3)"
"${BT}/apksigner" sign --ks "${GX_RELEASE_STORE_FILE}" \
    --ks-pass "pass:${GX_RELEASE_STORE_PASSWORD}" --ks-key-alias "${GX_RELEASE_KEY_ALIAS}" \
    --key-pass "pass:${GX_RELEASE_KEY_PASSWORD}" --lineage "${WORK}/lineage.bin" \
    --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
    --rotation-min-sdk-version 28 \
    --in "${APK}" --out "${WORK}/rotated.apk" \
    || fail "apksigner sign with the lineage failed"
mv "${WORK}/rotated.apk" "${APK}"

# 3. Prove it: the APK must still verify, and its v3 signature block must carry the
#    proof-of-rotation attribute (APK Signature Scheme v3 attribute id 0x3ba06f8c). apksigner's
#    --verbose output does not mention the lineage, so check the signed bytes for the attribute id.
"${BT}/apksigner" verify --verbose --print-certs "${APK}" | tee "${WORK}/verify.txt"
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" "${WORK}/verify.txt" \
    || fail "the re-signed APK has no v3 signature"
python3 - "${APK}" <<'PY' || fail "the re-signed APK carries no proof-of-rotation lineage; existing installs would still need a reinstall"
import struct, sys
data = open(sys.argv[1], "rb").read()
if struct.pack("<I", 0x3BA06F8C) not in data:
    sys.exit(1)
PY
echo "==> lineage attached; existing installs on Android 9+ accept this APK as an update"
