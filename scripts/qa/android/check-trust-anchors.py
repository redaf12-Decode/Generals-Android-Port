#!/usr/bin/env python3
# GeneralsX @feature Android security 12/11/2026
#
# Fail CI if the repository's cryptographic trust anchors are inconsistent, unparseable, or still
# carry a retired key.
#
# The port pins three public keys, in three files, for two independent signing domains:
#
#   update metadata   UpdateChecker.PUBLIC_KEY_B64      (signs update.json)
#   engine update     UpdateManager.PUBLIC_KEY_B64      (signs manifest.json)
#   engine update     GXSignedSettings.cpp kPublicKeyB64 (the engine checks the same manifest)
#
# A rotation touches all of them, and the failure mode of getting it half-done is silent: the
# launcher would keep trusting the old metadata key while the engine trusted the new manifest key,
# or vice versa, and nothing would say so until an update was refused (or, worse, accepted from a
# key that should have been retired). This check makes the invariants explicit:
#
#   1. Every anchor is a real, parseable P-256 SubjectPublicKeyInfo.
#   2. The launcher's engine-update key and the engine's own copy are byte-for-byte identical.
#   3. The metadata key and the engine-update key are different keys (domain separation).
#   4. The committed update.json.sig verifies against the metadata key, and NOT against the
#      engine-update key.
#   5. None of the anchors is one of the retired generations.
#   6. No retired key literal survives in the launcher's or the engine's active source.
#
# It uses openssl (present on every runner and developer machine) so it needs no Python crypto
# dependency.
import base64
import os
import re
import subprocess
import sys
import tempfile

# The repository root. Overridable so the signing self-test (tests/security/test_signing.py) can
# point the check at a fixture tree instead of the real checkout.
REPO_ROOT = os.environ.get("GX_REPO_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

UPDATE_CHECKER = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java",
                              "com", "ports", "generalsx", "UpdateChecker.java")
UPDATE_MANAGER = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java",
                              "com", "ports", "generalsx", "UpdateManager.java")
SIGNED_SETTINGS = os.path.join(REPO_ROOT, "GeneralsMD", "Code", "GameEngine", "Source",
                               "Common", "GXSignedSettings.cpp")
UPDATE_JSON = os.path.join(REPO_ROOT, "update.json")
UPDATE_SIG = os.path.join(REPO_ROOT, "update.json.sig")

# Retired generations, by the SHA-256 of their SubjectPublicKeyInfo. Kept as fingerprints (not the
# key bytes) so this file is not itself a home for a retired key.
RETIRED_SPKI_SHA256 = {
    # update-metadata key, first generation (retired 12/11/2026)
    "89d7abc6c96ebc12774ae3523c3ee0b8da506f4561b1db5ef7d0787e4bc0088b",
    # engine-update key, first generation (retired 12/11/2026)
    "631c9268592960106c859ea89d91538d94df20396dc0b1190132c228d1a7baee",
}

# The literal base64 of those retired keys, so a leftover copy in source is caught even if the
# anchor was edited to point at a new key while the old constant stayed behind.
RETIRED_KEY_B64 = [
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/KZ+Tz9g/zR2Fp5E3drAjbOuJXveYBUsbVzbVfFZLT1AZI3SniD6OUw5gQSmB68unf5HBbrY5tw3WKa6IWDOpw==",
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFjy+4K0lTRmnwQe+nQqXreCMtJehCl1wiYNgq5Rr/MHWkDukps0eUbmuyxSenyFL4T5zo+WBIFeLDO5PXFoG/A==",
]

# Where a retired literal may still legitimately appear: documentation that records the rotation,
# and this script (which lists them on purpose).
LITERAL_SCAN_SKIP = ("docs/", "scripts/qa/android/check-trust-anchors.py")


def fail(message):
    print("::error::" + message)
    sys.exit(1)


def java_constant(path, name):
    with open(path, encoding="utf-8") as f:
        source = f.read()
    m = re.search(name + r'\s*=\s*"([^"]+)"', source)
    if not m:
        fail("could not read %s from %s" % (name, os.path.relpath(path, REPO_ROOT)))
    return m.group(1)


def cpp_constant(path, name):
    with open(path, encoding="utf-8") as f:
        source = f.read()
    m = re.search(name + r'\[\]\s*=\s*"([^"]+)"', source)
    if not m:
        fail("could not read %s from %s" % (name, os.path.relpath(path, REPO_ROOT)))
    return m.group(1)


