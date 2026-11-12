# GeneralsX @feature Android security 12/11/2026
#
# Signing-security self-tests. They exercise the real checks the repository relies on --
# scripts/qa/android/check-update-metadata.py, check-trust-anchors.py, check-no-committed-secrets.py
# and verify-release-apk.py -- against throwaway keys generated inside the test. No production key
# is ever a fixture, and nothing here touches the network.
#
# The metadata matrix proves the requirements that matter: a fresh signature is accepted, and an
# old-key signature, modified metadata, an invalid base64 signature and a missing signature are all
# rejected. The trust-anchor matrix proves the three pinned anchors must agree, must be different
# keys for the two domains, must verify the committed metadata under the metadata key only, and
# must carry no retired key. The APK and secret-scan checks prove they refuse what they should.
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QA_DIR = os.path.join(REPO_ROOT, "scripts", "qa", "android")
UPDATE_DIR = os.path.join(REPO_ROOT, "scripts", "update")

UPDATE_CHECKER_REL = os.path.join("android", "app", "src", "main", "java", "com", "generalsx",
                                  "zerohour", "UpdateChecker.java")
UPDATE_MANAGER_REL = os.path.join("android", "app", "src", "main", "java", "com", "generalsx",
                                  "zerohour", "UpdateManager.java")
SIGNED_SETTINGS_REL = os.path.join("GeneralsMD", "Code", "GameEngine", "Source", "Common",
                                   "GXSignedSettings.cpp")


# --- helpers ------------------------------------------------------------------------------------

