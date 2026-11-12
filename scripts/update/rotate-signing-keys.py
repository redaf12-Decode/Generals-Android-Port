#!/usr/bin/env python3
# GeneralsX @feature Android security 12/11/2026
#
# Rotate every cryptographic identity the Android port uses, in one place, so the three pinned
# trust anchors and the GitHub Secrets that back them cannot drift apart.
#
# The port has three independent signing domains:
#
#   APK signing        an RSA-4096 JKS/PKCS12 keystore, used by Gradle and apksigner
#   update metadata    an ECDSA P-256 key, signs update.json (UpdateChecker.PUBLIC_KEY_B64)
#   engine update      an ECDSA P-256 key, signs manifest.json (UpdateManager.PUBLIC_KEY_B64,
#                      mirrored byte-for-byte in the engine's GXSignedSettings.cpp)
#
# The two subcommands are separate on purpose: `generate` writes a complete, new key set into a
# directory OUTSIDE the repository and prints what to do with it; `apply` rewrites the pinned
# public keys and re-signs update.json with the new metadata key. Generating and applying are
# separate so the new keys can be backed up and the secrets configured before the source tree
# starts trusting them.
#
# Nothing this script writes into the repository is secret: only public keys, a re-signed
# update.json.sig, and a note of the rotation. The private material stays in the output directory
# (mode 600) and in GitHub Actions secrets.
#
# Usage:
#   scripts/update/rotate-signing-keys.py generate --out ~/gx-keys
#   # back up ~/gx-keys offline, then configure the secrets it prints (or run set-secrets.sh)
#   scripts/update/rotate-signing-keys.py apply --keys ~/gx-keys
#   # commit the source changes, then re-sign the updates branch with the Sign update workflow
import argparse
import base64
import datetime
import hashlib
import os
import re
import subprocess
import sys

# The repository root. Overridable so the rotation can be exercised against a fixture tree in the
# signing self-test without touching the real anchors.
REPO_ROOT = os.environ.get("GX_REPO_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

ANCHORS = {
    "metadata": os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com",
                             "ports", "generalsx", "UpdateChecker.java"),
    "engine_java": os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com",
                                "ports", "generalsx", "UpdateManager.java"),
    "engine_native": os.path.join(REPO_ROOT, "GeneralsMD", "Code", "GameEngine", "Source",
                                  "Common", "GXSignedSettings.cpp"),
}
UPDATE_JSON = os.path.join(REPO_ROOT, "update.json")
UPDATE_SIG = os.path.join(REPO_ROOT, "update.json.sig")

KEYSTORE_NAME = "generalsx-release.jks"
METADATA_KEY_NAME = "update-metadata-key.pem"
ENGINE_KEY_NAME = "engine-update-key.pem"


def run(cmd, **kwargs):
    return subprocess.run(cmd, check=True, **kwargs)


def spki_sha256(der):
    canonical = subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-outform", "DER"],
                               input=der, stdout=subprocess.PIPE, check=True).stdout
    return hashlib.sha256(canonical).hexdigest()


def public_b64(private_pem):
    der = subprocess.run(["openssl", "ec", "-in", private_pem, "-pubout", "-outform", "DER"],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True).stdout
    return base64.b64encode(der).decode("ascii")


