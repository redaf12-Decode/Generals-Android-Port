#!/usr/bin/env python3
"""Validate the R8-protected release build of the GeneralsX Zero Hour launcher.

Run after `gradle :app:assembleRelease` (and/or `:app:bundleRelease`). It does not
build anything itself: it reads the artifacts R8 just produced and fails if any of
the guarantees the release build is supposed to give is missing.

What it checks, and why each one matters:

  1. R8 actually ran and produced a mapping file. minifyEnabled true with an empty
     mapping means the build was not really obfuscated (or an incremental run reused
     a stale output), so the rest of this script would be meaningless.

  2. The launcher's own classes were obfuscated. Entry points (the Activities) and
     the JNI contract are pinned on purpose, but everything else -- helpers, models,
     views -- must have been renamed. A regression that turns one of the narrow
     -keep rules into a package-wide one shows up here and nowhere else.

  3. The JNI contract survived. The engine binds GeneralsZHActivity's text-editor
     methods by the static Java_... naming convention (AndroidTextEditor.cpp) and
     looks three more methods up with GetMethodID; SDL3's native half FindClass's
     org/libsdl/app/* by literal name and RegisterNatives's a literal method table.
     A rename on either side is an UnsatisfiedLinkError or a silent no-op on device,
     which no compile-time check can catch. This is the check that would have caught
     it.

  4. The colour resources the launcher resolves by name at runtime are still in the
     APK. ThemeHelper builds "gzh_navy_..."/"gzh_accent_..." strings and calls
     Resources.getIdentifier(); the shrinker cannot see those, so res/raw/keep.xml
     guards them. Losing one is a silent wrong colour, not a crash, so it is asserted
     rather than trusted.

  5. The signing certificate is unchanged. R8 does not touch signing, but a release
     variant that quietly picked up a different key would break update-over-install
     for every existing user, and that is worth an assertion even if it "cannot"
     happen.

  6. Package identity is unchanged (applicationId, versionCode, launcher activity).

Usage:

    python3 scripts/qa/android/check-r8-protection.py

Options let a CI job point at a different build directory or a different expected
signing certificate. Exit status is 0 when every check passes, 1 otherwise; each
failure prints a GitHub Actions ::error:: annotation so it is visible in the run
summary.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile


# The launcher's package. Named once; every path below is derived from it.
PACKAGE = "com.generalsx.zerohour"

# Classes that are deliberately NOT obfuscated, and must therefore still be present
# under their original names in the dex. These are the Android entry points (named in
# AndroidManifest.xml) plus the two SDL classes the native half binds by name.
EXPECTED_UNOBFUSCATED = [
    f"{PACKAGE}.GeneralsZHActivity",
    f"{PACKAGE}.SetupActivity",
    f"{PACKAGE}.FolderPickerActivity",
    f"{PACKAGE}.TouchControlsActivity",
    f"{PACKAGE}.LogViewerActivity",
    f"{PACKAGE}.ModManagerActivity",
    f"{PACKAGE}.ReplayCheckActivity",
    f"{PACKAGE}.MapPackActivity",
    f"{PACKAGE}.ControlBarPackActivity",
    f"{PACKAGE}.NetworkDiagnosticsActivity",
    f"{PACKAGE}.GeneralsOnlineActivity",
    "org.libsdl.app.SDLActivity",
    "org.libsdl.app.SDLInputConnection",
    "org.libsdl.app.SDLAudioManager",
    "org.libsdl.app.SDLControllerManager",
    "org.libsdl.app.HIDDeviceManager",
]

# Method names that must survive verbatim, because native code names them as strings.
# Each entry is (class, method) and the reason is the file that looks it up.
JNI_METHODS = [
    # AndroidTextEditor.cpp, static Java_ naming convention:
    # Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorChanged
    # Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorDone
    (f"{PACKAGE}.GeneralsZHActivity", "nativeTextEditorChanged"),
    (f"{PACKAGE}.GeneralsZHActivity", "nativeTextEditorDone"),
    # AndroidTextEditor.cpp, GetMethodID on the running Activity.
    (f"{PACKAGE}.GeneralsZHActivity", "showTextEditor"),
    (f"{PACKAGE}.GeneralsZHActivity", "hideTextEditor"),
    # udp.cpp, GetMethodID on the running Activity.
    (f"{PACKAGE}.GeneralsZHActivity", "setLanMulticastLock"),
    # SDL3 SDL_android.c RegisterNatives / GetStaticMethodID on SDLActivity.
    ("org.libsdl.app.SDLActivity", "nativeSetupJNI"),
    ("org.libsdl.app.SDLActivity", "nativeRunMain"),
    ("org.libsdl.app.SDLActivity", "clipboardGetText"),
    ("org.libsdl.app.SDLActivity", "clipboardHasText"),
    ("org.libsdl.app.SDLActivity", "clipboardSetText"),
    ("org.libsdl.app.SDLActivity", "getContext"),
    ("org.libsdl.app.SDLActivity", "sendMessage"),
    ("org.libsdl.app.SDLActivity", "setOrientation"),
    ("org.libsdl.app.SDLActivity", "showTextInput"),
    ("org.libsdl.app.SDLActivity", "showFileDialog"),
    ("org.libsdl.app.SDLAudioManager", "nativeSetupJNI"),
    ("org.libsdl.app.SDLControllerManager", "nativeSetupJNI"),
    ("org.libsdl.app.SDLInputConnection", "nativeCommitText"),
    ("org.libsdl.app.HIDDeviceManager", "initialize"),
    ("org.libsdl.app.HIDDeviceManager", "openDevice"),
]

# Colour resource names the launcher builds at runtime (ThemeHelper). One per family
# it can produce, so a shrinker that started dropping a whole family is caught.
DYNAMIC_RESOURCES = [
    "gzh_primary",
    "gzh_navy_primary",
    "gzh_day_primary",
    "gzh_accent_blue_primary",
    "gzh_day_accent_red_surface",
    "gzh_status_ok",
    "gzh_navy_status_warn",
]

# Entry points and resources that must never be dropped, checked independently of R8.
REQUIRED_RESOURCES = [
    "ic_launcher_foreground",
    "ic_launcher_background",
    "ic_launcher_monochrome",
    "touch_editor_background",
    "app_name",
]

LAUNCHER_ACTIVITY = f"{PACKAGE}.SetupActivity"


def fail(msg):
    print(f"::error::{msg}")
    print(f"FAIL: {msg}")
    return 1


def ok(msg):
    print(f"ok: {msg}")


def read_dex_blob(apk_path):
    """Concatenate every classes*.dex in the APK into one byte string."""
    with zipfile.ZipFile(apk_path) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        if not dexes:
            raise SystemExit(f"::error::{apk_path} contains no classes.dex")
        return b"".join(z.read(n) for n in dexes), dexes


def read_arsc(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        return z.read("resources.arsc")


def parse_mapping(mapping_path):
    """Return (renamed, kept) as dicts/sets of fully-qualified class names.

    mapping.txt lines are either "orig -> new:" (a class header) or indented member
    lines. `renamed` maps orig -> new for classes that were obfuscated; `kept` is the
    set of classes whose name is unchanged.
    """
    renamed, kept = {}, set()
    cls_re = re.compile(r"^(\S+) -> (\S+):$")
    with open(mapping_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = cls_re.match(line.rstrip("\n"))
            if not m:
                continue
            orig, new = m.group(1), m.group(2)
            if orig == new:
                kept.add(orig)
            else:
                renamed[orig] = new
    return renamed, kept


def check_mapping(mapping_path, min_app_renamed):
    """Returns (failures, renamed_dict)."""
    if not os.path.isfile(mapping_path):
        return fail(
            f"no R8 mapping file at {mapping_path} -- R8 did not run, or ran without "
            "-keepattributes SourceFile,LineNumberTable"
        ), {}
    size = os.path.getsize(mapping_path)
    if size == 0:
        return fail(f"{mapping_path} is empty"), {}

    renamed, kept = parse_mapping(mapping_path)
    app_renamed = sorted(c for c in renamed if c.startswith(PACKAGE + "."))
    app_kept = sorted(c for c in kept if c.startswith(PACKAGE + "."))

    print(f"    mapping.txt: {size} bytes, {len(renamed)} renamed, {len(kept)} kept")
    print(f"    launcher classes renamed: {len(app_renamed)}")

    if len(app_renamed) < min_app_renamed:
        return fail(
            f"only {len(app_renamed)} launcher classes were obfuscated "
            f"(expected at least {min_app_renamed}) -- a keep rule has probably "
            f"widened to a whole package"
        ), {}

    unexpected = [c for c in app_kept if c not in EXPECTED_UNOBFUSCATED]
    if unexpected:
        return fail(
            "these launcher classes were NOT obfuscated but are not pinned entry "
            "points: " + ", ".join(unexpected[:20])
        ), {}

    ok(f"R8 obfuscated {len(app_renamed)} launcher classes; "
       f"{len(app_kept)} kept (all expected entry points)")
    return 0, renamed


def check_mapping_matches_apk(apk_path, renamed):
    """The mapping file and the APK must describe the same build.

    A stale mapping.txt from an earlier run would let every check above pass against
    the wrong artifact, so prove the link: the obfuscated names the mapping records
    must actually appear in this APK's dex. This is exactly what goes wrong if
    someone runs the checks against a build directory that was not rebuilt.
    """
    blob, _ = read_dex_blob(apk_path)
    # R8 rewrites a reference to a class it removed into a synthetic marker name
    # ("R8$$REMOVED$$CLASS$$91") that never appears in the dex. Those are not
    # obfuscated classes, so they cannot be used to link the mapping to the APK.
    app_new = [
        new for orig, new in renamed.items()
        if orig.startswith(PACKAGE + ".") and "$$REMOVED$$" not in new
    ]
    if not app_new:
        return fail("the mapping records no obfuscated launcher classes")
    missing = [n for n in app_new if n.replace(".", "/").encode() not in blob]
    if missing:
        return fail(
            f"{len(missing)} obfuscated names from mapping.txt are absent from the APK "
            f"(e.g. {missing[0]}) -- the mapping does not belong to this APK; rebuild "
            f"and re-run"
        )
    ok(f"mapping.txt matches the APK ({len(app_new)} obfuscated launcher classes "
       f"found in the dex)")
    return 0


def check_dex(apk_path):
    blob, dexes = read_dex_blob(apk_path)
    print(f"    dex: {', '.join(dexes)}")

    missing = [c for c in EXPECTED_UNOBFUSCATED if c.replace(".", "/").encode() not in blob]
    if missing:
        return fail("classes missing from the release dex (renamed by mistake?): "
                    + ", ".join(missing))
    ok(f"all {len(EXPECTED_UNOBFUSCATED)} pinned entry-point/JNI classes present")

    missing_methods = [f"{c}#{m}" for c, m in JNI_METHODS if m.encode() not in blob]
    if missing_methods:
        return fail("JNI method names missing from the dex (native lookups would fail): "
                    + ", ".join(missing_methods))
    ok(f"all {len(JNI_METHODS)} JNI-bound method names present verbatim")
    return 0


def check_resources(apk_path):
    arsc = read_arsc(apk_path)
    missing = [n for n in DYNAMIC_RESOURCES + REQUIRED_RESOURCES if n.encode() not in arsc]
    if missing:
        return fail(
            "resources dropped by the resource shrinker but still needed at runtime "
            "(ThemeHelper getIdentifier / launcher icon): " + ", ".join(missing)
        )
    ok(f"{len(DYNAMIC_RESOURCES)} runtime-resolved colours + "
       f"{len(REQUIRED_RESOURCES)} required resources present in resources.arsc")
    return 0


def check_signing(apk_path, apksigner, expected_sha256):
    if not apksigner or not os.path.isfile(apksigner):
        print("    apksigner not found; skipping certificate check")
        return 0
    proc = subprocess.run(
        [apksigner, "verify", "--print-certs", apk_path],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        return fail(f"apksigner verify failed for {apk_path}:\n{proc.stderr}")
    m = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", proc.stdout)
    if not m:
        return fail("could not read the signing certificate digest")
    got = m.group(1).lower()
    if expected_sha256 and got != expected_sha256.lower():
        return fail(
            f"signing certificate changed: got {got}, expected {expected_sha256} -- "
            f"existing users could not install this as an update"
        )
    ok(f"signing certificate SHA-256 {got}"
       + (" (matches the committed debug key)" if expected_sha256 else ""))
    return 0


def check_identity(apk_path, aapt2, expected_version_code):
    if not aapt2 or not os.path.isfile(aapt2):
        print("    aapt2 not found; skipping package-identity check")
        return 0
    proc = subprocess.run([aapt2, "dump", "badging", apk_path],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        return fail(f"aapt2 dump badging failed:\n{proc.stderr}")
    out = proc.stdout
    if f"package: name='{PACKAGE}'" not in out:
        return fail(f"applicationId is not {PACKAGE}")
    if f"launchable-activity: name='{LAUNCHER_ACTIVITY}'" not in out:
        return fail(f"launcher activity is not {LAUNCHER_ACTIVITY}")
    if expected_version_code:
        m = re.search(r"versionCode='(\d+)'", out)
        if not m or m.group(1) != str(expected_version_code):
            return fail(f"versionCode changed: {m.group(1) if m else '?'} "
                        f"!= {expected_version_code}")
    ok(f"package {PACKAGE}, launcher {LAUNCHER_ACTIVITY}, identity unchanged")
    return 0


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", "..", ".."))

    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", default=os.path.join(
        repo, "android/app/build/outputs/apk/release/app-release.apk"))
    ap.add_argument("--mapping", default=os.path.join(
        repo, "android/app/build/outputs/mapping/release/mapping.txt"))
    ap.add_argument("--min-app-renamed", type=int, default=20,
                    help="minimum number of launcher classes that must be obfuscated")
    ap.add_argument("--apksigner", default=os.environ.get("APKSIGNER", ""),
                    help="path to apksigner (default: $APKSIGNER)")
    ap.add_argument("--aapt2", default=os.environ.get("AAPT2", ""),
                    help="path to aapt2 (default: $AAPT2)")
    ap.add_argument("--keystore", default=os.path.join(
        repo, "android/app/debug.keystore"))
    ap.add_argument("--keystore-pass", default="android")
    ap.add_argument("--keystore-alias", default="androiddebugkey")
    ap.add_argument("--expected-version-code", default="")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        return fail(f"release APK not found at {args.apk} -- run "
                    f"`gradle :app:assembleRelease` first")

    # Derive the expected signing digest from the committed keystore, so this check
    # stays honest if the key ever legitimately changes.
    expected = ""
    if os.path.isfile(args.keystore) and shutil.which("keytool"):
        proc = subprocess.run(
            ["keytool", "-list", "-v", "-keystore", args.keystore,
             "-storepass", args.keystore_pass, "-alias", args.keystore_alias],
            capture_output=True, text=True)
        m = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", proc.stdout)
        if m:
            expected = m.group(1).replace(":", "").lower()

    failures = 0

    print("[R8 mapping]")
    rc, renamed = check_mapping(args.mapping, args.min_app_renamed)
    failures += rc

    if renamed:
        print("[mapping vs APK]")
        failures += check_mapping_matches_apk(args.apk, renamed)

    for name, fn in [
        ("dex contents", lambda: check_dex(args.apk)),
        ("resources", lambda: check_resources(args.apk)),
        ("signing", lambda: check_signing(args.apk, args.apksigner, expected)),
        ("package identity", lambda: check_identity(
            args.apk, args.aapt2, args.expected_version_code)),
    ]:
        print(f"[{name}]")
        failures += fn()

    print()
    if failures:
        print(f"FAILED: {failures} check(s) failed -- the release build is not "
              f"correctly protected")
        return 1
    print("PASSED: R8 protection verified (obfuscation, JNI contract, resources, "
          "signing, identity)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
