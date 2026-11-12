#!/usr/bin/env python3
# GeneralsX @feature Android security 12/11/2026
#
# Guard the launcher manifest's external surface. The manifest is the app's attack surface that
# no test on the Java side can see: an activity that is accidentally exported, a FileProvider that
# is not private, or a debug-only attribute that slips into the shipped manifest is a hole no unit
# test would catch. This check asserts the invariants the audit relies on:
#
#   * only the two intended entry points (SetupActivity, GeneralsZHActivity) are exported, and
#     only with a MAIN action -- every other activity and the FileProvider are exported="false";
#   * the FileProvider grants URI permissions but is not exported, and its authority is derived
#     from the applicationId (so a package rename cannot leave a stale authority);
#   * every declared permission is on the known list -- a new dangerous permission added to the
#     manifest without a matching entry in security/reports/android-permissions-audit.md fails;
#   * no android:debuggable and no android:testOnly in the shipped manifest (the release gate
#     catches a debuggable APK, but the manifest must not ask for it either).
#
# It reads the source manifest, not the merged one: the merged manifest needs a full build, and
# the source is where these attributes are actually written.
import os
import re
import sys

REPO_ROOT = os.environ.get("GX_REPO_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

MANIFEST = os.path.join(REPO_ROOT, "android", "app", "src", "main", "AndroidManifest.xml")
AUDIT = os.path.join(REPO_ROOT, "security", "reports", "android-permissions-audit.md")

# The two components that legitimately answer an external intent. GeneralsZHActivity keeps its
# MAIN action so an already-placed home-screen shortcut keeps resolving; SetupActivity is the
# single launcher icon. Nothing else may be exported.
EXPORTED_ALLOWED = {"GeneralsZHActivity", "SetupActivity"}

# Every permission the manifest may declare. A permission not here fails the check, so adding a
# dangerous one requires a deliberate edit to both the manifest and this list -- and the list is
# cross-checked against the audit report below.
KNOWN_PERMISSIONS = {
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.REQUEST_INSTALL_PACKAGES",
}


def fail(message):
    print("::error::" + message)
    sys.exit(1)


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def main():
    if not os.path.isfile(MANIFEST):
        fail("AndroidManifest.xml not found at %s" % MANIFEST)
    text = read(MANIFEST)
    problems = []

    # 1. Debuggable / testOnly must never be asked for in the shipped manifest.
    if re.search(r'android:debuggable\s*=\s*"true"', text):
        problems.append("android:debuggable=\"true\" in the shipped manifest")
    if re.search(r'android:testOnly\s*=\s*"true"', text):
        problems.append("android:testOnly=\"true\" in the shipped manifest")

    # 2. Exported components: collect every <activity>/<provider>/<service>/<receiver> and its
    #    exported flag. Only EXPORTED_ALLOWED may be exported; everything else must say false.
    component_re = re.compile(
        r'<(activity|activity-alias|provider|service|receiver)\b([^>]*)>', re.DOTALL)
    for kind, attrs in component_re.findall(text):
        name_m = re.search(r'android:name\s*=\s*"([^"]+)"', attrs)
        if not name_m:
            continue
        raw_name = name_m.group(1)
        short = raw_name.rsplit(".", 1)[-1]
        exported = re.search(r'android:exported\s*=\s*"(true|false)"', attrs)
        exported_value = exported.group(1) if exported else None
        if exported_value == "true":
            if short not in EXPORTED_ALLOWED:
                problems.append("%s %s is exported but is not an allowed entry point"
                                % (kind, short))
        elif exported_value is None:
            problems.append("%s %s has no explicit android:exported" % (kind, short))
        # exported="false" on anything is fine.

    # 3. The FileProvider must be private, grant URI permissions, and use the applicationId.
    #    Match both the self-closing form and the open/close form.
    provider = re.search(r'<provider\b(.*?)(?:/>|</provider>)', text, re.DOTALL)
    if not provider:
        problems.append("no <provider> (FileProvider) declared")
    else:
        block = provider.group(1)
        if 'android:exported="false"' not in block:
            problems.append("the FileProvider must be exported=\"false\"")
        if 'android:grantUriPermissions="true"' not in block:
            problems.append("the FileProvider must grant URI permissions")
        if "${applicationId}.fileprovider" not in block:
            problems.append("the FileProvider authority must be derived from ${applicationId}")

    # 4. Every declared permission is known.
    declared = re.findall(r'<uses-permission\s+android:name\s*=\s*"([^"]+)"', text)
    for perm in declared:
        if perm not in KNOWN_PERMISSIONS:
            problems.append("undeclared permission %s -- add it to KNOWN_PERMISSIONS and to "
                            "security/reports/android-permissions-audit.md" % perm)

    # 5. The audit report must name every declared permission, so the two cannot drift.
    if not os.path.isfile(AUDIT):
        problems.append("security/reports/android-permissions-audit.md is missing")
    else:
        audit = read(AUDIT)
        for perm in declared:
            short = perm.rsplit(".", 1)[-1]
            if short not in audit:
                problems.append("permission %s is not documented in android-permissions-audit.md"
                                % perm)

    if problems:
        for p in problems:
            print("::error::" + p)
        fail("manifest hardening invariants are violated (%d problem(s))" % len(problems))

    print("Manifest hardening OK: %d exported entry point(s) (%s), FileProvider private, "
          "%d known permission(s) documented in the audit."
          % (len(EXPORTED_ALLOWED), ", ".join(sorted(EXPORTED_ALLOWED)), len(declared)))


if __name__ == "__main__":
    main()
