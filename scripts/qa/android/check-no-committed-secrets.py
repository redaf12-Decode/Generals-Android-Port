#!/usr/bin/env python3
# GeneralsX @feature Android security 10/11/2026
#
# Fail if signing material or an obviously-committed secret has crept into the tracked tree.
#
# The repository shipped a committed android/app/debug.keystore with the well-known password
# "android", and used it to sign the released APK. Removing the file is not enough on its own --
# the same mistake is one `git add` away -- so this runs in CI and refuses a keystore, a private
# key, or a hardcoded credential in the launcher's source.
#
# It scans tracked files only (git ls-files), so build outputs and caches are not considered.
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

# Keystore containers are always signing material. .pem/.key may be public material (a CA
# bundle, a public key), so those are judged by content below, not by name.
FORBIDDEN_SUFFIXES = (".keystore", ".jks", ".p12", ".pfx")

# A PEM private key block in any text file.
PRIVATE_KEY_MARKER = "-----BEGIN " + "PRIVATE KEY-----"

# Paths that legitimately contain the word "key" but are not secrets.
ALLOW = (
    "android/app/src/main/res/",
    "docs/",
)

# Credential-looking assignments in Java source. Deliberately narrow to avoid false positives on
# resource ids, JSON keys and the like.
SECRET_PATTERNS = [
    re.compile(r'(?i)\b(api[_-]?key|secret|password|passwd|token)\s*[:=]\s*"[^"\s]{16,}"'),
]


def tracked_files():
    out = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT,
                         stdout=subprocess.PIPE, check=True).stdout.decode()
    return [line for line in out.splitlines() if line]


def main():
    problems = []
    for rel in tracked_files():
        if rel.startswith(ALLOW):
            continue
        lower = rel.lower()
        if lower.endswith(FORBIDDEN_SUFFIXES):
            problems.append("%s: signing material must not be committed" % rel)
            continue
        # Only read plausible text files for the content checks. .pem/.key are read for the
        # private-key marker; a public CA bundle or public key has no such block and passes.
        if not lower.endswith((".java", ".kt", ".gradle", ".properties", ".xml", ".yml", ".yaml",
                               ".py", ".sh", ".pem", ".key")):
            continue
        path = os.path.join(REPO_ROOT, rel)
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                text = f.read()
        except OSError:
            continue
        if PRIVATE_KEY_MARKER in text:
            problems.append("%s: contains a PEM private key block" % rel)
        if rel.endswith((".java", ".kt")):
            for pattern in SECRET_PATTERNS:
                for m in pattern.finditer(text):
                    # The update-manager public key is public by design; only flag secrets.
                    if "PUBLIC_KEY_B64" in text[max(0, m.start() - 40):m.start()]:
                        continue
                    problems.append("%s: looks like a hardcoded secret: %s"
                                    % (rel, m.group(0)[:60]))

    if problems:
        for p in problems:
            print("::error::" + p)
        sys.exit(1)
    print("No committed signing material or hardcoded secrets found.")


if __name__ == "__main__":
    main()
