#!/usr/bin/env python3
# GeneralsX @feature Android security 10/11/2026
#
# Generate the launcher's update metadata (update.json and its detached signature
# update.json.sig) from the APK that was actually built, so the published metadata can never
# disagree with the artifact it points at.
#
# Before this, update.json was hand-edited and shipped an all-zero sha256, a zero size and a
# version/URL that matched no release -- exactly the placeholder values the launcher's own
# validation now refuses. This script derives every field from the APK:
#
#   versionCode / versionName / packageName   read out of the APK's own binary manifest, and
#                                             checked against the values the build claims
#   apkSha256                                 the streaming SHA-256 of the APK bytes
#   apkSizeBytes                              the APK's size in bytes
#   apkUrl                                    where the release will publish the APK
#
# If any value cannot be produced, or the APK's own manifest disagrees with the version the
# release is being cut as, the script exits non-zero so the publishing job fails rather than
# publishing metadata that does not match its artifact.
#
# Signing (--sign-key): the metadata is signed with the same detached ECDSA-P256/SHA-256 scheme
# the engine-update manifest uses (base64 over the exact bytes). The matching public key is
# pinned in UpdateChecker.PUBLIC_KEY_B64. The private key is never in the repository.
#
# Usage:
#   generate-update-json.py --apk apk/GeneralsXZH-android-v1.5.0.apk \
#       --version-name 1.5.0 --version-code 10500 --package-name com.ports.generalsx \
#       --url https://github.com/OWNER/REPO/releases/download/v1.5.0/GeneralsXZH-android-v1.5.0.apk \
#       --notes-file docs/releases/v1.5.0/notes.md --out-dir . --sign-key /path/key.pem
import argparse
import base64
import hashlib
import json
import os
import struct
import sys
import zipfile

# --- binary AndroidManifest.xml reader -----------------------------------------------------
# A dependency-free reader for the attributes this script needs (package, versionCode,
# versionName). It is deliberately strict: anything it cannot parse is an error, never a guess.

_RES_XML_TYPE = 0x0003
_RES_STRING_POOL_TYPE = 0x0001
_RES_XML_START_ELEMENT_TYPE = 0x0102

_ATTR_TYPE_STRING = 0x03
_ATTR_TYPE_INT_DEC = 0x10
_ATTR_TYPE_INT_HEX = 0x11

_ANDROID_NS = "http://schemas.android.com/apk/res/android"


class AxmlError(Exception):
    pass


def _read_string_pool(chunk, base):
    # chunk starts at base; header is 8 bytes (type, headerSize, size)
    _type, header_size, size = struct.unpack_from("<HHI", chunk, base)
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", chunk, base + 8)
    utf8 = (flags & (1 << 8)) != 0
    offsets = struct.unpack_from("<%dI" % string_count, chunk, base + header_size)
    data_base = base + strings_start
    out = []
    for off in offsets:
        pos = data_base + off
        if utf8:
            # UTF-8: two lengths (char count then byte count), each 1 or 2 bytes.
            n = chunk[pos]
            pos += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | chunk[pos]
                pos += 1
            nbytes = chunk[pos]
            pos += 1
            if nbytes & 0x80:
                nbytes = ((nbytes & 0x7F) << 8) | chunk[pos]
                pos += 1
            raw = chunk[pos:pos + nbytes]
            out.append(raw.decode("utf-8", errors="replace"))
        else:
            n = struct.unpack_from("<H", chunk, pos)[0]
            pos += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", chunk, pos)[0]
                pos += 2
            raw = chunk[pos:pos + n * 2]
            out.append(raw.decode("utf-16-le", errors="replace"))
    return out


def _parse_manifest_attrs(data):
    """Return the attribute dict of the <manifest> element, namespaced names stripped."""
    if len(data) < 8:
        raise AxmlError("manifest too short")
    magic, _hs, _size = struct.unpack_from("<HHI", data, 0)
    if magic != _RES_XML_TYPE:
        raise AxmlError("not a binary XML document (magic 0x%04x)" % magic)

    strings = None
    pos = 8
    while pos + 8 <= len(data):
        ctype, header_size, csize = struct.unpack_from("<HHI", data, pos)
        if csize <= 0 or pos + csize > len(data):
            raise AxmlError("chunk at %d runs past the document" % pos)
        if ctype == _RES_STRING_POOL_TYPE:
            strings = _read_string_pool(data, pos)
        elif ctype == _RES_XML_START_ELEMENT_TYPE:
            if strings is None:
                raise AxmlError("element before string pool")
            # StartElement: header(16) then lineNumber, comment, ns, name, then the
            # attributeExt block.
            name_idx = struct.unpack_from("<I", data, pos + 20)[0]
            element_name = strings[name_idx] if name_idx < len(strings) else ""
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, pos + 24)
            attrs = {}
            attr_base = pos + 16 + attr_start
            for i in range(attr_count):
                apos = attr_base + i * attr_size
                ns_idx, nm_idx, raw_idx = struct.unpack_from("<III", data, apos)
                data_type = data[apos + 15]
                value_data = struct.unpack_from("<I", data, apos + 16)[0]
                ns = strings[ns_idx] if ns_idx != 0xFFFFFFFF and ns_idx < len(strings) else ""
                nm = strings[nm_idx] if nm_idx < len(strings) else ""
                if ns == _ANDROID_NS:
                    key = "android:" + nm
                else:
                    key = nm
                if data_type == _ATTR_TYPE_STRING:
                    val = strings[value_data] if value_data < len(strings) else ""
                elif data_type in (_ATTR_TYPE_INT_DEC, _ATTR_TYPE_INT_HEX):
                    val = value_data
                else:
                    val = value_data
                attrs[key] = val
            if element_name == "manifest":
                return attrs
        pos += csize
    raise AxmlError("no <manifest> element found")