def generate(args):
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    os.chmod(out, 0o700)

    # A PKCS12 keystore -- what Android, keytool and apksigner all default to today -- stores one
    # password for the whole container: keytool refuses a -keypass that differs from -storepass.
    # So the "keystore password" and the "key password" are the same strong random value, and both
    # GitHub secrets carry it. JKS could keep them distinct, but it is the legacy format.
    store_pass = base64.b64encode(os.urandom(24)).decode("ascii").replace("/", "_").replace("+", "-")
    key_pass = store_pass

    keystore = os.path.join(out, KEYSTORE_NAME)
    if os.path.exists(keystore):
        sys.exit("refusing to overwrite existing %s; choose another --out" % keystore)
    print("generating RSA-4096 APK signing keystore ...")
    run(["keytool", "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
         "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
         "-validity", str(args.validity_days), "-alias", args.alias,
         "-storepass", store_pass, "-keypass", key_pass,
         "-dname", "CN=GeneralsX, O=GeneralsX, C=US"])
    os.chmod(keystore, 0o600)

    print("generating ECDSA P-256 update-metadata key ...")
    metadata_pem = os.path.join(out, METADATA_KEY_NAME)
    run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", metadata_pem])
    os.chmod(metadata_pem, 0o600)

    print("generating ECDSA P-256 engine-update key ...")
    engine_pem = os.path.join(out, ENGINE_KEY_NAME)
    run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", engine_pem])
    os.chmod(engine_pem, 0o600)

    metadata_b64 = public_b64(metadata_pem)
    engine_b64 = public_b64(engine_pem)
    metadata_fp = spki_sha256(base64.b64decode(metadata_b64))
    engine_fp = spki_sha256(base64.b64decode(engine_b64))

    cert = subprocess.run(["keytool", "-list", "-v", "-keystore", keystore,
                           "-storepass", store_pass, "-alias", args.alias],
                          stdout=subprocess.PIPE, check=True).stdout.decode()
    m = re.search(r"SHA256:\s*([0-9A-F:]+)", cert)
    if not m:
        sys.exit("could not read the new certificate's SHA-256 fingerprint")
    cert_sha256 = m.group(1)

    public_env = os.path.join(out, "public-keys.env")
    with open(public_env, "w", encoding="utf-8") as f:
        f.write("# New public keys and the certificate fingerprint (NOT secret).\n")
        f.write("UPDATE_METADATA_PUBLIC_KEY_B64=%s\n" % metadata_b64)
        f.write("ENGINE_UPDATE_PUBLIC_KEY_B64=%s\n" % engine_b64)
        f.write("UPDATE_METADATA_SPKI_SHA256=%s\n" % metadata_fp)
        f.write("ENGINE_UPDATE_SPKI_SHA256=%s\n" % engine_fp)
        f.write("ANDROID_RELEASE_CERT_SHA256=%s\n" % cert_sha256)

    keystore_b64 = os.path.join(out, "keystore.b64")
    with open(keystore_b64, "w", encoding="ascii") as f:
        f.write(base64.b64encode(open(keystore, "rb").read()).decode("ascii") + "\n")
    os.chmod(keystore_b64, 0o600)

    store_pass_file = os.path.join(out, "store.pass")
    key_pass_file = os.path.join(out, "key.pass")
    for path, value in ((store_pass_file, store_pass), (key_pass_file, key_pass)):
        with open(path, "w", encoding="ascii") as f:
            f.write(value)
        os.chmod(path, 0o600)

    set_secrets = os.path.join(out, "set-secrets.sh")
    with open(set_secrets, "w", encoding="utf-8") as f:
        f.write("""#!/usr/bin/env bash
# GeneralsX @feature Android security 12/11/2026 Configure the GitHub Actions secrets for the
# keys generated next to this file. Run it, then delete this directory from any machine that is
# not your offline backup. Requires `gh` authenticated with admin on the repository.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${GX_REPO:-redaf12-Decode/Generals-Android-Port}"
gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --repo "$REPO" < "$HERE/keystore.b64"
gh secret set ANDROID_RELEASE_STORE_PASSWORD  --repo "$REPO" < "$HERE/store.pass"
gh secret set ANDROID_RELEASE_KEY_PASSWORD    --repo "$REPO" < "$HERE/key.pass"
gh secret set ANDROID_RELEASE_KEY_ALIAS       --repo "$REPO" --body "%s"
gh secret set ANDROID_RELEASE_CERT_SHA256     --repo "$REPO" --body "%s"
gh secret set UPDATE_METADATA_SIGNING_KEY     --repo "$REPO" < "$HERE/%s"
gh secret set UPDATE_SIGNING_KEY              --repo "$REPO" < "$HERE/%s"
echo "Secrets configured. Now run: scripts/update/rotate-signing-keys.py apply --keys $HERE"
""" % (args.alias, cert_sha256, METADATA_KEY_NAME, ENGINE_KEY_NAME))
    os.chmod(set_secrets, 0o700)

    print()
    print("New key set written to %s (mode 600; %s)" % (out, "KEEP OFFLINE"))
    print("  APK certificate SHA-256 : %s" % cert_sha256)
    print("  update-metadata SPKI    : %s" % metadata_fp)
    print("  engine-update SPKI      : %s" % engine_fp)
    print()
    print("Next:")
    print("  1. Back up %s somewhere offline. Losing it means you can never update an install." % out)
    print("  2. Configure the GitHub secrets:  bash %s" % set_secrets)
    print("  3. Rewrite the pinned anchors:    scripts/update/rotate-signing-keys.py apply --keys %s" % out)
    print("  4. Re-sign the updates branch:    Actions -> Sign update (with the new UPDATE_SIGNING_KEY)")
    print("  5. Commit the source changes on New-app.")


