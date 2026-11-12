#!/usr/bin/env python3
# GeneralsX @feature Android security 12/11/2026
#
# Fail CI if the driver trust pins disagree, or if either is malformed.
#
# Two files together decide which custom Vulkan drivers this port vouches for:
#
#   scripts/build/android/fetch-turnip.sh   TURNIP_ZIP_SHA256  -- the archive the APK bundles
#   CustomDriverPackage.java                VERIFIED_DRIVER_SHA256 -- the libraries marked verified
#
# The fetch script pins the *archive*; the Java allowlist pins the *driver library inside it*.
# They are different hashes of different bytes, so this check cannot compare them directly -- what
# it enforces is that both are present, both are well-formed SHA-256 hex, and neither has been left
# as a placeholder or an empty list. The library hash in the allowlist is the one the release must
# actually publish; recompute it with `sha256sum` on the driver .so the pinned archive contains.
#
#     python3 scripts/qa/android/check-driver-allowlist.py
#
# Exit status is 0 when the pins are well-formed and present, 1 otherwise.
import os
import re
import sys

REPO_ROOT = os.environ.get("GX_REPO_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

FETCH_TURNIP = os.path.join(REPO_ROOT, "scripts", "build", "android", "fetch-turnip.sh")
CUSTOM_DRIVER = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java",
                             "com", "ports", "generalsx", "CustomDriverPackage.java")

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def main():
    problems = []

    try:
        turnip = read(FETCH_TURNIP)
    except OSError as e:
        print("::error::cannot read %s: %s" % (FETCH_TURNIP, e))
        return 1
    m = re.search(r'^TURNIP_ZIP_SHA256="([^"]*)"', turnip, re.MULTILINE)
    if not m:
        problems.append("fetch-turnip.sh has no TURNIP_ZIP_SHA256 pin")
    elif not SHA256_RE.match(m.group(1)):
        problems.append("fetch-turnip.sh TURNIP_ZIP_SHA256 is not a SHA-256 hex digest: %r"
                        % m.group(1))

    try:
        java = read(CUSTOM_DRIVER)
    except OSError as e:
        print("::error::cannot read %s: %s" % (CUSTOM_DRIVER, e))
        return 1
    block = re.search(r"VERIFIED_DRIVER_SHA256\s*=\s*\{(.*?)\}", java, re.DOTALL)
    if not block:
        problems.append("CustomDriverPackage.java has no VERIFIED_DRIVER_SHA256 array")
    else:
        hashes = re.findall(r'"([0-9a-fA-F]{64})"', block.group(1))
        if not hashes:
            problems.append("VERIFIED_DRIVER_SHA256 is empty -- no driver would ever be verified")
        for h in hashes:
            if not SHA256_RE.match(h.lower()):
                problems.append("VERIFIED_DRIVER_SHA256 entry is not a SHA-256 hex digest: %r" % h)

    # The Java allowlist is what the launcher marks verified; it must not be a copy of the archive
    # pin (that would mean someone pasted the wrong hash into the wrong file).
    if m and SHA256_RE.match(m.group(1)):
        if block and m.group(1) in re.findall(r'"([0-9a-fA-F]{64})"', block.group(1)):
            problems.append("the archive pin equals a library allowlist entry -- the two pin "
                            "different bytes (the .zip vs the .so inside it) and cannot be the same")

    if problems:
        for p in problems:
            print("::error::" + p)
        return 1

    print("OK: driver archive pin and library allowlist are present and well-formed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