def apk_identity(apk_path):
    """(package, versionCode:int, versionName:str) from the APK's own binary manifest."""
    with zipfile.ZipFile(apk_path) as z:
        data = z.read("AndroidManifest.xml")
    attrs = _parse_manifest_attrs(data)
    package = attrs.get("package")
    version_code = attrs.get("android:versionCode")
    version_name = attrs.get("android:versionName")
    if not package:
        raise AxmlError("manifest has no package attribute")
    if version_code is None:
        raise AxmlError("manifest has no versionCode attribute")
    if not isinstance(version_code, int):
        try:
            version_code = int(str(version_code))
        except ValueError:
            raise AxmlError("versionCode is not an integer: %r" % version_code)
    return package, version_code, (version_name or "")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def release_notes(notes_file):
    if not notes_file or not os.path.isfile(notes_file):
        return []
    notes = []
    with open(notes_file, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                continue
            if line.startswith(("-", "*", "\u2022")):
                line = line[1:].strip()
            if line:
                notes.append(line)
    return notes[:20]


def main():
    ap = argparse.ArgumentParser(description="Generate and sign the launcher's update.json")
    ap.add_argument("--apk", required=True)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--version-code", required=True, type=int)
    ap.add_argument("--package-name", required=True)
    ap.add_argument("--url", required=True, help="the HTTPS URL the APK will be published at")
    ap.add_argument("--notes-file")
    ap.add_argument("--out-dir", default=".")
    ap.add_argument("--sign-key", help="PEM private key; when given, writes update.json.sig")
    ap.add_argument("--allow-unsigned", action="store_true",
                    help="write metadata without a signature (for a dry run only)")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        sys.exit("ERROR: APK not found: %s" % args.apk)

    package, version_code, version_name = apk_identity(args.apk)
    if package != args.package_name:
        sys.exit("ERROR: APK package %r != declared %r" % (package, args.package_name))
    if version_code != args.version_code:
        sys.exit("ERROR: APK versionCode %d != declared %d" % (version_code, args.version_code))
    if version_name != args.version_name:
        sys.exit("ERROR: APK versionName %r != declared %r" % (version_name, args.version_name))

    digest = sha256_file(args.apk)
    size = os.path.getsize(args.apk)
    if size <= 0:
        sys.exit("ERROR: APK is empty")
    if set(digest) == {"0"}:
        sys.exit("ERROR: refusing to publish an all-zero digest")

    if not args.url.startswith("https://"):
        sys.exit("ERROR: --url must be https:// (got %r)" % args.url)

    doc = {
        "versionCode": version_code,
        "versionName": version_name,
        "releaseNotes": release_notes(args.notes_file),
        "packageName": package,
        "apkUrl": args.url,
        "apkSha256": digest,
        "apkSizeBytes": size,
    }
    payload = (json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode("utf-8")

    os.makedirs(args.out_dir, exist_ok=True)
    json_path = os.path.join(args.out_dir, "update.json")
    with open(json_path, "wb") as f:
        f.write(payload)
    print("wrote %s (versionCode=%d versionName=%s sha256=%s size=%d)"
          % (json_path, version_code, version_name, digest, size))

    sig_path = os.path.join(args.out_dir, "update.json.sig")
    if args.sign_key:
        import subprocess
        if not os.path.isfile(args.sign_key):
            sys.exit("ERROR: signing key not found: %s" % args.sign_key)
        sig = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", args.sign_key],
            input=payload, stdout=subprocess.PIPE, check=True).stdout
        with open(sig_path, "w", encoding="ascii") as f:
            f.write(base64.b64encode(sig).decode("ascii") + "\n")
        print("wrote %s" % sig_path)
    elif not args.allow_unsigned:
        os.remove(json_path)
        sys.exit("ERROR: no --sign-key given. Metadata must be signed before publication "
                 "(pass --allow-unsigned only for a local dry run).")


if __name__ == "__main__":
    main()
