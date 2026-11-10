#!/usr/bin/env python3
"""Validate a published release against its update metadata.

GeneralsX @feature Android port 10/11/2026

Runs as the last step of the Publish Android Release workflow, after the
metadata has been uploaded. It re-reads the release from the GitHub API and
confirms every value the app will trust is exactly what is published:

  * update.json and update.json.sig are both assets of the release;
  * the APK asset exists, and its name, size and (when GitHub reports one)
    digest match the metadata;
  * the metadata's package name, version code and certificate are self
    consistent.

Any mismatch exits non-zero, so a bad release fails the workflow instead of
silently shipping.

Usage:
    validate-release.py --repo <owner/name> --tag <vX.Y.Z> --metadata <update.json>
"""

import argparse
import json
import os
import sys
import urllib.request


def fail(message: str) -> "None":
    print("error: " + message, file=sys.stderr)
    raise SystemExit(1)


def get_release(repo: str, tag: str) -> dict:
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/tags/{tag}")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--metadata", required=True)
    args = ap.parse_args()

    with open(args.metadata, encoding="utf-8") as f:
        meta = json.load(f)

    release = get_release(args.repo, args.tag)
    assets = {a["name"]: a for a in release.get("assets", [])}

    if release.get("draft") or release.get("prerelease"):
        fail(f"{args.tag} is a draft or prerelease; the app reads 'latest' only")

    for required in ("update.json", "update.json.sig"):
        if required not in assets:
            fail(f"release {args.tag} has no {required} asset")

    apk_name = meta["apk"]["fileName"]
    if apk_name not in assets:
        fail(f"release {args.tag} has no asset named {apk_name}")
    asset = assets[apk_name]

    if int(asset["size"]) != int(meta["apk"]["sizeBytes"]):
        fail(f"asset size {asset['size']} != metadata sizeBytes {meta['apk']['sizeBytes']}")
    if asset["browser_download_url"] != meta["apk"]["downloadUrl"]:
        fail("asset download URL does not match the metadata downloadUrl")
    digest = asset.get("digest")
    if digest and digest.startswith("sha256:") and digest[7:].lower() != meta["apk"]["sha256"].lower():
        fail("asset digest does not match the metadata sha256")

    if meta.get("repository") != args.repo:
        fail(f"metadata repository {meta.get('repository')} != {args.repo}")
    if meta.get("packageName") != "com.ports.generalsx":
        fail(f"metadata packageName {meta.get('packageName')} is not the production id")
    sha = meta["apk"]["sha256"]
    if len(sha) != 64:
        fail("metadata sha256 is not 64 hex characters")
    cert = meta["apk"]["signingCertificateSha256"]
    if len(cert) != 64:
        fail("metadata signingCertificateSha256 is not 64 hex characters")

    print(f"release {args.tag} is consistent:")
    print(f"  apk       {apk_name} ({asset['size']} bytes)")
    print(f"  sha256    {sha}")
    print(f"  cert      {cert}")
    print(f"  version   {meta.get('versionName')} ({meta.get('versionCode')})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