def spki_sha256(key_b64, tmpdir, tag):
    """SHA-256 of the SubjectPublicKeyInfo DER, and a PEM copy for openssl, from a base64 SPKI."""
    try:
        der = base64.b64decode(key_b64, validate=True)
    except Exception as e:
        fail("%s is not valid base64: %s" % (tag, e))
    der_path = os.path.join(tmpdir, tag + ".der")
    with open(der_path, "wb") as f:
        f.write(der)
    canonical = subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-in", der_path,
                                "-outform", "DER"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if canonical.returncode != 0:
        fail("%s is not a parseable public key: %s" % (tag, canonical.stderr.decode().strip()))
    pem_path = os.path.join(tmpdir, tag + ".pem")
    pem = subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-in", der_path,
                          "-out", pem_path], stderr=subprocess.PIPE)
    if pem.returncode != 0:
        fail("%s could not be written as PEM: %s" % (tag, pem.stderr.decode().strip()))
    import hashlib
    return hashlib.sha256(canonical.stdout).hexdigest(), pem_path


def signature_verifies(pem_path, sig_path, body):
    result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pem_path,
                             "-signature", sig_path],
                            input=body, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return result.returncode == 0


def scan_for_retired_literals():
    out = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT,
                         stdout=subprocess.PIPE, check=True).stdout.decode()
    problems = []
    for rel in out.splitlines():
        if not rel or rel.startswith(LITERAL_SCAN_SKIP):
            continue
        if not rel.endswith((".java", ".kt", ".cpp", ".cc", ".cxx", ".h", ".hpp")):
            continue
        path = os.path.join(REPO_ROOT, rel)
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                text = f.read()
        except OSError:
            continue
        for literal in RETIRED_KEY_B64:
            if literal in text:
                problems.append("%s still contains a retired public key" % rel)
    return problems


def main():
    with tempfile.TemporaryDirectory() as tmpdir:
        metadata_key = java_constant(UPDATE_CHECKER, "PUBLIC_KEY_B64")
        engine_key_java = java_constant(UPDATE_MANAGER, "PUBLIC_KEY_B64")
        engine_key_native = cpp_constant(SIGNED_SETTINGS, "kPublicKeyB64")

        metadata_fp, metadata_pem = spki_sha256(metadata_key, tmpdir, "metadata")
        engine_fp, engine_pem = spki_sha256(engine_key_java, tmpdir, "engine")

        if engine_key_java != engine_key_native:
            fail("the launcher's engine-update key (UpdateManager) and the engine's own copy "
                 "(GXSignedSettings.cpp) differ: the launcher and the engine would trust different "
                 "engine-update identities")

        if metadata_fp == engine_fp:
            fail("the metadata key and the engine-update key are the same key; the two signing "
                 "domains must stay independent")

        for name, fp in (("metadata", metadata_fp), ("engine-update", engine_fp)):
            if fp in RETIRED_SPKI_SHA256:
                fail("the %s anchor is a retired key (SPKI SHA-256 %s)" % (name, fp))

        # The committed metadata must verify under the metadata key and under nothing else.
        if not os.path.isfile(UPDATE_JSON) or not os.path.isfile(UPDATE_SIG):
            fail("update.json / update.json.sig are missing")
        body = open(UPDATE_JSON, "rb").read()
        sig_text = open(UPDATE_SIG, encoding="ascii").read().strip()
        try:
            sig = base64.b64decode(sig_text, validate=True)
        except Exception as e:
            fail("update.json.sig is not valid base64: %s" % e)
        sig_path = os.path.join(tmpdir, "update.sig")
        with open(sig_path, "wb") as f:
            f.write(sig)

        if not signature_verifies(metadata_pem, sig_path, body):
            fail("update.json.sig does not verify against the pinned metadata key "
                 "(UpdateChecker.PUBLIC_KEY_B64)")
        if signature_verifies(engine_pem, sig_path, body):
            fail("update.json.sig verifies against the engine-update key: the two signing domains "
                 "are not independent")

        problems = scan_for_retired_literals()
        if problems:
            for p in problems:
                print("::error::" + p)
            sys.exit(1)

        print("Trust anchors OK.")
        print("  update metadata   SPKI SHA-256 %s" % metadata_fp)
        print("  engine update     SPKI SHA-256 %s (launcher == engine)" % engine_fp)
        print("  update.json.sig   verifies under the metadata key only")


if __name__ == "__main__":
    main()