def run(cmd, cwd=None, env=None, input_bytes=None):
    full_env = dict(os.environ)
    if env:
        full_env.update(env)
    return subprocess.run(cmd, cwd=cwd, env=full_env, input=input_bytes,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def run_bin(cmd, input_bytes=None):
    """Capture binary stdout only. openssl writes DER and signatures to stdout but logs to stderr,
    so the two must not be merged or the bytes are corrupted."""
    return subprocess.run(cmd, input=input_bytes, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL).stdout


def openssl_ec_keypair(tmpdir, name):
    """(private_pem_path, public_spki_b64) for a fresh P-256 key."""
    priv = os.path.join(tmpdir, name + ".pem")
    run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", priv])
    der = run_bin(["openssl", "ec", "-in", priv, "-pubout", "-outform", "DER"])
    return priv, base64.b64encode(der).decode("ascii")


def sign_b64(private_pem, data):
    sig = run_bin(["openssl", "dgst", "-sha256", "-sign", private_pem], input_bytes=data)
    return base64.b64encode(sig).decode("ascii") + "\n"


def spki_sha256(b64):
    der = base64.b64decode(b64)
    canonical = run_bin(["openssl", "pkey", "-pubin", "-inform", "DER", "-outform", "DER"],
                        input_bytes=der)
    return hashlib.sha256(canonical).hexdigest()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def java_with_key(key_b64):
    return ('package com.generalsx.zerohour;\n'
            'final class X {\n'
            '    static final String PUBLIC_KEY_B64 =\n'
            '        "%s";\n'
            '}\n' % key_b64)


def cpp_with_key(key_b64):
    return ('const char kPublicKeyB64[] =\n'
            '\t"%s";\n' % key_b64)


def git_init(path):
    run(["git", "init", "-q"], cwd=path)
    run(["git", "config", "user.email", "t@t"], cwd=path)
    run(["git", "config", "user.name", "t"], cwd=path)
    run(["git", "add", "-A"], cwd=path)
    run(["git", "commit", "-qm", "fixture"], cwd=path)


# --- update metadata: the accept/reject matrix --------------------------------------------------

def test_update_metadata_accepts_a_fresh_signature():
    with tempfile.TemporaryDirectory() as d:
        priv, pub = openssl_ec_keypair(d, "meta")
        body = json.dumps({
            "versionCode": 10500, "versionName": "1.5.0", "releaseNotes": [],
            "packageName": "com.ports.generalsx",
            "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/"
                      "v1.5.0/app.apk",
            "apkSha256": "a" * 64, "apkSizeBytes": 123,
        }).encode()
        write(os.path.join(d, "update.json"), body.decode())
        write(os.path.join(d, "update.json.sig"), sign_b64(priv, body))
        write(os.path.join(d, UPDATE_CHECKER_REL), java_with_key(pub))
        r = run([sys.executable, os.path.join(QA_DIR, "check-update-metadata.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode == 0, r.stdout.decode()


def test_update_metadata_rejects_a_signature_from_an_old_key():
    with tempfile.TemporaryDirectory() as d:
        old_priv, _old_pub = openssl_ec_keypair(d, "old")
        _new_priv, new_pub = openssl_ec_keypair(d, "new")
        body = json.dumps({
            "versionCode": 10500, "packageName": "com.ports.generalsx",
            "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.5.0/a.apk",
            "apkSha256": "b" * 64, "apkSizeBytes": 10,
        }).encode()
        write(os.path.join(d, "update.json"), body.decode())
        # Signed with the OLD key, but the app pins the NEW key.
        write(os.path.join(d, "update.json.sig"), sign_b64(old_priv, body))
        write(os.path.join(d, UPDATE_CHECKER_REL), java_with_key(new_pub))
        r = run([sys.executable, os.path.join(QA_DIR, "check-update-metadata.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "an old-key signature must be rejected"


def test_update_metadata_rejects_modified_metadata():
    with tempfile.TemporaryDirectory() as d:
        priv, pub = openssl_ec_keypair(d, "meta")
        body = json.dumps({
            "versionCode": 10500, "packageName": "com.ports.generalsx",
            "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.5.0/a.apk",
            "apkSha256": "c" * 64, "apkSizeBytes": 10,
        }).encode()
        sig = sign_b64(priv, body)
        # The file changed after it was signed.
        write(os.path.join(d, "update.json"), body.decode().replace("10500", "99999"))
        write(os.path.join(d, "update.json.sig"), sig)
        write(os.path.join(d, UPDATE_CHECKER_REL), java_with_key(pub))
        r = run([sys.executable, os.path.join(QA_DIR, "check-update-metadata.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "modified metadata must be rejected"


def test_update_metadata_rejects_invalid_base64_signature():
    with tempfile.TemporaryDirectory() as d:
        _priv, pub = openssl_ec_keypair(d, "meta")
        write(os.path.join(d, "update.json"), json.dumps({
            "versionCode": 10500, "packageName": "com.ports.generalsx",
            "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.5.0/a.apk",
            "apkSha256": "d" * 64, "apkSizeBytes": 10,
        }))
        write(os.path.join(d, "update.json.sig"), "not base64 at all!!!")
        write(os.path.join(d, UPDATE_CHECKER_REL), java_with_key(pub))
        r = run([sys.executable, os.path.join(QA_DIR, "check-update-metadata.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "an invalid base64 signature must be rejected"


def test_update_metadata_rejects_a_missing_signature():
    with tempfile.TemporaryDirectory() as d:
        _priv, pub = openssl_ec_keypair(d, "meta")
        write(os.path.join(d, "update.json"), json.dumps({
            "versionCode": 10500, "packageName": "com.ports.generalsx",
            "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.5.0/a.apk",
            "apkSha256": "e" * 64, "apkSizeBytes": 10,
        }))
        write(os.path.join(d, UPDATE_CHECKER_REL), java_with_key(pub))
        r = run([sys.executable, os.path.join(QA_DIR, "check-update-metadata.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "metadata with no signature file must be rejected"


# --- trust anchors: the two domains and the three copies ----------------------------------------

def _anchor_fixture(d, metadata_pub, engine_pub, *, native_key=None, sig_key=None, extra_files=None):
    body = json.dumps({
        "versionCode": 10500, "packageName": "com.ports.generalsx",
        "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/v1.5.0/a.apk",
        "apkSha256": "f" * 64, "apkSizeBytes": 10,
    }).encode()
    write(os.path.join(d, "update.json"), body.decode())
    write(os.path.join(d, UPDATE_CHECKER_REL), java_with_key(metadata_pub))
    write(os.path.join(d, UPDATE_MANAGER_REL), java_with_key(engine_pub))
    write(os.path.join(d, SIGNED_SETTINGS_REL), cpp_with_key(native_key or engine_pub))
    for path, text in (extra_files or {}).items():
        write(os.path.join(d, path), text)
    return body


def test_trust_anchors_accept_a_consistent_set():
    with tempfile.TemporaryDirectory() as d:
        meta_priv, meta_pub = openssl_ec_keypair(d, "meta")
        _eng_priv, eng_pub = openssl_ec_keypair(d, "eng")
        body = _anchor_fixture(d, meta_pub, eng_pub)
        write(os.path.join(d, "update.json.sig"), sign_b64(meta_priv, body))
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-trust-anchors.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode == 0, r.stdout.decode()


def test_trust_anchors_reject_java_native_drift():
    with tempfile.TemporaryDirectory() as d:
        meta_priv, meta_pub = openssl_ec_keypair(d, "meta")
        _eng_priv, eng_pub = openssl_ec_keypair(d, "eng")
        _other_priv, other_pub = openssl_ec_keypair(d, "other")
        body = _anchor_fixture(d, meta_pub, eng_pub, native_key=other_pub)
        write(os.path.join(d, "update.json.sig"), sign_b64(meta_priv, body))
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-trust-anchors.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "a launcher/native key mismatch must be rejected"


def test_trust_anchors_reject_shared_domain_key():
    with tempfile.TemporaryDirectory() as d:
        meta_priv, meta_pub = openssl_ec_keypair(d, "meta")
        # The engine key is the SAME as the metadata key: the domains are not independent.
        body = _anchor_fixture(d, meta_pub, meta_pub)
        write(os.path.join(d, "update.json.sig"), sign_b64(meta_priv, body))
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-trust-anchors.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "the two domains must not share a key"


def test_trust_anchors_reject_signature_verifying_under_engine_key():
    with tempfile.TemporaryDirectory() as d:
        _meta_priv, meta_pub = openssl_ec_keypair(d, "meta")
        eng_priv, eng_pub = openssl_ec_keypair(d, "eng")
        # update.json.sig is signed by the ENGINE key, not the metadata key.
        body = _anchor_fixture(d, meta_pub, eng_pub)
        write(os.path.join(d, "update.json.sig"), sign_b64(eng_priv, body))
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-trust-anchors.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "metadata signed by the engine key must be rejected"


def test_trust_anchors_reject_a_retired_literal_in_source():
    with tempfile.TemporaryDirectory() as d:
        meta_priv, meta_pub = openssl_ec_keypair(d, "meta")
        _eng_priv, eng_pub = openssl_ec_keypair(d, "eng")
        retired = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFjy+4K0lTRmnwQe+nQqXreCMtJehCl1wiYNgq5Rr/MHWk"
                   "Dukps0eUbmuyxSenyFL4T5zo+WBIFeLDO5PXFoG/A==")
        body = _anchor_fixture(d, meta_pub, eng_pub, extra_files={
            "android/app/src/main/java/com/generalsx/zerohour/Leftover.java":
                java_with_key(retired),
        })
        write(os.path.join(d, "update.json.sig"), sign_b64(meta_priv, body))
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-trust-anchors.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "a retired key literal left in source must be rejected"


# --- committed-secret scan ----------------------------------------------------------------------

def test_secret_scan_rejects_a_committed_keystore():
    with tempfile.TemporaryDirectory() as d:
        write(os.path.join(d, "android/app/debug.keystore"), "\x30\x82binary")
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-no-committed-secrets.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "a committed keystore must be rejected"


def test_secret_scan_rejects_a_pem_private_key():
    with tempfile.TemporaryDirectory() as d:
        write(os.path.join(d, "keys/signing.pem"), "-----BEGIN " + "PRIVATE KEY-----\nabc\n")
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-no-committed-secrets.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode != 0, "a PEM private key must be rejected"


def test_secret_scan_accepts_a_clean_tree():
    with tempfile.TemporaryDirectory() as d:
        write(os.path.join(d, "README.md"), "nothing secret here\n")
        git_init(d)
        r = run([sys.executable, os.path.join(QA_DIR, "check-no-committed-secrets.py")],
                env={"GX_REPO_ROOT": d})
        assert r.returncode == 0, r.stdout.decode()


# --- release APK verification -------------------------------------------------------------------

def test_verify_release_apk_refuses_a_non_apk():
    """A file that is not a signed APK must be refused, not waved through."""
    with tempfile.TemporaryDirectory() as d:
        apk = os.path.join(d, "not-an.apk")
        with open(apk, "wb") as f:
            f.write(b"this is not an APK\n")
        r = run([sys.executable, os.path.join(QA_DIR, "verify-release-apk.py"), "--apk", apk])
        assert r.returncode != 0, "a non-APK file must be refused"


def test_verify_release_apk_refuses_when_the_toolchain_is_missing():
    """No apksigner/aapt2 on PATH or in the SDK must fail, never pass silently."""
    with tempfile.TemporaryDirectory() as d:
        apk = os.path.join(d, "x.apk")
        with open(apk, "wb") as f:
            f.write(b"PK\x03\x04")
        env = {"PATH": "/nonexistent", "ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}
        r = run([sys.executable, os.path.join(QA_DIR, "verify-release-apk.py"), "--apk", apk],
                env=env)
        assert r.returncode != 0, "a missing apksigner must fail the check"


DEBUG_CERT_SHA256 = "644a3b0f8234c6ea8960ece9b55a161c00966b8dd522d2558d84883f4f77c48e"
DEBUG_DN = "CN=GeneralsX Android Debug, OU=GeneralsX, O=GeneralsX, C=US"
PROD_DN = "CN=GeneralsX, O=GeneralsX, C=US"


def _stub_build_tools(d, dn, cert_sha256, debuggable=False):
    """A build-tools dir with stub apksigner/aapt2, so the verifier's decision logic can be
    exercised without a real APK. Returns the dir path."""
    bt = os.path.join(d, "build-tools")
    os.makedirs(bt, exist_ok=True)
    apksigner = os.path.join(bt, "apksigner")
    with open(apksigner, "w", encoding="utf-8") as f:
        f.write("#!/bin/sh\ncat <<'EOF'\n"
                "Signer #1 certificate DN: %s\n"
                "Signer #1 certificate SHA-256 digest: %s\n"
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "EOF\nexit 0\n" % (dn, cert_sha256))
    os.chmod(apksigner, 0o755)
    aapt2 = os.path.join(bt, "aapt2")
    with open(aapt2, "w", encoding="utf-8") as f:
        line = ("package: name='com.ports.generalsx' versionCode='10402'\n"
                + ("application-debuggable\n" if debuggable else ""))
        f.write("#!/bin/sh\ncat <<'EOF'\n%sEOF\nexit 0\n" % line)
    os.chmod(aapt2, 0o755)
    return bt


def _run_verifier(d, bt, extra):
    apk = os.path.join(d, "x.apk")
    if not os.path.isfile(apk):
        with open(apk, "wb") as f:
            f.write(b"PK\x03\x04")
    return run([sys.executable, os.path.join(QA_DIR, "verify-release-apk.py"),
                "--apk", apk, "--build-tools", bt] + extra)


def test_verify_release_apk_rejects_a_rejected_certificate():
    """A certificate on the reject list (the committed debug key) must be refused even when no
    expected fingerprint was given."""
    with tempfile.TemporaryDirectory() as d:
        bt = _stub_build_tools(d, DEBUG_DN, DEBUG_CERT_SHA256)
        r = _run_verifier(d, bt, ["--reject-cert-sha256", DEBUG_CERT_SHA256])
        assert r.returncode != 0, "a rejected (compromised) certificate must be refused"


def test_verify_release_apk_rejects_a_debug_certificate_by_subject():
    """Even without a reject list, a certificate whose subject is a debug certificate is refused,
    so a debug-signed release cannot slip through on fingerprint alone."""
    with tempfile.TemporaryDirectory() as d:
        bt = _stub_build_tools(d, DEBUG_DN, DEBUG_CERT_SHA256)
        r = _run_verifier(d, bt, ["--expect-cert-sha256", DEBUG_CERT_SHA256])
        assert r.returncode != 0, "a debug certificate must be refused by subject"


def test_verify_release_apk_rejects_a_debuggable_apk():
    with tempfile.TemporaryDirectory() as d:
        bt = _stub_build_tools(d, PROD_DN, "AA" * 32, debuggable=True)
        r = _run_verifier(d, bt, [])
        assert r.returncode != 0, "a debuggable APK must be refused"


def test_verify_release_apk_accepts_a_production_signed_apk():
    """The positive control: a non-debuggable APK with a non-debug certificate and the expected
    fingerprint passes."""
    with tempfile.TemporaryDirectory() as d:
        bt = _stub_build_tools(d, PROD_DN, "AA" * 32)
        r = _run_verifier(d, bt, ["--expect-cert-sha256", "AA" * 32])
        assert r.returncode == 0, r.stdout.decode()


# --- the rotation script, and the committed repository's own consistency -------------------------

def test_the_rotation_script_rewrites_all_anchors_and_re_signs():
    """generate + apply against a fixture tree must move all three anchors and re-sign update.json,
    after which the trust-anchor check passes on the rotated tree."""
    with tempfile.TemporaryDirectory() as d:
        keys = os.path.join(d, "keys")
        root = os.path.join(d, "repo")
        script = os.path.join(UPDATE_DIR, "rotate-signing-keys.py")

        gen = run([sys.executable, script, "generate", "--out", keys])
        assert gen.returncode == 0, gen.stdout.decode()

        # A minimal tree with the three anchor files and an update.json to re-sign.
        write(os.path.join(root, UPDATE_CHECKER_REL), java_with_key("OLDMETADATA"))
        write(os.path.join(root, UPDATE_MANAGER_REL), java_with_key("OLDENGINE"))
        write(os.path.join(root, SIGNED_SETTINGS_REL), cpp_with_key("OLDENGINE"))
        write(os.path.join(root, "update.json"), json.dumps({
            "versionCode": 10500, "versionName": "1.5.0", "releaseNotes": [],
            "packageName": "com.ports.generalsx",
            "apkUrl": "https://github.com/redaf12-Decode/Generals-Android-Port/releases/download/"
                      "v1.5.0/app.apk",
            "apkSha256": "a" * 64, "apkSizeBytes": 123,
        }))

        env = {"GX_REPO_ROOT": root}
        app = run([sys.executable, script, "apply", "--keys", keys], env=env)
        assert app.returncode == 0, app.stdout.decode()

        # The rotated tree must now be self-consistent (three agreeing anchors, fresh signature).
        git_init(root)
        checker = run([sys.executable, os.path.join(QA_DIR, "check-trust-anchors.py")], env=env)
        assert checker.returncode == 0, checker.stdout.decode()
        meta = run([sys.executable, os.path.join(QA_DIR, "check-update-metadata.py")], env=env)
        assert meta.returncode == 0, meta.stdout.decode()


def test_the_committed_trust_anchors_are_consistent():
    r = run([sys.executable, os.path.join(QA_DIR, "check-trust-anchors.py")])
    assert r.returncode == 0, r.stdout.decode()


def test_print_pinned_key_emits_the_committed_anchors_as_pem():
    """The helper the signing workflows use must emit the very anchors that are pinned in source,
    so a signature is checked against what devices actually trust."""
    for domain, anchor_file in (("metadata", UPDATE_CHECKER_REL), ("engine", UPDATE_MANAGER_REL)):
        r = run([sys.executable, os.path.join(QA_DIR, "print-pinned-key.py"), domain])
        assert r.returncode == 0, r.stdout.decode()
        pem = r.stdout.decode()
        assert pem.startswith("-----BEGIN PUBLIC KEY-----"), pem
        # The PEM decodes to the same SPKI that the source constant holds.
        der = run_bin(["openssl", "pkey", "-pubin", "-inform", "PEM", "-outform", "DER"],
                      input_bytes=pem.encode())
        source = open(os.path.join(REPO_ROOT, anchor_file), encoding="utf-8").read()
        m = re.search(r'PUBLIC_KEY_B64\s*=\s*"([^"]+)"', source)
        assert base64.b64decode(m.group(1)) == der


def test_the_committed_update_metadata_verifies():
    r = run([sys.executable, os.path.join(QA_DIR, "check-update-metadata.py")])
    assert r.returncode == 0, r.stdout.decode()