def _replace_java_constant(path, name, value):
    with open(path, encoding="utf-8") as f:
        source = f.read()
    pattern = re.compile(r'(' + name + r'\s*=\s*\n?\s*")([^"]+)(")')
    if not pattern.search(source):
        sys.exit("could not find %s in %s" % (name, path))
    source = pattern.sub(lambda m: m.group(1) + value + m.group(3), source, count=1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(source)


def _replace_cpp_constant(path, name, value):
    with open(path, encoding="utf-8") as f:
        source = f.read()
    pattern = re.compile(r'(' + name + r'\[\]\s*=\s*\n?\s*")([^"]+)(")')
    if not pattern.search(source):
        sys.exit("could not find %s in %s" % (name, path))
    source = pattern.sub(lambda m: m.group(1) + value + m.group(3), source, count=1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(source)


def apply(args):
    keys = os.path.abspath(args.keys)
    public_env = os.path.join(keys, "public-keys.env")
    if not os.path.isfile(public_env):
        sys.exit("no public-keys.env in %s; run `generate` first" % keys)
    values = {}
    with open(public_env, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                values[k] = v

    metadata_key = os.path.join(keys, METADATA_KEY_NAME)
    if not os.path.isfile(metadata_key):
        sys.exit("no %s in %s; the metadata signature cannot be renewed without it"
                 % (METADATA_KEY_NAME, keys))

    _replace_java_constant(ANCHORS["metadata"], "PUBLIC_KEY_B64",
                           values["UPDATE_METADATA_PUBLIC_KEY_B64"])
    _replace_java_constant(ANCHORS["engine_java"], "PUBLIC_KEY_B64",
                           values["ENGINE_UPDATE_PUBLIC_KEY_B64"])
    _replace_cpp_constant(ANCHORS["engine_native"], "kPublicKeyB64",
                          values["ENGINE_UPDATE_PUBLIC_KEY_B64"])
    print("pinned anchors rewritten:")
    for label, path in ANCHORS.items():
        print("  %-14s %s" % (label, os.path.relpath(path, REPO_ROOT)))

    body = open(UPDATE_JSON, "rb").read()
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", metadata_key],
                         input=body, stdout=subprocess.PIPE, check=True).stdout
    with open(UPDATE_SIG, "w", encoding="ascii") as f:
        f.write(base64.b64encode(sig).decode("ascii") + "\n")
    print("re-signed update.json with the new metadata key")

    summary = os.path.join(keys, "rotation-summary-%s.txt"
                           % datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d"))
    with open(summary, "w", encoding="utf-8") as f:
        f.write("GeneralsX Android signing rotation, %s\n"
                % datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"))
        f.write("update-metadata SPKI SHA-256 : %s\n" % values["UPDATE_METADATA_SPKI_SHA256"])
        f.write("engine-update SPKI SHA-256   : %s\n" % values["ENGINE_UPDATE_SPKI_SHA256"])
        f.write("APK certificate SHA-256      : %s\n" % values["ANDROID_RELEASE_CERT_SHA256"])
    print("summary written to %s" % summary)
    print()
    print("Now run the checks, then commit:")
    print("  python3 scripts/qa/android/check-trust-anchors.py")
    print("  python3 scripts/qa/android/check-update-metadata.py")


def main():
    ap = argparse.ArgumentParser(description="Rotate the Android port's signing keys")
    sub = ap.add_subparsers(dest="command", required=True)

    gen = sub.add_parser("generate", help="write a complete new key set outside the repository")
    gen.add_argument("--out", required=True, help="directory to write the new keys into")
    gen.add_argument("--alias", default="generalsx")
    gen.add_argument("--validity-days", type=int, default=12000,
                     help="at least 25 years; the default is ~32 years")
    gen.set_defaults(func=generate)

    app = sub.add_parser("apply", help="rewrite the pinned anchors and re-sign update.json")
    app.add_argument("--keys", required=True, help="the directory `generate` wrote")
    app.set_defaults(func=apply)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
