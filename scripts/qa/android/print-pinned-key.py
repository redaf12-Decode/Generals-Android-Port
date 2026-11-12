#!/usr/bin/env python3
# GeneralsX @feature Android security 12/11/2026
#
# Print one of the repository's pinned public trust anchors as a PEM, so a signing workflow can
# verify that what it just signed actually verifies against the key the launcher and engine pin.
# A secret that has drifted from the source anchor would otherwise publish a signature every
# device rejects; this makes that a failure at sign time.
#
#   print-pinned-key.py metadata   # UpdateChecker.PUBLIC_KEY_B64   (update.json)
#   print-pinned-key.py engine     # UpdateManager.PUBLIC_KEY_B64   (manifest.json)
#
# Only public material is emitted. The value is read from source (the same anchors
# check-trust-anchors.py validates), never from an environment variable or a remote document.
import argparse
import base64
import os
import re
import subprocess
import sys

REPO_ROOT = os.environ.get("GX_REPO_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

SOURCES = {
    "metadata": (os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com",
                              "generalsx", "zerohour", "UpdateChecker.java"), "PUBLIC_KEY_B64"),
    "engine": (os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com",
                            "generalsx", "zerohour", "UpdateManager.java"), "PUBLIC_KEY_B64"),
}


def main():
    ap = argparse.ArgumentParser(description="Print a pinned trust anchor as PEM")
    ap.add_argument("domain", choices=sorted(SOURCES))
    args = ap.parse_args()

    path, name = SOURCES[args.domain]
    with open(path, encoding="utf-8") as f:
        source = f.read()
    m = re.search(name + r'\s*=\s*"([^"]+)"', source)
    if not m:
        sys.exit("could not read %s from %s" % (name, path))

    der = base64.b64decode(m.group(1), validate=True)
    pem = subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-outform", "PEM"],
                         input=der, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if pem.returncode != 0:
        sys.exit("the %s anchor is not a parseable public key: %s"
                 % (args.domain, pem.stderr.decode().strip()))
    sys.stdout.write(pem.stdout.decode("ascii"))


if __name__ == "__main__":
    main()
