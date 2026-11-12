#!/usr/bin/env python3
# GeneralsX @feature Android security 10/11/2026
#
# Validate update.json and update.json.sig in the repository, and fail if either is not a
# publishable, self-consistent, correctly signed pair.
#
# This is the CI counterpart to the launcher's own check: the launcher refuses metadata that is
# unsigned, whose signature does not verify against the pinned key, or whose fields are
# placeholders. Running the same rules here means a bad update.json cannot be merged in the first
# place -- the failure mode the launcher used to ship with (an all-zero sha256 and a zero size).
#
# It reads the pinned public key straight out of UpdateChecker.java so the key and this check
# cannot drift apart.
import base64
import json
import os
import re
import sys

# The repository root. Overridable so the signing self-test can point the check at a fixture tree.
REPO_ROOT = os.environ.get("GX_REPO_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
UPDATE_JSON = os.path.join(REPO_ROOT, "update.json")
UPDATE_SIG = os.path.join(REPO_ROOT, "update.json.sig")
CHECKER = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java",
                       "com", "ports", "generalsx", "UpdateChecker.java")

TRUSTED_HOST = "github.com"
TRUSTED_PATH_PREFIX = "/redaf12-Decode/Generals-Android-Port/releases/download/"


def fail(message):
    print("::error::" + message)
    sys.exit(1)


def pinned_public_key():
    with open(CHECKER, encoding="utf-8") as f:
        source = f.read()
    m = re.search(r'PUBLIC_KEY_B64\s*=\s*"([^"]+)"', source)
    if not m:
        fail("could not read PUBLIC_KEY_B64 from UpdateChecker.java")
    return m.group(1)


def main():
    if not os.path.isfile(UPDATE_JSON):
        fail("update.json is missing")
    if not os.path.isfile(UPDATE_SIG):
        fail("update.json.sig is missing: update metadata must be signed")

    body = open(UPDATE_JSON, "rb").read()
    try:
        doc = json.loads(body)
    except ValueError as e:
        fail("update.json is not valid JSON: %s" % e)

    # Fields the launcher reads.
    version_code = doc.get("versionCode")
    package = doc.get("packageName")
    url = doc.get("apkUrl")
    sha = doc.get("apkSha256", "")
    size = doc.get("apkSizeBytes")

    if not isinstance(version_code, int) or version_code <= 0:
        fail("versionCode must be a positive integer")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", package or ""):
        fail("packageName is missing or malformed: %r" % package)
    if not isinstance(size, int) or size <= 0:
        fail("apkSizeBytes is zero or not an integer (placeholder): %r" % size)
    if not re.fullmatch(r"[0-9a-fA-F]{64}", sha) or set(sha) == {"0"}:
        fail("apkSha256 is missing, malformed or the all-zero placeholder: %r" % sha)
    if not isinstance(url, str) or not url.startswith("https://"):
        fail("apkUrl is not an https:// URL: %r" % url)
    # Scheme, host and path, not a substring test.
    m = re.match(r"https://([^/]+)(/.*)$", url)
    if not m or m.group(1) != TRUSTED_HOST or not m.group(2).startswith(TRUSTED_PATH_PREFIX):
        fail("apkUrl must be https://%s%s... (got %r)" % (TRUSTED_HOST, TRUSTED_PATH_PREFIX, url))

    # Signature: base64 ECDSA-P256/SHA-256 over the exact bytes of update.json.
    sig_text = open(UPDATE_SIG, encoding="ascii").read().strip()
    if not sig_text:
        fail("update.json.sig is empty")
    try:
        sig = base64.b64decode(sig_text, validate=True)
    except Exception as e:
        fail("update.json.sig is not base64: %s" % e)

    key_b64 = pinned_public_key()
    try:
        from cryptography.hazmat.primitives.serialization import load_der_public_key
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives import hashes
    except ImportError:
        # cryptography is not always installed; fall back to openssl rather than skip the check.
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            der = os.path.join(d, "key.der")
            with open(der, "wb") as f:
                f.write(base64.b64decode(key_b64))
            pem = os.path.join(d, "key.pem")
            subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-in", der,
                            "-out", pem], check=True)
            raw = os.path.join(d, "sig.bin")
            with open(raw, "wb") as f:
                f.write(sig)
            result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pem,
                                     "-signature", raw], input=body,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if result.returncode != 0:
                fail("update.json.sig does not verify against the pinned public key")
    else:
        pub = load_der_public_key(base64.b64decode(key_b64))
        try:
            pub.verify(sig, body, ec.ECDSA(hashes.SHA256()))
        except Exception:
            fail("update.json.sig does not verify against the pinned public key")

    print("update.json OK: versionCode=%d packageName=%s size=%d sha256=%s"
          % (version_code, package, size, sha))
    print("update.json.sig OK: verified against the pinned key in UpdateChecker.java")


if __name__ == "__main__":
    main()
