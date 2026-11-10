#!/usr/bin/env python3
"""Generate the update-metadata signing keypair (ECDSA P-256).

GeneralsX @feature Android port 10/11/2026

The application verifies the update metadata with a public key compiled into it
(UpdateChecker.PUBLIC_KEY_B64). This script makes the matching pair:

  * the private key is written OUTSIDE the repository (never commit it) and is
    what the release workflow reads from the UPDATE_METADATA_KEY secret;
  * the public key is printed as the Java constant to paste into UpdateChecker.

Usage:
    scripts/update/generate-metadata-key.py [private-key-out]

With no argument the private key goes to ./update-metadata-key.pem (add it to
GitHub Actions secrets and delete it from disk). A new pair invalidates the old
one: only regenerate when the embedded public key is replaced in the same commit,
or every client stops trusting the metadata.
"""

import base64
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "update-metadata-key.pem"
    key = ec.generate_private_key(ec.SECP256R1())
    private_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    with open(out, "wb") as f:
        f.write(private_pem)
    public_der = key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    print("Private key written to:", out)
    print("Store its contents as the GitHub Actions secret UPDATE_METADATA_KEY, then delete the file.")
    print()
    print("Paste this into UpdateChecker.PUBLIC_KEY_B64:")
    print(base64.b64encode(public_der).decode("ascii"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
