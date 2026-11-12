#!/usr/bin/env python3
# GeneralsX @feature Android security 12/11/2026
#
# Guard the Java package migration: the application's own classes must live under
# com/ports/generalsx and the applicationId must stay com.ports.generalsx, with no leftover
# com.generalsx.zerohour anywhere the build reads.
#
# It fails on:
#   * a Java/XML/manifest file under android/app/src still naming com.generalsx.zerohour;
#   * a native JNI symbol (Java_com_generalsx_zerohour_...) that would no longer match the
#     launcher's class after the move -- a silent UnsatisfiedLinkError at runtime;
#   * a java/com/generalsx/zerohour source directory still present;
#   * a build.gradle namespace or applicationId that is not com.ports.generalsx;
#   * a FileProvider authority that is not com.ports.generalsx.fileprovider.
#
# Third-party packages (org.libsdl.app) are not touched. Documentation and this repository's
# own history may still mention the old name and are not scanned.
import os
import re
import sys

REPO_ROOT = os.environ.get("GX_REPO_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

OLD_PACKAGE = "com.generalsx.zerohour"
NEW_PACKAGE = "com.ports.generalsx"
OLD_JNI = "Java_com_generalsx_zerohour"
NEW_JNI = "Java_com_ports_generalsx"

APP_SRC = os.path.join(REPO_ROOT, "android", "app", "src")
GRADLE = os.path.join(REPO_ROOT, "android", "app", "build.gradle")
MANIFEST = os.path.join(REPO_ROOT, "android", "app", "src", "main", "AndroidManifest.xml")
NATIVE_DIRS = [os.path.join(REPO_ROOT, "Core"), os.path.join(REPO_ROOT, "GeneralsMD"),
               os.path.join(REPO_ROOT, "Generals")]
TEXT_SUFFIXES = (".java", ".kt", ".xml", ".gradle", ".kts", ".pro")


def fail(message):
    print("::error::" + message)
    sys.exit(1)


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def walk(root, suffixes):
    for dirpath, dirnames, filenames in os.walk(root):
        # Never look inside build output: stale intermediates mention the old package by design.
        dirnames[:] = [d for d in dirnames if d != "build"]
        for name in filenames:
            if name.endswith(suffixes):
                yield os.path.join(dirpath, name)


def main():
    problems = []

    # 1. No old-package reference in the application's own sources.
    for path in walk(APP_SRC, TEXT_SUFFIXES):
        if OLD_PACKAGE in read(path):
            problems.append("old package in %s" % os.path.relpath(path, REPO_ROOT))

    # 2. No stale source directory.
    for rel in ("main/java/com/generalsx/zerohour", "test/java/com/generalsx/zerohour"):
        if os.path.isdir(os.path.join(APP_SRC, rel)):
            problems.append("stale source directory android/app/src/%s" % rel)

    # 3. Native JNI symbols must match the launcher's new package.
    for root in NATIVE_DIRS:
        if not os.path.isdir(root):
            continue
        for path in walk(root, (".cpp", ".c", ".h", ".hpp")):
            text = read(path)
            if OLD_JNI in text:
                problems.append("stale JNI symbol in %s" % os.path.relpath(path, REPO_ROOT))

    # 4. Gradle namespace and applicationId.
    if os.path.isfile(GRADLE):
        gradle = read(GRADLE)
        if not re.search(r"namespace\s+['\"]%s['\"]" % re.escape(NEW_PACKAGE), gradle):
            problems.append("build.gradle namespace is not '%s'" % NEW_PACKAGE)
        if not re.search(r"applicationId\s+['\"]%s['\"]" % re.escape(NEW_PACKAGE), gradle):
            problems.append("build.gradle applicationId is not '%s'" % NEW_PACKAGE)
    else:
        problems.append("android/app/build.gradle not found")

    # 5. FileProvider authority.
    if os.path.isfile(MANIFEST):
        manifest = read(MANIFEST)
        if "%s.fileprovider" % NEW_PACKAGE not in manifest \
                and "${applicationId}.fileprovider" not in manifest:
            problems.append("FileProvider authority is not %s.fileprovider" % NEW_PACKAGE)
    else:
        problems.append("AndroidManifest.xml not found")

    if problems:
        for p in problems:
            print("::error::" + p)
        fail("Java package migration is incomplete (%d problem(s))" % len(problems))

    print("Package migration OK: sources under java/com/ports/generalsx, namespace and "
          "applicationId %s, JNI symbols %s*, FileProvider authority preserved." %
          (NEW_PACKAGE, NEW_JNI))


if __name__ == "__main__":
    main()
