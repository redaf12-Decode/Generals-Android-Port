#!/usr/bin/env python3
"""Generate and sign the update metadata from a real, signed release APK.

GeneralsX @feature Android port 10/11/2026

The application never trusts a hand-written update.json. This script builds the
metadata out of the exact artifact that is published -- package name, version
code and name from the APK's own manifest, size and SHA-256 from its bytes, the
signing certificate SHA-256 from its signature -- then signs the whole document
with the ECDSA P-256 key the release workflow keeps in the UPDATE_METADATA_KEY
secret. The app verifies that signature with the public key compiled into
UpdateChecker before it believes any field.

Nothing here is fabricated: a missing value fails the run.

Usage:
    generate-update-metadata.py --apk <signed.apk> --repo <owner/name>
        --tag <vX.Y.Z> --out <dir> [--notes <file>]
        [--key <private-key.pem>] [--asset-url <url>] [--min-version-code <n>]

Environment:
    GITHUB_TOKEN   used to look up the release asset URL and size when --asset-url
                   is not given (the repository is private).
"""

import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

SCHEMA_VERSION = 1


def fail(message: str) -> "None":
    print("error: " + message, file=sys.stderr)
    raise SystemExit(1)


def find_tool(name: str) -> str:
    """aapt2/apksigner live in the Android SDK build-tools, not on PATH in CI."""
    from shutil import which

    path = which(name)
    if path:
        return path
    roots = []
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        roots.append(os.path.join(sdk, "build-tools"))
    for root in roots:
        if not os.path.isdir(root):
            continue
        for version in sorted(os.listdir(root), reverse=True):
            candidate = os.path.join(root, version, name)
            if os.path.isfile(candidate):
                return candidate
    fail(f"{name} not found on PATH or in the Android SDK build-tools")


def read_badging(apk: str) -> dict:
    """package name, versionCode and versionName from the APK manifest (aapt2)."""
    out = subprocess.run([find_tool("aapt2"), "dump", "badging", apk],
                         capture_output=True, text=True, check=True).stdout
    info = {}
    for line in out.splitlines():
        if line.startswith("package:"):
            for part in line.split():
                if part.startswith("name="):
                    info["packageName"] = part.split("=", 1)[1].strip("'")
                elif part.startswith("versionCode="):
                    info["versionCode"] = int(part.split("=", 1)[1].strip("'"))
                elif part.startswith("versionName="):
                    info["versionName"] = part.split("=", 1)[1].strip("'")
    if "packageName" not in info or "versionCode" not in info:
        fail("could not read package name / version code from " + apk)
    return info


def read_signer_sha256(apk: str) -> str:
    """The signing certificate's SHA-256, from apksigner (the v2/v3 signer)."""
    out = subprocess.run([find_tool("apksigner"), "verify", "--print-certs", apk],
                         capture_output=True, text=True)
    if out.returncode != 0:
        fail("apksigner could not verify " + apk + ": " + out.stderr.strip())
    for line in out.stdout.splitlines():
        if "SHA-256 digest" in line:
            return line.split(":", 1)[1].strip().lower()
    fail("no signer certificate SHA-256 in apksigner output")


def file_sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def release_asset(repo: str, tag: str, name: str) -> dict:
    """The release asset whose name matches, straight from the GitHub API."""
    url = f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
    req = urllib.request.Request(url)
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=30) as resp:
        release = json.load(resp)
    for asset in release.get("assets", []):
        if asset.get("name") == name:
            return asset
    fail(f"release {tag} has no asset named {name}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--notes", default=None)
    ap.add_argument("--key", default=os.environ.get("UPDATE_METADATA_KEY_FILE"))
    ap.add_argument("--asset-url", default=None)
    ap.add_argument("--min-version-code", type=int, default=0)
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        fail("no APK at " + args.apk)

    badging = read_badging(args.apk)
    apk_name = os.path.basename(args.apk)
    size = os.path.getsize(args.apk)
    sha256 = file_sha256(args.apk)
    cert = read_signer_sha256(args.apk)

    if args.asset_url:
        download_url = args.asset_url
        asset_size = size
    else:
        asset = release_asset(args.repo, args.tag, apk_name)
        download_url = asset["browser_download_url"]
        asset_size = int(asset["size"])
        if asset_size != size:
            fail(f"asset size {asset_size} != local APK size {size}")
        # When GitHub reports a digest, it must match the bytes we hashed.
        digest = asset.get("digest")
        if digest and digest.startswith("sha256:") and digest[7:].lower() != sha256:
            fail("GitHub asset digest does not match the APK")

    release_url = f"https://github.com/{args.repo}/releases/tag/{args.tag}"
    notes = ""
    if args.notes and os.path.isfile(args.notes):
        with open(args.notes, encoding="utf-8") as f:
            notes = f.read().strip()

    metadata = {
        "schemaVersion": SCHEMA_VERSION,
        "repository": args.repo,
        "releaseTag": args.tag,
        "versionName": badging.get("versionName", ""),
        "versionCode": badging["versionCode"],
        "packageName": badging["packageName"],
        "releaseUrl": release_url,
        "apk": {
            "fileName": apk_name,
            "downloadUrl": download_url,
            "sizeBytes": size,
            "sha256": sha256,
            "signingCertificateSha256": cert,
        },
        "minSupportedVersionCode": args.min_version_code,
        "releaseNotes": notes,
    }

    payload = json.dumps(metadata, indent=2, sort_keys=False).encode("utf-8") + b"\n"
    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "update.json")
    with open(json_path, "wb") as f:
        f.write(payload)

    if args.key:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        with open(args.key, "rb") as f:
            key = serialization.load_pem_private_key(f.read(), password=None)
        if not isinstance(key, ec.EllipticCurvePrivateKey):
            fail("the metadata key must be an EC P-256 private key")
        signature = key.sign(payload, ec.ECDSA(hashes.SHA256()))
        with open(json_path + ".sig", "wb") as f:
            f.write(base64.b64encode(signature) + b"\n")
        print("wrote", json_path, "and its signature")
    else:
        print("wrote", json_path, "(unsigned: no --key given)")

    print(f"  packageName        {metadata['packageName']}")
    print(f"  versionCode        {metadata['versionCode']}")
    print(f"  sizeBytes          {size}")
    print(f"  sha256             {sha256}")
    print(f"  signingCertificate {cert}")
    print(f"  downloadUrl        {download_url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
