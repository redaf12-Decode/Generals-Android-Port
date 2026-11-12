#!/usr/bin/env python3
# GeneralsX @feature Android security 12/11/2026
#
# Verify a release APK the way the security requirements say it must be verified, in one reusable
# place so a local release build and CI run the identical check:
#
#   * it is signed (apksigner verifies it);
#   * it is signed by the EXPECTED production certificate (fingerprint match), so a build signed
#     with the wrong -- or a debug -- key is refused;
#   * it is NOT debuggable;
#   * it carries the expected applicationId.
#
# It refuses an unsigned APK, a debug-signed one, a wrong-fingerprint one, and one with the wrong
# package id. It never falls back to "assume it is fine" when a tool is missing: apksigner and
# aapt2 must be found (from --build-tools, ANDROID_HOME/ANDROID_SDK_ROOT build-tools, or PATH).
#
# Usage:
#   verify-release-apk.py --apk app-release.apk --expect-package com.ports.generalsx \
#       [--expect-cert-sha256 AA:BB:...] [--build-tools "$ANDROID_HOME/build-tools/35.0.0"]
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys


def fail(message):
    print("::error::" + message)
    sys.exit(1)


def find_tool(name, build_tools):
    candidates = []
    if build_tools:
        candidates.append(os.path.join(build_tools, name))
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        bt_root = os.path.join(sdk, "build-tools")
        for d in sorted(glob.glob(os.path.join(bt_root, "*")), reverse=True):
            candidates.append(os.path.join(d, name))
    found = shutil.which(name)
    if found:
        candidates.append(found)
    for c in candidates:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


def normalise_fingerprint(value):
    return value.replace(":", "").replace(" ", "").upper()


# The APK Signature Scheme v3 proof-of-rotation attribute id. A v3 block that carries a signing
# lineage includes this attribute; its presence is what lets Android 9+ accept the APK as an update
# to one signed by an ancestor key. apksigner's --verbose output does not mention it, so the signed
# bytes are inspected directly.
PROOF_OF_ROTATION_ATTR_ID = 0x3BA06F8C


def has_proof_of_rotation(apk_path):
    import struct
    with open(apk_path, "rb") as f:
        data = f.read()
    return struct.pack("<I", PROOF_OF_ROTATION_ATTR_ID) in data


def main():
    ap = argparse.ArgumentParser(description="Verify a signed release APK")
    ap.add_argument("--apk", required=True)
    ap.add_argument("--expect-package", default="com.ports.generalsx")
    ap.add_argument("--expect-cert-sha256", default="",
                    help="colon- or space-separated SHA-256 of the expected signing certificate")
    ap.add_argument("--reject-cert-sha256", action="append", default=[],
                    help="a certificate SHA-256 the APK must NOT be signed with (repeatable); "
                         "use for a known-compromised key such as a committed debug keystore")
    ap.add_argument("--build-tools", default="",
                    help="a specific build-tools directory (default: the newest under the SDK)")
    ap.add_argument("--require-rotation", action="store_true",
                    help="fail unless the APK carries a v3 proof-of-rotation lineage")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        fail("APK not found: %s" % args.apk)

    apksigner = find_tool("apksigner", args.build_tools)
    aapt2 = find_tool("aapt2", args.build_tools)
    if not apksigner:
        fail("apksigner not found (checked --build-tools, the SDK, and PATH)")
    if not aapt2:
        fail("aapt2 not found (checked --build-tools, the SDK, and PATH)")

    verify = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", args.apk],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = verify.stdout.decode("utf-8", "replace")
    print(text)
    if verify.returncode != 0:
        fail("apksigner verify failed: the APK is not correctly signed")

    # The certificate the release is signed with.
    m = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F: ]+)", text)
    if not m:
        fail("could not read the signing certificate's SHA-256 digest from apksigner")
    cert_sha256 = normalise_fingerprint(m.group(1))
    print("Signer #1 certificate SHA-256: %s" % cert_sha256)

    # v2+ is required: the launcher's own ApkInstaller checks the signing certificate, and Android
    # 9+ (this app's floor) installs only v2+ or v3 signed packages.
    if "Verified using v2 scheme" not in text and "Verified using v3 scheme" not in text:
        fail("the APK has no v2/v3 signature (v1 only is not acceptable for a release)")

    if args.expect_cert_sha256:
        want = normalise_fingerprint(args.expect_cert_sha256)
        if cert_sha256 != want:
            fail("APK certificate does not match the expected production certificate\n"
                 "  expected: %s\n  actual:   %s" % (want, cert_sha256))
        print("Certificate matches the expected production certificate.")

    # A compromised certificate (a committed debug keystore) must never sign a release, whatever
    # the expected fingerprint is. Refuse it explicitly, and refuse the debug-certificate shape
    # (its subject carries "Android Debug") as a second, independent line of defence.
    rejected = {normalise_fingerprint(v) for v in args.reject_cert_sha256}
    if cert_sha256 in rejected:
        fail("the APK is signed with a rejected (compromised) certificate: %s" % cert_sha256)
    m_dn = re.search(r"Signer #1 certificate DN:\s*(.+)", text)
    signer_dn = m_dn.group(1).strip() if m_dn else ""
    if "Android Debug" in signer_dn:
        fail("the APK is signed with a debug certificate (%r); a release must use the "
             "production key" % signer_dn)

    # Badging: package id and the debuggable flag.
    badging = subprocess.run([aapt2, "dump", "badging", args.apk],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if badging.returncode != 0:
        fail("aapt2 dump badging failed: %s" % badging.stdout.decode("utf-8", "replace"))
    badging_text = badging.stdout.decode("utf-8", "replace")
    if "application-debuggable" in badging_text:
        fail("the release APK is debuggable")
    if "package: name='%s'" % args.expect_package not in badging_text:
        fail("unexpected package id; expected %r" % args.expect_package)

    # Proof-of-rotation: if the APK is meant to update an install signed with the old key, its v3
    # block must carry the lineage. When --require-rotation is set, its absence is a failure, so a
    # release that would force existing users to uninstall is caught before it ships.
    if args.require_rotation and not has_proof_of_rotation(args.apk):
        fail("--require-rotation was set but the APK carries no v3 proof-of-rotation lineage; "
             "existing installs would be forced to uninstall and reinstall")

    print("OK: signed%s, non-debuggable, package %s%s"
          % ("" if not args.expect_cert_sha256 else " with the expected certificate",
             args.expect_package,
             ", carries a proof-of-rotation lineage" if has_proof_of_rotation(args.apk) else ""))


if __name__ == "__main__":
    main()
